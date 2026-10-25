# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Rôle de ce dépôt : le modèle de toute nouvelle app

`lieluinit` est le **dépôt standard** des apps Android de l'utilisateur. Chaque nouvelle app commence par une
copie de ce dépôt, afin d'avoir d'emblée : la même configuration Gradle/CI, la création automatique d'un APK
**release** publié en release GitHub, la mise à jour automatique depuis git, la sauvegarde automatique,
l'export/import manuel, la restauration. L'app n'affiche qu'une page **INIT** (`ui/init/InitScreen.kt`).

**Quand l'utilisateur crée un nouveau dépôt d'app :** y copier ce modèle, puis lancer
`scripts/new-app.sh "<Nom>" <applicationId> [owner/repo]` (renomme package, applicationId, nom, dépôt des mises à
jour), vérifier avec `scripts/verify.sh`, pousser. Ne pas réécrire la config à la main : toute amélioration de la
config commune se fait ICI d'abord, puis se propage aux apps.

Sources d'inspiration : le dépôt `rangele` (app « Yakwa », inventaire de placard) — mêmes conventions de CI,
de scripts, de mise à jour et de sauvegarde, généralisées ici.

## Commandes

```
./gradlew ktlintCheck               # lint Kotlin (ktlint 1.3.1, échoue le build si violations)
./gradlew ktlintFormat              # auto-fix
./gradlew test                      # tests unitaires (staging + production)
./gradlew testStagingDebugUnitTest --tests "com.lielu.app.backup.BackupManagerTest"   # un seul test
./gradlew assembleDebug             # APK debug (staging + production)
./gradlew assembleRelease           # APK release signés (staging + production)
```

APK produit : `app/build/outputs/apk/<staging|production>/release/app-<flavor>-release.apk`.

### Gradle en session Claude Code sur le web

