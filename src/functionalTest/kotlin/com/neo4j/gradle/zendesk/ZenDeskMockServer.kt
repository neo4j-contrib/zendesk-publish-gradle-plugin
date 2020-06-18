package com.neo4j.gradle.zendesk

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.StringReader


class ZenDeskMockServer {
  private val server = MockWebServer()
  val klaxon = Klaxon()
  var dataReceived = mutableListOf<JsonObject>()
  var autoGeneratedId = 100
  fun setup(): MockWebServer {
    val articles = readText("/articles.json")
    // Setup mock server to simulate ZenDesk
    val dispatcher: Dispatcher = object : Dispatcher() {
      @Throws(InterruptedException::class)
      override fun dispatch(request: RecordedRequest): MockResponse {
        when (request.path) {
          "/api/v2/help_center/en-us/sections/789/articles.json" -> {
            if (request.method == "GET") {
              return MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(articles)
                .setResponseCode(200)
            }
            if (request.method == "POST") {
              val data = klaxon.parseJsonObject(StringReader(request.body.readUtf8()))
              dataReceived.add(data)
              return MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"article": { "id": ${autoGeneratedId++} } }""")
                .setResponseCode(201)
            }
          }
          "/api/v2/help_center/articles/99/translations/en-us.json" -> {
            if (request.method == "PUT") {
              val data = klaxon.parseJsonObject(StringReader(request.body.readUtf8()))
              dataReceived.add(data)
              return MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"translation": { "id": 99 } }""")
                .setResponseCode(200)
            }
          }
          "/api/v2/help_center/articles/115015697128/translations/en-us.json" -> {
            if (request.method == "PUT") {
              val data = klaxon.parseJsonObject(StringReader(request.body.readUtf8()))
              dataReceived.add(data)
              return MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"translation": { "id": 115015697128 } }""")
                .setResponseCode(200)
            }
          }
          "/api/v2/help_center/en-us/articles/99.json" -> {
            if (request.method == "PUT") {
              val data = klaxon.parseJsonObject(StringReader(request.body.readUtf8()))
              dataReceived.add(data)
              return MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"article": { "id": 99 } }""")
                .setResponseCode(200)
            }
          }
          "/api/v2/help_center/en-us/articles/115015697128.json" -> {
            if (request.method == "PUT") {
              val data = klaxon.parseJsonObject(StringReader(request.body.readUtf8()))
              dataReceived.add(data)
              return MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"article": { "id": 115015697128 } }""")
                .setResponseCode(200)
            }
          }
        }
        return MockResponse().setResponseCode(404)
      }
    }
    server.dispatcher = dispatcher
    return server
  }

  private fun readText(fileName: String): String {
    return ZenDeskMockServer::class.java.getResourceAsStream(fileName).reader().readText()
  }
}
