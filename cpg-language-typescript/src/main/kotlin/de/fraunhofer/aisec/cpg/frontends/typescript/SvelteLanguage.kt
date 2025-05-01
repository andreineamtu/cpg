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

import de.fraunhofer.aisec.cpg.frontends.Language
import de.fraunhofer.aisec.cpg.graph.types.* // Assuming standard types might be reused initially
import kotlin.reflect.KClass
import org.neo4j.ogm.annotation.Transient

/** The Svelte language. */
class SvelteLanguage : Language<SvelteLanguageFrontend>() {

    override val fileExtensions = listOf("svelte")
    override val namespaceDelimiter =
        "." // Default, might need adjustment based on Svelte's module system specifics

    @Transient
    override val frontend: KClass<out SvelteLanguageFrontend> = SvelteLanguageFrontend::class

    // Implement required abstract member
    @Transient override val builtInTypes: Map<String, Type> = mapOf()

    @Transient override val compoundAssignmentOperators = setOf<String>()

    // Initially, we can inherit or leave out operator/built-in type definitions.
    // These would need refinement based on how Svelte's <script> context behaves
    // compared to standard JavaScript. For now, let's keep it simple.

    // Example if inheriting JS operators:
    // override val conjunctiveOperators = listOf("&&", "&&=", "??", "??=")
    // override val disjunctiveOperators = listOf("||", "||=")
    // override val compoundAssignmentOperators = setOf(...)

    // Example for built-in types (might be same as JS initially):
    // @Transient
    // override val builtInTypes = mapOf(...)

    // TODO: Consider inheriting from JavaScriptLanguage if useful for built-ins/operators
}
