package com.neo4j.gradle.zendesk

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import com.beust.klaxon.KlaxonException
import org.gradle.api.DefaultTask
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import java.io.StringReader
import java.security.MessageDigest
import java.time.Duration


open class ZenDeskExtension(objects: ObjectFactory) {
  val scheme: Property<String> = objects.property()
  val host: Property<String> = objects.property()
  val port: Property<Int> = objects.property()
  val email: Property<String> = objects.property()
  val apiToken: Property<String> = objects.property()
}

open class ZenDeskPlugin : Plugin<Project> {

  override fun apply(target: Project) {
    target.extensions.create("zendesk", ZenDeskExtension::class.java)
  }
}

data class Author(val name: String?, val firstName: String?, val lastName: String?, val email: String?, val tags: List<String>)

abstract class ZenDeskUploadTask : DefaultTask() {

  @InputFiles
  var sources: MutableList<ConfigurableFileTree> = mutableListOf()

  @Input
  var scheme: String = "https"

  @Input
  var host: String = ""

  @Input
  @Optional
  val port: Property<Int> = project.objects.property()

  @Input
  var email: String = ""

  @Input
  var apiToken: String = ""

  @Input
  val userSegmentId: Property<Number> = project.objects.property()

  @Input
  val permissionGroupId: Property<Number> = project.objects.property()

  @Input
  val sectionId: Property<Number> = project.objects.property()

  @Input
  var locale: String = "en-us"

  @Input
  var notifySubscribers: Boolean = true

  @Input
  @Optional
  var commentsDisabled: Boolean? = null

  @TaskAction
  fun task() {
    if (!userSegmentId.isPresent) {
      throw InvalidUserDataException("The userSegmentId property is mandatory, aborting...")
    }
    if (!permissionGroupId.isPresent) {
      throw InvalidUserDataException("The permissionGroupId property is mandatory, aborting...")
    }
    if (!sectionId.isPresent) {
      throw InvalidUserDataException("The sectionId property is mandatory, aborting...")
    }
    val zenDeskUpload = ZenDeskUpload(
      locale = locale,
      userSegmentId = userSegmentId.get().toLong(),
      permissionGroupId = permissionGroupId.get().toLong(),
      sectionId = sectionId.get().toLong(),
      notifySubscribers = notifySubscribers,
      commentsDisabled = commentsDisabled,
      sources = sources,
      connectionInfo = zenDeskConnectionInfo(),
      logger = logger
    )
    zenDeskUpload.publish()
  }

  private fun zenDeskConnectionInfo(): ZenDeskConnectionInfo {
    val zenDeskExtension = project.extensions.findByType(ZenDeskExtension::class.java)
    val hostValue = zenDeskExtension?.host?.getOrElse(host) ?: host
    val schemeValue = zenDeskExtension?.scheme?.getOrElse(scheme) ?: scheme
    val emailValue = zenDeskExtension?.email?.getOrElse(email) ?: email
    val apiTokenValue = zenDeskExtension?.apiToken?.getOrElse(apiToken) ?: apiToken
    val portValue = (zenDeskExtension?.port ?: port).orNull
    return ZenDeskConnectionInfo(
      scheme = schemeValue,
      host = hostValue,
      port = portValue,
      email = emailValue,
      apiToken = apiTokenValue
    )
  }

  fun setSource(sources: FileCollection) {
    sources.forEach {
      this.sources.add(project.fileTree(it))
    }
  }

  fun setSource(source: String) {
    this.sources.add(project.fileTree(source))
  }

  fun setSource(vararg sources: String?) {
    sources.forEach {
      if (it != null) {
        this.sources.add(project.fileTree(it))
      }
    }
  }

  fun setSource(sources: List<String>) {
    sources.forEach {
      this.sources.add(project.fileTree(it))
    }
  }

  fun setSource(source: ConfigurableFileTree) {
    this.sources.add(source)
  }
}

data class ZenDeskConnectionInfo(val scheme: String,
                                 val host: String,
                                 val port: Int?,
                                 val email: String,
                                 val apiToken: String,
                                 val connectTimeout: Duration = Duration.ofSeconds(10),
                                 val writeTimeout: Duration = Duration.ofSeconds(10),
                                 val readTimeout: Duration = Duration.ofSeconds(30))

