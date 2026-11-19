#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# SoundSync — Turnkey Debug APK Build & Device Deployment Workflow
# =============================================================================

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

echo "=== [1/4] SoundSync Debug APK Build Workflow ==="
echo "Working directory: $PROJECT_DIR"
echo "Timestamp: $(date -u +"%Y-%m-%d %H:%M:%SZ")"

# Ensure gradlew has execute permission
chmod +x ./gradlew

# Prepare .env if missing
if [ ! -f ".env" ]; then
    if [ -f ".env.example" ]; then
        cp .env.example .env
    else
        touch .env
    fi
fi

# Prepare debug keystore if base64 encoded
if [ -f "debug.keystore.base64" ] && [ ! -f "debug.keystore" ]; then
    base64 -d debug.keystore.base64 > debug.keystore
fi

echo "=== [2/4] Assembling Debug APK via Gradle ==="
./gradlew assembleDebug --no-daemon

APK_SOURCE="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_SOURCE" ]; then
    echo "ERROR: Built APK not found at $APK_SOURCE" >&2
    exit 1
fi

APK_SIZE_BYTES=$(stat -c%s "$APK_SOURCE" 2>/dev/null || stat -f%z "$APK_SOURCE" 2>/dev/null || wc -c < "$APK_SOURCE")
APK_SIZE_MB=$(( APK_SIZE_BYTES / 1024 / 1024 ))
echo "Successfully built APK: $APK_SOURCE ($APK_SIZE_MB MB)"

echo "=== [3/4] Deploying APK to Device Storage ==="
DEST_DIR="/storage/emulated/0/Download"
DEST_FOLDER="$DEST_DIR/SoundSync-debug"

if [ -d "$DEST_DIR" ]; then
    mkdir -p "$DEST_FOLDER"
    
    # 1. Primary debug APK in Downloads root
    cp -f "$APK_SOURCE" "$DEST_DIR/SoundSync-debug.apk"
    
    # 2. Artifact folder matching GitHub Actions download convention
    cp -f "$APK_SOURCE" "$DEST_FOLDER/SoundSync-debug.apk"
    
    # 3. Versioned debug APK
    VERSION_TAG="v1.0.117"
    cp -f "$APK_SOURCE" "$DEST_DIR/SoundSync-${VERSION_TAG}-debug.apk"
    
    echo "Deployed to device storage:"
    echo "  - $DEST_DIR/SoundSync-debug.apk"
    echo "  - $DEST_FOLDER/SoundSync-debug.apk"
    echo "  - $DEST_DIR/SoundSync-${VERSION_TAG}-debug.apk"
else
    echo "Notice: $DEST_DIR not found in current environment (skipping copy to device storage)."
fi

echo "=== [4/4] Verification & Checksums ==="
sha256sum "$APK_SOURCE"
echo "=== Build Workflow Complete ==="
