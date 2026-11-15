#!/usr/bin/env bash
# Records the Flash Flood trailer: launches a scripted Fabric dev client and captures its
# window plus game audio (not the microphone) to trailer/out/flash_flood_trailer.mp4.
#
# First run: macOS will ask for Screen Recording permission for the app running this script
# (Terminal, iTerm, IntelliJ...). Grant it, restart that app if asked, and run again.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$ROOT/fabric/run-trailer"  # Loom runDir is relative to the fabric module
SIGNALS="$RUN_DIR/trailer-signals"
OUT="$ROOT/trailer/out/flash_flood_trailer.mp4"
RECORDER="$ROOT/trailer/build/recorder"

echo "==> Building recorder"
mkdir -p "$ROOT/trailer/build"
swiftc -O -swift-version 5 "$ROOT/trailer/recorder/main.swift" -o "$RECORDER" 2>/dev/null

echo "==> Preparing a fresh trailer world"
rm -rf "$RUN_DIR/saves/FlashFloodTrailer" "$SIGNALS"
mkdir -p "$RUN_DIR" "$SIGNALS"
# Skip first-launch screens and toasts, no music, don't pause when the window loses focus.
cat > "$RUN_DIR/options.txt" <<'EOF'
onboardAccessibility:false
tutorialStep:none
joinedFirstServer:true
skipMultiplayerWarning:true
pauseOnLostFocus:false
bobView:false
renderDistance:12
simulationDistance:10
soundCategory_music:0.0
narrator:0
EOF

echo "==> Setting up shaders (Sodium + Iris + Complementary Unbound)"
# Release jars go in the trailer run's mods/ folder; Fabric Loader remaps them for the dev
# environment at launch. They never touch the mod's own build or release jars.
fetch() { # url dest sha512
    if [ -f "$2" ] && [ "$(shasum -a 512 "$2" | cut -d' ' -f1)" = "$3" ]; then return; fi
    curl -fsSL -A "flash-flood-dev-build/1.0" -o "$2.part" "$1"
    if [ "$(shasum -a 512 "$2.part" | cut -d' ' -f1)" != "$3" ]; then
        rm -f "$2.part"; echo "Checksum mismatch for $(basename "$2")" >&2; exit 1
    fi
    mv "$2.part" "$2"
}
mkdir -p "$RUN_DIR/mods" "$RUN_DIR/shaderpacks" "$RUN_DIR/config"
fetch "https://cdn.modrinth.com/data/AANobbMI/versions/SMxNOGZ6/sodium-fabric-0.8.13%2Bmc1.21.1.jar" \
    "$RUN_DIR/mods/sodium-fabric-0.8.13+mc1.21.1.jar" \
    8614a374593698069a80ac74de8cd9c28f3928d16819a0e0d6320b538e823f7e54e8be06d5ea5a9c268beb0e02073209d1f831a02dede851588b972367680853
fetch "https://cdn.modrinth.com/data/YL57xq9U/versions/bAo1Qhte/iris-fabric-1.8.14-beta.1%2Bmc1.21.1.jar" \
    "$RUN_DIR/mods/iris-fabric-1.8.14-beta.1+mc1.21.1.jar" \
    a7fbb629793c52f0be8b049f787cb598879239b1ad8e1de62e103c8b9efff140e3232b93ef1f14e505d262897d8cf9505b1126396429ad4056bff969c8674e52
SHADER="ComplementaryUnbound_r5.9.3.zip"
fetch "https://cdn.modrinth.com/data/R6NEzAwj/versions/B1kyfoUZ/$SHADER" "$RUN_DIR/shaderpacks/$SHADER" \
    a085d1d8cda67cd17a2b414eeba48bbd809da069f269bab46246235ec8fe714a275013c70d0504527b0987d810deddddba4564332414ab3b3bd7435932ff808c
cat > "$RUN_DIR/config/iris.properties" <<EOF
enableShaders=true
shaderPack=$SHADER
EOF

echo "==> Starting recorder"
"$RECORDER" --signal-dir "$SIGNALS" --out "$OUT" &
REC_PID=$!
sleep 1
if ! kill -0 "$REC_PID" 2>/dev/null; then
    wait "$REC_PID" || true
    echo "Recorder failed to start (see message above)." >&2
    exit 1
fi

echo "==> Launching the game (it drives itself and quits when done)"
cd "$ROOT"
./gradlew :fabric:runTrailer --console=plain || true

# If the game exited early, tell the recorder to stop.
touch "$SIGNALS/stop"
wait "$REC_PID" || true

if [ -f "$OUT" ]; then
    echo "==> Normalizing audio"
    RAW="${OUT%.mp4}_raw.mp4"
    mv "$OUT" "$RAW"
    if "$RECORDER" --normalize "$RAW" "$OUT" && [ -f "$OUT" ]; then
        rm -f "$RAW"
    else
        echo "Audio normalization failed; keeping the raw recording." >&2
        mv "$RAW" "$OUT"
    fi
    echo "==> Done: $OUT"
else
    echo "No video was written." >&2
    exit 1
fi
