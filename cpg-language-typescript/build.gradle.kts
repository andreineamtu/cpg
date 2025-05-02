/*
 * Copyright (c) 2022, Fraunhofer AISEC. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *                    $$$$$$\  $$$$$$$\   $$$$$$\
 *                   $$  __$$\ $$  __$$\ $$  __$$\
 *                   $$ /  \__|$$ |  $$ |$$ /  \__|
 *                   $$ |      $$$$$$$  |$$ |$$$$\
 *                   $$ |      $$  ____/ $$ |\_$$ |
 *                   $$ |  $$\ $$ |      $$ |  $$ |
 *                   \$$$$$   |$$ |      \$$$$$   |
 *                    \______/ \__|       \______/
 *
 */

import com.github.gradle.node.npm.task.NpmTask
import com.github.gradle.node.npm.task.NpxTask
import io.github.masch0212.deno.RunDenoTask

plugins {
    id("cpg.frontend-conventions")
    alias(libs.plugins.deno)
    alias(libs.plugins.node)
}

mavenPublishing {
    pom {
        name.set("Code Property Graph - JavaScript/TypeScript Frontend")
        description.set("A JavaScript/TypeScript language frontend for the CPG")
    }
}

// --- Deno Integration for TypeScript Parser (Original) ---
val compileWindowsX8664 =
    tasks.register<RunDenoTask>("compileWindowsX8664") {
        dependsOn(tasks.installDeno)
        command(
            "compile",
            "-E",
            "-R",
            "--target",
            "x86_64-pc-windows-msvc",
            "-o",
            "build/resources/main/typescript/parser-windows-x86_64",
            "src/main/typescript/src/parser.ts",
        )
        outputs.dir("build/resources/main/typescript")
        outputs.cacheIf { true }
    }

val compileMacOSX8664 =
    tasks.register<RunDenoTask>("compileMacOSX8664") {
        dependsOn(tasks.installDeno)
        command(
            "compile",
            "-E",
            "-R",
            "--target",
            "x86_64-apple-darwin",
            "-o",
            "build/resources/main/typescript/parser-macos-x86_64",
            "src/main/typescript/src/parser.ts",
        )
        outputs.dir("build/resources/main/typescript")
        outputs.cacheIf { true }
    }

val compileMacOSAarch64 =
    tasks.register<RunDenoTask>("compileMacOSAarch64") {
        dependsOn(tasks.installDeno)
        command(
            "compile",
            "-E",
            "-R",
            "--target",
            "aarch64-apple-darwin",
            "-o",
            "build/resources/main/typescript/parser-macos-aarch64",
            "src/main/typescript/src/parser.ts",
        )
        outputs.dir("build/resources/main/typescript")
        outputs.cacheIf { true }
    }

val compileLinuxX8664 =
    tasks.register<RunDenoTask>("compileLinuxX8664") {
        dependsOn(tasks.installDeno)
        command(
            "compile",
            "-E",
            "-R",
            "--target",
            "x86_64-unknown-linux-gnu",
            "-o",
            "build/resources/main/typescript/parser-linux-x86_64",
            "src/main/typescript/src/parser.ts",
        )
        outputs.dir("build/resources/main/typescript")
        outputs.cacheIf { true }
    }

val compileLinuxAarch64 =
    tasks.register<RunDenoTask>("compileLinuxAarch64") {
        dependsOn(tasks.installDeno)
        command(
            "compile",
            "-E",
            "-R",
            "--target",
            "aarch64-unknown-linux-gnu",
            "-o",
            "build/resources/main/typescript/parser-linux-aarch64",
            "src/main/typescript/src/parser.ts",
        )
        outputs.dir("build/resources/main/typescript")
        outputs.cacheIf { true }
    }

// --- Node.js Integration for Svelte Parser ---

// Configure Node.js version
node {
    version.set("18.17.0") // Example version, align if needed
    download.set(true)
}

// Task to install npm dependencies for the Svelte parser
val svelteNpmInstall =
    tasks.register<NpmTask>("svelteNpmInstall") {
        description = "Installs npm dependencies for the Svelte parser"
        workingDir.set(file("src/main/svelte-parser"))
        args.set(listOf("install"))
        // Ensure node tasks are configured correctly
        inputs.files(
            "src/main/svelte-parser/package.json",
            "src/main/svelte-parser/package-lock.json",
        )
        outputs.dir("src/main/svelte-parser/node_modules")
    }

// Task to compile the Svelte parser using TypeScript compiler (tsc) via npx
val svelteTsc =
    tasks.register<NpxTask>("svelteTsc") {
        description = "Compiles the Svelte parser using tsc"
        dependsOn(svelteNpmInstall)
        workingDir.set(file("src/main/svelte-parser"))
        // Execute tsc using npx
        command.set("tsc")
        args.set(
            listOf("--build", "tsconfig.json")
        ) // Use --build flag for tsconfig project compilation
        // Define inputs and outputs for caching and dependency tracking
        inputs.dir("src/main/svelte-parser/src")
        inputs.file("src/main/svelte-parser/tsconfig.json")
        outputs.dir("src/main/svelte-parser/dist")
        outputs.cacheIf { true }
    }

// Restore original processResources and add Svelte parser artifact
tasks.processResources {
    dependsOn(
        compileWindowsX8664,
        compileMacOSX8664,
        compileMacOSAarch64,
        compileLinuxX8664,
        compileLinuxAarch64,
        // Add dependency on the Svelte parser compilation task
        svelteTsc,
    )
    // Add the compiled Svelte parser to the resources
    from(svelteTsc.get().outputs.files) {
        into("svelte") // Place it inside a 'svelte' directory within resources
        rename { filename ->
            if (filename == "parser.js") { // Assuming the output is parser.js
                "parser.js"
            } else {
                filename // Keep other files (like .map) if generated
            }
        }
    }
}

// Restore original Kotlin compilation dependency
tasks.compileKotlin { dependsOn(tasks.processResources) }
