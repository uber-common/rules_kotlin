/*
 * Copyright 2025 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.bazel.kotlin.compiler

import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPluginOption
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JdkRelease
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.getToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Paths
import org.jetbrains.kotlin.buildtools.api.arguments.enums.KotlinVersion as BtapiKotlinVersion

@Suppress("unused")
@OptIn(ExperimentalBuildToolsApi::class)
class BuildToolsAPICompiler(
  /**
   * Build Tools implementation jars with required dependencies
   */
  btImplClasspath: Array<String>,
) : KotlinBtapiCompiler {
  /** The state derived from the runtime classloader, initialized once and reused by every [exec]. */
  private class BtImplRuntime(
    val kotlinToolchains: KotlinToolchains,
    val exitCodes: Map<CompilationResult, Int>,
  )

  private val runtime by lazy {
    val classLoader =
      URLClassLoader(
        btImplClasspath.map { File(it).toURI().toURL() }.toTypedArray(),
        SharedApiClassesClassLoader(), // allows only BuildTools API classes from the parent loader
      )
    BtImplRuntime(
      kotlinToolchains = KotlinToolchains.loadImplementation(classLoader),
      exitCodes = exitCodesFrom(classLoader),
    )
  }

  /**
   * CompilationResult to int exit codes mapping
   */
  private fun exitCodesFrom(classLoader: ClassLoader): Map<CompilationResult, Int> {
    val exitCodeClass = classLoader.loadClass("org.jetbrains.kotlin.cli.common.ExitCode")
    val getCode = exitCodeClass.getMethod("getCode")
    val constantsByName = exitCodeClass.enumConstants.associateBy { (it as Enum<*>).name }

    fun codeOf(name: String): Int {
      val constant =
        checkNotNull(constantsByName[name]) {
          "the compiler runtime's ExitCode declares no constant named $name"
        }
      return getCode.invoke(constant) as Int
    }

    return mapOf(
      CompilationResult.COMPILATION_SUCCESS to codeOf("OK"),
      CompilationResult.COMPILATION_ERROR to codeOf("COMPILATION_ERROR"),
      CompilationResult.COMPILATION_OOM_ERROR to codeOf("OOM_ERROR"),
      CompilationResult.COMPILER_INTERNAL_ERROR to codeOf("INTERNAL_ERROR"),
    )
  }

  @OptIn(ExperimentalBuildToolsApi::class, ExperimentalCompilerArgument::class)
  override fun exec(
    errStream: PrintStream,
    compilationUnit: CompilationUnit,
    configuration: CompilerConfiguration,
    plugins: List<CompilerPluginSpec>,
  ): Int {
    System.setProperty("zip.handler.uses.crc.instead.of.timestamp", "true")

    val operationBuilder =
      runtime.kotlinToolchains
        .getToolchain<JvmPlatformToolchain>()
        .jvmCompilationOperationBuilder(
          compilationUnit.sources.map { Paths.get(it) },
          Paths.get(compilationUnit.destination),
        )

    val args = operationBuilder.compilerArguments

    // 1. User pass-through flags (resets the builder; must be first).
    val passthroughArguments = configuration.passthroughArguments
    if (passthroughArguments.isNotEmpty()) {
      args.applyArgumentStrings(passthroughArguments)
    }

    if (plugins.isNotEmpty()) {
      args[CommonCompilerArguments.COMPILER_PLUGINS] =
        plugins.map { plugin ->
          CompilerPlugin(
            pluginId = plugin.id,
            classpath = plugin.classpath.map { Paths.get(it) },
            rawArguments =
              plugin.options.map { option ->
                CompilerPluginOption(option.substringBefore('='), option.substringAfter('=', ""))
              },
            orderingRequirements = emptySet(),
          )
        }
    }

    // The default scripting plugin must be 'off' on every compilation. Only .kt and .java sources are compiled.
    args[CommonCompilerArguments.X_DISABLE_DEFAULT_SCRIPTING_PLUGIN] = true

    // 2. Toolchain defaults -- only for options the user did not set, so user flags win.
    var effectiveJvmTarget = args[JvmCompilerArguments.JVM_TARGET]
    if (effectiveJvmTarget == null) {
      effectiveJvmTarget = requireJvmTarget(configuration.jvmTarget)
      args[JvmCompilerArguments.JVM_TARGET] = effectiveJvmTarget
    }
    // Unless set explicitly, ensure the JDK API version corresponds to the selected jvm target.
    if (args[JvmCompilerArguments.X_JDK_RELEASE] == null) {
      val jdkRelease = jdkReleaseFor(effectiveJvmTarget)
      if (jdkRelease != null) {
        args[JvmCompilerArguments.X_JDK_RELEASE] = jdkRelease
      }
    }
    if (args[CommonCompilerArguments.API_VERSION] == null) {
      args[CommonCompilerArguments.API_VERSION] =
        requireKotlinVersion(version = configuration.apiVersion, fieldName = "kotlin_api_version")
    }
    if (args[CommonCompilerArguments.LANGUAGE_VERSION] == null) {
      args[CommonCompilerArguments.LANGUAGE_VERSION] =
        requireKotlinVersion(
          version = configuration.languageVersion,
          fieldName = "kotlin_language_version",
        )
    }

    // 3. Rules-managed settings -- applied last so user flags cannot clobber them.
    // The rules assemble the complete classpath explicitly (including the Kotlin stdlib),
    // so the compiler must not try to locate a Kotlin home distribution to auto-append stdlib/reflect.
    args[JvmCompilerArguments.NO_STDLIB] = true
    args[JvmCompilerArguments.NO_REFLECT] = true

    args[JvmCompilerArguments.MODULE_NAME] = configuration.moduleName
    if (compilationUnit.classpath.isNotEmpty()) {
      args[JvmCompilerArguments.CLASSPATH] =
        compilationUnit.classpath.map { Paths.get(File(it).absolutePath) }
    }
    if (compilationUnit.friendPaths.isNotEmpty()) {
      args[JvmCompilerArguments.X_FRIEND_PATHS] =
        compilationUnit.friendPaths.map { Paths.get(File(it).absolutePath) }
    }

    val result =
      runtime.kotlinToolchains.createBuildSession().use { session ->
        session.executeOperation(
          operationBuilder.build(),
          logger = createLogger(errStream, verbose = configuration.verbose),
        )
      }

    return runtime.exitCodes.getValue(result)
  }

  private fun requireJvmTarget(target: String): JvmTarget {
    val normalizedTarget = normalizeJvmTarget(target.trim())
    return JvmTarget.entries.firstOrNull { it.stringValue == normalizedTarget }
      ?: throw IllegalArgumentException(
        "Unsupported kotlin_jvm_target '$target'. Supported values: " +
          JvmTarget.entries.joinToString(", ") { it.stringValue },
      )
  }

  private fun normalizeJvmTarget(target: String): String =
    when (target) {
      "6" -> "1.6"
      "8" -> "1.8"
      else -> target
    }

  /**
   * The -Xjdk-release matching the given JVM target -- their string forms coincide (e.g. "1.8" /
   * "17"), and JdkRelease's values are a superset of JvmTarget's, so this resolves for every valid
   * target. Returns null only if a future Build Tools API splits the two enums; callers treat that
   * as "no default" (the user can set -Xjdk-release explicitly) rather than a failure.
   */
  @OptIn(ExperimentalCompilerArgument::class)
  private fun jdkReleaseFor(jvmTarget: JvmTarget): JdkRelease? =
    JdkRelease.entries.firstOrNull { it.stringValue == jvmTarget.stringValue }

  /**
   * Parse a Kotlin version string to the typed enum and fail fast for unsupported values.
   */
  private fun requireKotlinVersion(
    version: String,
    fieldName: String,
  ): BtapiKotlinVersion =
    BtapiKotlinVersion.entries.firstOrNull { it.stringValue == version.trim() }
      ?: throw IllegalArgumentException(
        "Unsupported $fieldName '$version'. Supported values: " +
          BtapiKotlinVersion.entries.joinToString(", ") { it.stringValue },
      )

  private fun createLogger(
    out: PrintStream,
    verbose: Boolean,
  ): KotlinLogger =
    object : KotlinLogger {
      override val isDebugEnabled: Boolean = verbose

      override fun error(
        msg: String,
        throwable: Throwable?,
      ) {
        out.println(msg)
        throwable?.printStackTrace(out)
      }

      override fun warn(
        msg: String,
        throwable: Throwable?,
      ) {
        out.println(msg)
        throwable?.printStackTrace(out)
      }

      override fun info(msg: String) {
        if (verbose) {
          out.println(msg)
        }
      }

      override fun debug(msg: String) {
        if (verbose) {
          out.println(msg)
        }
      }

      override fun lifecycle(msg: String) {
        if (verbose) {
          out.println(msg)
        }
      }
    }
}
