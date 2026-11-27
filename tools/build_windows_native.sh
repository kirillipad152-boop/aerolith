#!/bin/sh
# Builds aerolith_physics.dll for 64-bit Windows from Linux or macOS, so that one jar can carry both natives.
# Needs: cargo, `pip install ziglang`, and the Rust target (added here if rustup is present).
# Usage: build_windows_native.sh NATIVE_DIR TARGET_DIR   (the DLL ends up in TARGET_DIR/x86_64-pc-windows-gnu/release)
set -eu
native="$1"
target="$2"
here="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
python="${AEROLITH_ZIG_PYTHON:-python3}"
if ! "$python" -m ziglang version >/dev/null 2>&1; then
    echo "Building the Windows library needs Zig: run 'pip install ziglang' (and set AEROLITH_ZIG_PYTHON if it is not in python3)." >&2
    exit 1
fi
export AEROLITH_ZIG_PYTHON="$python"
if command -v rustup >/dev/null 2>&1; then rustup target add x86_64-pc-windows-gnu >/dev/null 2>&1 || true; fi
export CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER="$here/windows/zig-cc"
export CARGO_TARGET_X86_64_PC_WINDOWS_GNU_RUSTFLAGS="-C dlltool=$here/windows/zig-dlltool"
cd "$native"
exec cargo build --locked --release --lib --target x86_64-pc-windows-gnu --target-dir "$target"
