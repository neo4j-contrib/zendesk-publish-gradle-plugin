package com.neo4j.gradle.zendesk

import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.logging.Logger
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream
import java.nio.file.Paths


data class ArticleAttributes(val slug: String,
                             val id: Long?,
                             val title: String,
                             val author: Author?,
                             val tags: List<String>,
                             val position: Int = 1000000,
                             val promoted: Boolean = false,
                             val content: String)

class ArticleAttributesReader(val logger: Logger) {

  private val yaml = Yaml()

  /**
   * Get a list of documents with attributes (read from a YAML file).
   * The YAML file is generated in a pre-task.
   */
  fun get(sources: MutableList<ConfigurableFileTree>): List<ArticleAttributes> {
    return sources
      .flatten()
      .filter { it.extension == "html" }
      .mapNotNull { file ->
        val yamlFile = Paths.get(file.toPath().parent.toString(), "${file.nameWithoutExtension}.yml").toFile()
        if (!yamlFile.exists()) {
          val fileName = file.name
          val yamlFileAbsolutePath = yamlFile.absolutePath
          logger.warn("Missing YAML file: $yamlFileAbsolutePath, unable to publish $fileName to ZenDesk")
          null
        } else {
          try {
            fromFile(yamlFile, file)
          } catch (e: Exception) {
            val yamlFileAbsolutePath = yamlFile.absolutePath
            val fileName = file.name
            logger.warn("Error while parsing the YAML file: $yamlFileAbsolutePath, unable to publish $fileName to ZenDesk", e)
            null
          }
        }
      }
  }

  private fun fromFile(yamlFile: File, file: File): ArticleAttributes? {
    logger.debug("Loading $yamlFile")
    val attributes = yaml.load(FileInputStream(yamlFile)) as Map<*, *>
    val yamlFileAbsolutePath = yamlFile.absolutePath
    val fileName = file.name
    val content = file.readText(Charsets.UTF_8)
    return fromMap(attributes, content, yamlFileAbsolutePath, fileName)
  }

  internal fun fromMap(attributes: Map<*, *>, content: String, yamlFileAbsolutePath: String, fileName: String): ArticleAttributes? {
    logger.debug("Document attributes in the YAML file: $attributes")
    val slug = getSlug(attributes, yamlFileAbsolutePath, fileName)
    val title = getTitle(attributes, yamlFileAbsolutePath, fileName)
    return if (slug != null && title != null) {
      // optional attribute
      val id = getId(attributes)
      val tags = getTags(attributes)
      val author = getAuthor(attributes)
      val position = getPosition(attributes) ?: 1000000
      val promoted = getPromoted(attributes) ?: false
      ArticleAttributes(slug, id, title, author, tags, position, promoted, content)
    } else {
      null
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
    if (value is String) {
      return value.toBoolean()
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
      val tagsValue = author["tags"]
      val tags = if (tagsValue is List<*>) tagsValue.filterIsInstance<String>() else emptyList()
      return Author(author["name"] as String?, author["first_name"] as String?, author["last_name"] as String?, author["email"] as String?, tags)
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
