#!/usr/bin/env bash
# Prepares a release: sets versionName (and bumps versionCode) in app/build.gradle.kts,
# commits, and tags v<version>. Pushing the tag starts .github/workflows/release.yml,
# which builds, signs and publishes the APK.
#
#   tools/release.sh 0.2.0          prepare locally, then review and push yourself
#   tools/release.sh 0.2.0 --push   prepare and push main and the tag
#
# A version with a suffix (0.2.0-beta1) is published as a pre-release.
set -euo pipefail
cd "$(dirname "$0")/.."

version=${1:?usage: tools/release.sh <version> [--push]}
push=${2:-}
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "not a version: $version" >&2; exit 2; }
gradle=app/build.gradle.kts

[ "$(git branch --show-current)" = main ] || { echo "release from main" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "commit or stash your changes first" >&2; exit 1; }
git fetch -q origin main && [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] \
    || { echo "main isn't the same as origin/main; pull or push first" >&2; exit 1; }
! git rev-parse -q --verify "refs/tags/v$version" >/dev/null || { echo "v$version already exists" >&2; exit 1; }

current=$(sed -nE 's/^ *versionName = "([^"]+)".*/\1/p' $gradle)
if [ "$current" != "$version" ]; then
    code=$(sed -nE 's/^ *versionCode = ([0-9]+).*/\1/p' $gradle)
    sed -i -E "s/^( *versionCode = )[0-9]+/\1$((code + 1))/; s/^( *versionName = )\"[^\"]+\"/\1\"$version\"/" $gradle
    git commit -q -m "Release $version" -- $gradle
    echo "versionName $current -> $version, versionCode $code -> $((code + 1))"
fi
git tag -a "v$version" -m "Wiggins $version"
echo "tagged v$version at $(git rev-parse --short HEAD)"

if [ "$push" = --push ]; then
    git push -q origin main "v$version"
    echo "pushed; the release workflow is building it"
else
    echo "to publish: git push origin main v$version"
fi
