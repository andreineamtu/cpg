import de.fraunhofer.aisec.cpg.helpers.Benchmark
import com.github.gradle.node.npm.task.NpmTask
import com.github.gradle.node.npm.task.NpmInstallTask

plugins {
    // Apply the frontend convention plugin, mirroring typescript frontend
    id("cpg.frontend-conventions") 
    // Still need node plugin for our parser build
    id("com.github.node-gradle.node") version "7.0.1"
}

// Required for accessing the Version Catalog in the plugins block
// See: https://docs.gradle.org/8.0/userguide/platforms.html#sub:using-standard-java-platform-plugins
// This might be implicitly handled by frontend-conventions, but keeping it for now
@Suppress("DSL_SCOPE_VIOLATION") 
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Publishing info - might need adjustment later
mavenPublishing {
    pom {
        name.set("Code Property Graph - Svelte Frontend")
        description.set("A Svelte language frontend for the CPG")
    }
}

apply<Benchmark>()

dependencies {
    // Dependencies might be simplified if frontend-conventions handles them
    // Keep explicit core dependency for now
    api(project(":cpg-core"))

    // Other standard dependencies (check if needed, potentially covered by conventions)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.slf4j.api)
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.annotations)
    implementation(libs.jackson.datatype.jsr310)

    // Test dependencies (check if needed, potentially covered by conventions)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
    testImplementation(project(":cpg-core")).capabilities {
        requireCapability("de.fraunhofer.aisec.cpg:cpg-core-test-fixtures")
    }
}

// --- Node.js Integration --- 
node {
    version.set("18.17.1")
    npmVersion.set("9.6.7")
    download.set(true)
    workDir.set(file("${layout.buildDirectory}/nodejs")) 
}

val parserDir = file("src/main/parser")

tasks.register<NpmInstallTask>("npmInstallDev") {
    dependsOn(tasks.nodeSetup)
    workingDir.set(parserDir) 
    inputs.file("package.json")
    inputs.file("package-lock.json").optional(true)
    outputs.dir("node_modules")
}

tasks.register<NpmTask>("compileSvelteParser") {
    dependsOn(tasks.npmInstallDev)
    workingDir.set(parserDir)
    args.set(listOf("run", "build"))
    inputs.file("tsconfig.json")
    inputs.dir("src")
    outputs.dir(layout.buildDirectory.dir("parser/src"))
}

tasks.compileKotlin {
    dependsOn(tasks.compileSvelteParser)
    doFirst {
        copy {
            from(layout.buildDirectory.dir("parser/src"))
            include("parser.js")
            into(layout.buildDirectory.dir("resources/main/svelte"))
            println("Copied parser.js to resources/main/svelte")
        }
    }
}

java {
    sourceSets["main"].resources {
       srcDirs("build/resources/main/svelte")
       include("parser.js")
    }
} 