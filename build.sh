#!/usr/bin/env bash
set -e

PROPS="gradle.properties"
STAMP=".last_build_tree"

# Read current version
current=$(grep '^mod_version=' "$PROPS" | cut -d= -f2)

# Get the current source tree hash (tracked files only, excluding gradle.properties itself)
tree_hash=$(git ls-files | grep -v '^gradle\.properties$' | git hash-object --stdin-paths | sha256sum | cut -c1-16)

last_hash=""
[ -f "$STAMP" ] && last_hash=$(cat "$STAMP")

if [ "$tree_hash" != "$last_hash" ]; then
    # Bump patch version: split on '.', increment last segment
    IFS='.' read -ra parts <<< "$current"
    parts[-1]=$(( ${parts[-1]} + 1 ))
    new_version=$(IFS='.'; echo "${parts[*]}")

    sed -i "s/^mod_version=.*/mod_version=$new_version/" "$PROPS"
    echo "$tree_hash" > "$STAMP"
    echo "Version bumped: $current → $new_version"
else
    echo "No source changes, keeping version $current"
fi

./gradlew build "$@"
