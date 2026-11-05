# LieluTodo web

Version web de LieluTodo (client Vikunja « façon Todoist »), qui vit dans le même dépôt que l'APK et parle au même
serveur (`https://task.lielu.eu`). Site statique : **aucune étape de build**, JavaScript ES modules.

- Boîte de réception, Aujourd'hui, À venir (bandeau de semaine), projets imbriqués, favoris, étiquettes, filtres
  enregistrés, terminées, recherche (`/` ou `Ctrl+K`).
- Ajout rapide en langage naturel (`Q`) : « Appeler Marie demain 9h #Perso @urgent p1 », récurrences, dates relatives.
- Détail d'une tâche : description, sous-tâches, commentaires, rappels, priorité, étiquettes, dupliquer, supprimer.
- Glisser-déposer pour l'ordre manuel, annuler après terminaison, thème clair/sombre, mobile.
- **Hors ligne d'abord** : état en `localStorage`, écritures en file puis renvoyées dans l'ordre ; PWA installable.

## Notes (Joplin Server)

Page **Notes** (`#/notes`, raccourci `G` puis `N`) : mêmes notes que l'APK, synchronisées avec Joplin Server
(`https://notes.lielu.eu`), lisibles et modifiables hors ligne (`js/notes/`).

- Éditeur Markdown : barre de mise en forme (titre, gras, italique, barré, case à cocher, listes, citation, code,
  lien), `Ctrl+B`/`I`/`K`, listes prolongées à l'Entrée, annuler/rétablir, aperçu, titre tiré de la 1re ligne.
- Carnets : création, filtre ; **partage** d'un carnet à la racine avec un autre compte du serveur (participants,
  retrait, arrêt) et invitations reçues à accepter/refuser.
- Le mot de passe Joplin ouvre une session et n'est **jamais** stocké (seul l'id de session l'est).
- **Joplin Server n'autorise le CORS que pour `https://joplinapp.org`** : le site passe donc toujours par la réécriture
  `/jp/*` → `https://notes.lielu.eu/*` de `vercel.json` (indispensable). Un autre serveur Joplin ne marche que s'il
  autorise ce site.

## Développer

```
node web/dev/fake-server.mjs 5173   # faux serveur Vikunja + site ; connexion demo / demo
node --test web/test/*.test.mjs               # tests (analyseur d'ajout rapide, dates)
JOPLIN_DEV_URL=http://localhost:22300 node web/dev/fake-server.mjs 5173   # + notes via un Joplin Server local (/jp/*)
```

## Déployer (Vercel)

Projet Vercel lié à ce dépôt, **Root Directory = `web`**, aucune commande de build. `vercel.json` réécrit
`/vk/*` vers `https://task.lielu.eu/*` : le navigateur parle ainsi au serveur sans CORS. Un autre serveur Vikunja
est appelé directement (il doit autoriser CORS).

Le `vercel.json` à la racine du dépôt (`outputDirectory: web`) fait la même chose si le Root Directory du projet reste
sur la racine ; il doit rester identique à `web/vercel.json` (réécritures et en-têtes).