Le hook `SessionStart` (`.claude/hooks/session-start.sh`) installe ktlint **et**, si `dl.google.com` est
accessible, le SDK Android (`scripts/setup-android-sdk.sh`, dans `~/android-sdk`) : `./gradlew` y est alors
utilisable (compilation, tests, `assembleRelease` vérifiés — c'est ainsi que ce modèle a été validé). Si la
politique réseau bloque `dl.google.com`, seuls `scripts/ktlint.sh` (Maven Central) et `scripts/verify.sh --fast`
marchent : annoncer alors que compilation et tests ne sont validés que par la CI, ne jamais présenter un
changement non compilé comme vérifié. Ne pas ajouter `ktlint_code_style` au `.editorconfig`.

## Avant de pousser

```
scripts/verify.sh            # ktlint, marqueurs de conflit, migrations Room (si présentes), version catalog, Gradle
scripts/verify.sh --fast     # idem sans Gradle
scripts/ci-status.sh --wait <branche>   # attend et affiche le verdict de la CI pour ce commit
```

Le hook `.githooks/pre-push` (activé par le hook `SessionStart` ; sur une machine de dev :
`git config core.hooksPath .githooks`) lance `verify.sh` à chaque push et refuse un push vers `staging`/`main`
tant que le commit n'a pas de run CI vert (`SKIP_VERIFY=1`, `SKIP_CI_GUARD=1` en dernier recours).
Ordre : `verify.sh` → pousser la branche de travail → `ci-status.sh --wait` → seulement ensuite merger dans
`staging`/`main`.

## CI et releases

`.github/workflows/build.yml`, sur chaque push de **n'importe quelle branche** : `ktlintCheck` → tests unitaires
→ `assembleRelease` (valide que l'APK release se construit, lint vital inclus). Sur `main` et `staging`
uniquement, l'APK est ensuite publié :
- `main` → release `v<version>` avec `<NomApp>-<version>.apk` (« Latest release » GitHub) ;
- `staging` → pre-release `staging-v<version>` avec `<NomApp>-Staging-<version>.apk`.

`<version>` = `<appVersionBase>.<run_number>` ; le run number est aussi le `versionCode` (`BUILD_NUMBER`), condition
pour que la mise à jour intégrée voie la release comme plus récente. Une release **par build**, jamais un tag
écrasé. Les APK sont signés avec le keystore versionné `app/debug.keystore` par défaut, ou avec les secrets
`KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` s'ils existent (README, « Signature »).
R8 est désactivé (`isMinifyEnabled = false`) : ne l'activer qu'après l'avoir testé sur un appareil.

## Architecture

Package `com.lielu.app` (renommé par `new-app.sh`). MVVM, Compose, DI manuelle (`AppContainer`).

- `data/settings` — DataStore Preferences : thème, réglages de sauvegarde automatique, statut de la dernière
  sauvegarde, drapeau de bienvenue.
- `backup` — **le cœur de la sauvegarde.**
  - `BackupSection` : une famille de données (`id` stable, `export()`, `restore()` qui REMPLACE et est atomique).
  - `BackupFormat` : enveloppe JSON `{format:"lielu-backup", version, appId, exportedAt, sections:{…}}` ; refuse
    les fichiers d'un autre format ou d'une version d'enveloppe plus récente.
  - `BackupManager` : assemble/restaure toutes les sections ; valide tout le fichier avant le premier changement.
  - `AutoBackupRunner` : écrit `<nom>-auto-yyyyMMdd-HHmmss.json` dans le dossier choisi, élague au-delà de N copies
    (jamais les fichiers d'un autre préfixe, jamais avant que la nouvelle copie soit écrite), restaure la plus récente.
  - `BackupFolder`/`SafBackupFolder` : dossier SAF (`ACTION_OPEN_DOCUMENT_TREE`, autorisation persistante) ;
    abstrait pour les tests.
- `work` — `AutoBackupWorker` (passage quotidien) + `AutoBackupScheduler`. WorkManager est initialisé à la demande
  (`LieluApplication` implémente `Configuration.Provider`, l'init `androidx.startup` est retirée du manifest) pour
  fournir un `WorkerFactory` avec les dépendances d'`AppContainer`.
- `ui` — `init/` (page INIT + invitation de restauration au premier lancement), `settings/` (thème, sauvegarde
  auto, export/import, mise à jour), `update/` (fenêtre de mise à jour au lancement), `theme/`, `navigation/`.

### Brancher les données de l'app dans la sauvegarde

Une app dérivée qui ajoute Room (ou tout autre stockage) écrit une `BackupSection` et l'ajoute à
`AppContainer.backupSections` — sinon ses données ne seront ni sauvegardées ni restaurées. Modèle à suivre :
`restore()` dans `database.withTransaction { deleteAll(); insertAll(…) }`, champs ajoutés plus tard lus avec une
valeur par défaut (`optXxx`/`has(...)`) pour que les anciens fichiers restent lisibles. Ajouter un test
export → modification → import, comme `BackupManagerTest`. Avec Room : `exportSchema = true`, une
`Migration` par version (vérifié par `verify.sh`), et la règle `room { schemaDirectory(...) }` par flavor pour que
les tâches KSP de `staging`/`production` n'écrivent pas en parallèle dans le même dossier. Les règles
Auto Backup Android (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`) incluent déjà `database/` et
`datastore/`.

### Mise à jour automatique

`UpdateManager` (lielugit-updater, dépôt Maven local `libs/lielugit-maven`) interroge la **dernière release
non-prerelease** du dépôt `BuildConfig.GITHUB_REPOSITORY` : `GITHUB_REPOSITORY` en CI, sinon
`appGithubRepository` de `gradle.properties`. Elle n'est active que pour `production` en `release`
(`AppContainer.updatesEnabled`) : debug et staging ont un autre `applicationId`, Android refuserait d'installer
l'APK par-dessus. Vérification à chaque passage au premier plan (`force = true`, une requête GitHub par ouverture).
Changer de signature casse la mise à jour (désinstallation nécessaire).

### Configuration Gradle notable

- `namespace`/`applicationId` : `com.lielu.app` ; `minSdk` 26, `targetSdk`/`compileSdk` 35 ; JVM 17.
- Identité de l'app dans `gradle.properties` (`appName`, `appVersionBase`, `appGithubRepository`).
- Versions de dépendances centralisées dans `gradle/libs.versions.toml` — ajouter toute dépendance là.
- Flavors `staging` (suffixe `.staging`, nom « <App> Staging ») et `production` (identité par défaut). Nommé
  `production` pour éviter le source set réservé `main`.
- `debug` : `applicationIdSuffix = ".debug"`. Le keystore versionné signe debug et release par défaut.

## Style

- `ktlint` (config par défaut, `android.set(true)`, `ignoreFailures.set(false)`), `.editorconfig` : 4 espaces,
  ligne max 120, exception de nommage pour les fonctions `@Composable`. `kotlin.code.style=official`.
- Aucune couleur en dur dans un écran : tout passe par `ui/theme/Theme.kt`.
- Textes de l'interface en français.
