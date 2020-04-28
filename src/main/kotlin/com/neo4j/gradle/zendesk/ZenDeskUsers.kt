package com.neo4j.gradle.zendesk

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.KlaxonException
import org.gradle.api.logging.Logger

class ZenDeskUsers(private val zenDeskHttpClient: ZenDeskHttpClient, private val logger: Logger) {

  /**
   * Find a user from an email.
   * This method is currently not used!
   * It can be used to find an user from the "author.email" property defines in the metadata (YAML) file.
   */
  fun findUser(email: String): ZenDeskUser? {
    val url = zenDeskHttpClient.baseUrlBuilder()
      .addPathSegment("search.json")
      .addQueryParameter("query", "type:user email:$email")
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
              ZenDeskUser(user["id"] as Int, user["name"] as String)
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

data class ZenDeskUser(val id: Int, val name: String)
