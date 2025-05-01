import * as svelte from 'svelte/compiler';
import * as fs from 'node:fs';
import * as path from 'node:path';

// Get the file path from command-line arguments
const filePath = process.argv[2];
if (!filePath) {
    console.error(JSON.stringify({ error: 'No file path provided.' }));
    process.exit(1);
}

const absolutePath = path.resolve(filePath);

try {
    // Read the Svelte file content
    const source = fs.readFileSync(absolutePath, 'utf-8');

    // Parse the Svelte file
    const ast = svelte.parse(source, { filename: absolutePath /* Add other options if needed */ });

    // Output the AST as JSON to stdout
    // Use a replacer to handle potential circular references or complex objects if necessary,
    // although svelte.parse AST is typically clean JSON.
    console.log(JSON.stringify(ast, null, 2)); // Pretty print for readability during debug

} catch (error: any) {
    // Output error details as JSON to stderr
    const errorOutput = {
        error: 'Svelte parsing failed',
        message: error.message || 'Unknown error',
        filename: absolutePath,
        position: error.position ? { line: error.start?.line, column: error.start?.column } : undefined,
        code: error.code || undefined, // Include error code if available
        rawError: error.toString() // Include raw error string for debugging
    };
    console.error(JSON.stringify(errorOutput));
    process.exit(1);
} 