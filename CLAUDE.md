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
écrasé. LieluTodo : les secrets `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` sont configurés,
les APK publiés sont signés avec la clé de publication (README, « Signature » : empreinte attendue). Le keystore
versionné `app/debug.keystore` ne sert qu'à défaut de secrets (builds locaux) — dépôt public, il ne doit jamais signer
une app distribuée.
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
Auto Backup Android (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`) incluent `database/`, `datastore/`, `todo-cache.json`(+`.bak`) et `attachments/pending/` (jamais le jeton de session).

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
  sauvegarde (`TodoBackupSection`). Une panne serveur (502/503/504, 408, 429, ou 500 partout) = hors ligne, les écritures sont gardées. Une écriture refusée (4xx, ou 500 répété alors que `GET /user` répond) sort de la file vers `TodoState.failed` : jamais effacée en silence, elle ne bloque pas les suivantes, l'utilisateur la renvoie ou l'abandonne (Parcourir > « Modifications non envoyées »). Au rafraîchissement, la version locale prime pour tout objet visé par une écriture en file ou refusée (`mergeTasks`/`mergeProjects`/`mergeLabels`).
- Une `Task` garde son JSON serveur (`raw`) : Vikunja remplace la tâche entière à chaque mise à jour, renvoyer
  seulement nos champs effacerait les autres.
- Envoi d'une modification = fusion à trois (`mergeEdits`, Kotlin et web) : relit la tâche (`GET /tasks/{id}`), garde
  nos champs changés depuis la version serveur de départ (`Task.serverRaw` ; web : `raw`), prend le reste du serveur.
  Le cache range `serverRaw` (clé `_server_raw`) pour les tâches en attente seulement ; absent (ancien cache) =
  envoi de la version locale entière. Une modification faite pendant l'envoi repart dans une nouvelle écriture.
- Descriptions et commentaires : Vikunja stocke du HTML, l'app manipule du texte. Conversion à la frontière du modèle
  (`RichText.kt` = `web/js/richtext.js`, cas partagés `web/test/fixtures/richtext.json`) ; une description inchangée
  est renvoyée avec son HTML d'origine (`htmlForServer`), pour ne pas perdre la mise en forme du site.
- `QuickAddParser` : ajout rapide en langage naturel (dates, heure, `#projet`, `@étiquette`, `p1`-`p4`, récurrence).
  Une date seule est stockée à midi (heure « sans heure » masquée dans l'UI).
- `reminder/` (AlarmManager, notifications Terminer/Reporter, résumé quotidien), `widget/` (liste + ajout rapide),
  `work/SyncWorker` (30 min), `QuickAddActivity` (widget, tuile, raccourci, « Partager »).
- Tests : `TodoRepositoryTest` joue contre un faux serveur en mémoire ; tout nouveau type d'écriture y ajoute sa route.

- Autres écritures en file (hors ligne aussi) : pièces jointes (fichier copié dans `files/attachments/pending`,
  envoyé en multipart), commentaires modifiés/supprimés, étiquettes modifiées/supprimées,
  notifications lues. Seules les opérations rares que le serveur doit valider (filtres enregistrés, déplacement ou
  ordre des projets, partage) exigent d'être en ligne.
- Pas de vue Kanban : Vikunja n'a pas de statut de tâche (les colonnes sont des buckets propres à chaque projet),
  la vue a été retirée. `PendingOp.Kind.MOVE_BUCKET` reste uniquement pour envoyer d'anciens déplacements en file.
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

## Notes (Joplin Server)

Écran Notes (icône sur l'accueil), synchronisé en lecture/écriture avec un Joplin Server (`https://notes.lielu.eu`)
via son protocole de synchro (`POST /api/sessions` → `X-API-AUTH`, delta `items/root:/:/delta` + cursor).

- `data/joplin` : `JoplinApi`/`HttpJoplinApi`, `JoplinParser` (format d'item `BaseItem.serialize`). Une note existante
  est renvoyée avec `patch()` sur son contenu serveur (`Note.serverRaw`) : seuls titre, corps, carnet et dates changent,
  les autres propriétés (to-do, échéance, auteur…) sont gardées — même raison que `Task.raw`. `serialize()` = nouvelle
  note seulement. Items chiffrés (E2EE, `encryption_applied: 1`) ignorés, jamais réécrits.
- `data/notes` : `FileNotesRepository` (`notes-cache.json` atomique + `.bak`, un cache illisible est mis de côté en
  `.corrupt`, jamais écrasé ; toutes les écritures sous verrou, `update(id) { … }` pour lire-modifier-écrire).
  `NotesSync` : une synchro à la fois (Mutex) ; une note modifiée pendant son envoi reste `dirty` ; delta incrémental
  (resync complète seulement sur `resyncRequired` ou cache vide). `SyncPlanner` (pur, testé) : version distante
  changée depuis `serverUpdatedTime` alors que la note est modifiée ici = conflit, la version distante est gardée dans
  une copie « (conflit …) ». Suppression locale = corbeille Joplin (`deleted_time`), pas de suppression définitive.
- Éditeur : enregistrement local différé (400 ms), synchro à la FERMETURE (`onCleared` → `applicationScope`, qui
  enregistre aussi la dernière frappe et supprime une note restée vide) ; `NotesSyncWorker` toutes les 30 min.
- Identifiants : URL et e-mail dans le DataStore ; mot de passe chiffré dans `SessionStore` (hors sauvegarde Android et
  hors export). `NoteBackupSection` exporte notes (avec état de synchro et `serverRaw`) et carnets.
- Tests : `NotesSyncTest` (faux serveur Joplin), `SyncPlannerTest`, `FileNotesRepositoryTest`, `JoplinParserTest`.
- Carnets : créés ici (hors ligne aussi, `Folder.dirty`), envoyés AVANT leurs notes. Partage (en ligne,
  `NotesSync.shareFolder`, ordre vérifié contre un vrai serveur) : synchro → `POST /api/shares {folder_id}` → carnet et
  notes renvoyés avec `share_id` → `POST /api/shares/{id}/users {email}`. Toute note d'un carnet partagé part avec son
  `share_id` (sinon les autres ne la voient pas). Invitations : `GET /api/share_users`, `PATCH … {status: 1|2}`. Seuls
  les carnets à la racine se partagent.
- Suppression d'un carnet (APK et web, hors ligne aussi) : le carnet, ses sous-carnets et leurs notes vont à la
  corbeille Joplin (`deleted_time`) à la synchro suivante. APK : `Folder.deleted` (+ `dirty`), `NotesRepository.
  deleteFolder`, `folders` (Flow) les masque, `getFolders()` les rend ; web : `state.deletedFolders`. Carnet jamais
  envoyé (404) = simplement retiré.
- Format d'item : JAMAIS de retour à la ligne final (Joplin lit les propriétés depuis la dernière ligne : un `\n` final =
  « Missing required property: type_ », écriture refusée). Session réutilisée entre synchros : le serveur répond 429
  après quelques connexions rapprochées.
- Brouillon vide jamais envoyé : ni envoyé ni gardé (`SyncPlanner`/`planSync`).
- Images jointes (`![nom](:/<id>)`, ressource Joplin) : fichier lu par `GET /api/items/root:/.resource/<id>:/content`
  (`resourceContent`/`resourceBlob`), gardé hors ligne (APK : `files/joplin-resources/`, hors sauvegarde ; web : Cache
  Storage `lielutodo-joplin-resources`, épargné par `sw.js`). Aperçu : `NoteImages.split` (APK) / `renderMarkdown` +
  `data-jres` (web) ; une note qui contient une image s'ouvre en aperçu. CSP du site : `img-src … blob: https:`.
- Identifiants Joplin : serveur + e-mail aussi copiés dans SharedPreferences `session` (`SecretStore.joplinServer/
  joplinEmail`, secours si le DataStore perd ses clés). `SessionStore` ne crée JAMAIS de clé Keystore pour déchiffrer
  (une clé recréée rendait les mots de passe illisibles pour toujours) ; un mot de passe illisible est relu plus tard.
- APK : notes synchronisées à chaque ouverture/retour au premier plan (`MainActivity.syncNotesOnOpen`), « tirer pour
  rafraîchir » sur l'onglet Notes (`NotesSync.syncing`), carte « Notes non connectées » avec formulaire pré-rempli
  (`DEFAULT_JOPLIN_SERVER`, aussi valeur par défaut du réglage). Serveur + e-mail Joplin font partie de la section
  `settings` des sauvegardes (restaurés seulement si absents) ; le mot de passe jamais.
- Web : la synchro des notes est visible (`meta.syncing`/`meta.progress` : ligne d'état sous le titre, roue du haut,
  « Récupération de vos notes… » à la première) ; contenus téléchargés 6 par 6. La page de connexion a, comme l'APK, une
  section Notes facultative.
- Test contre un vrai serveur : `JoplinServerIntegrationTest` (Kotlin) et le test « intégration » de
  `web/test/notes.test.mjs`, ignorés sans `JOPLIN_IT_URL` (+ `JOPLIN_IT_USER1/PASS1`, `USER2/PASS2`). Serveur local :
  `docker run -d --network host -e APP_BASE_URL=http://localhost:22300 -e APP_PORT=22300 -e MAX_TIME_DRIFT=0
  joplin/server:latest` (admin@localhost / admin ; `MAX_TIME_DRIFT=0` si NTP est bloqué).
- Web : `web/js/notes/` (`joplin.js` = parser + client, `notes-store.js` = store + synchro + partage, `markdown.js` =
  édition + rendu sûr, `notes-view.js` = écrans). Même logique que l'APK : toute évolution de l'un se porte sur l'autre
  (cas de test miroirs dans `MarkdownEditingTest`/`notes.test.mjs`). Joplin Server n'autorise le CORS que pour
  joplinapp.org : le site passe TOUJOURS par la réécriture `/jp/*` (`vercel.json`, racine et `web/`).
- APK d'une branche de travail : artefact du run CI (`apk-<branche>-<run>`), jamais versionné dans le dépôt.

## Version web (`web/`)

Même serveur Vikunja, même logique « local d'abord » que l'APK, en site statique sans build (ES modules, voir
`web/README.md`). `js/store.js` = `TodoRepository`, `js/parser.js` = `QuickAddParser` (port fidèle : toute évolution de
l'un se répercute sur l'autre), `js/model.js` = `Models.kt`. Déployée sur Vercel (Root Directory `web`, réécriture
`/vk/*` -> `task.lielu.eu`, simple repli : le site appelle le serveur en direct dès que `VIKUNJA_CORS_ORIGINS` l'autorise).
Session Vikunja 2.x : le jeton ne dure que 10 min ; un cookie HttpOnly (30 j, chemin `/api/v1/user/token/refresh`, sans
domaine) le renouvelle. Connexion ET renouvellement passent donc par `/api/v1/login` et `/api/v1/user/token/refresh`
sur le site (réécritures dans les deux `vercel.json`) pour que le navigateur garde le cookie ; `createApi` renouvelle en
silence sur un 401 et rejoue l'appel. Session impossible à renouveler : reconnexion demandée SANS effacer le cache ni
les écritures en attente (`sess.userId` : un autre compte remet le cache à zéro avant tout envoi). L'APK, lui, se
reconnecte avec le mot de passe mémorisé (`VikunjaClient.reauthenticate`).
Adresses des serveurs : masquées sur les pages de connexion (tâches et notes), réglables seulement sur la page cachée
`#/serveurs` (aucun lien, `js/servers.js`, clé `lielutodo.servers`, valeurs par défaut task/notes.lielu.eu). Changer
de serveur de tâches déconnecte et vide le cache (après confirmation), changer de serveur Joplin déconnecte les notes.
Tests : `node --test web/test/*.test.mjs` ; faux serveur : `web/dev/fake-server.mjs`. Les deux parseurs d'ajout rapide
jouent les mêmes cas (`web/test/fixtures/quickadd.json`, `QuickAddSharedCasesTest`) : tout changement de l'un y ajoute un cas.
Un nouveau type d'écriture se branche dans `store.js` (action + `apply`) ET dans le faux serveur. Assignation web =
écriture `assign` (comme `PendingOp.Kind.ASSIGN`), utilisateurs du projet en cache (`state.projectUsers`) ; l'ajout
rapide assigne « moi » par défaut, comme l'APK. Même politique d'erreurs que l'APK (`failed`, `mergeTasks`, `mergeCreated`). Les écritures non envoyées et les objets qu'elles visent sont rangés à part (`lielutodo.pending`) et écrits avant le cache : un quota dépassé sacrifie le cache, jamais elles. Tests du store : `web/test/store.test.mjs` (faux serveur en mémoire).

## Règle : l'heure est obligatoire avec une date

Toute date d'échéance saisie (ajout rapide depuis n'importe où, détail d'une tâche) doit être suivie d'un choix
d'heure : jamais de « Sans heure », jamais de date seule enregistrée à midi par un nouveau chemin
(`DATE_ONLY_TIME` ne sert qu'à lire d'anciennes tâches). `QuickAdd.needsTime` décide, `QuickAddSheet` demande
l'heure après la date ou à l'envoi. Tout nouveau `DatePickerSheet` doit être accompagné d'un `TimePickerDialog` ;
`TimeRequiredTest` échoue sinon. Récurrence ajoutée à une tâche sans date : le détail demande d'abord la date puis l'heure (`pendingRepeat`).
