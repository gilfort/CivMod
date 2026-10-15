#!/usr/bin/env bash
# Compares the GameTest benchmark of a base ref (default: main) with the current checkout.
# Runs the GameTest server RUNS times on each side and prints the median of the
# "CivMod code (total)" and "Server tick" averages, so single noisy runs do not mislead.
#
# Usage: scripts/bench-compare.sh [base-ref=main] [runs=3]
# The base ref is checked out in a temporary sibling worktree that is removed afterwards.
set -u
cd "$(dirname "$0")/.."
# Git Bash on Windows: native git needs C:/... paths, not /c/...
root=$(pwd -W 2>/dev/null || pwd)
base=${1:-main}
runs=${2:-3}
tree="$root/../CivMod-bench-$(echo "$base" | tr '/' '-')"

git worktree add -q --detach "$tree" "$base" || exit 1
trap 'git -C "$root" worktree remove --force "$tree"' EXIT

# Prints "<civmod avg> <server tick avg>" in ms for one run in directory $1.
run_once() {
    local log
    log=$(cd "$1" && ./gradlew runGameTestServer --console=plain 2>&1)
    if ! grep -qE "All [0-9]+ required tests passed" <<<"$log"; then
        echo "GameTests failed in $1, see ./gradlew runGameTestServer there" >&2
        return 1
    fi
    local civ tick
    civ=$(grep -F "[benchmark] CivMod code (total)" <<<"$log" | tail -1 | sed -E 's/.*avg ([0-9.]+) ms.*/\1/')
    tick=$(grep -F "[benchmark] Server tick" <<<"$log" | tail -1 | sed -E 's/.*avg ([0-9.]+) ms.*/\1/')
    echo "$civ $tick"
}

median() { sort -g | awk '{a[NR]=$1} END {print (NR%2 ? a[(NR+1)/2] : (a[NR/2]+a[NR/2+1])/2)}'; }

measure() {
    local dir=$1 label=$2 out=() i r
    for ((i = 1; i <= runs; i++)); do
        r=$(run_once "$dir") || exit 1
        echo "  $label run $i: civmod ${r% *} ms, server tick ${r#* } ms" >&2
        out+=("$r")
    done
    local civ tick
    civ=$(printf '%s\n' "${out[@]}" | cut -d' ' -f1 | median)
    tick=$(printf '%s\n' "${out[@]}" | cut -d' ' -f2 | median)
    printf '%-8s civmod avg %s ms | server tick avg %s ms (median of %d)\n' "$label" "$civ" "$tick" "$runs"
}

echo "Base: $base, runs per side: $runs" >&2
base_line=$(measure "$tree" base) || exit 1
head_line=$(measure "$root" HEAD) || exit 1
echo "$base_line"
echo "$head_line"
