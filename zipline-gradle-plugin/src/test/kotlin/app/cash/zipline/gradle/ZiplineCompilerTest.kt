/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.cash.zipline.gradle

import app.cash.zipline.QuickJs
import app.cash.zipline.loader.CURRENT_ZIPLINE_VERSION
import app.cash.zipline.loader.ZiplineFile
import app.cash.zipline.loader.ZiplineManifest
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Test

class ZiplineCompilerTest {
  private val quickJs = QuickJs.create()

  @After
  fun after() {
    quickJs.close()
  }

  @Test
  fun `write to and read from zipline`() {
    assertZiplineCompile("src/test/resources/happyPath/", true) {
      val readZiplineFile = it.source().buffer().use { source ->
        ZiplineFile.read(source)
      }
      assertEquals(CURRENT_ZIPLINE_VERSION, readZiplineFile.ziplineVersion)
      quickJs.execute(readZiplineFile.quickjsBytecode.toByteArray())
      val exception = assertFailsWith<Exception> {
        quickJs.evaluate("demo.sayHello()", "test.js")
      }
      // .kt files in the stacktrace means that the sourcemap was applied correctly.
      assertThat(exception.stackTraceToString()).startsWith(
        """
          |app.cash.zipline.QuickJsException: boom!
          |	at JavaScript.goBoom1(throwException.kt)
          |	at JavaScript.goBoom2(throwException.kt:9)
          |	at JavaScript.goBoom3(throwException.kt:6)
          |	at JavaScript.sayHello(throwException.kt:3)
          |	at JavaScript.<eval>(test.js)
          |""".trimMargin()
      )
    }
  }

  @Test
  fun `no source map`() {
    assertZiplineCompile("src/test/resources/happyPathNoSourceMap/", false) {
      val readZiplineFile = it.source().buffer().use { source ->
        ZiplineFile.read(source)
      }
      assertEquals(CURRENT_ZIPLINE_VERSION, readZiplineFile.ziplineVersion)
      quickJs.execute(readZiplineFile.quickjsBytecode.toByteArray())
      assertEquals("Hello, guy!", quickJs.evaluate("greet('guy')", "test.js"))
    }
  }

  @Test
  fun `js with imports and exports`() {
    assertZiplineCompile("src/test/resources/jsWithImportsExports/", false) {
      quickJs.execute(it.source().buffer().use { source ->
        ZiplineFile.read(source)
      }.quickjsBytecode.toByteArray())
    }
  }

  @Test
  fun `incremental compile`() {
    val rootProject = "src/test/resources/incremental"
    val outputDir = File("$rootProject/base/build/zipline")

    // Clean up dir from previous runs
    if (outputDir.exists()) {
      outputDir.deleteRecursively()
    }

    // Start with base compile to generate manifest and starting files
    assertZiplineCompile("$rootProject/base", false) {
      quickJs.execute(it.source().buffer().use { source ->
        ZiplineFile.read(source)
      }.quickjsBytecode.toByteArray())
    }

    assertZiplineIncrementalCompile("$rootProject/base",
      addedFiles = File("$rootProject/added").listFiles()!!.asList(),
      modifiedFiles = File("$rootProject/modified").listFiles()!!.asList(),
      removedFiles = File("$rootProject/removed").listFiles()!!.asList()){
      quickJs.execute(it.source().buffer().use { source ->
        ZiplineFile.read(source)
      }.quickjsBytecode.toByteArray())
    }

    // Jello file was removed
    assertFalse(File("$outputDir/jello.zipline").exists())
    // Bello file was added
    quickJs.execute(readZiplineFile(File("$outputDir/bello.zipline")).quickjsBytecode.toByteArray())
    assertEquals("Bello!", quickJs.evaluate("bello()", "test.js"))
    // Hello file was replaced with bonjour
    quickJs.execute(readZiplineFile(File("$outputDir/hello.zipline")).quickjsBytecode.toByteArray())
    assertEquals("Bonjour, guy!", quickJs.evaluate("greet('guy')", "test.js"))
    // Yello file remains untouched
    quickJs.execute(readZiplineFile(File("$outputDir/yello.zipline")).quickjsBytecode.toByteArray())
    assertEquals("HELLO", quickJs.evaluate("greet()", "test.js"))
  }

