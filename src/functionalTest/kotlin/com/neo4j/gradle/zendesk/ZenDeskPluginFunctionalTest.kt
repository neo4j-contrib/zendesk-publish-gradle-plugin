package com.neo4j.gradle.zendesk

import com.beust.klaxon.JsonObject
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
    workspaceDir.resolve("build.gradle").writeText(
      """
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
"""
    )

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
      val testFiles = listOf(
        FileWithMetadata(
          fileNameWithoutExtension = "test",
          fileContent = """<section>
  <h2>Introduction to Neo4j 4.0</h2>
</section>""",
          metadataContent = yaml.dump(
            mapOf(
              "slug" to "00-intro-neo4j-about",
              "title" to "Introduction to Neo4j 4.0"
            )
          )
        )
      )
      val workspaceDir = setupWorkspace(Workspace("html", basicZenDeskUploadBuildGradle("html", server)), testFiles)

      // Run the build
      val runner = GradleRunner.create()
      runner.forwardOutput()
      runner.withPluginClasspath()
      runner.withArguments(":zenDeskUpload")
      runner.withProjectDir(workspaceDir)
      val result = runner.build()

      assertEquals(1, zenDeskMockServer.dataReceived.size)
      val postJson = zenDeskMockServer.dataReceived.first()
      val article = postJson.obj("article")
      assertEquals(true, postJson.boolean("notify_subscribers"))
      assertNotNull(article)
      val labelNames = article.array<JsonObject>("label_names")
      assertNotNull(labelNames)
      assertEquals(0, labelNames.value.size)
      assertEquals(1000000, article["position"] as Int) // default value
      assertEquals(false, article["promoted"] as Boolean) // default value
      assertEquals(123, article["user_segment_id"] as Int)
      assertEquals(456, article["permission_group_id"] as Int)
      assertEquals("Introduction to Neo4j 4.0", article["title"] as String)
      assertEquals(
        """<section>
  <h2>Introduction to Neo4j 4.0</h2>
</section>
<!-- METADATA! {"slug":"00-intro-neo4j-about","digest":"5fa88c95ef96082130375a64836a2bf4"} !METADATA -->""",
        article["body"] as String
      )
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
      val testFiles = listOf(
        FileWithMetadata(
          fileNameWithoutExtension = "test",
          fileContent = """<section>
  <h2>Introduction to Neo4j 4.1</h2>
</section>""",
          metadataContent = yaml.dump(
            mapOf(
              "slug" to "00-intro-neo4j-4-1",
              "title" to "Introduction to Neo4j 4.1"
            )
          )
        )
      )
      val workspaceDir = setupWorkspace(
        Workspace("html", basicZenDeskUploadBuildGradle("html", server, notifySubscribers = false)),
        testFiles
      )

      // Run the build
      val runner = GradleRunner.create()
      runner.forwardOutput()
      runner.withPluginClasspath()
      runner.withArguments(":zenDeskUpload")
      runner.withProjectDir(workspaceDir)
      val result = runner.build()

      assertEquals(1, zenDeskMockServer.dataReceived.size)
      val postJson = zenDeskMockServer.dataReceived.first()
      val article = postJson.obj("article")
      assertNotNull(article)
      assertEquals("Introduction to Neo4j 4.1", article["title"] as String)
      assertEquals(false, postJson.boolean("notify_subscribers"))
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
      val testFiles = listOf(
        FileWithMetadata(
          fileNameWithoutExtension = "test",
          fileContent = """<section>
  <h2>Neo4j 3.5.4 Patch Released ( April 6, 2019 )</h2>
</section>""",
          metadataContent = yaml.dump(
            mapOf(
              "slug" to "neo4j-3-5-4-patch-released",
              "title" to "Neo4j 3.5.4 Patch Released ( April 6, 2019 )",
              "zendesk_id" to 99,
              "tags" to listOf("release")
            )
          )
        )
      )
      val workspaceDir = setupWorkspace(Workspace("update", basicZenDeskUploadBuildGradle("update", server)), testFiles)

      // Run the build
      val runner = GradleRunner.create()
      runner.forwardOutput()
      runner.withPluginClasspath()
      runner.withArguments(":zenDeskUpload")
      runner.withProjectDir(workspaceDir)
      val result = runner.build()

      assertEquals(2, zenDeskMockServer.dataReceived.size)
      val putTranslationJson = zenDeskMockServer.dataReceived[0]

      assertEquals("Neo4j 3.5.4 Patch Released ( April 6, 2019 )", putTranslationJson.string("title"))
      assertEquals(
        """<section>
  <h2>Neo4j 3.5.4 Patch Released ( April 6, 2019 )</h2>
</section>
<!-- METADATA! {"slug":"neo4j-3-5-4-patch-released","digest":"8d958b15c3e277481363e84db855294f"} !METADATA -->""",
        putTranslationJson.string("body")
      )
      assertEquals(123, putTranslationJson.int("user_segment_id"))
      assertEquals(456, putTranslationJson.int("permission_group_id"))

      val putArticleJson = zenDeskMockServer.dataReceived[1]
      val article = putArticleJson["article"] as JsonObject
      assertEquals("release", (article.array<String>("label_names").orEmpty().first()))
      assertEquals(1000000, article.int("position"))
      assertEquals(false, article.boolean("promoted"))

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
      val testFiles = listOf(
        FileWithMetadata(
          fileNameWithoutExtension = "test",
          fileContent = "<p>If the opportunity arises such that you are in need of replicating your existing Causal Cluster cluster to a new hardware setup, the following can be used to allow for minimal downtime.</p>",
          metadataContent = yaml.dump(
            mapOf(
              "slug" to "a-method-to-replicate-a-causal-cluster-to-new-hardware-with-minimum-downtime",
              "title" to "A method to replicate a Causal Cluster to new hardware with minimum downtime",
              "zendesk_id" to 115015697128,
              "comments_disabled" to true
            )
          )
        )
      )
      val workspaceDir = setupWorkspace(
        Workspace("content-no-change", basicZenDeskUploadBuildGradle("content-no-change", server)),
        testFiles
      )

      // Run the build
      val runner = GradleRunner.create()
      runner.forwardOutput()
      runner.withPluginClasspath()
      runner.withArguments(":zenDeskUpload")
      runner.withProjectDir(workspaceDir)
      runner.withDebug(true)
      val result = runner.build()

      assertEquals(0, zenDeskMockServer.dataReceived.size)
      val task = result.task(":zenDeskUpload")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    } finally {
      server.shutdown()
    }
  }

  private fun basicZenDeskUploadBuildGradle(
    workspaceDir: String,
    server: MockWebServer,
    notifySubscribers: Boolean = true
  ): String {
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

  data class FileWithMetadata(
    val fileNameWithoutExtension: String,
    val fileContent: String,
    val metadataContent: String
  )

  data class Workspace(val directoryName: String, val buildGradleContent: String)
}
