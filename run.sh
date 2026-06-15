#!/usr/bin/env bash
#
# Convenience launcher for the KASE FIX client.
# It builds the project (if needed) and then starts the interactive app.
#
# Usage:
#   ./run.sh            # build + run
#   ./run.sh --no-build # run the already-built jar without rebuilding
#
set -euo pipefail

# Always run from the folder this script lives in.
cd "$(dirname "$0")"

JAR="target/kase-fix-client.jar"

if [[ "${1:-}" != "--no-build" || ! -f "$JAR" ]]; then
  echo ">> Building with Maven..."
  mvn -q clean package
fi

echo ">> Launching KASE FIX client..."
java -jar "$JAR"
