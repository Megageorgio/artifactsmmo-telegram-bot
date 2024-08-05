import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.reader
import kotlin.io.path.writeBytes
import kotlin.io.path.writer

plugins {
  java
  kotlin("jvm") version "2.0.0"
  kotlin("plugin.serialization") version "2.0.0"
  id("io.ktor.plugin") version "2.3.12"
}

group = "artifactsmmo-kotlin-starter"
version = "1.0-SNAPSHOT"
application {
  mainClass.set("ru.megageorgio.artifactsmmo.bot.MainKt")
}
repositories {
  mavenCentral()
  maven("https://jitpack.io")
}

val fabrikt: Configuration by configurations.creating

var apiSpecFile: Path = projectDir.resolve("src/main/resources/openapi.json").toPath()
val fixedApiSpecFile: Path = projectDir.resolve("build/fixed-openapi.json").toPath()

// fabrikt cannot hande allOf, anyOf or enums
val fixApiSpecFile = tasks.create("fixOpenApiSpec") {
  doLast {
    if (!apiSpecFile.exists()) {
      apiSpecFile = projectDir.resolve("build/openapi.json").toPath()
      val url = URL("https://api.artifactsmmo.com/openapi.json")
      if (!apiSpecFile.exists()) {
        apiSpecFile.writeBytes(url.readBytes())
      }
    }
    val gson = GsonBuilder().setPrettyPrinting().create()
    val tree = apiSpecFile.reader().use { reader ->
      gson.fromJson(reader, JsonObject::class.java)
    }
    fixApiSpecFileTree(tree)
    fixedApiSpecFile.deleteIfExists()
    fixedApiSpecFile.parent.createDirectories()
    fixedApiSpecFile.writer().use { writer ->
      gson.toJson(tree, writer)
    }
  }
}

fun fixApiSpecFileTree(tree: JsonElement) {
  if (tree is JsonObject) {
    tree.entrySet().forEach { (_, value) ->
      fixApiSpecFileTree(value)
    }
    val allOf = tree.get("allOf")
    if (allOf is JsonArray && !allOf.isEmpty) {
      val element = allOf.get(0)
      if (element is JsonObject) {
        val ref = element.get("\$ref")
        if (ref != null) {
          tree.remove("allOf")
          tree.add("\$ref", ref)
        }
      }
    }
    val anyOf = tree.get("anyOf")
    if (anyOf is JsonArray && !anyOf.isEmpty) {
      val first = anyOf.get(0)
      if (first is JsonObject) {
        tree.remove("anyOf")
        first.entrySet().forEach { (key, value) ->
          tree.add(key, value)
        }
        if (anyOf.size() == 2) {
          val second = anyOf.get(1)
          if (second is JsonObject) {
            val type = second.get("type")
            if (type is JsonPrimitive && type.asString == "null") {
              tree.addProperty("nullable", true)
            }
          }
        }
      }
    }
    tree.remove("enum")

    // Replace MyCharacterSchema with CharacterSchema, so we can easily reuse the model
    // MyCharacterSchema only contains the account name in addition to CharacterSchema
    tree.remove("MyCharacterSchema")
    val ref = tree.get("\$ref")
    if (ref is JsonPrimitive) {
      tree.addProperty("\$ref", ref.asString.replace("MyCharacterSchema", "CharacterSchema"))
    }

    // remove from required if the entries are nullable
    val properties = tree.get("properties")
    if (properties is JsonObject) {
      val required = tree.get("required")
      if (required is JsonArray) {
        for ((key, value) in properties.entrySet()) {
          if (value is JsonObject) {
            val nullable = value.get("nullable")
            if (nullable is JsonPrimitive && nullable.asBoolean) {
              required.removeAll { it is JsonPrimitive && it.asString == key }
            }
          }
        }
      }
    }

    val itr = tree.entrySet().iterator()
    while (itr.hasNext()) {
      val (_, value) = itr.next()
      if (value is JsonObject) {
        val description = value.get("description")
        if (description is JsonPrimitive && description.asString.lowercase().contains("deprecated**")) {
          itr.remove()
        }
      }
    }
  } else if (tree is JsonArray) {
    tree.forEach { value ->
      fixApiSpecFileTree(value)
    }
  }
}

val generateOpenApiCode = tasks.create<JavaExec>("generateOpenApiCode") {
  dependsOn(fixApiSpecFile)
  val apiFile = fixedApiSpecFile.toString().replace('\\', '/')
  val generationDir = projectDir.resolve("build/generated").toString().replace('\\', '/')
  inputs.files(apiFile)
  outputs.dir(generationDir)
  outputs.cacheIf { true }
  classpath(fabrikt)
  mainClass.set("com.cjbooms.fabrikt.cli.CodeGen")
  args = mutableListOf(
    "--output-directory", generationDir,
    "--base-package", "artifactsmmo",
    "--api-file", apiFile,
    "--targets", "http_models",
    "--targets", "client",
    "--validation-library", "jakarta_validation",
    "--http-client-opts", "suspend_modifier",
    "--http-client-target", "open_feign",
  )
}

tasks.withType<KotlinCompile>().configureEach {
  dependsOn(generateOpenApiCode)
}

dependencies {
  fabrikt("com.cjbooms:fabrikt:+")

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0-RC")
  implementation("jakarta.validation:jakarta.validation-api:3.1.0")
  implementation("jakarta.annotation:jakarta.annotation-api:3.0.0")
  val feignVersion = "13.3"
  implementation("io.github.openfeign:feign-okhttp:$feignVersion")
  implementation("io.github.openfeign:feign-jackson:$feignVersion")
  implementation("io.github.openfeign:feign-kotlin:$feignVersion")
  val jacksonVersion = "2.17.2"
  implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
  implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
  implementation("com.github.kotlin-telegram-bot.kotlin-telegram-bot:dispatcher:6.1.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
  // Test
  testImplementation(kotlin("test"))
}



tasks.test {
  useJUnitPlatform()
}

kotlin {
  jvmToolchain(21)
}

sourceSets {
  main {
    kotlin {
      srcDir("build/generated/src/main/kotlin")
    }
  }
}
