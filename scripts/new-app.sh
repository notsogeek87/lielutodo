#!/usr/bin/env bash
# Transforme une copie de ce modèle en l'app d'un nouveau dépôt : nom, applicationId, package Kotlin,
# dépôt GitHub des mises à jour. À lancer une fois, juste après avoir créé le dépôt depuis le modèle.
#
# Usage :
#   scripts/new-app.sh "Mon App" com.exemple.monapp [owner/repo]
#
# `owner/repo` est déduit du remote `origin` s'il est omis. Le script peut être relancé : il repart des
# valeurs actuelles du projet (applicationId courant lu dans app/build.gradle.kts).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [ $# -lt 2 ] || [ $# -gt 3 ]; then
    sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
fi

APP_NAME="$1"
NEW_PKG="$2"
REPO="${3:-}"

if [ -z "$REPO" ]; then
    REMOTE="$(git remote get-url origin 2>/dev/null || true)"
    REPO="$(printf '%s' "$REMOTE" | sed -E 's#^(git@github\.com:|https?://([^@/]*@)?[^/]*github\.com/|.*/git/)##; s#\.git$##')"
fi

fail() { echo "new-app.sh : $1" >&2; exit 2; }

[[ "$APP_NAME" =~ ^[^\"\\\$\`]+$ ]] || fail "nom invalide (pas de guillemet, antislash, \$ ni backtick) : $APP_NAME"
[[ "$NEW_PKG" =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]] || fail "applicationId invalide : $NEW_PKG (ex. com.exemple.monapp)"
[[ "$REPO" =~ ^[^/[:space:]]+/[^/[:space:]]+$ ]] || fail "dépôt GitHub introuvable : passez owner/repo en 3e argument"

OLD_PKG="$(sed -n 's/^ *applicationId = "\(.*\)"/\1/p' app/build.gradle.kts | head -1)"
[ -n "$OLD_PKG" ] || fail "applicationId actuel introuvable dans app/build.gradle.kts"

# Nom de projet Gradle sans espace ni caractère spécial.
ROOT_NAME="$(printf '%s' "$APP_NAME" | tr -cd 'A-Za-z0-9_-')"
[ -n "$ROOT_NAME" ] || ROOT_NAME="App"

OLD_PATH="${OLD_PKG//./\/}"
NEW_PATH="${NEW_PKG//./\/}"

echo "→ $OLD_PKG  ⇒  $NEW_PKG   (app « $APP_NAME », dépôt $REPO)"

if [ "$OLD_PKG" != "$NEW_PKG" ]; then
    for set in main test; do
        src="app/src/$set/java/$OLD_PATH"
        dst="app/src/$set/java/$NEW_PATH"
        [ -d "$src" ] || continue
        mkdir -p "$dst"
        # `cp -a` puis suppression : fonctionne même si l'ancien et le nouveau chemin partagent un préfixe.
        cp -a "$src/." "$dst/"
        rm -rf "$src"
        # Retire les dossiers parents devenus vides (com/lielu/app → com/lielu → com).
        dir="$(dirname "$src")"
        while [ "$dir" != "app/src/$set/java" ] && rmdir "$dir" 2>/dev/null; do dir="$(dirname "$dir")"; done
    done
    grep -rlF "$OLD_PKG" app/src app/build.gradle.kts | while read -r f; do
        sed -i "s/$(printf '%s' "$OLD_PKG" | sed 's/\./\\./g')/$NEW_PKG/g" "$f"
    done
    # namespace == applicationId dans ce modèle ; le sed ci-dessus a déjà couvert les deux.
fi

sed -i "s/^appName=.*/appName=$APP_NAME/" gradle.properties
sed -i "s#^appGithubRepository=.*#appGithubRepository=$REPO#" gradle.properties
sed -i "s/^rootProject.name = .*/rootProject.name = \"$ROOT_NAME\"/" settings.gradle.kts
sed -i "s#<string name=\"app_name\" translatable=\"false\">.*</string>#<string name=\"app_name\" translatable=\"false\">$APP_NAME</string>#" \
    app/src/main/res/values/strings.xml

# Le README décrit le modèle : on garde un titre propre à l'app et on signale le reste à reprendre.
if [ -f README.md ] && grep -q '^# LieluInit' README.md; then
    sed -i "1s/.*/# $APP_NAME/" README.md
fi

echo "✓ Terminé. Étapes suivantes :"
echo "  1. scripts/verify.sh          (formatage + vérifications statiques)"
echo "  2. git add -A && git commit    puis pousser : la CI construit l'APK release"
echo "  3. Remplacer l'icône (app/src/main/res/drawable/ic_launcher_foreground.xml) et la page INIT"
