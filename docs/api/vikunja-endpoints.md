# Points d'accès Vikunja utilisés

Pour qui : développeur qui touche à `VikunjaClient`, `TodoRepository` ou `web/js/api.js`. Base : `<serveur>/api/v1`,
jeton Bearer. En web, `/vk/*` est réécrit vers `task.lielu.eu` (voir `web/vercel.json`).

## Routes appelées (extraites de `TodoRepository.kt`)

| Domaine | Méthode et route |
| --- | --- |
| Session | `POST /login`, `GET /user`, `GET /users` |
| Tâches | `PUT /projects/{p}/tasks` (création), `POST /tasks/{id}` (mise à jour complète), `DELETE /tasks/{id}`, `POST /tasks/{id}/position` |
| Liste d'un projet | `GET /projects/{p}/views/{v}/tasks` |
| Projets | `GET/PUT /projects`, `POST/DELETE /projects/{id}`, `GET /projects/{p}/projectusers`, `/projects/{p}/users` |
| Étiquettes | `PUT /labels`, `POST/DELETE /labels/{id}`, `POST /tasks/{id}/labels/bulk` |
| Relations | `PUT /tasks/{id}/relations` (sous-tâche) |
| Assignation | `PUT /tasks/{id}/assignees`, `DELETE /tasks/{id}/assignees/{u}` |
| Commentaires | `GET/PUT /tasks/{id}/comments`, `POST/DELETE /tasks/{id}/comments/{c}` |
| Pièces jointes | `GET /tasks/{id}/attachments`, envoi multipart, `DELETE /tasks/{id}/attachments/{a}` |
| Kanban (retiré de l'UI, ancien `MOVE_BUCKET` encore envoyé) | `POST /projects/{p}/views/{v}/buckets/{b}/tasks` |
| Filtres | `GET/PUT /filters`, `POST/DELETE /filters/{id}` |
| Notifications | `GET/POST /notifications`, `POST /notifications/{id}` |

## Types d'écritures en file (`PendingOp.Kind`)

`CREATE`, `UPDATE`, `DELETE`, `COMMENT`, `CREATE_PROJECT`, `CREATE_LABEL`, `RELATE`, `ASSIGN`, `POSITION`, `ATTACH`,
`DELETE_ATTACHMENT`, `COMMENT_EDIT`, `COMMENT_DELETE`, `LABEL_UPDATE`, `LABEL_DELETE`, `MOVE_BUCKET`, `MARK_READ`,
`MARK_ALL_READ`.

Champs : `taskId` (objet visé), `otherId` (second objet), `text`, `flag`, `extra` (couleur d'étiquette, nom/type de
fichier, `projet:vue` d'un déplacement Kanban).

```kotlin
PendingOp(PendingOp.Kind.COMMENT, taskId, text = "Bien reçu")
```

> Cette liste reflète le code à la date du 2026-10-26 ; la source de vérité reste `TodoRepository.kt`.
