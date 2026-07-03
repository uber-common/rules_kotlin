/*
 * Copyright 2025 The Bazel Authors. All rights reserved.
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

package io.bazel.kotlin.builder.tasks.jvm

import io.bazel.kotlin.builder.utils.ArgMap
import io.bazel.kotlin.builder.utils.ArgMaps
import io.bazel.kotlin.builder.utils.Flag
import io.bazel.worker.Status
import io.bazel.worker.Work
import io.bazel.worker.WorkerContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.regex.Pattern
import java.util.zip.ZipFile

/**
 * KSP2 worker task.
 *
 * Executes KSP2 symbol processing entirely within the worker:
 * 1. Stages source files to a temporary directory (for worker isolation)
 * 2. Unpacks srcjars to a temporary directory
 * 3. Runs KSP2 via the cached Ksp2Invoker
 * 4. Packages generated sources/classes into output JARs
 *
 * This is a separate command from the main Build command for cleaner separation.
 */
class Ksp2Task : Work {
  // Cache processor classloaders across worker invocations, keyed by the (sorted) processor
  // classpath. The persistent worker reuses a single Ksp2Task instance, so without this cache a
  // fresh URLClassLoader is created for every action; the loaded class metadata is never reclaimed
  // and the worker eventually dies with `OutOfMemoryError: Compressed class space`. Targets sharing
  // the same KSP processors now reuse one classloader instead of creating hundreds.
  private val classLoaderCache = ConcurrentHashMap<List<String>, ClassLoaderEntry>()

