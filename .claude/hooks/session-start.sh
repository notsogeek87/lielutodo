#!/usr/bin/env bash
# Prépare une session Claude Code : garde-fou `pre-push` installé, lint et Gradle exécutables localement.
#
# Dans une session sur le web, rien n'est préinstallé : ni le SDK Android (donc pas de Gradle), ni ktlint.
# On les installe ici pour que le premier retour sur le code vienne de la machine, pas de la CI.
set -euo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

# Garde-fou avant push (ktlint + vérifications statiques + contrôle de CI verte pour staging/main).
# `core.hooksPath` est une config locale au clone : elle doit être (re)posée dans chaque session.
git -C "$PROJECT_DIR" config core.hooksPath .githooks 2>/dev/null || true

# Sur une machine de dev normale, `./gradlew` marche déjà : rien d'autre à préparer.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
fi

# ktlint (Maven Central) : reproduit `./gradlew ktlintCheck` sans Gradle.
JAR="$("$PROJECT_DIR/scripts/ktlint.sh" --install-only)"
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
    echo "export KTLINT_JAR=\"$JAR\"" >> "$CLAUDE_ENV_FILE"
fi
echo "ktlint prêt : $JAR"

# SDK Android (dl.google.com) : nécessaire à Gradle (compilation, tests, APK). Si la politique réseau
# bloque ce domaine, on n'insiste pas : seule la CI pourra alors valider la compilation (voir CLAUDE.md).
if curl -s -o /dev/null --max-time 10 "https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml"; then
    if SDK="$("$PROJECT_DIR/scripts/setup-android-sdk.sh")"; then
        if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
            echo "export ANDROID_HOME=\"$SDK\"" >> "$CLAUDE_ENV_FILE"
        fi
        echo "SDK Android prêt : $SDK — ./gradlew est utilisable"
    else
        echo "SDK Android : installation échouée, Gradle indisponible (la CI validera la compilation)"
    fi
else
    echo "dl.google.com injoignable : Gradle indisponible dans cette session (la CI validera la compilation)"
fi

echo "Avant tout push : scripts/verify.sh (le hook pre-push le lance automatiquement)"
echo "État de la CI d'une branche : scripts/ci-status.sh [--wait] [branche]"
