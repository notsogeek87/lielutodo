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

## LieluTodo : client Vikunja « façon Todoist » (ce dépôt)

Serveur : `https://task.lielu.eu` (Vikunja, API `/api/v1`). Le serveur est personnel et peut être coupé : tout doit
rester lisible ET modifiable hors ligne.

- `data/api` : `HttpApi` (abstrait pour les tests), `VikunjaClient` (HttpURLConnection + org.json, connexion 6 s),
  `SessionStore` (jeton dans SharedPreferences, volontairement hors sauvegarde Android).
- `data/todo/TodoRepository` : source de vérité « local d'abord ». Chaque action modifie l'état local puis met une
  `PendingOp` en file (envoyée dans l'ordre, ids temporaires négatifs remappés à la création). `refresh()` n'écrase
  jamais une écriture en attente. Cache : `FileLocalCache` (JSON atomique + copie `.bak`), codec partagé avec la
  sauvegarde (`TodoBackupSection`). Une panne serveur (5xx) = hors ligne, les écritures sont gardées.
- Une `Task` garde son JSON serveur (`raw`) : Vikunja remplace la tâche entière à chaque mise à jour, renvoyer
  seulement nos champs effacerait les autres.
- `QuickAddParser` : ajout rapide en langage naturel (dates, heure, `#projet`, `@étiquette`, `p1`-`p4`, récurrence).
  Une date seule est stockée à midi (heure « sans heure » masquée dans l'UI).
- `reminder/` (AlarmManager, notifications Terminer/Reporter, résumé quotidien), `widget/` (liste + ajout rapide),
  `work/SyncWorker` (30 min), `QuickAddActivity` (widget, tuile, raccourci, « Partager »).
- Tests : `TodoRepositoryTest` joue contre un faux serveur en mémoire ; tout nouveau type d'écriture y ajoute sa route.

- Autres écritures en file (hors ligne aussi) : pièces jointes (fichier copié dans `files/attachments/pending`,
  envoyé en multipart), commentaires modifiés/supprimés, étiquettes modifiées/supprimées, déplacements Kanban,
  notifications lues. Seules les opérations rares que le serveur doit valider (filtres enregistrés, déplacement ou
  ordre des projets, partage) exigent d'être en ligne.
- Cache : `TodoState` est sérialisé par `TodoStateCodec` (cache disque ET sauvegarde). Tout nouveau champ d'état s'y
  ajoute avec une valeur par défaut à la lecture, sinon les anciens fichiers deviennent illisibles.
- Réglages propres à l'appareil (verrou, son, icône d'app) : `AppPrefs` (SharedPreferences, hors sauvegarde). L'icône
  est un `activity-alias` du manifest : un seul actif à la fois, le lanceur MAIN est sur l'alias, pas sur MainActivity.
- Verrou : écran de confirmation de l'appareil (`KeyguardManager`), aucune donnée biométrique ne passe par l'app.
- Productivité et journal d'activité : calculés sur les dates `done_at`/`created` des tâches en cache (`Productivity.kt`).
- Agenda de l'appareil (option, Parcourir) : `calendar/CalendarSync` recopie les tâches datées non terminées dans un
  agenda choisi (sens tâche -> agenda seulement, événements sans rappel pour ne pas doubler les alarmes de l'app).
  `reconcileEvents` (pure, testée dans `CalendarPlanTest`) compare l'état des tâches à la table tâche -> événement
  (SharedPreferences `calendar_sync`, hors sauvegarde) : date changée ici, sur le web ou par synchro, terminée,
  supprimée -> même chemin. Date sans heure = événement « toute la journée ». Ne rien faire tant que `state.loaded`
  est faux (une liste vide effacerait l'agenda).
- Piège d'édition : après un reformatage ktlint, un `str.replace` sur du code déjà reformaté peut ne rien changer en
  silence. Vérifier chaque point d'entrée UI (grep) et préférer un remplacement qui échoue si l'ancre manque.

## Version web (`web/`)

Même serveur Vikunja, même logique « local d'abord » que l'APK, en site statique sans build (ES modules, voir
`web/README.md`). `js/store.js` = `TodoRepository`, `js/parser.js` = `QuickAddParser` (port fidèle : toute évolution de
l'un se répercute sur l'autre), `js/model.js` = `Models.kt`. Déployée sur Vercel (Root Directory `web`, réécriture
`/vk/*` -> `task.lielu.eu`). Tests : `node --test web/test/*.test.mjs` ; faux serveur : `web/dev/fake-server.mjs`.
Un nouveau type d'écriture se branche dans `store.js` (action + `apply`) ET dans le faux serveur.