  companion object {
    private val FLAGFILE_RE = Pattern.compile("""^--flagfile=((.*)-(\d+).params)$""").toRegex()

    enum class Ksp2Flags(
      override val flag: String,
    ) : Flag {
      MODULE_NAME("--module_name"),
      SOURCES("--sources"),
      SOURCE_JARS("--source_jars"),
      LIBRARIES("--libraries"),
      PROCESSOR_CLASSPATH("--processor_classpath"),
      GENERATED_SOURCES_OUTPUT("--generated_sources_output"),
      GENERATED_CLASSES_OUTPUT("--generated_classes_output"),
      LANGUAGE_VERSION("--language_version"),
      API_VERSION("--api_version"),
      JVM_TARGET("--jvm_target"),
      JDK_HOME("--jdk_home"),
      KSP_OPTIONS("--ksp_options"),
      EXPERIMENTAL_PSI_RESOLUTION("--experimental_psi_resolution"),
    }

    fun parseKspOptions(entries: List<String>): Map<String, String> =
      entries.associate { entry ->
        val eqIdx = entry.indexOf('=')
        if (eqIdx >= 0) entry.substring(0, eqIdx) to entry.substring(eqIdx + 1) else entry to ""
      }

    /**
     * Fingerprints a processor classpath by path, size, and jar contents. Bazel can reuse the same
     * output path after rebuilding a processor, so a path-only cache key is insufficient.
     */
    fun fingerprintOf(sortedClasspath: List<String>): String {
      val digest = MessageDigest.getInstance("SHA-256")
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      for (path in sortedClasspath) {
        val file = File(path)
        digest.update(path.toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        digest.update(file.length().toString().toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        Files.newInputStream(file.toPath()).use { input ->
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
          }
        }
      }
      return digest.digest().joinToString("") { "%02x".format(it) }
    }
  }

  override fun invoke(
    ctx: WorkerContext.TaskContext,
    args: Iterable<String>,
  ): Status {
    val argsList = args.toList()
    check(argsList.isNotEmpty()) { "expected at least a single arg" }

    val lines =
      FLAGFILE_RE.matchEntire(argsList[0])?.groups?.get(1)?.let {
        Files.readAllLines(FileSystems.getDefault().getPath(it.value), StandardCharsets.UTF_8)
      } ?: argsList

    val argMap = ArgMaps.from(lines)

    return if (execute(ctx, argMap) == 0) Status.SUCCESS else Status.ERROR
  }

  private fun execute(
    taskContext: WorkerContext.TaskContext,
    argMap: ArgMap,
  ): Int {
    val workingDir = taskContext.directory
    val moduleName = argMap.mandatorySingle(Ksp2Flags.MODULE_NAME)

    // Create temporary directories for KSP2 processing
    val kspWorkDir = workingDir.resolve("_ksp2").resolve(moduleName)
    val stagedSourcesDir = kspWorkDir.resolve("staged_sources")
    val kotlinOutputDir = kspWorkDir.resolve("kotlin_out")
    val javaOutputDir = kspWorkDir.resolve("java_out")
    val classOutputDir = kspWorkDir.resolve("class_out")
    val resourceOutputDir = kspWorkDir.resolve("resource_out")
    val cachesDir = kspWorkDir.resolve("caches")

    listOf(
      stagedSourcesDir,
      kotlinOutputDir,
      javaOutputDir,
      classOutputDir,
      resourceOutputDir,
      cachesDir,
    ).forEach {
      Files.createDirectories(it)
    }

    try {
      // Stage source files to isolated directory
      val sourceRoots = mutableSetOf<String>()
      val javaSourceRoots = mutableSetOf<String>()

      // Stage individual source files
      val sources = argMap.optional(Ksp2Flags.SOURCES) ?: emptyList()
      for (source in sources) {
        val sourceFile = File(source)
        val targetFile = stagedSourcesDir.resolve(source).toFile()
        targetFile.parentFile?.mkdirs()
        sourceFile.copyTo(targetFile, overwrite = true)

        // Track source roots (directories containing sources)
        val sourceRoot =
          if (sourceFile.parentFile != null) {
            stagedSourcesDir.resolve(sourceFile.parentFile.path).toString()
          } else {
            stagedSourcesDir.toString()
          }
        sourceRoots.add(sourceRoot)
        if (source.endsWith(".java")) {
          javaSourceRoots.add(sourceRoot)
        }
      }

      // Unpack srcjars directly
      val srcjars = argMap.optional(Ksp2Flags.SOURCE_JARS) ?: emptyList()
      for (srcjar in srcjars) {
        ZipFile(srcjar).use { zip ->
          zip.entries().asSequence().forEach { entry ->
            if (!entry.isDirectory) {
              val targetFile = stagedSourcesDir.resolve(entry.name).toFile()
              targetFile.parentFile?.mkdirs()
              zip.getInputStream(entry).use { input ->
                targetFile.outputStream().use { output ->
                  input.copyTo(output)
                }
              }
              // Track source root for srcjar contents
              val parentDir = targetFile.parentFile?.path ?: stagedSourcesDir.toString()
              sourceRoots.add(parentDir)
              if (entry.name.endsWith(".java")) {
                javaSourceRoots.add(parentDir)
              }
            }
          }
        }
      }

      // If no sources, add a placeholder source root
      if (sourceRoots.isEmpty()) {
        sourceRoots.add(stagedSourcesDir.toString())
      }

      // Reuse a cached classloader (keyed by processor classpath) to avoid exhausting compressed
      // class space across the many actions a persistent worker handles.
      val processorClasspath = argMap.optional(Ksp2Flags.PROCESSOR_CLASSPATH) ?: emptyList()
      val entry = getOrCreateEntry(processorClasspath)

      val processorOptions = parseKspOptions(argMap.optional(Ksp2Flags.KSP_OPTIONS) ?: emptyList())
      val experimentalPsiResolution =
        argMap.optionalSingle(Ksp2Flags.EXPERIMENTAL_PSI_RESOLUTION)?.toBoolean() ?: false

      val invoker =
        entry.invokerClass
          .getConstructor(ClassLoader::class.java)
          .newInstance(entry.classLoader)

      // Execute KSP2
      val code =
        entry.executeMethod.invoke(
          invoker,
          moduleName,
          sourceRoots.map { File(it) },
          javaSourceRoots.map { File(it) },
          argMap.optional(Ksp2Flags.LIBRARIES)?.map { File(it) } ?: emptyList<File>(),
          kotlinOutputDir.toFile(),
          javaOutputDir.toFile(),
          classOutputDir.toFile(),
          resourceOutputDir.toFile(),
          cachesDir.toFile(),
          kspWorkDir.toFile(), // projectBaseDir
          kspWorkDir.toFile(), // outputBaseDir
          argMap.optionalSingle(Ksp2Flags.JVM_TARGET),
          argMap.optionalSingle(Ksp2Flags.LANGUAGE_VERSION),
          argMap.optionalSingle(Ksp2Flags.API_VERSION),
          argMap.optionalSingle(Ksp2Flags.JDK_HOME)?.let { File(it) },
          processorOptions,
          experimentalPsiResolution,
          1, // logLevel
        ) as Int

      if (code != 0) {
        taskContext.error { "KSP2 failed with exit code: $code" }
        return code
      }

      // Package generated sources into srcjar
      val generatedSourcesOutput = argMap.mandatorySingle(Ksp2Flags.GENERATED_SOURCES_OUTPUT)
      packageDirectoriesToJar(
        outputPath = generatedSourcesOutput,
        directories = listOf(kotlinOutputDir, javaOutputDir),
      )

      // Package generated classes/resources into jar
      val generatedClassesOutput = argMap.mandatorySingle(Ksp2Flags.GENERATED_CLASSES_OUTPUT)
      packageDirectoriesToJar(
        outputPath = generatedClassesOutput,
        directories = listOf(classOutputDir, resourceOutputDir),
      )
      return 0
    } catch (e: Exception) {
      taskContext.error(e) { "KSP2 execution failed" }
      return 1
    } finally {
      // Clean up temporary directories
      try {
        kspWorkDir.toFile().deleteRecursively()
      } catch (_: Exception) {
        // Ignore cleanup errors
      }
    }
  }

  private fun getOrCreateEntry(processorClasspath: List<String>): ClassLoaderEntry {
    // Sort for cache matching, but keep the original order for the URLClassLoader so class
    // loading priority is preserved.
    val key = processorClasspath.sorted()
    val fingerprint = fingerprintOf(key)
    return classLoaderCache.compute(key) { _, existing ->
      if (existing != null && existing.fingerprint == fingerprint) {
        return@compute existing
      }

      // A processor jar may have been rebuilt at the same Bazel output path. Close the stale
      // loader before replacing it so repeated edits do not leak class metadata or open jars.
      if (existing != null) {
        runCatching { existing.classLoader.close() }
      }

      val urls = processorClasspath.map { File(it).toURI().toURL() }.toTypedArray()
      // Use the platform classloader (JDK modules only, no Kotlin classes) as parent so the
      // processor loads its own kotlin-stdlib/kotlin-reflect from its classpath into one
      // classloader, keeping Kotlin reflection working in KSP processors.
      val cl = URLClassLoader(urls, ClassLoader.getPlatformClassLoader())
      val invokerClass = cl.loadClass("io.bazel.kotlin.ksp2.Ksp2Invoker")
      val executeMethod =
        invokerClass.getMethod(
          "execute",
          String::class.java, // moduleName
          List::class.java, // sourceRoots
          List::class.java, // javaSourceRoots
          List::class.java, // libraries
          File::class.java, // kotlinOutputDir
          File::class.java, // javaOutputDir
          File::class.java, // classOutputDir
          File::class.java, // resourceOutputDir
          File::class.java, // cachesDir
          File::class.java, // projectBaseDir
          File::class.java, // outputBaseDir
          String::class.java, // jvmTarget
          String::class.java, // languageVersion
          String::class.java, // apiVersion
          File::class.java, // jdkHome
          Map::class.java, // processorOptions
          Boolean::class.javaPrimitiveType, // experimentalPsiResolution
          Int::class.java, // logLevel
        )
      ClassLoaderEntry(cl, invokerClass, executeMethod, fingerprint)
    }!!
  }

  /**
   * Package files from directories into a JAR file.
   * Includes directory entries for compatibility with tools that expect them.
   */
  private fun packageDirectoriesToJar(
    outputPath: String,
    directories: List<Path>,
  ) {
    val manifest =
      Manifest().apply {
        mainAttributes.putValue("Manifest-Version", "1.0")
        mainAttributes.putValue("Created-By", "rules_kotlin KSP2")
      }

    JarOutputStream(FileOutputStream(outputPath), manifest).use { jar ->
      val addedEntries = mutableSetOf<String>()

      for (dir in directories) {
        if (!Files.exists(dir)) continue

        Files.walk(dir).use { stream ->
          stream.forEach { path ->
            val relativePath = dir.relativize(path).toString().replace('\\', '/')
            if (relativePath.isEmpty()) return@forEach

            if (Files.isDirectory(path)) {
              // Add directory entry (must end with /)
              val dirEntry = "$relativePath/"
              if (dirEntry !in addedEntries) {
                addedEntries.add(dirEntry)
                jar.putNextEntry(JarEntry(dirEntry))
                jar.closeEntry()
              }
            } else if (Files.isRegularFile(path)) {
              // Ensure parent directories are added first
              val parts = relativePath.split("/")
              var parentPath = ""
              for (i in 0 until parts.size - 1) {
                parentPath += parts[i] + "/"
                if (parentPath !in addedEntries) {
                  addedEntries.add(parentPath)
                  jar.putNextEntry(JarEntry(parentPath))
                  jar.closeEntry()
                }
              }

              // Add file entry
              if (relativePath !in addedEntries) {
                addedEntries.add(relativePath)
                jar.putNextEntry(JarEntry(relativePath))
                Files.copy(path, jar)
                jar.closeEntry()
              }
            }
          }
        }
      }
    }
  }
}
