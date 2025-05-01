#!/bin/bash

# Simple script to run the SvelteLanguageFrontend tests.
# Assumes it is run from the root of the CPG project (e.g., code-base/cpg/).

echo "Running Svelte Language Frontend tests..."

# Get the directory where the script resides
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Go to the CPG root directory (one level up from the script directory)
cd "$SCRIPT_DIR/.."

# Execute the Gradle test command
./gradlew :cpg-language-svelte:test --tests de.fraunhofer.aisec.cpg.frontends.svelte.SvelteLanguageFrontendTest -PenableSvelteFrontend=true

# Capture the exit code
EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
  echo "Svelte tests finished successfully."
else
  echo "Svelte tests failed with exit code $EXIT_CODE."
fi

exit $EXIT_CODE 