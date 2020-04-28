package com.neo4j.gradle.zendesk

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull


class ZenDeskPluginFunctionalTest {

  private val klaxon = Klaxon()
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
  userSegmentId = 123
  permissionGroupId = 456
  sectionId = 789
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
    var postJson = JsonObject()
    // Setup mock server to simulate ZenDesk
    val dispatch = { request: RecordedRequest ->
      when (request.method) {
        "POST" -> {
          when (request.path) {
            "/api/v2/help_center/en-us/sections/789/articles.json" -> {
              postJson = klaxon.parseJsonObject(StringReader(request.body.readUtf8()))
              MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"article": { "id": 1 } }""")
                .setResponseCode(201)
            }
            else -> MockResponse().setResponseCode(404)
          }
        }
        "GET" -> {
          when (request.path) {
            "/api/v2/help_center/en-us/sections/789/articles.json" -> {
              MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"articles": [], "count": 0, "page": 1 }""")
                .setResponseCode(200)
            }
            else -> MockResponse().setResponseCode(404)
          }
        }
        else -> MockResponse().setResponseCode(404)
      }
    }
    withServer(dispatch) { server ->
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

      val labelNames = postJson.array<JsonObject>("label_names")
      assertNotNull(labelNames)
      assertEquals(labelNames.value.size, 0)
      assertEquals(postJson["position"] as Int, 1000000) // default value
      assertEquals(postJson["promoted"] as Boolean, false) // default value
      assertEquals(postJson["user_segment_id"] as Int, 123)
      assertEquals(postJson["permission_group_id"] as Int, 456)
      assertEquals(postJson["title"] as String, "Introduction to Neo4j 4.0")
      assertEquals(postJson["body"] as String, """<section>
  <h2>Introduction to Neo4j 4.0</h2>
</section>
<!-- METADATA! {"slug":"00-intro-neo4j-about"} !METADATA -->""")
      val task = result.task(":zenDeskUpload")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    }
  }

  @Test
  fun `should update an existing article by id`() {
    val articleJson = readText("/neo4j-3-5-4-released-article-99.json")
    var putArticleJson = JsonObject()
    var putTranslationJson = JsonObject()
    val dispatch = { request: RecordedRequest ->
      when (request.method) {
        "PUT" -> {
          when (request.path) {
            "/api/v2/help_center/articles/99/translations/en-us.json" -> {
              putTranslationJson = klaxon.parseJsonObject(StringReader(request.body.readUtf8()))
              MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"translation": { "id": 501 } }""")
                .setResponseCode(200)
            }
            "/api/v2/help_center/en-us/articles/99.json" -> {
              putArticleJson = klaxon.parseJsonObject(StringReader(request.body.readUtf8()))
              MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"article": { "id": 99 } }""")
                .setResponseCode(200)
            }
            else -> MockResponse().setResponseCode(404)
          }
        }
        "GET" -> {
          when (request.path) {
            "/api/v2/help_center/en-us/sections/789/articles.json" -> {
              MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"articles": [$articleJson], "count": 1, "page": 1 }""")
                .setResponseCode(200)
            }
            else -> MockResponse().setResponseCode(404)
          }
        }
        else -> MockResponse().setResponseCode(404)
      }
    }
    withServer(dispatch) { server ->
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

      assertEquals(putTranslationJson.string("title"), "Neo4j 3.5.4 Patch Released ( April 6, 2019 )")
      assertEquals(putTranslationJson.string("body"), """<section>
  <h2>Neo4j 3.5.4 Patch Released ( April 6, 2019 )</h2>
</section>
<!-- METADATA! {"slug":"neo4j-3-5-4-patch-released"} !METADATA -->""")
      assertEquals(putTranslationJson.int("user_segment_id"), 123)
      assertEquals(putTranslationJson.int("permission_group_id"), 456)

      val article = putArticleJson["article"] as JsonObject
      assertEquals(article.array<String>("label_names").orEmpty().first(), "release")
      assertEquals(article.int("position"), 1000000)
      assertEquals(article.boolean("promoted"), false)

      val task = result.task(":zenDeskUpload")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    }
  }

  private fun basicZenDeskUploadBuildGradle(workspaceDir: String, server: MockWebServer): String {
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

  private fun withServer(dispatch: (RecordedRequest) -> MockResponse, test: (MockWebServer) -> Unit) {
    val server = MockWebServer()
    val dispatcher: Dispatcher = object : Dispatcher() {
      @Throws(InterruptedException::class)
      override fun dispatch(request: RecordedRequest): MockResponse {
        return dispatch(request)
      }
    }
    server.dispatcher = dispatcher
    try {
      server.start()
      test(server)
    } finally {
      server.shutdown()
    }
  }

  private fun readText(fileName: String): String {
    return ZenDeskPluginFunctionalTest::class.java.getResourceAsStream(fileName).reader().readText()
  }

  data class FileWithMetadata(val fileNameWithoutExtension: String, val fileContent: String, val metadataContent: String)
  data class Workspace(val directoryName: String, val buildGradleContent: String)
}
