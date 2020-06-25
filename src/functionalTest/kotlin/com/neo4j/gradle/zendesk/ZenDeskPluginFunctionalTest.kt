package com.neo4j.gradle.zendesk

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import okhttp3.mockwebserver.MockWebServer
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull


class ZenDeskPluginFunctionalTest {

  private val yaml = Yaml()

  @Test
  fun `run task on empty directory`() {
    // Setup the test build
    val workspaceDir = File("build/functionalTest")
    workspaceDir.mkdirs()
    val emptyDir = File("build/functionalTest/empty")
    emptyDir.mkdirs()
    workspaceDir.resolve("settings.gradle").writeText("")
    workspaceDir.resolve("build.gradle").writeText("""
import com.neo4j.gradle.zendesk.ZenDeskUploadTask

plugins {
  id('com.neo4j.gradle.zendesk.ZenDeskPlugin')
}

zendesk {
  email = 'user@domain.com'
  apiToken = 'abcd'
  host = 'localhost'
  scheme = 'http'
}

task zenDeskUpload(type: ZenDeskUploadTask) {
  source = "empty"
  userSegmentId = 1
  permissionGroupId = 456
  sectionId = 360001234567
}
""")

    // Run the build
    val runner = GradleRunner.create()
    runner.forwardOutput()
    runner.withPluginClasspath()
    runner.withArguments("zenDeskUpload")
    runner.withProjectDir(workspaceDir)
    val result = runner.build()

    val task = result.task(":zenDeskUpload")
    assertEquals(TaskOutcome.SUCCESS, task?.outcome)
  }

