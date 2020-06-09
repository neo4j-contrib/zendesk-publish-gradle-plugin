plugins {
    id("com.gradle.plugin-publish") version "0.11.0"
    `java-gradle-plugin`
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    google()
    jcenter()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.4.0")
    implementation("org.yaml:snakeyaml:1.25")
    implementation("com.beust:klaxon:5.0.1")
    implementation(gradleApi())
    // Align versions of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    // Use the Kotlin JDK 8 standard library.
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.5.0")
    testImplementation("org.assertj:assertj-core:3.11.1")
}

version = "0.1.3"

gradlePlugin {
    plugins {
        create("zendeskPlugin") {
            id = "com.neo4j.gradle.zendesk.ZenDeskPlugin"
            implementationClass = "com.neo4j.gradle.zendesk.ZenDeskPlugin"
        }
    }
}

pluginBundle {
    website = "https://neo4j.com/"
    vcsUrl = "https://github.com/neo4j-contrib/zendesk-publish-gradle-plugin"

    (plugins) {
        "zendeskPlugin" {
            id = "com.neo4j.gradle.zendesk.ZenDeskPlugin"
            displayName = "Publish articles to ZenDesk"
            description = "A plugin to publish articles to .ZenDesk from an HTML file and a YAML file that contains metadata"
            tags = listOf("zendesk", "publish", "articles")
        }
    }
}

// Add a source set for the functional test suite
val functionalTestSourceSet = sourceSets.create("functionalTest") {
}

gradlePlugin.testSourceSets(functionalTestSourceSet)
configurations.getByName("functionalTestImplementation").extendsFrom(configurations.getByName("testImplementation"))

// Add a task to run the functional tests
val functionalTest by tasks.creating(Test::class) {
    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
}

val check by tasks.getting(Task::class) {
    // Run the functional tests as part of `check`
    dependsOn(functionalTest)
}
