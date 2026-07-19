#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"

if [[ -z "$JAVA_HOME" || ! -x "$JAVA_HOME/bin/jlink" ]]; then
  echo "需要 Java 17 JDK（包含 jlink）" >&2
  exit 1
fi

rm -rf "$ROOT_DIR/runtime"
"$JAVA_HOME/bin/jlink" \
  --add-modules java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.ec,jdk.unsupported,jdk.zipfs \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress=2 \
  --output "$ROOT_DIR/runtime"

"$ROOT_DIR/runtime/bin/java" -version
