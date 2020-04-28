package com.neo4j.gradle.zendesk

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull

class ZenDeskPluginTest {
  @Test
  fun `plugin registers task`() {
    // Create a test project and apply the plugin
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("com.neo4j.gradle.zendesk.ZenDeskPlugin")
    // Verify the result
    assertNotNull(project.extensions.findByName("zendesk"))
  }
}