internal class ZenDeskUpload(val locale: String,
                             val userSegmentId: Long,
                             val permissionGroupId: Long,
                             val sectionId: Long,
                             val notifySubscribers: Boolean,
                             val commentsDisabled: Boolean?,
                             val sources: MutableList<ConfigurableFileTree>,
                             val connectionInfo: ZenDeskConnectionInfo,
                             val logger: Logger) {

  private val klaxon = Klaxon()
  private val httpClient = ZenDeskHttpClient(connectionInfo, logger)
  private val htmlMetadataRegex = Regex("<!-- METADATA! (?<json>.*) !METADATA -->\$")
  private val md5Digest = MessageDigest.getInstance("MD5")

  fun publish(): Boolean {
    val zenDeskUsersService = ZenDeskUsers(httpClient, logger)
    val articleAttributesReader = ArticleAttributesReader(logger)
    val zendeskUsersCache = mutableMapOf<String, ZenDeskUser?>()
    val articlesWithAttributes = articleAttributesReader.get(sources)
    if (articlesWithAttributes.isEmpty()) {
      logger.info("No article to upload")
      return false
    }
    val existingArticles = allArticles()
    val existingArticlesById = existingArticles.mapNotNull {
      val id = it.long("id")
      if (id != null) {
        id to it
      } else {
        null
      }
    }.toMap()
    val existingArticlesBySlug = existingArticles.mapNotNull {
      val slug = getMetadataSlugFromHTML(it)
      if (slug != null) {
        slug to it
      } else {
        null
      }
    }.toMap()
    for (article in articlesWithAttributes) {
      val articleData = mutableMapOf(
        "label_names" to article.tags,
        "position" to article.position,
        "promoted" to article.promoted,
        "comments_disabled" to article.commentsDisabled
      )
      val author = article.author
      if (author != null) {
        val authorKey = author.email ?: author.name
        if (authorKey != null) {
          val zendeskAuthor = if (zendeskUsersCache.containsKey(authorKey)) {
            zendeskUsersCache[authorKey]
          } else {
            val zendeskUser = zenDeskUsersService.findUser(author)
            zendeskUsersCache[authorKey] = zendeskUser
            zendeskUser
          }
          if (zendeskAuthor != null) {
            articleData["author_id"] = zendeskAuthor.id
          }
        }
      }
      val digest = computeDigest(articleData, article)
      val translationsData = mapOf(
        "title" to article.title,
        "body" to appendMetadataToHTML(article.content, JsonObject(mapOf("slug" to article.slug, "digest" to digest))),
        "user_segment_id" to userSegmentId,
        "permission_group_id" to permissionGroupId
      )
      val existingArticle = if (article.id != null) {
        existingArticlesById[article.id]
      } else {
        existingArticlesBySlug[article.slug]
      }
      if (existingArticle != null) {
        val currentDigest = getMetadataDigestFromHTML(existingArticle)
        val articleId = existingArticle.long("id")!!
        val articlePromoted = existingArticle.boolean("promoted")!!
        val articleCommentsDisabled = existingArticle.boolean("comments_disabled")!!
        // REMIND: we should add promoted and comments_disabled in the digest but the trick is that it will update every articles (since we didn't have them initially!)
        if (currentDigest == digest && articlePromoted == article.promoted && articleCommentsDisabled == article.commentsDisabled) {
          logger.quiet("Skipping article with id: $articleId and slug: ${article.slug}, content has not changed")
        } else {
          logger.info("Updating article id: $articleId and slug: ${article.slug} with article: $articleData and translations: ${translationsData.filterKeys { it != "body" }}")
          val successful = updateArticle(articleId, article.slug, articleData, translationsData)
          if (successful) {
            logger.quiet("Successfully updated the article with id: $articleId and slug: ${article.slug}")
          }
        }
      } else {
        logger.info("Creating a new article for slug: ${article.slug} with article: $articleData and translations: ${translationsData.filterKeys { it != "body" }}")
        val articleId = createArticle(article.slug, mapOf("article" to articleData.plus(translationsData), "notify_subscribers" to notifySubscribers))
        if (articleId != null) {
          logger.quiet("Successfully created a new article with id: $articleId and slug: ${article.slug}")
        }
      }
    }
    return true
  }

  private fun createArticle(articleSlug: String, data: Map<String, Any>): Long? {
    val articlesUrl = httpClient.baseUrlBuilder()
      .addPathSegment("help_center")
      .addPathSegment(locale)
      .addPathSegment("sections")
      .addPathSegment(sectionId.toString())
      .addPathSegment("articles.json")
      .build()
    val articleId = httpClient.executeRequest(httpClient.buildPostRequest(articlesUrl, data)) { responseBody ->
      (httpClient.parseJsonObject(responseBody)["article"] as JsonObject).long("id")
    }
    if (articleId == null) {
      logger.error("Unable to create article with slug: $articleSlug")
      return null
    }
    return articleId
  }

  private fun updateArticle(articleId: Long, articleSlug: String, articleData: Map<String, Any>, translationsData: Map<String, Any>): Boolean {
    val translationsUrl = httpClient.baseUrlBuilder()
      .addPathSegment("help_center")
      .addPathSegment("articles")
      .addPathSegment(articleId.toString())
      .addPathSegment("translations")
      .addPathSegment("$locale.json")
      .build()
    val updateTranslationsSuccessful = httpClient.executeRequest(httpClient.buildPutRequest(translationsUrl, translationsData)) { responseBody ->
      (httpClient.parseJsonObject(responseBody)["translation"] as JsonObject).long("id") != null
    } ?: false
    if (!updateTranslationsSuccessful) {
      logger.error("Unable to update translations for the article with id: $articleId and slug: $articleSlug")
      return false
    }
    val articleUrl = httpClient.baseUrlBuilder()
      .addPathSegment("help_center")
      .addPathSegment(locale)
      .addPathSegment("articles")
      .addPathSegment("$articleId.json")
      .build()
    val updateArticleSuccessful = httpClient.executeRequest(httpClient.buildPutRequest(articleUrl, mapOf("article" to articleData))) { responseBody ->
      (httpClient.parseJsonObject(responseBody)["article"] as JsonObject).long("id") != null
    } ?: false
    if (!updateArticleSuccessful) {
      logger.error("Unable to update the article with id: $articleId and slug: $articleSlug")
      return false
    }
    return true
  }

  private fun appendMetadataToHTML(html: String, metadata: JsonObject): String {
    return """$html
<!-- METADATA! ${metadata.toJsonString()} !METADATA -->"""
  }

  private fun getMetadataSlugFromHTML(existingArticle: JsonObject): String? {
    val metadata = getMetadataFromHTML(existingArticle.string("body").orEmpty())
    return metadata?.string("slug")
  }

  private fun getMetadataDigestFromHTML(existingArticle: JsonObject): String? {
    val metadata = getMetadataFromHTML(existingArticle.string("body").orEmpty())
    return metadata?.string("digest")
  }

  private fun computeDigest(articleData: MutableMap<String, Any>, article: ArticleAttributes): String {
    val data = klaxon.toJsonString(articleData + mapOf(
      "title" to article.title,
      "content" to article.content,
      "user_segment_id" to userSegmentId,
      "permission_group_id" to permissionGroupId
    ))
    return md5Digest
      .digest(data.toByteArray())
      .fold("", { str, it -> str + "%02x".format(it) })
  }

  private fun getMetadataFromHTML(html: String): JsonObject? {
    val find = htmlMetadataRegex.find(html)
    val jsonMatchGroup = find?.groups?.get("json")
    if (jsonMatchGroup != null) {
      val json = jsonMatchGroup.value
      return klaxon.parseJsonObject(StringReader(json))
    }
    return null
  }

  private fun allArticles(): List<JsonObject> {
    val url = httpClient.baseUrlBuilder()
      .addPathSegment("help_center")
      .addPathSegment(locale)
      .addPathSegment("sections")
      .addPathSegment(sectionId.toString())
      .addPathSegment("articles.json")
      .build()
    return httpClient.executeRequest(httpClient.buildGetRequest(url)) { responseBody ->
      try {
        val json = httpClient.parseJsonObject(responseBody)
        val pageCount = json.int("page_count") ?: 1
        val articles = (json["articles"] as JsonArray<*>).filterIsInstance<JsonObject>()
        articles + ((2..pageCount).flatMap { page ->
          val urlPerPage = httpClient.baseUrlBuilder()
            .addPathSegment("help_center")
            .addPathSegment(locale)
            .addPathSegment("sections")
            .addPathSegment(sectionId.toString())
            .addPathSegment("articles.json")
            .addQueryParameter("page", page.toString())
            .build()
          httpClient.executeRequest(httpClient.buildGetRequest(urlPerPage)) { responseBody ->
            (httpClient.parseJsonObject(responseBody)["articles"] as JsonArray<*>).filterIsInstance<JsonObject>()
          } ?: emptyList()
        })
      } catch (e: KlaxonException) {
        logger.error("Unable to parse the response", e)
        emptyList()
      }
    } ?: emptyList()
  }
}
