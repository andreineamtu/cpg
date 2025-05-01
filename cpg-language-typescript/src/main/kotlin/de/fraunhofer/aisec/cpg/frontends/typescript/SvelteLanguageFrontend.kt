/*
 * Copyright (c) 2025, Fraunhofer AISEC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
package de.fraunhofer.aisec.cpg.frontends.typescript

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.fraunhofer.aisec.cpg.TranslationContext
import de.fraunhofer.aisec.cpg.frontends.FrontendUtils
import de.fraunhofer.aisec.cpg.frontends.LanguageFrontend
import de.fraunhofer.aisec.cpg.frontends.TranslationException
import de.fraunhofer.aisec.cpg.graph.*
import de.fraunhofer.aisec.cpg.graph.declarations.*
import de.fraunhofer.aisec.cpg.graph.types.*
import de.fraunhofer.aisec.cpg.sarif.PhysicalLocation
import de.fraunhofer.aisec.cpg.sarif.Region
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.Throws
import kotlin.collections.Map
import kotlin.let

class SvelteLanguageFrontend(ctx: TranslationContext, language: SvelteLanguage = SvelteLanguage()) :
    LanguageFrontend<SvelteNode, SvelteNode>(ctx, language) {

    private val svelteParserScriptResourcePath = "/svelte/parser.js"
    private val nodeExecutable = "node"
    private val mapper: ObjectMapper =
        jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .addMixIn(SvelteNode::class.java, SvelteNodeMixin::class.java)

    private var currentFileContent: String = ""

    @Throws(TranslationException::class)
    override fun parse(file: File): TranslationUnitDeclaration {
        currentFileContent = file.readText()
        val tempParserScript = extractParserScript(svelteParserScriptResourcePath)
        val tud = newTranslationUnitDeclaration(file.name, currentFileContent)
        tud.language = this.language ?: SvelteLanguage()
        ctx.scopeManager.resetToGlobal(tud)
        this.ctx.currentTranslationUnit = tud

        try {
            val processBuilder =
                ProcessBuilder(nodeExecutable, tempParserScript.absolutePath, file.absolutePath)

            log.info(
                "Executing SVELTE parser: {} {} {}",
                nodeExecutable,
                tempParserScript.absolutePath,
                file.absolutePath,
            )

            val process = processBuilder.start()
            val astJson = process.inputStream.bufferedReader().readText()
            val errors = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                log.error(
                    "SVELTE parser script failed with exit code {}. Error output:\n{}",
                    exitCode,
                    errors,
                )
                var errorMsg = "Svelte parser failed with exit code $exitCode."
                var physLoc: PhysicalLocation? = null
                try {
                    val errorMap: Map<*, *> = mapper.readValue(errors, Map::class.java)
                    errorMsg = errorMap["message"] as? String ?: "Unknown Svelte parser error"
                    val errorPosMap = errorMap["position"] as? Map<*, *>
                    val line = (errorPosMap?.get("line") as? Number)?.toInt()
                    val col = (errorPosMap?.get("column") as? Number)?.toInt()
                    val region =
                        if (line != null && col != null) Region(line, col + 1, line, col + 1)
                        else Region()
                    physLoc = file.toUri()?.let { uri -> PhysicalLocation(uri, region) }
                } catch (e: Exception) {
                    log.warn("Could not parse Svelte parser error JSON: {}", errors, e)
                }
                val problem =
                    newProblemDeclaration(errorMsg, ProblemNode.ProblemType.PARSER, physLoc)
                tud.addDeclaration(problem)
                throw TranslationException(errorMsg)
            }

            if (astJson.isBlank()) {
                throw TranslationException("Svelte parser returned empty AST.")
            }

            log.debug("Received Svelte AST JSON for {}:\n" + "{}", file.name, astJson)

            val svelteAstRoot: Root =
                try {
                    mapper.readValue(astJson, Root::class.java)
                } catch (e: Exception) {
                    log.error("Failed to deserialize Svelte AST JSON for {}", file.name, e)
                    throw TranslationException("Failed to deserialize Svelte AST JSON", e)
                }

            log.info("Successfully deserialized Svelte AST for {}", file.name)

            handleRoot(tud, svelteAstRoot)

            return tud
        } catch (e: Exception) {
            log.error("Error processing Svelte file {}", file.name, e)
            if (e is TranslationException) throw e
            else throw TranslationException(e.message ?: "Unknown error during Svelte parsing", e)
        } finally {
            Files.deleteIfExists(tempParserScript.toPath())
            currentFileContent = ""
            this.ctx.currentTranslationUnit = null
        }
    }

    private fun handleRoot(tud: TranslationUnitDeclaration, ast: Root) {
        val componentName = tud.name.substringBeforeLast('.', tud.name)
        val record = newRecordDeclaration(componentName, "class", rawNode = ast)
        record.location = locationOf(ast)
        record.language = this.language ?: SvelteLanguage()
        ctx.scopeManager.enterScope(record)

        record.addComment("Svelte script, style, and fragment handling simplified.")

        ctx.scopeManager.leaveScope(record)
        tud.addDeclaration(record)
    }

    private fun extractParserScript(resourcePath: String): File {
        val resourceStream: InputStream? =
            SvelteLanguageFrontend::class.java.getResourceAsStream(resourcePath)

        if (resourceStream == null) {
            throw TranslationException("Could not find parser script in resources: $resourcePath")
        }

        val tempFile = Files.createTempFile("svelte-parser-", ".js").toFile()
        tempFile.deleteOnExit()

        resourceStream.use { input ->
            Files.copy(input, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        log.debug("Extracted parser script to {}", tempFile.absolutePath)
        return tempFile
    }

    override fun typeOf(typeNode: SvelteNode): Type {
        log.warn(
            "Svelte type resolution not implemented yet. Returning unknown type for node type: {}",
            typeNode.type,
        )
        return unknownType()
    }

    override fun codeOf(astNode: SvelteNode): String? {
        if (
            astNode.start >= 0 &&
                astNode.end <= currentFileContent.length &&
                astNode.start <= astNode.end
        ) {
            return currentFileContent.substring(astNode.start, astNode.end)
        } else {
            log.warn(
                "Invalid start/end indices for node type {}: start={}, end={}, contentLength={}",
                astNode.type,
                astNode.start,
                astNode.end,
                currentFileContent.length,
            )
            return null
        }
    }

    override fun locationOf(astNode: SvelteNode): PhysicalLocation? {
        val currentTU = this.ctx.currentTranslationUnit ?: return null
        return this.ctx.locationCache.computeIfAbsent(astNode) { node ->
            val region = this.getRegionFromStartEnd(node.start, node.end)
            currentTU.name.toUri()?.let { uri -> PhysicalLocation(uri, region) }
        }
    }

    private fun getRegionFromStartEnd(start: Int, end: Int): Region {
        if (start < 0 || end < 0 || start > end || end > currentFileContent.length) {
            log.warn(
                "Invalid start/end offset for region calculation: start={}, end={}",
                start,
                end,
            )
            return Region(-1, -1, -1, -1)
        }
        return FrontendUtils.parseRegion(currentFileContent, start, end)
    }

    override fun setComment(node: Node, astNode: SvelteNode) {
        // Placeholder implementation
    }
}
