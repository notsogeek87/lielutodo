# Documentation LieluTodo

Pour qui : toute personne (ou Claude) qui développe ou maintient LieluTodo, client Vikunja « façon Todoist »
(APK Android + version web) pour le serveur `https://task.lielu.eu`.

## Architecture
- [Vue d'ensemble](architecture/overview.md) — couches, flux de données, sauvegarde, mise à jour.
- [ADR 2026-10-26 — Local d'abord, file d'écritures](architecture/2026-10-26-local-first-file-d-ecritures.md)

## API
- [Points d'accès Vikunja utilisés](api/vikunja-endpoints.md) — routes appelées, types d'écritures en file.
- [Syntaxe d'ajout rapide](api/quick-add-syntax.md) — langage naturel reconnu (APK et web).

## Guides
- [Développement, tests et CI](guides/developpement-et-ci.md)
- [Ajouter un nouveau type d'écriture hors ligne](guides/ajouter-une-ecriture-hors-ligne.md)

## Legacy
- [Ancien README du modèle `lieluinit`](legacy/readme-modele-lieluinit.md)
