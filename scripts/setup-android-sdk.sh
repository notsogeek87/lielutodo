#!/usr/bin/env bash
# Installe un SDK Android minimal (plateforme 35 + build-tools) pour pouvoir lancer Gradle hors Android Studio :
# session Claude Code sur le web, runner vierge, conteneur. Idempotent : ne retélécharge rien si déjà là.
#
# Usage :
#   scripts/setup-android-sdk.sh            # installe dans ~/android-sdk (ou $ANDROID_SDK_DIR)
#   export ANDROID_HOME="$(scripts/setup-android-sdk.sh)"
#
# Écrit le chemin du SDK sur la DERNIÈRE ligne de stdout (le reste va sur stderr) et crée local.properties
# (ignoré par git) pour que Gradle le trouve sans variable d'environnement.
# Nécessite dl.google.com : bloqué par certaines politiques réseau (voir CLAUDE.md).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

SDK="${ANDROID_SDK_DIR:-${ANDROID_HOME:-$HOME/android-sdk}}"
# Versions alignées sur app/build.gradle.kts (compileSdk) et sur AGP 8.7 (build-tools par défaut).
PLATFORM="platforms;android-35"
BUILD_TOOLS="build-tools;35.0.0"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

log() { echo "setup-android-sdk : $*" >&2; }

if [ ! -d "$SDK/platforms/android-35" ] || [ ! -d "$SDK/build-tools/35.0.0" ]; then
    if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
        log "téléchargement des outils en ligne de commande Android…"
        mkdir -p "$SDK/cmdline-tools"
        tmp="$(mktemp -d)"
        trap 'rm -rf "$tmp"' EXIT
        curl -sSL --fail --max-time 600 -o "$tmp/tools.zip" "$CMDLINE_TOOLS_URL"
        unzip -q "$tmp/tools.zip" -d "$tmp"
        rm -rf "$SDK/cmdline-tools/latest"
        mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    fi
    log "installation de $PLATFORM et $BUILD_TOOLS…"
    # `yes` ferme son tube à la fin de sdkmanager : SIGPIPE attendu, pas une erreur.
    { yes || true; } | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "$PLATFORM" "$BUILD_TOOLS" "platform-tools" >&2
fi

printf 'sdk.dir=%s\n' "$SDK" > local.properties
echo "$SDK"
