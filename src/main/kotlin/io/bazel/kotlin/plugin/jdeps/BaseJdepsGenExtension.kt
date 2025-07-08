package io.bazel.kotlin.plugin.jdeps

import com.google.common.io.ByteStreams
import com.google.devtools.build.lib.view.proto.Deps
import com.google.protobuf.ByteString
import io.bazel.kotlin.builder.utils.jars.JarOwner
import org.jetbrains.kotlin.config.CompilerConfiguration
import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.stream.Collectors

abstract class BaseJdepsGenExtension(
  protected val configuration: CompilerConfiguration,
) {
  protected fun onAnalysisCompleted(
    explicitClassesCanonicalPaths: Set<String>,
    implicitClassesCanonicalPaths: Set<String>,
    usedResources: Set<String>,
  ) {
    val directDeps = configuration.getList(JdepsGenConfigurationKeys.DIRECT_DEPENDENCIES)
    val targetLabel = configuration.getNotNull(JdepsGenConfigurationKeys.TARGET_LABEL)
    val explicitDeps = createDepsMap(explicitClassesCanonicalPaths)

    doWriteJdeps(directDeps, targetLabel, explicitDeps, implicitClassesCanonicalPaths, usedResources)

    doStrictDeps(configuration, targetLabel, directDeps, explicitDeps)
  }

  /**
   * Compute hash of internal jar class ABI definition.
   */
  protected fun getHashFromJarEntry(
    jarPath: String,
    internalPath: String,
  ): ByteArray {
    val jarFile = JarFile(jarPath)
    val entry = jarFile.getEntry(internalPath)
    val bytes = ByteStreams.toByteArray(jarFile.getInputStream(entry))
    return MessageDigest.getInstance("SHA-256").digest(bytes)
  }

  /**
   * Returns a map of jars to classes loaded from those jars.
   */
  private fun createDepsMap(classes: Set<String>): Map<String, List<String>> {
    val jarsToClasses = mutableMapOf<String, MutableList<String>>()
    classes.forEach {
      val parts = it.split("!/")
      val jarPath = parts[0]
      if (jarPath.endsWith(".jar")) {
        jarsToClasses.computeIfAbsent(jarPath) { ArrayList() }.add(parts[1])
      }
    }
    return jarsToClasses
  }

  private fun doWriteJdeps(
    directDeps: MutableList<String>,
    targetLabel: String,
    explicitDeps: Map<String, List<String>>,
    implicitClassesCanonicalPaths: Set<String>,
    usedResources: Set<String>,
  ) {
    val trackClassUsage = configuration.getNotNull(JdepsGenConfigurationKeys.TRACK_CLASS_USAGE).equals("on")
    val trackResourceUsage = configuration.getNotNull(JdepsGenConfigurationKeys.TRACK_RESOURCE_USAGE).equals("on")
    val implicitDeps = createDepsMap(implicitClassesCanonicalPaths)

    // Build and write out deps.proto
    val jdepsOutput = configuration.getNotNull(JdepsGenConfigurationKeys.OUTPUT_JDEPS)

    val rootBuilder = Deps.Dependencies.newBuilder()
    rootBuilder.success = true
    rootBuilder.ruleLabel = targetLabel

    explicitDeps.toSortedMap().forEach { (jarPath, usedClasses) ->
      val dependency = Deps.Dependency.newBuilder()
      dependency.kind = Deps.Dependency.Kind.EXPLICIT
      dependency.path = jarPath

      if (trackClassUsage) {
        // Add tracked classes and their (compile time) hash into final output, as needed for
        // compilation avoidance.
        usedClasses.stream().sorted().collect(Collectors.toList()).forEach { it ->
          val name = it.replace(".class", "").replace("/", ".")
          val hash = ByteString.copyFrom(getHashFromJarEntry(jarPath, it))
          val usedClass: Deps.UsedClass = Deps.UsedClass.newBuilder()
            .setFullyQualifiedName(name)
            .setInternalPath(it)
            .setHash(hash)
            .build()
          dependency.addUsedClasses(usedClass)
        }
      }

      rootBuilder.addDependency(dependency)
    }

    implicitDeps.keys.subtract(explicitDeps.keys).forEach {
      val dependency = Deps.Dependency.newBuilder()
      dependency.kind = Deps.Dependency.Kind.IMPLICIT
      dependency.path = it
      rootBuilder.addDependency(dependency)
    }

    if (trackResourceUsage) {
      usedResources.sorted().forEach { resource ->
        rootBuilder.addUsedResources(resource)
      }
    }

    BufferedOutputStream(File(jdepsOutput).outputStream()).use {
      it.write(rootBuilder.buildSorted().toByteArray())
    }
  }

  private fun doStrictDeps(
    compilerConfiguration: CompilerConfiguration,
    targetLabel: String,
    directDeps: MutableList<String>,
    explicitDeps: Map<String, List<String>>,
  ) {
    when (compilerConfiguration.getNotNull(JdepsGenConfigurationKeys.STRICT_KOTLIN_DEPS)) {
      "warn" -> checkStrictDeps(explicitDeps, directDeps, targetLabel)
      "error" -> {
        if (checkStrictDeps(explicitDeps, directDeps, targetLabel)) {
          error(
            "Strict Deps Violations - please fix",
          )
        }
      }
    }
  }

  /**
   * Prints strict deps warnings and returns true if violations were found.
   */
  private fun checkStrictDeps(
    result: Map<String, List<String>>,
    directDeps: List<String>,
    targetLabel: String,
  ): Boolean {
    val missingStrictDeps =
      result.keys
        .filter { !directDeps.contains(it) }
        .map { JarOwner.readJarOwnerFromManifest(Paths.get(it)) }

    if (missingStrictDeps.isNotEmpty()) {
      val missingStrictLabels = missingStrictDeps.mapNotNull { it.label }

      val open = "\u001b[35m\u001b[1m"
      val close = "\u001b[0m"

      var command =
        """
        $open ** Please add the following dependencies:$close
        ${
          missingStrictDeps.map { it.label ?: it.jar }.joinToString(" ")
        } to $targetLabel
        """

      if (missingStrictLabels.isNotEmpty()) {
        command += """$open ** You can use the following buildozer command:$close
        buildozer 'add deps ${
          missingStrictLabels.joinToString(" ")
        }' $targetLabel
        """
      }

      println(command.trimIndent())
      return true
    }
    return false
  }
}

private fun Deps.Dependencies.Builder.buildSorted(): Deps.Dependencies {
  val sortedDeps = dependencyList.sortedBy { it.path }
  sortedDeps.forEachIndexed { index, dep ->
    setDependency(index, dep)
  }
  return build()
}
