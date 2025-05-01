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
import de.fraunhofer.aisec.cpg.frontends.LanguageFrontend
import de.fraunhofer.aisec.cpg.frontends.TranslationException
import de.fraunhofer.aisec.cpg.graph.*
import de.fraunhofer.aisec.cpg.graph.declarations.*
import de.fraunhofer.aisec.cpg.graph.statements.CompoundStatement
import de.fraunhofer.aisec.cpg.graph.statements.Statement
import de.fraunhofer.aisec.cpg.graph.statements.expressions.*
import de.fraunhofer.aisec.cpg.graph.types.*
import de.fraunhofer.aisec.cpg.sarif.PhysicalLocation
import de.fraunhofer.aisec.cpg.sarif.Region
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.Throws

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
                    val errorMap = mapper.readValue(errors, Map::class.java)
                    errorMsg = errorMap["message"] as? String ?: "Unknown Svelte parser error"
                    val errorPosMap = errorMap["position"] as? Map<*, *>
                    val line = (errorPosMap?.get("line") as? Number)?.toInt()
                    val col = (errorPosMap?.get("column") as? Number)?.toInt()
                    val region =
                        if (line != null && col != null) Region(line, col + 1, line, col + 1)
                        else Region()
                    physLoc = file.toUri()?.let { PhysicalLocation(it, region) }
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
        ast.module?.let { handleScript(it, record, isModuleScript = true) }
        ast.instance?.let { handleScript(it, record, isModuleScript = false) }
        ast.css?.let { handleStyle(it, record) }
        handleFragment(ast.fragment, record)
        ctx.scopeManager.leaveScope(record)
        tud.addDeclaration(record)
    }

    private fun handleScript(ast: Script, parent: RecordDeclaration, isModuleScript: Boolean) {
        val scriptLocation = locationOf(ast)
        val scriptCode = codeOf(ast)

        log.info(
            "Processing {} script block at {}",
            if (isModuleScript) "module" else "instance",
            scriptLocation,
        )
        log.debug("Script content:\n{}", scriptCode)

        val method: MethodDeclaration =
            if (isModuleScript) {
                newMethodDeclaration("<module-init>", scriptCode ?: "", true, parent, rawNode = ast)
            } else {
                newMethodDeclaration(
                    "<instance-init>",
                    scriptCode ?: "",
                    false,
                    parent,
                    rawNode = ast,
                )
            }
        method.location = scriptLocation
        method.language = this.language
        ctx.scopeManager.addDeclaration(method)
        method.addComment(
            "${if (isModuleScript) "Module" else "Instance"} script content needs parsing: ${scriptCode?.take(100)}..."
        )
    }

    private fun handleStyle(ast: Style, parent: RecordDeclaration) {
        val styleLocation = locationOf(ast)
        val styleCode = ast.content.styles
        log.info("Found style block at {}", styleLocation)
        log.debug("Style content:\n{}", styleCode.take(200))
        parent.addComment("Style block content: ${styleCode.take(100)}...")
    }

    private fun handleFragment(ast: Fragment, parent: Node) {
        log.debug("Processing fragment with {} children.", ast.children.size)
        ctx.scopeManager.enterScope(parent)
        for (childNode in ast.children) {
            when (childNode) {
                is Text -> handleText(childNode, parent)
                is ExpressionTag -> handleExpressionTag(childNode, parent)
                is Comment -> handleComment(childNode, parent)
                is Element -> handleElement(childNode, parent)
                else -> {
                    log.warn(
                        "Unsupported Svelte AST node type encountered in fragment: {}",
                        childNode.type,
                    )
                    val problem =
                        newProblemDeclaration(
                            "Unsupported node type: ${childNode.type}",
                            ProblemNode.ProblemType.PARSER,
                            locationOf(childNode),
                        )
                    problem.language = this.language
                    ctx.scopeManager.addDeclaration(problem)
                }
            }
        }
        ctx.scopeManager.leaveScope(parent)
    }

    private fun handleText(ast: Text, parent: Node) {
        log.debug("Handling Text node: '{}'", ast.raw.trim().take(50))
        val commentText = "Template text: ${ast.raw.trim().take(100)}"
        parent.addComment(commentText)
    }

    private fun handleExpressionTag(ast: ExpressionTag, parent: Node) {
        log.debug("Handling ExpressionTag node: type {}", ast.expression.type)
        val expressionLocation = locationOf(ast.expression)
        val expressionCode = codeOf(ast.expression)
        val cpgExpression = handleTemplateExpression(ast.expression)
        val commentText = "Template expression parsed as: ${cpgExpression::class.simpleName}"
        parent.addComment(commentText)

        if (cpgExpression is Statement && parent is CompoundStatement) {
            parent.addStatement(cpgExpression)
        } else {
            log.warn(
                "Cannot add non-statement expression tag result to parent of type {}. Code: {}",
                parent.javaClass.simpleName,
                expressionCode,
            )
        }
    }

    private fun handleTemplateExpression(exprAst: ExpressionNode): Expression {
        val cpgExpression: Expression =
            when (exprAst) {
                is Identifier -> {
                    log.debug("Template Expression: Identifier '{}'", exprAst.name)
                    newReference(exprAst.name, unknownType(), rawNode = exprAst)
                }
                is Literal -> {
                    log.debug("Template Expression: Literal '{}'", exprAst.raw)
                    val type =
                        when (exprAst.value) {
                            is String -> primitiveType("string")
                            is Number -> primitiveType("number")
                            is Boolean -> primitiveType("boolean")
                            null -> unknownType()
                            else -> unknownType()
                        }
                    newLiteral(exprAst.value, type, rawNode = exprAst)
                }
                else -> {
                    log.warn("Unsupported expression type in template: {}", exprAst.type)
                    newProblemExpression(
                        "Unsupported template expression type: ${exprAst.type}",
                        rawNode = exprAst,
                    )
                }
            }
        cpgExpression.location = locationOf(exprAst)
        cpgExpression.language = this.language
        return cpgExpression
    }

    private fun handleComment(ast: Comment, parent: Node) {
        log.debug("Handling Comment node: '{}'", ast.data.trim().take(50))
        val commentText = "Template comment: ${ast.data.trim().take(100)}"
        parent.addComment(commentText)
    }

    private fun handleElement(ast: Element, parent: Node) {
        log.debug("Handling Element node: <{}>", ast.name)
        val elementLocation = locationOf(ast)
        val elementCode = codeOf(ast)
        val elementVar =
            newVariableDeclaration(ast.name, unknownType(), false, elementCode, rawNode = ast)
        elementVar.location = elementLocation
        elementVar.isImplicit = true
        elementVar.language = this.language
        ctx.scopeManager.addDeclaration(elementVar)
        val parentScopeNode = elementVar
        ctx.scopeManager.enterScope(parentScopeNode)
        for (attribute in ast.attributes) {
            handleAttribute(attribute, parentScopeNode)
        }
        val elementBody = newBlock(rawNode = ast)
        elementBody.location = elementLocation
        ctx.scopeManager.enterScope(elementBody)
        for (childNode in ast.children) {
            when (childNode) {
                is Text -> handleText(childNode, elementBody)
                is ExpressionTag -> handleExpressionTag(childNode, elementBody)
                is Comment -> handleComment(childNode, elementBody)
                is Element -> handleElement(childNode, elementBody)
                else -> {
                    log.warn(
                        "Unsupported Svelte AST node type encountered in element <{}>: {}",
                        ast.name,
                        childNode.type,
                    )
                    val problem =
                        newProblemDeclaration(
                            "Unsupported node type in <${ast.name}>: ${childNode.type}",
                            ProblemNode.ProblemType.PARSER,
                            locationOf(childNode),
                        )
                    problem.language = this.language
                    ctx.scopeManager.addDeclaration(problem)
                }
            }
        }
        ctx.scopeManager.leaveScope(elementBody)
        elementVar.initializer = elementBody
        ctx.scopeManager.leaveScope(parentScopeNode)

        val declStmt = newDeclarationStatement(rawNode = ast)
        declStmt.addDeclaration(elementVar)
        if (parent is CompoundStatement) parent.addStatement(declStmt)
        else if (parent is RecordDeclaration) parent.addDeclaration(declStmt)
        else if (parent is MethodDeclaration) parent.addStatement(declStmt)
        else {
            log.warn(
                "Could not add Element declaration statement to parent of type {}",
                parent.javaClass.simpleName,
            )
        }
    }

    private fun handleAttribute(ast: Attribute, parent: Declaration) {
        log.debug("Handling Attribute node: {}={...}", ast.name)
        val attributeLocation = locationOf(ast)
        val attributeCode = codeOf(ast)
        val attributeField =
            newFieldDeclaration(
                ast.name,
                unknownType(),
                listOf(),
                attributeCode,
                false,
                rawNode = ast,
            )
        attributeField.location = attributeLocation
        attributeField.isImplicit = true
        attributeField.language = this.language
        ctx.scopeManager.addDeclaration(attributeField)
        val valueExpressions = mutableListOf<Expression>()
        for (valueNode in ast.value) {
            when (valueNode) {
                is Text -> {
                    log.debug("Attribute Text value: '{}'", valueNode.raw)
                    val literal =
                        newLiteral(valueNode.data, primitiveType("string"), rawNode = valueNode)
                    literal.location = locationOf(valueNode)
                    valueExpressions.add(literal)
                }
                is ExpressionTag -> {
                    log.debug("Attribute ExpressionTag value: type {}", valueNode.expression.type)
                    val expr = handleTemplateExpression(valueNode.expression)
                    valueExpressions.add(expr)
                }
                else -> {
                    log.warn(
                        "Unsupported Svelte AST node type in attribute '{}' value: {}",
                        ast.name,
                        valueNode.type,
                    )
                    val problem =
                        newProblemExpression(
                            "Unsupported attribute value type: ${valueNode.type}",
                            rawNode = valueNode,
                        )
                    problem.location = locationOf(valueNode)
                    valueExpressions.add(problem)
                }
            }
        }
        if (valueExpressions.size == 1) {
            attributeField.initializer = valueExpressions.first()
        } else if (valueExpressions.size > 1) {
            log.warn(
                "Multiple value parts for attribute '{}'. Creating placeholder initializer.",
                ast.name,
            )
            val listInitializer = newInitializerListExpression(rawNode = ast)
            listInitializer.initializers = valueExpressions
            listInitializer.location = attributeLocation
            attributeField.initializer = listInitializer
        }
        if (parent is RecordDeclaration) {
            parent.addField(attributeField)
        } else if (parent is VariableDeclaration && parent.initializer is CompoundStatement) {
            parent.addComment("Attribute '$ast.name' handled, associated with element variable.")
        }
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
