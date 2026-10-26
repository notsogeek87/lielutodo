> **Obsolète** — ancien README du dépôt modèle `lieluinit`, conservé pour mémoire. Le chemin de package y est `com.lielu.app` ; il est `com.lielu.todo` dans ce dépôt. Voir le [README actuel](../../README.md).

# LieluTodo

Dépôt **modèle** pour toute nouvelle app Android native (Kotlin, Jetpack Compose) : à chaque création de
dépôt, on part d'ici pour obtenir tout de suite la même configuration. L'app livrée n'affiche qu'une page
**INIT** ; tout ce qui l'entoure est déjà en place.

## Ce que le modèle fournit

| Besoin | Mise en œuvre |
| --- | --- |
| **APK release automatique** | `.github/workflows/build.yml` : ktlint → tests unitaires → `assembleRelease` sur **toute** branche ; sur `main`/`staging`, l'APK signé est publié dans une **release GitHub** par build. |
| **Mise à jour automatique (git)** | Bibliothèque `lielugit-updater` : au lancement, l'app compare sa version à la dernière release GitHub **de son propre dépôt** et propose de l'installer. |
| **Sauvegarde automatique** | Une copie JSON datée par jour (WorkManager) dans un dossier au choix — Drive, Fichiers… — avec rotation des N plus récentes. |
| **Export / import manuel** | Paramètres → *Exporter…* / *Importer…* (sélecteur de fichiers Android). |
| **Restauration automatique** | Sauvegarde Android (Auto Backup) activée pour la base et le DataStore ; invitation « Restaurer un fichier » au premier lancement ; *Restaurer la dernière sauvegarde* en un geste. |
| **Garde-fous** | `scripts/verify.sh` + hook `pre-push` (ktlint, conflits, version catalog, Gradle), `scripts/ci-status.sh` pour attendre la CI. |

## Créer une nouvelle app depuis ce modèle

1. Créer le dépôt depuis ce modèle (sur GitHub : **Use this template**, ou copier les fichiers).
2. Dans le nouveau dépôt :
   ```
   scripts/new-app.sh "Mon App" com.exemple.monapp            # owner/repo déduit du remote origin
   scripts/new-app.sh "Mon App" com.exemple.monapp owner/repo # ou explicite
   ```
   Le script renomme le package Kotlin, l'`applicationId`, le nom affiché, le nom de projet Gradle et le dépôt
   cible des mises à jour.
3. `scripts/verify.sh`, committer, pousser sur une branche : la CI construit l'APK.
4. Fusionner dans `main` : la CI publie la release `v1.0.<n>` avec l'APK, que l'app installée détectera.
5. Remplacer l'icône (`app/src/main/res/drawable/ic_launcher_foreground.xml`) et le contenu de
   `ui/init/InitScreen.kt`.

## Où récupérer les APK

Onglet **Releases** du dépôt :
- `v<version>` (release) : APK de production, nommé `<NomApp>-<version>.apk` — celui que l'app installée va chercher.
- `staging-v<version>` (pre-release) : APK `staging`, installable à côté de la production.

`<version>` = `<appVersionBase>.<numéro de run CI>` (ex. `1.0.25`, base dans `gradle.properties`). Le numéro de
run est aussi le `versionCode`, donc strictement croissant.

**Première installation :** télécharger l'APK depuis la release, l'ouvrir, autoriser « Installer depuis cette
source » si Android le demande. **Ensuite :** rien à faire, l'app propose elle-même les mises à jour.

## Signature des APK

Par défaut, les APK release sont signés avec le keystore **versionné** `app/debug.keystore` (mots de passe
publics `android`) : zéro configuration, et la mise à jour par-dessus fonctionne puisque la signature est stable.
Pour une vraie clé de publication, ajouter les secrets du dépôt `KEYSTORE_BASE64` (le `.jks` encodé en base64),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` — ou, en local, un `keystore.properties` (`storeFile`,
`storePassword`, `keyAlias`, `keyPassword`). **Changer de clé impose de désinstaller l'app une fois.**

## Build

```
./gradlew ktlintCheck                  # lint Kotlin
./gradlew test                         # tests unitaires (staging + production)
./gradlew assembleDebug                # APK debug
./gradlew assembleRelease              # APK release (staging + production)
```

Sans Android Studio (conteneur, session web) : `scripts/setup-android-sdk.sh` installe le SDK nécessaire.

## Structure

- `app/src/main/java/com/lielu/app/backup` — format de sauvegarde, sections, export/import, sauvegarde auto.
- `app/src/main/java/com/lielu/app/data/settings` — réglages (DataStore).
- `app/src/main/java/com/lielu/app/work` — travail périodique WorkManager.
- `app/src/main/java/com/lielu/app/ui` — écrans Compose : `init/` (page INIT), `settings/`, `update/`, thème, navigation.
- `libs/lielugit-maven` — dépôt Maven local de `lielugit-updater` (aucun jeton nécessaire).

Compatibilité : `minSdk` 26 (Android 8.0), `targetSdk`/`compileSdk` 35.

## Permissions

| Permission | Usage |
| --- | --- |
| `INTERNET` | Interroger l'API publique des releases GitHub du dépôt et télécharger l'APK de mise à jour. Aucun autre appel réseau. |
| `REQUEST_INSTALL_PACKAGES` (déclarée par `lielugit-updater`) | Lancer l'installation de l'APK téléchargé. |

## Licence

GNU General Public License v3.0 (GPL-3.0) — voir [`LICENSE`](LICENSE).
