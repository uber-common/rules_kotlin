/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package io.bazel.kotlin.builder.tasks.jvm.btapi

import com.google.devtools.build.lib.view.proto.Deps
import io.bazel.kotlin.builder.tasks.JvmTaskExecutor
import io.bazel.kotlin.builder.tasks.jvm.CompilationArgs
import io.bazel.kotlin.builder.tasks.jvm.InternalCompilerPlugins
import io.bazel.kotlin.builder.tasks.jvm.JDepsGenerator.emptyJdeps
import io.bazel.kotlin.builder.tasks.jvm.JDepsGenerator.writeJdeps
import io.bazel.kotlin.builder.tasks.jvm.createAbiJar
import io.bazel.kotlin.builder.tasks.jvm.createCoverageInstrumentedJar
import io.bazel.kotlin.builder.tasks.jvm.createGeneratedClassJar
import io.bazel.kotlin.builder.tasks.jvm.createGeneratedJavaSrcJar
import io.bazel.kotlin.builder.tasks.jvm.createGeneratedKspKotlinSrcJar
import io.bazel.kotlin.builder.tasks.jvm.createGeneratedStubJar
import io.bazel.kotlin.builder.tasks.jvm.createOutputJar
import io.bazel.kotlin.builder.tasks.jvm.createdGeneratedKspClassesJar
import io.bazel.kotlin.builder.tasks.jvm.expandWithGeneratedSources
import io.bazel.kotlin.builder.tasks.jvm.kaptArgs
import io.bazel.kotlin.builder.tasks.jvm.plugins
import io.bazel.kotlin.builder.tasks.jvm.preProcessingSteps
import io.bazel.kotlin.builder.toolchain.CompilationStatusException
import io.bazel.kotlin.builder.toolchain.CompilationTaskContext
import io.bazel.kotlin.compiler.CompilationUnit
import io.bazel.kotlin.compiler.CompilerConfiguration
import io.bazel.kotlin.model.JvmCompilationTask
import java.io.BufferedInputStream
import java.io.PrintStream
import java.nio.file.Paths

/**
 * Executes JVM compilation tasks through the typed Build Tools API path.
 */
