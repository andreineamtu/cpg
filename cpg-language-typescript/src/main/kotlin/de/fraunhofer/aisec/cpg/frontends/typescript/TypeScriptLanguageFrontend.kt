/*
 * Copyright (c) 2021, Fraunhofer AISEC. All rights reserved.
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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.fraunhofer.aisec.cpg.TranslationContext
import de.fraunhofer.aisec.cpg.frontends.FrontendUtils
import de.fraunhofer.aisec.cpg.frontends.Language
import de.fraunhofer.aisec.cpg.frontends.LanguageFrontend
import de.fraunhofer.aisec.cpg.graph.*
import de.fraunhofer.aisec.cpg.graph.Annotation
import de.fraunhofer.aisec.cpg.graph.declarations.TranslationUnitDeclaration
import de.fraunhofer.aisec.cpg.graph.statements.expressions.CallExpression
import de.fraunhofer.aisec.cpg.graph.types.Type
import de.fraunhofer.aisec.cpg.sarif.PhysicalLocation
import de.fraunhofer.aisec.cpg.sarif.Region
import de.fraunhofer.aisec.cpg.sarif.translation.toUri
import java.io.File
import java.io.File.createTempFile
import java.io.FileNotFoundException
import java.io.LineNumberReader
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * This language frontend adds experimental support for TypeScript. It is definitely not feature
 * complete, but can be used to parse simple typescript snippets through the official typescript
 * parser written in TypeScript. It includes a simple binary (built by deno) that invokes this
 * parser. It basically dumps the AST in a JSON structure on stdout and this input is parsed by this
 * frontend.
 *
 * Because TypeScript is a strict super-set of JavaScript, this frontend can also be used to parse
 * JavaScript. However, this is not properly tested. Furthermore, the official TypeScript parser
 * also has built-in support for React dialects TSX and JSX.
 */
