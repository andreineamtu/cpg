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

import com.github.gradle.node.npm.task.NpmInstallTask
import com.github.gradle.node.npm.task.NpmTask
import io.github.masch0212.deno.RunDenoTask

plugins {
    id("cpg.frontend-conventions")
    alias(libs.plugins.deno)
    id("com.github.node-gradle.node") version "7.0.1"
}

mavenPublishing {
    pom {
        name.set("Code Property Graph - JavaScript/TypeScript Frontend")
        description.set("A JavaScript/TypeScript language frontend for the CPG")
    }
}

// --- Node.js Integration for Svelte Parser ---
val svelteParserDir = file("src/main/svelte-parser")

node {
    version.set("18.17.1")
    npmVersion.set("9.6.7")
    download.set(true)
    workDir.set(file("${layout.buildDirectory}/nodejs-svelte"))
    npmWorkDir.set(file("${layout.buildDirectory}/npm-svelte"))
}

val npmInstallSvelteParser =
    tasks.register<NpmInstallTask>("npmInstallSvelteParser") {
        description = "Installs npm dependencies for the Svelte parser."
        dependsOn(tasks.nodeSetup)
        workingDir.set(svelteParserDir)
        inputs.file(svelteParserDir.resolve("package.json"))
        inputs.file(svelteParserDir.resolve("package-lock.json")).optional(true)
        outputs.dir(svelteParserDir.resolve("node_modules"))
    }

val compileSvelteParser =
    tasks.register<NpmTask>("compileSvelteParser") {
        description = "Compiles the Svelte parser TypeScript to JavaScript using tsc."
        dependsOn(npmInstallSvelteParser)
        workingDir.set(svelteParserDir)
        args.set(listOf("run", "build"))
        inputs.file(svelteParserDir.resolve("tsconfig.json"))
        inputs.dir(svelteParserDir.resolve("src"))
        outputs.dir(svelteParserDir.resolve("dist"))
    }

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

tasks.processResources {
    dependsOn(
        compileWindowsX8664,
        compileMacOSX8664,
        compileMacOSAarch64,
        compileLinuxX8664,
        compileLinuxAarch64,
        compileSvelteParser,
    )

    from(compileSvelteParser.map { it.outputs.files }) {
        into("svelte")
        include("parser.js")
    }
}

tasks.compileKotlin { dependsOn(tasks.processResources) }

tasks.clean {
    delete(node.workDir)
    delete(node.npmWorkDir)
    delete(svelteParserDir.resolve("node_modules"))
    delete(svelteParserDir.resolve("dist"))
}
