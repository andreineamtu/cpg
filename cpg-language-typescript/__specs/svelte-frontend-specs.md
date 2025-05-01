# Specification: Svelte Language Frontend for CPG

Based on the approach for other frontends (like `cpg-language-typescript`) and CPG documentation ([Implementation Concepts](https://fraunhofer-aisec.github.io/cpg/CPG/impl/), [Language Frontends](https://fraunhofer-aisec.github.io/cpg/CPG/impl/language/)), adding support for Svelte involves:

1.  **Create a New Language Module (`cpg-language-svelte`):**
    *   A dedicated Gradle module to encapsulate Svelte-specific logic, build dependencies, and the parser script.
    *   Applies the `cpg.frontend-conventions` Gradle plugin for consistency.

2.  **Integrate Svelte Parser (`svelte/compiler`):
    *   Uses the official `svelte.parse` function.
    *   Implemented via a Node.js script (`src/main/parser/src/parser.ts`) written in TypeScript.
    *   This script takes a file path, parses the Svelte component, and outputs the AST as JSON to stdout.
    *   Errors are output as JSON to stderr.

3.  **Build Parser Script with Node.js/tsc:**
    *   The `cpg-language-svelte/build.gradle.kts` uses the `com.github.node-gradle.node` plugin.
    *   It manages Node.js/npm installation.
    *   It uses `package.json` (located in `src/main/parser/`) for dependencies (`svelte`, `typescript`, `@types/node`).
    *   Gradle tasks (`npmInstallDev`, `compileSvelteParser`) run `npm install` and `npm run build` (which executes `tsc -p src/main/parser/tsconfig.json`).
    *   The compiled `parser.js` is copied into the module's resources (`build/resources/main/svelte/`).

4.  **Implement `SvelteLanguage` and `SvelteLanguageFrontend`:**
    *   `SvelteLanguage.kt`: Defines the language metadata (name, extension `svelte`, associated frontend).
    *   `SvelteLanguageFrontend.kt`: Orchestrates the process:
        *   Extracts and executes the `parser.js` Node.js script using `ProcessBuilder`.
        *   Captures stdout (AST JSON) and stderr (errors).
        *   Deserializes the AST JSON into Kotlin data classes (`SvelteAST.kt`) representing the Svelte AST structure (`Root`, `Script`, `Fragment`, `Element`, `Attribute`, `Text`, etc.).
        *   Uses handler methods (`handleRoot`, `handleScript`, `handleFragment`, `handleElement`, `handleAttribute`, etc.) to traverse the Svelte AST.
        *   Maps Svelte AST nodes to CPG nodes (e.g., `RecordDeclaration` for component, `VariableDeclaration`/`FieldDeclaration` placeholders for elements/attributes, `Literal`/`Reference` for simple template expressions) using CPG builder functions (`newRecordDeclaration`, `newVariableDeclaration`, etc.).
        *   Establishes scopes using the `ScopeManager`.
        *   Implements `getCodeFromRawNode` and `getLocationFromRawNode` based on Svelte AST node properties.

5.  **Add Tests (`SvelteLanguageFrontendTest.kt`):**
    *   Uses the CPG testing framework (`analyze`).
    *   Requires sample `.svelte` files in test resources.
    *   Asserts the structure and properties of the generated CPG graph.
    *   Requires `-PenableSvelteFrontend=true` flag when running tests via Gradle.

**Key Challenges & TODOs:**

*   **Template Syntax Mapping:** Defining robust CPG representations for HTML elements, attributes, and especially Svelte directives (`on:`, `bind:`, `class:`, blocks like `{#if}`, `{#each}`, `{:await}`).
*   **Expression Parsing:** Fully parsing JavaScript/TypeScript expressions within template tags (`{...}`) and attribute values. Requires mapping various JS AST nodes (BinaryExpression, CallExpression, etc.) to CPG equivalents.
*   **Script Tag Parsing:** Integrating the actual JS/TS frontend (`TypeScriptLanguageFrontend`) to parse the content of `<script>` (instance) and `<script context="module">` tags within the correct scope and context of the Svelte component. This is complex due to shared scope and potential interactions between script and template.
*   **Reactivity (`$:`):** Representing Svelte's reactive declarations and assignments accurately, likely involving DFG analysis.
*   **Styling (`<style>`):** Parsing CSS is currently out of scope but could be added later, potentially creating comment nodes or basic structure.
*   **CFG/DFG:** Building accurate Control Flow and Data Flow Graphs, especially considering template logic and script/template interactions.


PR title and description
**feat: Add Svelte Language Frontend**

**Description:**

This PR introduces experimental support for analyzing Svelte (`.svelte`) files within the Cloud Property Graph (CPG). The goal is to enable security analysis and code understanding for projects built with the Svelte framework, similar to the existing support for other languages like TypeScript/JavaScript.

**Motivation:**

Svelte is a popular and growing component framework. Adding CPG support allows developers and security researchers to leverage CPG's capabilities for Svelte codebases.

**Implementation Approach:**

Inspired by the existing `cpg-language-typescript` frontend (#462), this implementation includes:

1.  **New Module:** A dedicated Gradle module `cpg-language-svelte` has been created to encapsulate the Svelte-specific logic.
2.  **Parser Integration:** It utilizes the official `svelte/compiler` (specifically `svelte.parse`) invoked via a Node.js script (`parser.js` bundled with Webpack) to generate an Abstract Syntax Tree (AST) from `.svelte` files. This script is called from the Kotlin frontend.

**Acknowledgements:**

*   Thanks to the contributors of the `cpg-language-typescript` frontend, which served as a valuable reference.