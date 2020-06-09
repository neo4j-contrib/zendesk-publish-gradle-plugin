package com.neo4j.gradle.zendesk

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.KlaxonException
import org.gradle.api.logging.Logger

class ZenDeskUsers(private val zenDeskHttpClient: ZenDeskHttpClient, private val logger: Logger) {

  /**
   * Find a user from an author.
   */
  fun findUser(author: Author): ZenDeskUser? {
    if (author.email == null && author.name == null) {
      return null
    }
    val query = if (author.email != null) {
      "type:user email:${author.email}"
    } else {
      val baseQuery = "type:user name:${author.name}"
      val tagsFilter = author.tags.joinToString(" ") { "tags: $it" }
      if (tagsFilter.isNotBlank()) {
        "$baseQuery $tagsFilter"
      } else {
        baseQuery
      }
    }
    val url = zenDeskHttpClient.baseUrlBuilder()
      .addPathSegment("search.json")
      .addQueryParameter("query", query)
      .build()
    return zenDeskHttpClient.executeRequest(zenDeskHttpClient.buildGetRequest(url)) { responseBody ->
      try {
        val json = zenDeskHttpClient.parseJsonObject(responseBody)
        val results = json["results"]
        if (results is JsonArray<*>) {
          val list = results.value
          if (list.isNotEmpty()) {
            val user = list.first()
            if (user is JsonObject) {
              ZenDeskUser((user["id"] as Number).toLong(), user["name"] as String)
            } else {
              null
            }
          } else {
            null
          }
        } else {
          null
        }
      } catch (e: KlaxonException) {
        logger.error("Unable to parse the response", e)
        null
      }
    }
  }
}

data class ZenDeskUser(val id: Long, val name: String)
