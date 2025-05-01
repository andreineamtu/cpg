package de.fraunhofer.aisec.cpg.frontends.svelte

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.fraunhofer.aisec.cpg.TranslationConfiguration
import de.fraunhofer.aisec.cpg.frontends.LanguageFrontend
import de.fraunhofer.aisec.cpg.frontends.TranslationException
import de.fraunhofer.aisec.cpg.graph.*
import de.fraunhofer.aisec.cpg.graph.declarations.*
import de.fraunhofer.aisec.cpg.graph.scopes.RecordScope // Import RecordScope
import de.fraunhofer.aisec.cpg.passes.scopes.ScopeManager
import de.fraunhofer.aisec.cpg.sarif.PhysicalLocation
import de.fraunhofer.aisec.cpg.sarif.Region
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.Throws

class SvelteLanguageFrontend(
    language: SvelteLanguage,
    config: TranslationConfiguration,
    scopeManager: ScopeManager = ScopeManager(),
) : LanguageFrontend(language, config, scopeManager) {

    private val parserScriptResourcePath = "/svelte/parser.js" // Adjusted path within resources
    private val nodeExecutable = "node" // Assumes node is in PATH
    // Configure ObjectMapper for polymorphism and to ignore unknown properties
    private val mapper: ObjectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .addMixIn(SvelteNode::class.java, SvelteNodeMixin::class.java)

    private var currentFileContent: String = "" // Store content for getCodeFromRawNode

    @Throws(TranslationException::class)
    override fun parse(file: File): TranslationUnitDeclaration {
        // Store file content for location mapping later
        currentFileContent = file.readText()

        val tempParserScript = extractParserScript()
        // Create the TUD now, passing the file content
        val tud = newTranslationUnitDeclaration(file.name, currentFileContent)
        tud.language = this.language // Set language if not done by newTranslationUnitDeclaration

        scopeManager.resetToGlobal(tud)

        try {
            val processBuilder = ProcessBuilder(
                nodeExecutable,
                tempParserScript.absolutePath,
                file.absolutePath
            )

            log.info("Executing Svelte parser: {} {} {}", nodeExecutable, tempParserScript.absolutePath, file.absolutePath)

            val process = processBuilder.start()
            val astJson = process.inputStream.bufferedReader().readText()
            val errors = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                log.error("Svelte parser script failed with exit code {}. Error output:
{}", exitCode, errors)
                try {
                   val errorMap = mapper.readValue(errors, Map::class.java)
                   val errorMsg = errorMap["message"] as? String ?: "Unknown error"
                   val errorPosMap = errorMap["position"] as? Map<*, *>
                   val line = (errorPosMap?.get("line") as? Number)?.toInt()
                   val col = (errorPosMap?.get("column") as? Number)?.toInt()
                   val region = if(line != null && col != null) Region(line, col + 1, line, col + 1) else Region() // SARIF is 1-based
                   val physLoc = PhysicalLocation(file.toURI(), region)
                   // Create a ProblemDeclaration in the TUD
                   val problem = newProblemDeclaration("Svelte parser failed: $errorMsg", "Parser Error", physLoc)
                   tud.addDeclaration(problem)

                   // Still throw exception to signal failure, but TUD contains the problem
                   throw TranslationException("Svelte parser failed: $errorMsg")
                } catch (e: Exception) {
                   throw TranslationException("Svelte parser failed with exit code $exitCode. Raw error: $errors")
                }
            }

            if (astJson.isBlank()) {
                 throw TranslationException("Svelte parser returned empty AST.")
            }

            log.debug("Received Svelte AST JSON for {}:
{}", file.name, astJson) // Log AST for debugging

            // Deserialize astJson into our Svelte AST data structure
            val svelteAstRoot: Root = try {
                mapper.readValue(astJson, Root::class.java)
            } catch (e: Exception) {
                log.error("Failed to deserialize Svelte AST JSON for {}", file.name, e)
                throw TranslationException("Failed to deserialize Svelte AST JSON", e)
            }

            log.info("Successfully deserialized Svelte AST for {}", file.name)

            // Start CPG conversion from the deserialized AST root
            handleRoot(tud, svelteAstRoot)

            return tud
        } catch (e: Exception) {
            log.error("Error processing Svelte file {}", file.name, e)
            // Ensure exception is wrapped in TranslationException if not already
            if (e is TranslationException) throw e else throw TranslationException(e)
        } finally {
            // Clean up the temporary script file
            Files.deleteIfExists(tempParserScript.toPath())
            currentFileContent = "" // Clear content after processing
        }
    }

    /**
     * Handles the root of the Svelte AST. Creates the component's RecordDeclaration
     * and processes its parts (script, module, fragment, style).
     */
    private fun handleRoot(tud: TranslationUnitDeclaration, ast: Root) {
        // Create a RecordDeclaration representing the Svelte component itself
        val componentName = tud.name.substringBeforeLast('.') // Simple name from filename
        val record = newRecordDeclaration(componentName, "class", ast) // Or "struct", kind might need adjustment
        record.location = tud.location // Component represents the whole file
        scopeManager.enterScope(record) // Enter component scope

        // Handle <script context="module">...</script>
        ast.module?.let { handleScript(it, record, isModuleScript = true) }

        // Handle <script>...</script> (instance script)
        ast.instance?.let { handleScript(it, record, isModuleScript = false) }

        // Handle <style>...</style>
        ast.css?.let { handleStyle(it, record) }

        // Handle the template fragment
        handleFragment(ast.fragment, record)

        // Add the component RecordDeclaration to the TUD
        scopeManager.leaveScope(record)
        tud.addDeclaration(record)
    }

    /**
     * Handles <script> tags (instance or module).
     * For now, creates a placeholder method/function or adds declarations to scope.
     */
    private fun handleScript(ast: Script, parent: RecordDeclaration, isModuleScript: Boolean) {
        val scriptLocation = getLocationFromRawNode(ast)
        val scriptCode = getCodeFromRawNode(ast) // Get the actual script code

        log.info("Processing {} script block at {}", if(isModuleScript) "module" else "instance", scriptLocation)
        log.debug("Script content:
{}", scriptCode)

        if (isModuleScript) {
            // Module script content conceptually belongs to the 'static' part of the component.
            // We could try to parse its declarations and add them directly to the record's scope.
            // Or create a static initializer block/method.
            // TODO: Implement module script parsing/handling (potentially reusing TS/JS frontend)
            val moduleInit = newMethodDeclaration("<module-init>", scriptCode ?: "", true, parent)
            moduleInit.location = scriptLocation
            scopeManager.addDeclaration(moduleInit)
            // For now, add a comment/problem indicating it needs real parsing
             parent.addComment("Module script content needs parsing: ${scriptCode?.take(100)}...")

        } else {
            // Instance script content runs when the component is instantiated.
            // It often contains variable declarations, lifecycle functions, etc.
            // This could be represented as a constructor or an instance initializer method.
            // TODO: Implement instance script parsing/handling (potentially reusing TS/JS frontend)
            val instanceInit = newMethodDeclaration("<instance-init>", scriptCode ?: "", false, parent)
            instanceInit.location = scriptLocation
            scopeManager.addDeclaration(instanceInit)
             // For now, add a comment/problem indicating it needs real parsing
             parent.addComment("Instance script content needs parsing: ${scriptCode?.take(100)}...")
        }
        // Note: A more robust approach would involve invoking the TS/JS frontend here
        // on the `ast.content`, providing the correct scope context. This is complex.
    }

    /**
     * Handles the <style> tag.
     * For now, maybe just create a comment or placeholder node.
     */
    private fun handleStyle(ast: Style, parent: RecordDeclaration) {
        val styleLocation = getLocationFromRawNode(ast)
        val styleCode = ast.content.styles
        log.info("Found style block at {}", styleLocation)
        log.debug("Style content:
{}", styleCode.take(200)) // Log snippet

        // TODO: Implement style block handling (potentially creating a comment or placeholder)
        // Could potentially involve a CSS parser in the future.
        parent.addComment("Style block content: ${styleCode.take(100)}...")
    }

    /**
     * Handles the main template fragment and its children.
     */
    private fun handleFragment(ast: Fragment, parent: Node) {
         log.debug("Processing fragment with {} children.", ast.children.size)
         scopeManager.enterScope(parent) // Assuming fragment children are in parent's scope initially

         for(childNode in ast.children) {
             // TODO: Call specific handlers based on childNode.type
             when(childNode) {
                 is Text -> handleText(childNode, parent)
                 is ExpressionTag -> handleExpressionTag(childNode, parent)
                 is Comment -> handleComment(childNode, parent)
                 is Element -> handleElement(childNode, parent)
                 // Add cases for other valid children (Blocks, etc.)
                 else -> {
                     log.warn("Unsupported Svelte AST node type encountered in fragment: {}", childNode.type)
                     val problem = newProblemDeclaration(
                         "Unsupported node type: ${childNode.type}",
                         "AST Conversion",
                         getLocationFromRawNode(childNode)
                     )
                     scopeManager.addDeclaration(problem) // Add problem to current scope
                 }
             }
         }
         scopeManager.leaveScope(parent)
    }

     /** Handles Text nodes - currently creates a comment */
    private fun handleText(ast: Text, parent: Node) {
        log.debug("Handling Text node: '{}'", ast.raw.trim().take(50))
        // For now, just add a comment to the parent node
        val commentText = "Template text: ${ast.raw.trim().take(100)}"
        if(parent is Declaration) parent.addComment(commentText)
        // Alternatively create a Literal<String> if appropriate CPG representation exists
    }

     /** Handles ExpressionTag nodes - attempts to parse inner expression */
    private fun handleExpressionTag(ast: ExpressionTag, parent: Node) {
        log.debug("Handling ExpressionTag node: type {}", ast.expression.type)
        val expressionLocation = getLocationFromRawNode(ast.expression)
        val expressionCode = getCodeFromRawNode(ast.expression)

        // Attempt to handle the inner expression
        val cpgExpression = handleTemplateExpression(ast.expression)

        // TODO: Connect the resulting cpgExpression to the parent CPG node.
        // How depends on the parent. If parent is an element's VariableDecl,
        // maybe this represents content? If it's in an attribute value,
        // it forms part of the attribute's initializer.
        // For now, add as a comment to the parent.
        val commentText = "Template expression parsed as: ${cpgExpression::class.simpleName}"
        if (parent is Declaration) parent.addComment(commentText)
        else if (parent is CompoundStatement) { // E.g., the placeholder for Element
            val commentNode = newComment(commentText)
            commentNode.location = expressionLocation
            parent.addStatement(commentNode)
        }
         // Add the expression itself to the scope if it's a statement (like ProblemExpression)
         if (cpgExpression is Statement && parent is CompoundStatement) {
            parent.addStatement(cpgExpression)
         } else if (cpgExpression is Statement) {
            scopeManager.addStatement(cpgExpression)
         }
    }

    /** 
     * Helper to handle different types of expressions found within template tags.
     * Returns the corresponding CPG Expression node.
     */
    private fun handleTemplateExpression(exprAst: ExpressionNode): Expression {
        val cpgExpression: Expression = when (exprAst) {
            is Identifier -> {
                log.debug("Template Expression: Identifier '{}'", exprAst.name)
                // Create a reference to the variable
                newReference(exprAst.name, unknownType(), exprAst)
            }
            is Literal -> {
                log.debug("Template Expression: Literal '{}'", exprAst.raw)
                // Determine type based on value
                val type = when (exprAst.value) {
                    is String -> primitiveType("string")
                    is Number -> primitiveType("number") // Or specific (int, double)
                    is Boolean -> primitiveType("boolean")
                    null -> unknownType() // Or a specific NullType
                    else -> unknownType()
                }
                newLiteral(exprAst.value, type, exprAst)
            }
            // TODO: Add cases for BinaryExpression, CallExpression, MemberExpression etc.
            else -> {
                log.warn("Unsupported expression type in template: {}", exprAst.type)
                newProblemExpression(
                    "Unsupported template expression type: ${exprAst.type}",
                    rawNode = exprAst
                )
            }
        }
        return cpgExpression
    }

     /** Handles Comment nodes - currently creates a comment */
    private fun handleComment(ast: Comment, parent: Node) {
        log.debug("Handling Comment node: '{}'", ast.data.trim().take(50))
        val commentText = "Template comment: ${ast.data.trim().take(100)}"
        if(parent is Declaration) parent.addComment(commentText)
    }

    /** Handles Element nodes - Creates a VariableDeclaration placeholder and processes children/attributes */
    private fun handleElement(ast: Element, parent: Node) {
        log.debug("Handling Element node: <{}>", ast.name)
        val elementLocation = getLocationFromRawNode(ast)
        val elementCode = getCodeFromRawNode(ast)

        // --- CPG Node Creation --- 
        // Option 1: Represent element as a variable declaration (placeholder)
        // We might need a custom type like "HTMLElement" or use unknownType()
        val elementVar = newVariableDeclaration(ast.name, unknownType(), false, elementCode)
        elementVar.location = elementLocation
        elementVar.isImplicit = true // Mark as implicit as it represents template structure
        // Add variable to the current scope (e.g., the component record or parent element scope)
        scopeManager.addDeclaration(elementVar)

        // Set this as the new parent scope for attributes and children
        val parentScopeNode = elementVar 

        // --- Original Placeholder Logic (Commented out) --- 
        /*
        val commentText = "Template Element: <${ast.name}>"
        if(parent is Declaration) parent.addComment(commentText)

        val elementPlaceholderNode = newCompoundStatement(getCodeFromRawNode(ast))
        elementPlaceholderNode.location = elementLocation
        scopeManager.addStatement(elementPlaceholderNode) 
        */

        // Process attributes, associating them with the elementVar
        scopeManager.enterScope(parentScopeNode) // Enter scope associated with the element
        for(attribute in ast.attributes) {
            handleAttribute(attribute, parentScopeNode) // Pass elementVar as parent
        }

        // Process child nodes recursively
        for(childNode in ast.children) {
             when(childNode) {
                 is Text -> handleText(childNode, parentScopeNode)
                 is ExpressionTag -> handleExpressionTag(childNode, parentScopeNode)
                 is Comment -> handleComment(childNode, parentScopeNode)
                 is Element -> handleElement(childNode, parentScopeNode) // Handle nested elements
                 // Add cases for other valid children (Blocks, etc.)
                 else -> {
                     log.warn("Unsupported Svelte AST node type encountered in element <{}>: {}", ast.name, childNode.type)
                     val problem = newProblemDeclaration(
                         "Unsupported node type in <${ast.name}>: ${childNode.type}",
                         "AST Conversion",
                         getLocationFromRawNode(childNode)
                     )
                      scopeManager.addDeclaration(problem)
                 }
             }
         }
         scopeManager.leaveScope(parentScopeNode)
    }

    /** Handles Attribute nodes - Creates a FieldDeclaration placeholder */
    private fun handleAttribute(ast: Attribute, parent: Node) {
        log.debug("Handling Attribute node: {}={...}", ast.name)
        val attributeLocation = getLocationFromRawNode(ast)
        val attributeCode = getCodeFromRawNode(ast)
        
        // --- CPG Node Creation --- 
        // Option 1: Represent attribute as a FieldDeclaration of the parent element node
        // The type depends on the attribute value (String, Expression, etc.)
        // For now, use unknownType()
        val attributeField = newFieldDeclaration(ast.name, unknownType(), listOf(), attributeCode, false, ast)
        attributeField.location = attributeLocation
        attributeField.isImplicit = true // Represents template structure

        // Add the field to the parent scope (which should be the element's scope)
        scopeManager.addDeclaration(attributeField)

        // Process the value and potentially set it as the FieldDeclaration's initializer
        // Note: Attribute values can be complex (list of Text/ExpressionTag)
        // We might need a helper to combine these into a single Expression (e.g., String concat)
        // For now, let's just process them recursively and maybe add as comments to the field.
        scopeManager.enterScope(attributeField) // Enter scope for value processing
        val valueExpressions = mutableListOf<Node>()
        for(valueNode in ast.value) {
             when(valueNode) {
                 is Text -> {
                    log.debug("Attribute Text value: '{}'", valueNode.raw)
                    // Create a Literal for the text part
                    val literal = newLiteral(valueNode.data, primitiveType("string"), valueNode)
                    valueExpressions.add(literal)
                 }
                 is ExpressionTag -> {
                    log.debug("Attribute ExpressionTag value: type {}", valueNode.expression.type)
                    // TODO: Properly handle/parse the expression tag here
                    // For now, create a placeholder expression or comment
                    val exprPlaceholder = newProblemExpression("Expression value for attribute '${ast.name}' needs parsing", rawNode = valueNode)
                    exprPlaceholder.location = getLocationFromRawNode(valueNode)
                    valueExpressions.add(exprPlaceholder)
                 }
                 // Handle other potential value types if necessary
                 else -> {
                      log.warn("Unsupported Svelte AST node type in attribute '{}' value: {}", ast.name, valueNode.type)
                 }
             }
        }
        scopeManager.leaveScope(attributeField)

        // TODO: Assign a combined/processed valueExpression list as the initializer
        // Simple case: if only one literal, assign it.
        if (valueExpressions.size == 1 && valueExpressions.first() is Expression) {
            attributeField.initializer = valueExpressions.first() as Expression
        } else if (valueExpressions.isNotEmpty()) {
            // More complex: Combine literals? Represent as template literal? Add as comments?
            attributeField.addComment("Attribute value nodes: ${valueExpressions.map { it::class.simpleName }.joinToString()}")
        }
        // --- Original Placeholder Logic (Commented out) ---
        /*
        val commentText = "Element Attribute: ${attributeCode}"
        if(parent is Declaration) parent.addComment(commentText)
        else if (parent is CompoundStatement) { // Add comment to placeholder scope
             val commentNode = newComment(commentText)
             commentNode.location = attributeLocation
             parent.addStatement(commentNode)
        }
        */
    }

    /**
     * Extracts the parser.js script from resources to a temporary file.
     */
    private fun extractParserScript(): File {
        val resourceStream: InputStream? = 
            SvelteLanguageFrontend::class.java.getResourceAsStream(parserScriptResourcePath)
        
        if (resourceStream == null) {
             throw TranslationException("Could not find parser script in resources: $parserScriptResourcePath")
        }

        val tempFile = Files.createTempFile("svelte-parser-", ".js").toFile()
        tempFile.deleteOnExit() // Ensure cleanup on JVM exit

        resourceStream.use { input ->
            Files.copy(input, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        
        log.debug("Extracted parser script to {}", tempFile.absolutePath)
        return tempFile
    }

    /**
     * Gets the source code snippet corresponding to a Svelte AST node.
     */
    override fun <T> getCodeFromRawNode(astNode: T): String? {
        if (astNode is SvelteNode) {
            // Check bounds to prevent IndexOutOfBoundsException
            if (astNode.start >= 0 && astNode.end <= currentFileContent.length && astNode.start <= astNode.end) {
                return currentFileContent.substring(astNode.start, astNode.end)
            } else {
                log.warn("Invalid start/end indices for node type {}: start={}, end={}, contentLength={}",
                    astNode.type, astNode.start, astNode.end, currentFileContent.length)
            }
        } else if (astNode is Map<*, *>) {
            // Fallback if we receive a raw map (less type-safe)
            val start = (astNode["start"] as? Number)?.toInt()
            val end = (astNode["end"] as? Number)?.toInt()
             if (start != null && end != null && start >= 0 && end <= currentFileContent.length && start <= end) {
                return currentFileContent.substring(start, end)
            }
        }
        return null // Or super.getCodeFromRawNode(astNode) if applicable
    }

    /**
     * Gets the PhysicalLocation (file, line, column) for a Svelte AST node.
     */
    override fun <T> getLocationFromRawNode(astNode: T): PhysicalLocation? {
        if (astNode is SvelteNode) {
            return this.locationCache.computeIfAbsent(astNode) { // Use cache
                val region = this.getRegionFromStartEnd(astNode.start, astNode.end)
                // Assuming currentTUD holds the current translation unit context
                PhysicalLocation(this.currentTU.name.toUri(), region)
            }
        } else if (astNode is Map<*, *>) {
             // Fallback for raw map
             val start = (astNode["start"] as? Number)?.toInt()
             val end = (astNode["end"] as? Number)?.toInt()
             if (start != null && end != null) {
                 val region = this.getRegionFromStartEnd(start, end)
                 return PhysicalLocation(this.currentTU.name.toUri(), region)
             }
        }
        return null // Or super.getLocationFromRawNode(astNode)
    }

    override fun <S, T> setComment(s: S, ctx: T) {
        // TODO: Implement if Svelte AST provides comments attached to nodes
        // Svelte AST has Comment nodes, handled in handleComment for now.
        // If comments are attached directly to other nodes, handle here.
        if(s is Node && ctx is SvelteNode) {
             // Check if ctx has associated comments and add them to s
        }
    }
} 