# Développement, tests et CI

Pour qui : développeur. Pourquoi : enchaîner correctement vérification, push et release.

```bash
./gradlew ktlintCheck          # lint (échoue si violations) ; ktlintFormat pour corriger
./gradlew test                 # tests unitaires staging + production
./gradlew assembleRelease      # APK release
node --test web/test/*.test.mjs        # tests web
node web/dev/fake-server.mjs 5173      # site + faux Vikunja, connexion demo / demo
```

## Avant de pousser
1. `scripts/verify.sh` (ou `--fast` sans Gradle) — lancé aussi par le hook `.githooks/pre-push`.
2. Pousser la branche de travail.
3. `scripts/ci-status.sh --wait <branche>`.
4. Seulement ensuite, fusion dans `staging`/`main` (le hook refuse sans CI verte).

## CI et releases
`.github/workflows/build.yml` : ktlint → tests → `assembleRelease` sur toute branche. `main` publie `v<version>`,
`staging` publie la pre-release `staging-v<version>`. `<version>` = `<appVersionBase>.<run_number>`, le run number est
aussi le `versionCode`. Signature : `app/debug.keystore` par défaut, ou secrets `KEYSTORE_*`. Changer de clé impose
de désinstaller l'app.

## Web
Site statique sans build, déployé sur Vercel (Root Directory `web`). Voir `web/README.md`.
