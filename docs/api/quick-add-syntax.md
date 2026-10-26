# Syntaxe d'ajout rapide

Pour qui : utilisateur et développeur. `QuickAddParser` (Kotlin) et `web/js/parser.js` sont des ports fidèles :
toute évolution de l'un se répercute sur l'autre. Seuls les repères reconnus sont retirés du titre.

| Repère | Exemples |
| --- | --- |
| Priorité | `p1` (urgente) à `p4` (aucune) |
| Projet | `#Perso` |
| Étiquette | `@urgent` |
| Heure | `9h`, `9:30`, `à 14h15` |
| Date | `aujourd'hui`/`auj`, `demain`, `après-demain`, `dans 3 jours/semaines/mois`, jour de semaine, `12/05`, `12/05/2027`, `le 15` |
| Récurrence | `tous les jours`, `chaque semaine`, `tous les mois`, `tous les 2 jours/semaines`, `chaque lundi` |

```
Appeler Marie demain 9h #Perso @urgent p1
Payer le loyer le 1er chaque mois
```

- Une date seule est stockée à midi (`DATE_ONLY_TIME`), heure masquée dans l'UI.
- Une heure sans date = aujourd'hui, ou demain si elle est déjà passée.
- Vikunja ne répète que les tâches avec échéance : sans date, la première occurrence est aujourd'hui.
- Tests : `QuickAddParserTest` (Kotlin), `web/test/parser.test.mjs`.
