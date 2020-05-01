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
import org.yaml.snakeyaml.Yaml
import java.io.FileInputStream
import java.io.StringReader
import java.nio.file.Paths
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

data class Author(val name: String, val firstName: String, val lastName: String, val email: String)

data class ArticleAttributes(val slug: String,
                             val id: Long?,
                             val title: String,
                             val author: Author?,
                             val tags: List<String>,
                             val position: Int = 1000000,
                             val promoted: Boolean = false,
                             val content: String)

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
                             val sources: MutableList<ConfigurableFileTree>,
                             val connectionInfo: ZenDeskConnectionInfo,
                             val logger: Logger) {

  private val yaml = Yaml()
  private val parser = Klaxon()
  private val httpClient = ZenDeskHttpClient(connectionInfo, logger)
  private val htmlMetadataRegex = Regex("<!-- METADATA! (?<json>.*) !METADATA -->\$")

  fun publish(): Boolean {
    val articlesWithAttributes = getArticlesWithAttributes()
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
      val metadata = getMetadataFromHTML(it.string("body").orEmpty())
      if (metadata != null) {
        val slug = metadata.string("slug")
        if (slug != null) {
          slug to it
        } else {
          null
        }
      } else {
        null
      }
    }.toMap()
    for (article in articlesWithAttributes) {
      val articleData = mutableMapOf(
        "label_names" to article.tags,
        "position" to article.position,
        "promoted" to article.promoted
      )
      val translationsData = mutableMapOf(
        "title" to article.title,
        "body" to appendMetadataToHTML(article.content, JsonObject(mapOf("slug" to article.slug))),
        "user_segment_id" to userSegmentId,
        "permission_group_id" to permissionGroupId
      )
      val existingArticle = if (article.id != null) {
        existingArticlesById[article.id]
      } else {
        existingArticlesBySlug[article.slug]
      }
      if (existingArticle != null) {
        val articleId = existingArticle.long("id")!!
        val successful = updateArticle(articleId, article.slug, articleData, translationsData)
        if (successful) {
          logger.quiet("Successfully updated the article with id: $articleId and slug: ${article.slug}")
        }
      } else {
        val articleId = createArticle(article.slug, mapOf("article" to articleData.plus(translationsData)))
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

  private fun getMetadataFromHTML(html: String): JsonObject? {
    val find = htmlMetadataRegex.find(html)
    val jsonMatchGroup = find?.groups?.get("json")
    if (jsonMatchGroup != null) {
      val json = jsonMatchGroup.value
      return parser.parseJsonObject(StringReader(json))
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

  /**
   * Get a list of documents with attributes (read from a YAML file).
   * The YAML file is generated in a pre-task.
   */
  private fun getArticlesWithAttributes(): List<ArticleAttributes> {
    return sources
      .flatten()
      .filter { it.extension == "html" }
      .mapNotNull { file ->
        val yamlFile = Paths.get(file.toPath().parent.toString(), "${file.nameWithoutExtension}.yml").toFile()
        val fileName = file.name
        val yamlFileAbsolutePath = yamlFile.absolutePath
        if (!yamlFile.exists()) {
          logger.warn("Missing YAML file: $yamlFileAbsolutePath, unable to publish $fileName to ZenDesk")
          null
        } else {
          logger.debug("Loading $yamlFile")
          val attributes = yaml.load(FileInputStream(yamlFile)) as Map<*, *>
          logger.debug("Document attributes in the YAML file: $attributes")
          val slug = getSlug(attributes, yamlFileAbsolutePath, fileName)
          val title = getTitle(attributes, yamlFileAbsolutePath, fileName)
          if (slug != null && title != null) {
            // optional attribute
            val id = getId(attributes)
            val tags = getTags(attributes)
            val author = getAuthor(attributes)
            val position = getPosition(attributes) ?: 1000000
            val promoted = getPromoted(attributes) ?: false
            ArticleAttributes(slug, id, title, author, tags, position, promoted, file.readText(Charsets.UTF_8))
          } else {
            null
          }
        }
      }
  }

  private fun getMandatoryString(attributes: Map<*, *>, name: String, yamlFilePath: String, fileName: String): String? {
    val value = attributes[name]
    if (value == null) {
      logger.warn("No $name found in: $yamlFilePath, unable to publish $fileName to ZenDesk")
      return null
    }
    if (value !is String) {
      logger.warn("$name must be a String in: $yamlFilePath, unable to publish $fileName to ZenDesk")
      return null
    }
    if (value.isBlank()) {
      logger.warn("$name must not be blank in: $yamlFilePath, unable to publish $fileName to ZenDesk")
      return null
    }
    return value
  }

  private fun getPosition(attributes: Map<*, *>): Int? {
    val value = attributes["position"]
    if (value is Int) {
      return value
    }
    return null
  }

  private fun getPromoted(attributes: Map<*, *>): Boolean? {
    val value = attributes["promoted"]
    if (value is Boolean) {
      return value
    }
    return null
  }

  private fun getTags(attributes: Map<*, *>): List<String> {
    val value = attributes["tags"] ?: return listOf()
    if (value is List<*>) {
      return value.filterIsInstance<String>()
    }
    return listOf()
  }

  private fun getAuthor(attributes: Map<*, *>): Author? {
    val author = attributes["author"]
    if (author is Map<*, *>) {
      return Author(author["name"] as String, author["first_name"] as String, author["last_name"] as String, author["email"] as String)
    }
    return null
  }

  private fun getTitle(attributes: Map<*, *>, yamlFilePath: String, fileName: String): String? {
    return getMandatoryString(attributes, "title", yamlFilePath, fileName)
  }

  private fun getSlug(attributes: Map<*, *>, yamlFilePath: String, fileName: String): String? {
    return getMandatoryString(attributes, "slug", yamlFilePath, fileName)
  }

  private fun getId(attributes: Map<*, *>): Long? {
    val value = attributes["zendesk_id"]
    if (value is Number) {
      return value.toLong()
    }
    return null
  }
}
