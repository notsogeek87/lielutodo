# LieluTodo web

Version web de LieluTodo (client Vikunja « façon Todoist »), qui vit dans le même dépôt que l'APK et parle au même
serveur (`https://task.lielu.eu`). Site statique : **aucune étape de build**, JavaScript ES modules.

- Boîte de réception, Aujourd'hui, À venir (bandeau de semaine), projets imbriqués, favoris, étiquettes, filtres
  enregistrés, terminées, recherche (`/` ou `Ctrl+K`).
- Ajout rapide en langage naturel (`Q`) : « Appeler Marie demain 9h #Perso @urgent p1 », récurrences, dates relatives.
- Détail d'une tâche : description, sous-tâches, commentaires, rappels, priorité, étiquettes, dupliquer, supprimer.
- Glisser-déposer pour l'ordre manuel, annuler après terminaison, thème clair/sombre, mobile.
- **Hors ligne d'abord** : état en `localStorage`, écritures en file puis renvoyées dans l'ordre ; PWA installable.

## Développer

```
node web/dev/fake-server.mjs 5173   # faux serveur Vikunja + site ; connexion demo / demo
node --test web/test/*.test.mjs               # tests (analyseur d'ajout rapide, dates)
```

## Déployer (Vercel)

Projet Vercel lié à ce dépôt, **Root Directory = `web`**, aucune commande de build. `vercel.json` réécrit
`/vk/*` vers `https://task.lielu.eu/*` : le navigateur parle ainsi au serveur sans CORS. Un autre serveur Vikunja
est appelé directement (il doit autoriser CORS).
