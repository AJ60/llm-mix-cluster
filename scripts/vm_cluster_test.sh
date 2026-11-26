#!/usr/bin/env bash
# ==============================================================================
# scripts/vm_cluster_test.sh
# Host-Machine Multi-VM Test Harness for Silica Cluster.
# Runs the isolated Linux-namespace multi-VM integration suite against a master.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${SILICA_MASTER_URL:-${2:-http://127.0.0.1:3000}}"
MODE="${1:-auto}"

case "$MODE" in
  auto|namespace)
    python3 "$SCRIPT_DIR/verify_cluster_environments.py" --base-url "$BASE_URL"
    ;;
  *)
    echo "Usage: $0 [auto|namespace] [master-url]" >&2
    exit 1
    ;;
esac
