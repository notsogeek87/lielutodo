# ADR : local d'abord, avec file d'écritures

- Date : 2026-10-26 (décision antérieure, consignée ici)
- Statut : acceptée

## Contexte
Le serveur Vikunja est personnel et peut être coupé. Les tâches doivent rester lisibles **et** modifiables hors ligne.

## Décision
`TodoRepository` est la source de vérité. Chaque action modifie d'abord l'état local, puis place une `PendingOp`
dans une file envoyée dans l'ordre.
- Les ids temporaires (négatifs) sont remappés à la création côté serveur.
- `refresh()` n'écrase jamais une écriture en attente.
- Une panne serveur (5xx) vaut « hors ligne » : les écritures sont conservées.
- Seules les opérations rares que le serveur doit valider exigent d'être en ligne : filtres enregistrés,
  déplacement/ordre des projets, partage.

## Conséquences
- Tout nouveau type d'écriture se branche dans le dépôt Kotlin, dans `web/js/store.js` **et** dans les faux serveurs
  de test (voir le [guide](../guides/ajouter-une-ecriture-hors-ligne.md)).
- Le cache (`FileLocalCache`, JSON atomique + copie `.bak`) et la sauvegarde partagent le même codec.
