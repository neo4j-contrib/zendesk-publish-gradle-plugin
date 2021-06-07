package com.neo4j.gradle.zendesk


import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test

class ArticleAttributesReaderTest {
  @Test
  fun `get article attributes`() {
    // Create a test project and apply the plugin
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("com.neo4j.gradle.zendesk.ZenDeskPlugin")

    val articleAttributesReader = ArticleAttributesReader(project.logger)
    val attributes = mapOf(
      "author" to mapOf(
        "name" to "Dave Gordon",
        "first_name" to "Dave",
        "last_name" to "Gordon",
        "email" to null
      ),
      "tags" to listOf("neo4jstaff"),
      "slug" to "cluster-election-rules-and-behavior-explained",
      "private" to "",
      "title" to "Cluster Election Rules and Behavior-Explained"
    )
    val articleAttributes = articleAttributesReader.fromMap(attributes, "<h1>Title</h1>", "", "")
    assertThat(articleAttributes).isNotNull
    assertThat(articleAttributes?.slug).isEqualTo("cluster-election-rules-and-behavior-explained")
    assertThat(articleAttributes?.tags).containsExactly("neo4jstaff")
    assertThat(articleAttributes?.author?.email).isNull()
    assertThat(articleAttributes?.author?.name).isEqualTo("Dave Gordon")
    assertThat(articleAttributes?.author?.firstName).isEqualTo("Dave")
    assertThat(articleAttributes?.author?.lastName).isEqualTo("Gordon")
  }

  @Test
  fun `get article attributes without email`() {
    // Create a test project and apply the plugin
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("com.neo4j.gradle.zendesk.ZenDeskPlugin")

    val articleAttributesReader = ArticleAttributesReader(project.logger)
    val attributes = mapOf(
      "author" to mapOf(
        "name" to "Dave Gordon",
        "first_name" to "Dave",
        "last_name" to "Gordon"
      ),
      "tags" to listOf("neo4jstaff"),
      "slug" to "cluster-election-rules-and-behavior-explained",
      "private" to "",
      "title" to "Cluster Election Rules and Behavior-Explained"
    )
    val articleAttributes = articleAttributesReader.fromMap(attributes, "<h1>Title</h1>", "", "")
    assertThat(articleAttributes).isNotNull
    assertThat(articleAttributes?.slug).isEqualTo("cluster-election-rules-and-behavior-explained")
    assertThat(articleAttributes?.tags).containsExactly("neo4jstaff")
    assertThat(articleAttributes?.author?.email).isNull()
    assertThat(articleAttributes?.author?.name).isEqualTo("Dave Gordon")
    assertThat(articleAttributes?.author?.firstName).isEqualTo("Dave")
    assertThat(articleAttributes?.author?.lastName).isEqualTo("Gordon")
  }

  @Test
  fun `get article attributes without last name`() {
    // Create a test project and apply the plugin
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("com.neo4j.gradle.zendesk.ZenDeskPlugin")

    val articleAttributesReader = ArticleAttributesReader(project.logger)
    val attributes = mapOf(
      "author" to mapOf(
        "name" to "Dave Gordon",
        "first_name" to "Dave"
      ),
      "tags" to listOf("neo4jstaff"),
      "slug" to "cluster-election-rules-and-behavior-explained",
      "private" to "",
      "title" to "Cluster Election Rules and Behavior-Explained"
    )
    val articleAttributes = articleAttributesReader.fromMap(attributes, "<h1>Title</h1>", "", "")
    assertThat(articleAttributes).isNotNull
    assertThat(articleAttributes?.slug).isEqualTo("cluster-election-rules-and-behavior-explained")
    assertThat(articleAttributes?.tags).containsExactly("neo4jstaff")
    assertThat(articleAttributes?.author?.email).isNull()
    assertThat(articleAttributes?.author?.name).isEqualTo("Dave Gordon")
    assertThat(articleAttributes?.author?.firstName).isEqualTo("Dave")
    assertThat(articleAttributes?.author?.lastName).isNull()
  }

  @Test
  fun `resolve promoted as boolean`() {
    // Create a test project and apply the plugin
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("com.neo4j.gradle.zendesk.ZenDeskPlugin")

    val articleAttributesReader = ArticleAttributesReader(project.logger)
    val attributes = mapOf(
      "tags" to listOf("neo4jstaff"),
      "slug" to "cluster-election-rules-and-behavior-explained",
      "private" to "",
      "title" to "Cluster Election Rules and Behavior-Explained",
      "promoted" to "true",
      "comments_disabled" to "true"
    )
    val articleAttributes = articleAttributesReader.fromMap(attributes, "<h1>Title</h1>", "", "")
    assertThat(articleAttributes).isNotNull
    assertThat(articleAttributes?.slug).isEqualTo("cluster-election-rules-and-behavior-explained")
    assertThat(articleAttributes?.tags).containsExactly("neo4jstaff")
    assertThat(articleAttributes?.commentsDisabled).isTrue
    assertThat(articleAttributes?.promoted).isTrue
  }

  @Test
  fun `use default comments disabled value`() {
    // Create a test project and apply the plugin
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("com.neo4j.gradle.zendesk.ZenDeskPlugin")

    val articleAttributesReader = ArticleAttributesReader(project.logger)
    val attributes = mapOf(
      "tags" to listOf("neo4jstaff"),
      "slug" to "cluster-election-rules-and-behavior-explained",
      "private" to "",
      "title" to "Cluster Election Rules and Behavior-Explained",
      "promoted" to "true"
    )
    val articleAttributes = articleAttributesReader.fromMap(
      attributes = attributes,
      content = "<h1>Title</h1>",
      yamlFileAbsolutePath = "",
      fileName = "",
      commentsDisabledDefaultValue = true
    )
    assertThat(articleAttributes).isNotNull
    assertThat(articleAttributes?.slug).isEqualTo("cluster-election-rules-and-behavior-explained")
    assertThat(articleAttributes?.tags).containsExactly("neo4jstaff")
    assertThat(articleAttributes?.commentsDisabled).isTrue
    assertThat(articleAttributes?.promoted).isTrue
  }
}
