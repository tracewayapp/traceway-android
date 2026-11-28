#!/usr/bin/env bash
# Publish one Maven release and atomically push its version commit (if needed) and tag.
set -euo pipefail
[[ ${GITHUB_REF_TYPE:-} == branch && ${VERSION:-} =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$ ]] || {
    echo "Dispatch from a branch with a valid version." >&2
    exit 1
}
git check-ref-format "refs/heads/$GITHUB_REF_NAME"
case "$1" in
    sdk)
        file=traceway/build.gradle.kts
        tag="v$VERSION"
        artifact=traceway
        extension=aar
        task=(:traceway:publishAndReleaseToMavenCentral)
        ;;
    plugin)
        file=gradle-plugin/build.gradle.kts
        tag="plugin-v$VERSION"
        artifact=traceway-symbols-plugin
        extension=jar
        task=(-p gradle-plugin publishAndReleaseToMavenCentral)
        ;;
    *) exit 2 ;;
esac

# Central may have accepted the release before a previous run could push Git.
base=https://repo.maven.apache.org/maven2
artifact_path="com/tracewayapp/$artifact/$VERSION/$artifact-$VERSION"
paths=("$artifact_path.pom" "$artifact_path.$extension")
if [[ $1 == plugin ]]; then
    marker=com.tracewayapp.symbols.gradle.plugin
    paths+=("com/tracewayapp/symbols/$marker/$VERSION/$marker-$VERSION.pom")
fi
present=0
for path in "${paths[@]}"; do
    status=$(curl --retry 3 --silent --show-error --output /dev/null --write-out '%{http_code}' "$base/$path")
    case "$status" in
        200) present=$((present + 1)) ;;
        404) ;;
        *) echo "Central returned $status for $path" >&2; exit 1 ;;
    esac
done
if (( present > 0 && present != ${#paths[@]} )); then
    echo "Only part of this publication is visible on Central. Retry after propagation." >&2
    exit 1
fi

source_sha=$(git rev-parse HEAD)
if git show-ref --verify --quiet "refs/tags/$tag"; then
    [[ $present -gt 0 && $(git rev-parse "refs/tags/$tag^{}") == "$source_sha" ]] || {
        echo "Existing tag $tag does not confirm this release at HEAD." >&2
        exit 1
    }
    exit 0
fi
if [[ $present -gt 0 || -n ${PUBLISHED_SHA:-} ]]; then
    [[ $present -gt 0 && ${PUBLISHED_SHA:-} == "$source_sha" ]] || {
        echo "To resume tagging, confirm Central has the entire release and provide its original source SHA." >&2
        exit 1
    }
fi

if [[ $1 == sdk ]]; then
    sed -i -E 's/^(val sdkVersion = ")[^"]+(".*)/\1'"$VERSION"'\2/' "$file"
    grep -Fxq "val sdkVersion = \"$VERSION\"" "$file"
else
    sed -i -E 's/(coordinates\("com\.tracewayapp", "traceway-symbols-plugin", ")[^"]+("\))/\1'"$VERSION"'\2/' "$file"
    grep -Fq "coordinates(\"com.tracewayapp\", \"traceway-symbols-plugin\", \"$VERSION\")" "$file"
fi
if (( present == 0 )); then
    ./gradlew "${task[@]}" --no-configuration-cache --stacktrace
fi
git add -- "$file"
if ! git diff --cached --quiet; then
    git commit -m "Release $tag"
fi
git tag -a "$tag" -m "Release $tag"
git push --atomic origin "HEAD:refs/heads/$GITHUB_REF_NAME" "refs/tags/$tag"