  private fun readZiplineFile(file: File): ZiplineFile {
    val readZiplineFile = file.source().buffer().use { source ->
      ZiplineFile.read(source)
    }
    assertEquals(CURRENT_ZIPLINE_VERSION, readZiplineFile.ziplineVersion)
    return readZiplineFile
  }

  private fun assertZiplineCompile(
    rootProject: String,
    dirHasSourceMaps: Boolean,
    nonManifestFileAssertions: (ziplineFile: File) -> Unit
  ) {
    val inputDir = File("$rootProject/jsBuild")
    val outputDir = File("$rootProject/build/zipline")
    outputDir.mkdirs()

    val mainModuleId = "./app.js"
    val mainFunction = "zipline.ziplineMain()"
    ZiplineCompiler.compile(
      inputDir = inputDir,
      outputDir = outputDir,
      mainFunction = mainFunction,
      mainModuleId = mainModuleId
    )

    val expectedNumberFiles = if (dirHasSourceMaps) inputDir.listFiles()!!.size / 2 else inputDir.listFiles()!!.size
    // Don't include Zipline manifest
    val actualNumberFiles = (outputDir.listFiles()?.size ?: 0) - 1
    assertEquals(expectedNumberFiles, actualNumberFiles)

    assertCompileResult(outputDir, mainModuleId, mainFunction, nonManifestFileAssertions)
  }

  private fun assertZiplineIncrementalCompile(
    rootProject: String,
    modifiedFiles: List<File>,
    addedFiles: List<File>,
    removedFiles: List<File>,
    nonManifestFileAssertions: (ziplineFile: File) -> Unit
  ) {
    val inputDir = File("$rootProject/jsBuild")
    val outputDir = File("$rootProject/build/zipline")
    outputDir.mkdirs()

    val mainModuleId = "./app.js"
    val mainFunction = "zipline.ziplineMain()"
    ZiplineCompiler.incrementalCompile(
      outputDir = outputDir,
      mainFunction = mainFunction,
      mainModuleId = mainModuleId,
      modifiedFiles = modifiedFiles,
      addedFiles = addedFiles,
      removedFiles = removedFiles
    )

    val expectedNumberFiles = inputDir.listFiles()!!.size + addedFiles.size - removedFiles.size
    // Don't include Zipline manifest
    val actualNumberFiles = (outputDir.listFiles()?.size ?: 0) - 1
    assertEquals(expectedNumberFiles, actualNumberFiles)

    assertCompileResult(outputDir, mainModuleId, mainFunction, nonManifestFileAssertions)
  }

  private fun assertCompileResult(
    outputDir: File,
    mainModuleId: String,
    mainFunction: String,
    nonManifestFileAssertions: (ziplineFile: File) -> Unit
  ) {
    // Load and parse manifest
    val manifestFile = File(outputDir, "manifest.zipline.json")
    val manifestString = manifestFile.readText()
    val manifest = Json.decodeFromString<ZiplineManifest>(manifestString)

    // Confirm that mainModuleId and mainFunction have been added to the manifest
    assertEquals(mainModuleId, manifest.mainModuleId)
    assertEquals(mainFunction, manifest.mainFunction)

    // Confirm that all manifest files are present in outputDir
    outputDir.listFiles()!!.forEach { ziplineFile ->
      manifest.modules.keys.contains(ziplineFile.path)
    }

    // Iterate over files by Manifest order
    manifest.modules.keys.forEach { ziplineFilePath ->
      // Ignore the Zipline Manifest JSON file
      if (!ziplineFilePath.endsWith(".zipline.json")) nonManifestFileAssertions(
        File(outputDir, ziplineFilePath
          .removePrefix("./")
          .replace(".js", ".zipline")
        )
      )
    }
  }
}
