#!/usr/bin/env bash
# Runs sonar-scanner in Docker against a target project directory.
#
# Usage:
#   ./bin/run-analysis.sh /path/to/project
#   ./bin/run-analysis.sh          # defaults to current directory
#
# Reads host_url, token, and scanner_args from config.toml.
# Project key defaults to the target directory's basename; override via SONAR_PROJECT_KEY env var.
# Override token via env: SONAR_TOKEN=xxx ./bin/run-analysis.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIG="$SCRIPT_DIR/config.toml"
IMAGE="sonarsource/sonar-scanner-cli"

# ---------------------------------------------------------------------------
# Read config.toml
# ---------------------------------------------------------------------------
read_toml() {
    python3 - "$CONFIG" "$1" <<'EOF'
import sys
try:
    import tomllib
except ModuleNotFoundError:
    import tomli as tomllib
with open(sys.argv[1], "rb") as f:
    c = tomllib.load(f)
keys = sys.argv[2].split(".")
val = c
for k in keys:
    val = val[k]
if isinstance(val, list):
    print("\n".join(val))
else:
    print(val)
EOF
}

HOST_URL="$(read_toml sonar.host_url)"
TOKEN="${SONAR_TOKEN:-$(read_toml sonar.token)}"

# Read extra scanner_args into an array (one per line from read_toml)
EXTRA_ARGS=()
while IFS= read -r line; do
    EXTRA_ARGS+=("$line")
done < <(read_toml sonar.scanner_args 2>/dev/null || true)

# ---------------------------------------------------------------------------
# Validate
# ---------------------------------------------------------------------------
if [[ -z "$TOKEN" ]]; then
    echo "ERROR: No token configured."
    echo "  Run ./bin/setup-sonar.sh, then set token in config.toml, or:"
    echo "  SONAR_TOKEN=<token> ./bin/run-analysis.sh"
    exit 1
fi

# ---------------------------------------------------------------------------
# Translate host URL for Docker networking
#   localhost → host.docker.internal  (so the container can reach the host)
# ---------------------------------------------------------------------------
DOCKER_HOST_URL="${HOST_URL/localhost/host.docker.internal}"
DOCKER_HOST_URL="${DOCKER_HOST_URL/127.0.0.1/host.docker.internal}"

# ---------------------------------------------------------------------------
# Determine list of target directories
# ---------------------------------------------------------------------------
if [[ $# -gt 0 ]]; then
    TARGETS=("$@")
else
    TARGETS=(
        "$ROOT_DIR/codesearch-java"
        "$ROOT_DIR/codesearch-python"
    )
fi

# ---------------------------------------------------------------------------
# Run analysis for each target
# ---------------------------------------------------------------------------
run_analysis() {
    local target_dir
    target_dir="$(cd "$1" && pwd)"
    local project_key="${SONAR_PROJECT_KEY:-$(basename "$target_dir")}"

    local docker_args=(
        run --rm
        -e "SONAR_HOST_URL=$DOCKER_HOST_URL"
        -e "SONAR_TOKEN=$TOKEN"
        -v "$target_dir:/usr/src"
        "$IMAGE"
        -Dsonar.projectKey="$project_key"
    )

    for arg in "${EXTRA_ARGS[@]}"; do
        [[ -z "$arg" ]] && continue
        [[ "$arg" == *sonar.host.url* ]] && continue
        [[ "$arg" == *sonar.token* ]]    && continue
        docker_args+=("$arg")
    done

    echo "=== sonar-scanner (Docker) ==="
    echo "Project : $project_key"
    echo "Source  : $target_dir"
    echo "Server  : $HOST_URL  (→ $DOCKER_HOST_URL inside container)"
    echo ""

    docker "${docker_args[@]}"

    echo ""
    echo "Analysis complete. View results at $HOST_URL/dashboard?id=$project_key"
    echo ""
}

for target in "${TARGETS[@]}"; do
    if [[ ! -d "$target" ]]; then
        echo "ERROR: Directory not found: $target"
        exit 1
    fi
    run_analysis "$target"
done