class TypeScriptLanguageFrontend(
    ctx: TranslationContext,
    language: Language<out LanguageFrontend<*, *>>,
) : LanguageFrontend<TypeScriptNode, TypeScriptNode>(ctx, language) {

    val declarationHandler = DeclarationHandler(this)
    val statementHandler = StatementHandler(this)
    val expressionHandler = ExpressionHandler(this)
    val typeHandler = TypeHandler(this)

    private var currentFileContent: String? = null

    private val mapper = jacksonObjectMapper()

    companion object {
        private val parserFile: File = createTempFile("parser", "-ts")

        init {
            val arch = System.getProperty("os.arch")
            val os: String =
                when {
                    System.getProperty("os.name").startsWith("Mac") -> {
                        "macos"
                    }
                    System.getProperty("os.name").startsWith("Linux") -> {
                        "linux"
                    }
                    else -> {
                        "windows"
                    }
                }

            try {
                val link = this::class.java.getResourceAsStream("/typescript/parser-$os-$arch")
                link?.use {
                    log.info(
                        "Extracting TS parser out of resources to {}",
                        parserFile.absoluteFile.toPath(),
                    )
                    Files.copy(
                        it,
                        parserFile.absoluteFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    parserFile.setExecutable(true)
                }
                    ?: log.warn(
                        "TS parser executable not found in resources for $os-$arch. Parsing TS/JS will fail."
                    )
            } catch (e: Exception) {
                log.warn(
                    "Failed to extract TS parser executable for $os-$arch. Parsing TS/JS will fail.",
                    e,
                )
            }
        }
    }

    override fun parse(file: File): TranslationUnitDeclaration {
        if (file.extension == "svelte") {
            log.info("Detected .svelte file, delegating to SvelteLanguageFrontend: {}", file.name)
            val svelteLanguage = SvelteLanguage()
            val svelteFrontend = SvelteLanguageFrontend(this.ctx, svelteLanguage)
            return svelteFrontend.parse(file)
        }

        log.debug("Parsing TS/JS file with Deno parser: {}", file.name)
        currentFileContent = file.readText()

        if (!parserFile.exists()) {
            val errorMsg =
                "TypeScript parser executable not found or failed to extract @ ${parserFile.absolutePath}. Cannot parse TS/JS files."
            log.error(errorMsg)
            val tud = newTranslationUnitDeclaration(file.name, file.readText())
            tud.language = this.language
            val problem = newProblemDeclaration(errorMsg, ProblemNode.ProblemType.PARSER)
            problem.location = file.toUri()?.let { PhysicalLocation(it, Region()) }
            tud.addDeclaration(problem)
            return tud
        }

        val p = Runtime.getRuntime().exec(arrayOf(parserFile.absolutePath, file.absolutePath))

        val node = mapper.readValue(p.inputStream, TypeScriptNode::class.java)

        val translationUnit = this.declarationHandler.handle(node) as TranslationUnitDeclaration

        handleComments(file, translationUnit)

        currentFileContent = null

        return translationUnit
    }

    override fun typeOf(type: TypeScriptNode): Type {
        return typeHandler.handleNode(type)
    }

    /**
     * Extracts comments from the file with a regular expression and calls a best effort approach
     * function that matches them to the closes ast node in the cpg.
     *
     * @param file The source of comments
     * @param translationUnit the ast root node which children get the comments associated to
     */
    fun handleComments(file: File, translationUnit: TranslationUnitDeclaration) {
        val matches: Sequence<MatchResult>? =
            currentFileContent?.let {
                Regex("(?:/\\*((?:[^*]|(?:\\*+[^*/]))*)\\*+/)|(?://(.*))").findAll(it)
            }
        matches?.toList()?.forEach { result ->
            val groups = result.groups
            groups[0]?.let {
                val commentRegion = getRegionFromStartEnd(file, it.range.first, it.range.last)

                var comment = groups[1]?.value ?: (groups[2]?.value ?: it.value)

                comment = comment.trim()

                comment = comment.trim('\n')

                FrontendUtils.matchCommentToNode(
                    comment,
                    commentRegion ?: translationUnit.location?.region ?: Region(),
                    translationUnit,
                )
            }
        }
    }

    override fun codeOf(astNode: TypeScriptNode): String? {
        return astNode.code
    }

    override fun locationOf(astNode: TypeScriptNode): PhysicalLocation {
        var position = astNode.location.pos

        astNode.code?.let { code ->
            currentFileContent?.let { position = it.indexOf(code, position) }
        }

        val region =
            getRegionFromStartEnd(File(astNode.location.file), position, astNode.location.end)
        return PhysicalLocation(File(astNode.location.file).toURI(), region ?: Region())
    }

    fun getRegionFromStartEnd(file: File, start: Int, end: Int): Region? {
        val content =
            this.currentFileContent
                ?: try {
                    file.readText()
                } catch (e: FileNotFoundException) {
                    log.error("File not found when trying to get region: {}", file.absolutePath)
                    return null
                }

        val lineNumberReader = LineNumberReader(content.reader())

        lineNumberReader.skip(start.toLong())
        val startLine = lineNumberReader.lineNumber + 1
        val remainingSkip = (end - start).toLong()
        if (remainingSkip < 0) {
            log.warn("Invalid range for region calculation: start={}, end={}", start, end)
            return null
        }
        lineNumberReader.skip(remainingSkip)
        val endLine = lineNumberReader.lineNumber + 1

        val region =
            FrontendUtils.parseColumnPositionsFromFile(
                content,
                end - start,
                start,
                startLine,
                endLine,
            )
        return region
    }

    override fun setComment(node: Node, astNode: TypeScriptNode) {
        // not implemented
    }

    internal fun getIdentifierName(node: TypeScriptNode) =
        node.firstChild("Identifier")?.let { this.codeOf(it) } ?: ""

    fun processAnnotations(node: Node, astNode: TypeScriptNode) {
        astNode.children
            ?.filter { it.type == "Decorator" }
            ?.map { handleDecorator(it) }
            ?.let { node.annotations += it }
    }

    private fun handleDecorator(node: TypeScriptNode): Annotation {
        val callExpr = node.firstChild("CallExpression")
        return if (callExpr != null) {
            val call = this.expressionHandler.handle(callExpr) as CallExpression

            val annotation = newAnnotation(call.name.localName, rawNode = node)

            annotation.members =
                call.arguments
                    .map { newAnnotationMember("", it).codeAndLocationFrom(it) }
                    .toMutableList()

            call.disconnectFromGraph()

            annotation
        } else {
            val name = this.getIdentifierName(node)

            newAnnotation(name, rawNode = node)
        }
    }
}

class Location(var file: String, var pos: Int, var end: Int)

class TypeScriptNode(
    var type: String,
    var children: List<TypeScriptNode>?,
    var location: Location,
    var code: String?,
) {
    /** Returns the first child node, that represent a type, if it exists. */
    val typeChildNode: TypeScriptNode?
        get() {
            return this.children?.firstOrNull {
                it.type == "TypeReference" ||
                    it.type == "AnyKeyword" ||
                    it.type == "StringKeyword" ||
                    it.type == "NumberKeyword" ||
                    it.type == "ArrayType" ||
                    it.type == "TypeLiteral"
            }
        }

    fun firstChild(type: String): TypeScriptNode? {
        return this.children?.firstOrNull { it.type == type }
    }
}
