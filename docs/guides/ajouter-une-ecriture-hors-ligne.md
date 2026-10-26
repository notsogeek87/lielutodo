# Ajouter un nouveau type d'écriture hors ligne

Pour qui : développeur ajoutant une action modifiant des données Vikunja. Pourquoi : garder le comportement
« local d'abord » ([ADR](../architecture/2026-10-26-local-first-file-d-ecritures.md)).

1. **Kotlin** : ajouter une valeur à `PendingOp.Kind` (`TodoState.kt`), la méthode du dépôt qui modifie l'état local
   puis appelle `enqueue(...)`, et le traitement de l'op à l'envoi dans `TodoRepository`.
2. **Codec** : tout nouveau champ de `TodoState` est lu avec une valeur par défaut dans `TodoStateCodec`.
3. **Test** : ajouter la route au faux serveur de `TodoRepositoryTest` puis un test d'écriture hors ligne puis envoi.
4. **Web** : ajouter l'action et son `apply` dans `web/js/store.js`, et la route dans `web/dev/fake-server.mjs`.
5. Si la donnée est nouvelle, vérifier qu'elle passe dans la sauvegarde (`TodoBackupSection`).

```kotlin
s.copy(ops = enqueue(s.ops, PendingOp(PendingOp.Kind.MARK_READ, id)))
```
