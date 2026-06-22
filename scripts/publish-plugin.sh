#!/usr/bin/env bash
# Publish the com.tracewayapp.symbols Gradle plugin to Maven Central.
# Local entrypoint; .github/workflows/publish-plugin.yml is a thin wrapper around it.
#
#   scripts/publish-plugin.sh <version>                 # publish to ~/.m2 (local smoke)
#   scripts/publish-plugin.sh <version> --release       # publish + release to Central
#   scripts/publish-plugin.sh <version> -- --dry-run    # validate task graph only
#
# --release needs the same env the SDK publish uses:
#   ORG_GRADLE_PROJECT_mavenCentralUsername / ...Password
#   ORG_GRADLE_PROJECT_signingInMemoryKey / ...signingInMemoryKeyPassword
set -euo pipefail

cd "$(dirname "$0")/.."

RELEASE=0
VERSION=""
EXTRA_ARGS=()

usage() {
    echo "usage: scripts/publish-plugin.sh <version> [--release] [-- <extra gradle args>]" >&2
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --release) RELEASE=1; shift ;;
        --) shift; EXTRA_ARGS=("$@"); break ;;
        -h|--help) usage ;;
        -*) echo "unknown flag: $1" >&2; usage ;;
        *) if [[ -z "$VERSION" ]]; then VERSION="$1"; shift; else echo "unexpected arg: $1" >&2; usage; fi ;;
    esac
done

[[ -n "$VERSION" ]] || usage

if ! echo "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$'; then
    echo "error: invalid version '$VERSION' (expected semver like 0.0.2 or 1.0.0-rc1)" >&2
    exit 1
fi

sed -i.bak -E \
    's/(coordinates\("com\.tracewayapp", "traceway-symbols-plugin", ")[^"]*(".*)/\1'"$VERSION"'\2/' \
    gradle-plugin/build.gradle.kts
rm -f gradle-plugin/build.gradle.kts.bak
grep -q "\"$VERSION\"" gradle-plugin/build.gradle.kts \
    || { echo "error: version bump did not apply to gradle-plugin/build.gradle.kts" >&2; exit 1; }
echo "coordinates -> $(grep -n 'coordinates(' gradle-plugin/build.gradle.kts)"

if [[ "$RELEASE" -eq 1 ]]; then
    TASK="publishAndReleaseToMavenCentral"
    : "${ORG_GRADLE_PROJECT_mavenCentralUsername:?set ORG_GRADLE_PROJECT_mavenCentralUsername}"
    : "${ORG_GRADLE_PROJECT_mavenCentralPassword:?set ORG_GRADLE_PROJECT_mavenCentralPassword}"
    : "${ORG_GRADLE_PROJECT_signingInMemoryKey:?set ORG_GRADLE_PROJECT_signingInMemoryKey}"
    echo ">> Releasing traceway-symbols-plugin $VERSION to Maven Central"
else
    TASK="publishToMavenLocal"
    echo ">> Local publish of traceway-symbols-plugin $VERSION to ~/.m2 (use --release for Central)"
fi

./gradlew -p gradle-plugin "$TASK" --no-configuration-cache --stacktrace "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}"
