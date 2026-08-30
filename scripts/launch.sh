#!/usr/bin/env bash
# Fetches the latest MicroBot client, builds this repo's plugins against it,
# side-loads them, and launches the client. Every step re-downloads/rebuilds
# fresh — nothing here is a one-time manual step.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLIENT_DIR="${MICROBOT_CLIENT_DIR:-$HOME/microbot-client}"
RUNELITE_PLUGINS_DIR="$HOME/.runelite/plugins"
VERSION_ENDPOINT="https://microbot.cloud/api/version/client"

mkdir -p "$CLIENT_DIR" "$RUNELITE_PLUGINS_DIR"

echo "==> Resolving latest MicroBot client version"
VERSION="$(curl -fsSL "$VERSION_ENDPOINT" || true)"
if [ -z "$VERSION" ]; then
    echo "==> Could not reach $VERSION_ENDPOINT; checking for a cached client jar"
    VERSION="$(ls "$CLIENT_DIR"/microbot-*.jar 2>/dev/null | sed -E 's/.*microbot-(.*)\.jar/\1/' | sort -V | tail -1 || true)"
    if [ -z "$VERSION" ]; then
        echo "No network and no cached client jar found. Aborting." >&2
        exit 1
    fi
    echo "==> Using cached version $VERSION"
fi

CLIENT_JAR="$CLIENT_DIR/microbot-$VERSION.jar"
if [ ! -f "$CLIENT_JAR" ]; then
    echo "==> Downloading microbot-$VERSION.jar"
    curl -fsSL -o "$CLIENT_JAR.tmp" \
        "https://github.com/chsami/Microbot/releases/download/$VERSION/microbot-$VERSION.jar"
    mv "$CLIENT_JAR.tmp" "$CLIENT_JAR"
else
    echo "==> microbot-$VERSION.jar already cached, skipping download"
fi

echo "==> Building plugins against microbot $VERSION"
cd "$REPO_DIR"
./gradlew build -PmicrobotClientVersion="$VERSION" --console=plain

echo "==> Side-loading built plugin jars into $RUNELITE_PLUGINS_DIR"
find "$REPO_DIR/plugins" -path "*/build/libs/*.jar" ! -name "*-sources.jar" -print0 \
    | while IFS= read -r -d '' jar; do
        base="$(basename "$jar")"
        # Skip the duplicate <subproject-name>.jar Gradle also emits alongside
        # the shadowJar-named archive, to avoid loading the plugin twice.
        if [[ "$base" == *"Plugin.jar" ]]; then
            cp "$jar" "$RUNELITE_PLUGINS_DIR/$base"
            echo "    -> $base"
        fi
    done

echo "==> Launching MicroBot client $VERSION"
JAVA_BIN="$(command -v java || true)"
if [ -z "$JAVA_BIN" ] || ! "$JAVA_BIN" -version 2>&1 | grep -q '"11'; then
    TOOLCHAIN_JAVA="$(find "$HOME/.gradle/jdks" -type f -name java -path "*jdk-11*/bin/java" 2>/dev/null | head -1)"
    if [ -n "$TOOLCHAIN_JAVA" ]; then
        JAVA_BIN="$TOOLCHAIN_JAVA"
    fi
fi
if [ -z "$JAVA_BIN" ]; then
    echo "No JDK 11 found on PATH or in ~/.gradle/jdks. Run './gradlew build' once first to let Gradle provision one." >&2
    exit 1
fi

echo "    using java: $JAVA_BIN"
exec "$JAVA_BIN" -ea -Xmx2g -jar "$CLIENT_JAR"
