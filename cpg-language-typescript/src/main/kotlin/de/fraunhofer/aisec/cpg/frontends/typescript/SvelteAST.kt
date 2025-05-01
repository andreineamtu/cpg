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

// Ensure all necessary imports are present
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/** Base interface for Svelte AST nodes, containing common properties. */
interface SvelteNode {
    val type: String
    val start: Int
    val end: Int
}

// --- Root and Script related ---

@JsonIgnoreProperties(ignoreUnknown = true)
data class Root(
    override val type: String = "Root",
    override val start: Int,
    override val end: Int,
    val options: SvelteOptions?,
    val fragment: Fragment,
    val css: Style? = null,
    val instance: Script? = null,
    val module: Script? = null,
) : SvelteNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class SvelteOptions(
    // ... properties ...
    val start: Int,
    val end: Int,
    val runes: Boolean?, /* ... other options */
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Script(
    override val type: String = "Script",
    override val start: Int,
    override val end: Int,
    val context: String,
    val content: String,
) : SvelteNode

// --- Template related ---

@JsonIgnoreProperties(ignoreUnknown = true)
data class Fragment(
    override val type: String = "Fragment",
    override val start: Int,
    override val end: Int,
    @JsonProperty("nodes") val children: List<SvelteNode>,
) : SvelteNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class Style(
    override val type: String = "Style",
    override val start: Int,
    override val end: Int,
    val content: StyleContent,
) : SvelteNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class StyleContent(val start: Int, val end: Int, val styles: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Text(
    override val type: String = "Text",
    override val start: Int,
    override val end: Int,
    val data: String,
    val raw: String,
) : SvelteNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class Comment(
    override val type: String = "Comment",
    override val start: Int,
    override val end: Int,
    val data: String,
) : SvelteNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExpressionTag(
    override val type: String = "ExpressionTag",
    override val start: Int,
    override val end: Int,
    val expression: ExpressionNode,
) : SvelteNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class Element(
    override val type: String = "Element",
    override val start: Int,
    override val end: Int,
    val name: String,
    val attributes: List<Attribute>,
    @JsonProperty("nodes") val children: List<SvelteNode>,
) : SvelteNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class Attribute(
    override val type: String = "Attribute",
    override val start: Int,
    override val end: Int,
    val name: String,
    val value: List<SvelteNode>,
) : SvelteNode

// --- Expressions ---

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
    visible = true,
)
@JsonSubTypes(
    JsonSubTypes.Type(value = Identifier::class, name = "Identifier"),
    JsonSubTypes.Type(value = Literal::class, name = "Literal"),
    // Add other expression types
)
interface ExpressionNode : SvelteNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class Identifier(
    override val type: String = "Identifier",
    override val start: Int,
    override val end: Int,
    val name: String,
) : ExpressionNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class Literal(
    override val type: String = "Literal",
    override val start: Int,
    override val end: Int,
    val value: Any?,
    val raw: String,
) : ExpressionNode

// --- Blocks (Placeholders) ---
@JsonIgnoreProperties(ignoreUnknown = true) interface Block : SvelteNode

// --- Polymorphism Mixin ---

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
    visible = true,
)
@JsonSubTypes(
    // List ALL concrete SvelteNode types that can appear in lists like Fragment.children
    JsonSubTypes.Type(value = Text::class, name = "Text"),
    JsonSubTypes.Type(value = ExpressionTag::class, name = "ExpressionTag"),
    JsonSubTypes.Type(value = Comment::class, name = "Comment"),
    JsonSubTypes.Type(
        value = Element::class,
        name = "Element",
    ), // Includes Component, SvelteElement if type name varies?
    JsonSubTypes.Type(value = Fragment::class, name = "Fragment"),
    // Attribute usually won't be a direct child, but maybe possible?
    JsonSubTypes.Type(value = Attribute::class, name = "Attribute"),
    // Expressions generally won't be direct children, they are inside ExpressionTag
    // JsonSubTypes.Type(value = Identifier::class, name = "Identifier"),
    // JsonSubTypes.Type(value = Literal::class, name = "Literal"),
    // Add Block subtypes here (e.g., IfBlock, EachBlock)
    // JsonSubTypes.Type(value = IfBlock::class, name = "IfBlock"),
    // JsonSubTypes.Type(value = EachBlock::class, name = "EachBlock")
)
interface SvelteNodeMixin
