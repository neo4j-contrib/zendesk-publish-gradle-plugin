package com.neo4j.gradle.zendesk

import com.beust.klaxon.Klaxon
import com.beust.klaxon.KlaxonException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.encode
import org.gradle.api.logging.Logger
import java.nio.charset.StandardCharsets


class ZenDeskHttpClient(val connectionInfo: ZenDeskConnectionInfo, private val logger: Logger) {

  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
  private val underlyingHttpClient = httpClient()
  private val klaxon = Klaxon()

  fun baseUrlBuilder(): HttpUrl.Builder {
    val builder = HttpUrl.Builder()
      .scheme(connectionInfo.scheme)
      .host(connectionInfo.host)
    if (connectionInfo.port != null) {
      builder.port(connectionInfo.port)
    }
    return builder
      .addPathSegment("api")
      .addPathSegment("v2")
  }

  /**
   * Execute a request that returns JSON.
   */
  fun <T> executeRequest(request: Request, mapper: (ResponseBody) -> T): T? {
    underlyingHttpClient.newCall(request).execute().use {
      if (it.isSuccessful) {
        it.body.use { responseBody ->
          if (responseBody != null) {
            val contentType = responseBody.contentType()
            if (contentType != null) {
              if (contentType.type == "application" && contentType.subtype == "json") {
                try {
                  return mapper(responseBody)
                } catch (e: KlaxonException) {
                  logger.error("Unable to parse the response", e)
                }
              } else {
                logger.warn("Content-Type must be application/json")
              }
            } else {
              logger.warn("Content-Type is undefined")
            }
          } else {
            logger.warn("Response is empty")
          }
        }
      } else {
        logger.warn("Request is unsuccessful - {request: $request, code: ${it.code}, message: ${it.message}, response: ${it.body?.string()}}")
      }
    }
    return null
  }

  fun buildGetRequest(url: HttpUrl): Request {
    return Request.Builder()
      .url(url)
      // ZenDesk API allows unauthenticated user
      .header("Authorization", basicAuth())
      .get()
      .build()
  }

  fun buildPostRequest(url: HttpUrl, data: Map<String, Any>): Request {
    return Request.Builder()
      .url(url)
      // ZenDesk API allows unauthenticated user
      .header("Authorization", basicAuth())
      .post(klaxon.toJsonString(data).toRequestBody(jsonMediaType))
      .build()
  }

  fun buildPutRequest(url: HttpUrl, data: Map<String, Any>): Request {
    return Request.Builder()
      .url(url)
      // ZenDesk API allows unauthenticated user
      .header("Authorization", basicAuth())
      .put(klaxon.toJsonString(data).toRequestBody(jsonMediaType))
      .build()
  }

  fun parseJsonObject(responseBody: ResponseBody) = klaxon.parseJsonObject(responseBody.charStream())

  private fun httpClient(): OkHttpClient {
    val client = OkHttpClient.Builder()
      .authenticator(object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
          if (responseCount(response) >= 3) {
            return null // unable to authenticate for the third time, we give up...
          }
          return response.request.newBuilder().header("Authorization", basicAuth()).build()
        }
      })
      .connectTimeout(connectionInfo.connectTimeout)
      .writeTimeout(connectionInfo.writeTimeout)
      .readTimeout(connectionInfo.readTimeout)
    return client.build()
  }

  private fun basicAuth(): String {
    val credentialEncoded = "${connectionInfo.email}/token:${connectionInfo.apiToken}".encode(StandardCharsets.ISO_8859_1).base64()
    return "Basic $credentialEncoded"
  }

  private fun responseCount(response: Response): Int {
    var count = 1
    var res = response.priorResponse
    while (res != null) {
      count++
      res = res.priorResponse
    }
    return count
  }
}

data class PaginatedResult<T>(val result: List<T>, val hasNext: Boolean)