class BtapiTaskExecutor(
  private val invoker: BtapiInvoker,
  private val plugins: InternalCompilerPlugins,
) : JvmTaskExecutor {
  override fun execute(
    context: CompilationTaskContext,
    task: JvmCompilationTask,
  ) {
    val preprocessedTask =
      task
        .preProcessingSteps(context)
        .runKaptIfNeeded(context)

    context.execute("compile classes") {
      preprocessedTask.apply {
        val outputLines =
          try {
            context.execute("kotlinc") {
              if (compileKotlin) {
                runKotlinCompiler(context)
              } else {
                if (outputs.jdeps.isNotEmpty()) {
                  writeJdeps(outputs.jdeps, emptyJdeps(info.label))
                }
                emptyList()
              }
            } to null
          } catch (e: CompilationStatusException) {
            e.lines to e
          }

        outputLines.first.apply(context::printCompilerOutput)
        outputLines.second?.let { throw it }

        if (outputs.jar.isNotEmpty()) {
          if (instrumentCoverage) {
            context.execute("create instrumented jar", ::createCoverageInstrumentedJar)
          } else {
            context.execute("create jar", ::createOutputJar)
          }
        }
        if (outputs.abijar.isNotEmpty()) {
          context.execute("create abi jar", ::createAbiJar)
        }
        if (outputs.generatedJavaSrcJar.isNotEmpty()) {
          context.execute("creating KAPT generated Java source jar", ::createGeneratedJavaSrcJar)
        }
        if (outputs.generatedJavaStubJar.isNotEmpty()) {
          context.execute("creating KAPT generated Kotlin stubs jar", ::createGeneratedStubJar)
        }
        if (outputs.generatedClassJar.isNotEmpty()) {
          context.execute("creating KAPT generated stub class jar", ::createGeneratedClassJar)
        }
        if (outputs.generatedKspSrcJar.isNotEmpty()) {
          context.execute("creating KSP generated src jar", ::createGeneratedKspKotlinSrcJar)
        }
        if (outputs.generatedKspClassesJar.isNotEmpty()) {
          context.execute("creating KSP generated classes jar", ::createdGeneratedKspClassesJar)
        }
      }
    }
  }

  /**
   * The KAPT stubs-and-apt pre-pass, followed by re-expanding the task with the generated sources.
   */
  private fun JvmCompilationTask.runKaptIfNeeded(
    context: CompilationTaskContext,
  ): JvmCompilationTask {
    if (
      (inputs.processorsList.isEmpty() && inputs.stubsPluginClasspathList.isEmpty()) ||
      inputs.kotlinSourcesList.isEmpty()
    ) {
      return this
    }
    // KSP is handled externally in Starlark, only KAPT runs through the builder
    if (outputs.generatedClassJar.isNullOrEmpty()) {
      return this
    }
    return context.execute("kapt (${inputs.processorsList.joinToString(", ")})") {
      val arguments =
        plugins(
          options = inputs.stubsPluginOptionsList.filterNot { o -> o.startsWith(plugins.kapt.id) },
          classpath = inputs.stubsPluginClasspathList,
        ).append(kaptArgs(context, plugins, "stubsAndApt"))
          .list()
      context
        .executeCompilerTask(
          { out -> invokeCompiler(context, arguments, directories.generatedClasses, out) },
          printOnSuccess = context.whenTracing { true } == true,
        ).let { outputLines ->
          context.whenTracing {
            printLines("kapt output", outputLines)
          }
          expandWithGeneratedSources()
        }
    }
  }

  private fun JvmCompilationTask.runKotlinCompiler(context: CompilationTaskContext): List<String> {
    val arguments =
      CompilationArgs()
        .given(outputs.jdeps)
        .notEmpty {
          plugin(plugins.jdeps) {
            flag("output", outputs.jdeps)
            flag("target_label", info.label)
            inputs.directDependenciesList.forEach {
              flag("direct_dependencies", it)
            }
            inputs.classpathList.forEach {
              flag("full_classpath", it)
            }
            flag("strict_kotlin_deps", info.strictKotlinDeps)
          }
        }.given(outputs.abijar)
        .notEmpty {
          plugin(plugins.jvmAbiGen) {
            flag("outputDir", directories.abiClasses)
            if (info.treatInternalAsPrivateInAbiJar) {
              flag("treatInternalAsPrivate", "true")
            }
            if (info.removePrivateClassesInAbiJar) {
              flag("removePrivateClasses", "true")
            }
            if (info.removeDebugInfo) {
              flag("removeDebugInfo", "true")
            }
            if (info.preserveDeclarationOrder) {
              flag("preserveDeclarationOrder", "true")
            }
            if (info.removeDataClassCopyIfConstructorIsPrivate) {
              flag("removeDataClassCopyIfConstructorIsPrivate", "true")
            }
          }
          given(outputs.jar).empty {
            plugin(plugins.skipCodeGen)
          }
        }.values(info.passthroughFlagsList)
        .append(
          plugins(
            options = inputs.compilerPluginOptionsList,
            classpath = inputs.compilerPluginClasspathList,
          ),
        ).list()

    context.whenTracing {
      context.printLines("Kotlin Compiler arguments:\n", arguments)
    }
    return context.executeCompilerTask(
      { out -> invokeCompiler(context, arguments, directories.classes, out) },
      printOnFail = false,
    )
  }

  private class TaskCompilationUnit(
    override val sources: List<String>,
    override val classpath: List<String>,
    override val friendPaths: List<String>,
    override val destination: String,
  ) : CompilationUnit

  private class TaskCompilerConfiguration(
    override val moduleName: String,
    override val jvmTarget: String,
    override val apiVersion: String,
    override val languageVersion: String,
    override val passthroughArguments: List<String>,
    override val verbose: Boolean,
  ) : CompilerConfiguration

  private fun JvmCompilationTask.invokeCompiler(
    context: CompilationTaskContext,
    arguments: List<String>,
    destination: String,
    out: PrintStream,
  ): Int =
    invoker.exec(
      errStream = out,
      compilationUnit =
        TaskCompilationUnit(
          sources = inputs.javaSourcesList + inputs.kotlinSourcesList,
          classpath = computeClasspath() + directories.generatedClasses,
          friendPaths = info.friendPathsList,
          destination = destination,
        ),
      configuration =
        TaskCompilerConfiguration(
          moduleName = info.moduleName,
          jvmTarget = info.toolchainInfo.jvm.jvmTarget,
          apiVersion = info.toolchainInfo.common.apiVersion,
          languageVersion = info.toolchainInfo.common.languageVersion,
          passthroughArguments = arguments,
          verbose = context.whenTracing { true } == true,
        ),
    )

  /**
   * The compile classpath, honoring the jdeps-based reduced classpath mode. Mirrors the legacy
   * baseArgs classpath computation so both paths compile against the same entries.
   */
  private fun JvmCompilationTask.computeClasspath(): List<String> =
    when (info.reducedClasspathMode) {
      "KOTLINBUILDER_REDUCED" -> {
        val transitiveDepsForCompile = mutableSetOf<String>()
        inputs.depsArtifactsList.forEach { jdepsPath ->
          BufferedInputStream(Paths.get(jdepsPath).toFile().inputStream()).use {
            val deps = Deps.Dependencies.parseFrom(it)
            deps.dependencyList.forEach { dep ->
              if (dep.kind == Deps.Dependency.Kind.EXPLICIT) {
                transitiveDepsForCompile.add(dep.path)
              }
            }
          }
        }
        inputs.directDependenciesList + transitiveDepsForCompile
      }

      else -> {
        inputs.classpathList
      }
    }
}
