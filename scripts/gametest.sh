#!/usr/bin/env bash
# Builds the mod and runs every GameTest, then prints only the summary.
# The full Gradle/server log stays in build/gametest.log.
#
# Usage: scripts/gametest.sh [extra gradle args]
# Exit code: Gradle's (non-zero on a compile error or a failed required test).
set -u
cd "$(dirname "$0")/.."

log=build/gametest.log
mkdir -p build
./gradlew build runGameTestServer --console=plain "$@" > "$log" 2>&1
code=$?

# Compile errors (javac / Error Prone) and Gradle task failures.
grep -E "\.java:[0-9]+: error:|^\* What went wrong:|^> Task .* FAILED" -A2 "$log" | head -40
# GameTest result and failing tests.
grep -E "required tests? (passed|failed)|GAME TESTS COMPLETE|Server thread/ERROR\]" "$log" | sed 's/^.*\]: //' | head -40
# Benchmark smoke test.
grep -F "[benchmark]" "$log" | sed 's/^.*\]: //'
grep -E "^BUILD (SUCCESSFUL|FAILED)" "$log"
echo "EXIT=$code (full log: $log)"
exit $code
