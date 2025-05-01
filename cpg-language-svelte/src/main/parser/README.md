# Svelte Parser Script

This directory contains the Node.js script used by the `SvelteLanguageFrontend` to parse `.svelte` files.

## Implementation

- The script (`src/parser.ts`) is written in TypeScript.
- It uses the official `svelte/compiler`'s `parse` function.
- It reads a file path from the command line arguments.
- It outputs the JSON representation of the Svelte AST to standard output.
- Errors during parsing are written as JSON to standard error.

## Build

- The script is compiled using `tsc` (TypeScript Compiler) via the `npm run build` command.
- The compilation is managed by the Gradle build process defined in `cpg-language-svelte/build.gradle.kts`.
- The compiled output (`parser.js`) is placed in the build directory and copied into the main resources for inclusion in the final CPG JAR. 