  @Test
  fun `should create a new article`() {
    // Setup mock server to simulate ZenDesk
    val zenDeskMockServer = ZenDeskMockServer()
    val server = zenDeskMockServer.setup()
    try {
      server.start()
      val testFiles = listOf(FileWithMetadata(
        fileNameWithoutExtension = "test",
        fileContent = """<section>
  <h2>Introduction to Neo4j 4.0</h2>
</section>""",
        metadataContent = yaml.dump(mapOf(
          "slug" to "00-intro-neo4j-about",
          "title" to "Introduction to Neo4j 4.0"
        ))
      ))
      val workspaceDir = setupWorkspace(Workspace("html", basicZenDeskUploadBuildGradle("html", server)), testFiles)

      // Run the build
      val runner = GradleRunner.create()
      runner.forwardOutput()
      runner.withPluginClasspath()
      runner.withArguments(":zenDeskUpload")
      runner.withProjectDir(workspaceDir)
      val result = runner.build()

      assertEquals(zenDeskMockServer.dataReceived.size, 1)
      val postJson = zenDeskMockServer.dataReceived.first()
      val article = postJson.obj("article")
      assertEquals(postJson.boolean("notify_subscribers"), true)
      assertNotNull(article)
      val labelNames = article.array<JsonObject>("label_names")
      assertNotNull(labelNames)
      assertEquals(labelNames.value.size, 0)
      assertEquals(article["position"] as Int, 1000000) // default value
      assertEquals(article["promoted"] as Boolean, false) // default value
      assertEquals(article["user_segment_id"] as Int, 123)
      assertEquals(article["permission_group_id"] as Int, 456)
      assertEquals(article["title"] as String, "Introduction to Neo4j 4.0")
      assertEquals(article["body"] as String, """<section>
  <h2>Introduction to Neo4j 4.0</h2>
</section>
<!-- METADATA! {"slug":"00-intro-neo4j-about","digest":"d2987a9f064fa2a1416adc87a3c1c6f1"} !METADATA -->""")
      val task = result.task(":zenDeskUpload")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `should create a new article (notification disabled)`() {
    // Setup mock server to simulate ZenDesk
    val zenDeskMockServer = ZenDeskMockServer()
    val server = zenDeskMockServer.setup()
    try {
      server.start()
      val testFiles = listOf(FileWithMetadata(
        fileNameWithoutExtension = "test",
        fileContent = """<section>
  <h2>Introduction to Neo4j 4.1</h2>
</section>""",
        metadataContent = yaml.dump(mapOf(
          "slug" to "00-intro-neo4j-4-1",
          "title" to "Introduction to Neo4j 4.1"
        ))
      ))
      val workspaceDir = setupWorkspace(Workspace("html", basicZenDeskUploadBuildGradle("html", server, notifySubscribers = false)), testFiles)

      // Run the build
      val runner = GradleRunner.create()
      runner.forwardOutput()
      runner.withPluginClasspath()
      runner.withArguments(":zenDeskUpload")
      runner.withProjectDir(workspaceDir)
      val result = runner.build()

      assertEquals(zenDeskMockServer.dataReceived.size, 1)
      val postJson = zenDeskMockServer.dataReceived.first()
      val article = postJson.obj("article")
      assertNotNull(article)
      assertEquals(article["title"] as String, "Introduction to Neo4j 4.1")
      assertEquals(postJson.boolean("notify_subscribers"), false)
      val task = result.task(":zenDeskUpload")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `should update an existing article by id`() {
    // Setup mock server to simulate ZenDesk
    val zenDeskMockServer = ZenDeskMockServer()
    val server = zenDeskMockServer.setup()
    try {
      server.start()
      val testFiles = listOf(FileWithMetadata(
        fileNameWithoutExtension = "test",
        fileContent = """<section>
  <h2>Neo4j 3.5.4 Patch Released ( April 6, 2019 )</h2>
</section>""",
        metadataContent = yaml.dump(mapOf(
          "slug" to "neo4j-3-5-4-patch-released",
          "title" to "Neo4j 3.5.4 Patch Released ( April 6, 2019 )",
          "zendesk_id" to 99,
          "tags" to listOf("release")
        ))
      ))
      val workspaceDir = setupWorkspace(Workspace("update", basicZenDeskUploadBuildGradle("update", server)), testFiles)

      // Run the build
      val runner = GradleRunner.create()
      runner.forwardOutput()
      runner.withPluginClasspath()
      runner.withArguments(":zenDeskUpload")
      runner.withProjectDir(workspaceDir)
      val result = runner.build()

      assertEquals(zenDeskMockServer.dataReceived.size, 2)
      val putTranslationJson = zenDeskMockServer.dataReceived[0]

      assertEquals(putTranslationJson.string("title"), "Neo4j 3.5.4 Patch Released ( April 6, 2019 )")
      assertEquals(putTranslationJson.string("body"), """<section>
  <h2>Neo4j 3.5.4 Patch Released ( April 6, 2019 )</h2>
</section>
<!-- METADATA! {"slug":"neo4j-3-5-4-patch-released","digest":"2d310dc2b66f0e7dcd87dbd36591e33a"} !METADATA -->""")
      assertEquals(putTranslationJson.int("user_segment_id"), 123)
      assertEquals(putTranslationJson.int("permission_group_id"), 456)

      val putArticleJson = zenDeskMockServer.dataReceived[1]
      val article = putArticleJson["article"] as JsonObject
      assertEquals(article.array<String>("label_names").orEmpty().first(), "release")
      assertEquals(article.int("position"), 1000000)
      assertEquals(article.boolean("promoted"), false)

      val task = result.task(":zenDeskUpload")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `should not update an existing article when content has not changed`() {
    // Setup mock server to simulate ZenDesk
    val zenDeskMockServer = ZenDeskMockServer()
    val server = zenDeskMockServer.setup()
    try {
      server.start()
      val testFiles = listOf(FileWithMetadata(
        fileNameWithoutExtension = "test",
        fileContent = "<p>If the opportunity arises such that you are in need of replicating your existing Causal Cluster cluster to a new hardware setup, the following can be used to allow for minimal downtime.</p>",
        metadataContent = yaml.dump(mapOf(
          "slug" to "a-method-to-replicate-a-causal-cluster-to-new-hardware-with-minimum-downtime",
          "title" to "A method to replicate a Causal Cluster to new hardware with minimum downtime",
          "zendesk_id" to 115015697128
        ))
      ))
      val workspaceDir = setupWorkspace(Workspace("content-no-change", basicZenDeskUploadBuildGradle("content-no-change", server)), testFiles)

      // Run the build
      val runner = GradleRunner.create()
      runner.forwardOutput()
      runner.withPluginClasspath()
      runner.withArguments(":zenDeskUpload")
      runner.withProjectDir(workspaceDir)
      val result = runner.build()

      assertEquals(zenDeskMockServer.dataReceived.size, 0)
      val task = result.task(":zenDeskUpload")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    } finally {
      server.shutdown()
    }
  }

  private fun basicZenDeskUploadBuildGradle(workspaceDir: String, server: MockWebServer, notifySubscribers: Boolean = true): String {
    return """import com.neo4j.gradle.zendesk.ZenDeskUploadTask

plugins {
  id('com.neo4j.gradle.zendesk.ZenDeskPlugin')
}

zendesk {
  email = 'user@domain.com'
  apiToken = 'abcd'
  port = ${server.port}
  host = '${server.hostName}'
  scheme = 'http'
}

task zenDeskUpload(type: ZenDeskUploadTask) {
  source = "$workspaceDir"
  userSegmentId = 123
  permissionGroupId = 456
  sectionId = 789
  notifySubscribers = $notifySubscribers
}"""
  }

  private fun setupWorkspace(workspace: Workspace, filesWithMetadata: List<FileWithMetadata>): File {
    // Setup the test build
    val workspaceDir = File("build/functionalTest")
    workspaceDir.mkdirs()
    val dir = File("build/functionalTest/${workspace.directoryName}")
    dir.mkdirs()
    filesWithMetadata.forEach {
      dir.resolve("${it.fileNameWithoutExtension}.html").writeText(it.fileContent)
      dir.resolve("${it.fileNameWithoutExtension}.yml").writeText(it.metadataContent.trimIndent())
    }
    workspaceDir.resolve("settings.gradle").writeText("")
    workspaceDir.resolve("build.gradle").writeText(workspace.buildGradleContent)
    return workspaceDir
  }

  data class FileWithMetadata(val fileNameWithoutExtension: String, val fileContent: String, val metadataContent: String)
  data class Workspace(val directoryName: String, val buildGradleContent: String)
}
