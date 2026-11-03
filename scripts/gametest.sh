#!/usr/bin/env bash
# Builds the mod and runs the GameTests, then prints only the summary and the slowest tests.
# The full Gradle/server log stays in build/gametest.log, the per-test report in build/gametest-report.xml.
#
# Usage: scripts/gametest.sh [-t <selector>] [extra gradle args]
#   -t <selector>  run only matching tests: namespaced ID with wildcards, e.g. 'civmod:delivery*'.
#                  Without -t every test runs (CI always runs every test).
# Exit code: Gradle's (non-zero on a compile error or a failed required test).
set -u
cd "$(dirname "$0")/.."

selector=
if [ "${1:-}" = "-t" ]; then
    selector=$2
    shift 2
fi

log=build/gametest.log
report=build/gametest-report.xml
mkdir -p build
rm -f "$report"
./gradlew build runGameTestServer --console=plain ${selector:+"-Pgametests=$selector"} "$@" > "$log" 2>&1
code=$?

# Compile errors (javac / Error Prone) and Gradle task failures.
grep -E "\.java:[0-9]+: error:|^\* What went wrong:|^> Task .* FAILED" -A2 "$log" | head -40
# GameTest result and failing tests.
grep -E "required tests? (passed|failed)|GAME TESTS COMPLETE|Server thread/ERROR\]" "$log" | sed 's/^.*\]: //' | head -40
# Benchmark smoke test.
grep -F "[benchmark]" "$log" | sed 's/^.*\]: //'
# Slowest tests (wall-clock seconds): a batch runs as long as its slowest test.
if [ -f "$report" ]; then
    echo "Slowest tests (s):"
    grep -oE '<testcase [^>]*>' "$report" \
        | sed -E 's/.* name="([^"]*)".* time="([^"]*)".*/\2 \1/' \
        | sort -rn | head -10 | sed 's/^/  /'
fi
grep -E "^BUILD (SUCCESSFUL|FAILED)" "$log"
echo "EXIT=$code (full log: $log${selector:+, selector: $selector})"
exit $code
