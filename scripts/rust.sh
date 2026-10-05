#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v cargo >/dev/null; then
 export CARGO_HOME=/workspace/tools/cargo
 export RUSTUP_HOME=/workspace/tools/rustup
 export PATH="$CARGO_HOME/bin:$PATH"
fi
exec cargo "$@" --manifest-path c2pa-worker/Cargo.toml
