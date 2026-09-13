# Prompt à donner à l'équipe qui développe le runner

> À copier-coller tel quel dans Claude Code, ouvert sur le dépôt de `claude-runner`.
> Rédigé le 2026-09-13 depuis le poste Corporate Center. Les mesures citées viennent de ce poste.

---

## Contexte

Tu travailles sur `claude-runner` (paquet `fr.claudegateway.runner.teams`, adaptateur Teams v1).

Un utilisateur ne peut pas lire Teams depuis son poste. **Tous** les outils de lecture
(`teams_find_meetings`, `teams_find_conversations`, `teams_search`, `teams_read_conversation`)
rendent 0 élément, avec `window.actualFrom` / `actualTo` à `null` et le manque
`NOTHING_OBSERVED — rien d'observé depuis le rattachement`.

Le problème : **ce n'est pas vrai que rien n'a été observé.** Teams est relié, connecté, la bonne
page est affichée à l'écran, et le réseau répond. Le message conduit l'assistant à demander à
l'utilisateur d'afficher des écrans qu'il a déjà affichés — cinq allers-retours perdus avant qu'on
comprenne que la panne était côté runner.

## Ce qui a été mesuré sur le poste

`teams_status` (état `LINKED`, Chrome/151.0.7922.138), bloc `diagnostic.observation` :

```
framesObserved: true
responses: 28
classified: 0
ignored: 20
unknownMicrosoft: 7
unknownElsewhere: 1
responsesByOrigin: { SERVICE_WORKER: 21, TEAMS_TAB: 6, WORKER: 1 }
attachedByOrigin:  { SERVICE_WORKER: 2,  WORKER: 2 }
classifiedByKind:  {}   // vide
```

28 réponses vues, **0 classée**. Au même moment, le DOM de l'onglet — lu via le port de débogage —
contenait bien le fil de la réunion cherchée, avec sa date, ses onglets *Recap* et *Q&A*.

Un relevé guidé (`--releve-teams`, un opérateur suivant les 4 étapes : fil, réunion passée,
récapitulatif, transcription) donne le même tableau :

| Classification | Lignes |
|---|---|
| `UNKNOWN` | 47 |
| `IGNORED` | 2 |
| `MEETING_DETAILS` | 2 |

Les **2 seules lignes classées** sont d'origine `WORKER`. **Aucune ligne d'origine `TEAMS_TAB`
n'est classée**, alors que ce sont elles qui portent le contenu attendu :

- `teams.microsoft.com/api/mt/emea/v2.0/me/calendars/events/iCalUId/{id}` — `TEAMS_TAB` — `UNKNOWN`
- `teams.microsoft.com/api/mcps/eu/collab/readcollabobject/V2/{id}/{id}/{id}` — `TEAMS_TAB` — `UNKNOWN`
- `teams.microsoft.com/api/mt/emea/v1/schedulingService/meetings` — `WORKER` — `MEETING_DETAILS` ✔

## Mon intuition — à vérifier, pas à croire

J'ai décompilé `TeamsUrls` et appelé `TeamsUrls.classify(...)` directement, hors runner. **Le
classifieur fait son travail** : il rend le bon type sur les chemins de ce tenant, à condition de
recevoir l'URL **complète, hôte compris**.

| Entrée | `classify` rend |
|---|---|
| `https://teams.microsoft.com/api/mt/emea/v2.0/me/calendars/events/iCalUId/{id}` | `CALENDAR_EVENT` |
| `/api/mt/emea/v2.0/me/calendars/events/iCalUId/{id}` (chemin seul) | `UNKNOWN` |
| `https://teams.microsoft.com/api/mcps/eu/collab/readcollabobject/V2/{id}/{id}/{id}` | `MEETING_COLLAB_OBJECT` |
| `/api/mcps/eu/collab/readcollabobject/V2/{id}/{id}/{id}` (chemin seul) | `UNKNOWN` |
| `https://teams.microsoft.com/api/csa/emea/api/v2/teams/users/me/conversations` | `CONVERSATION_LIST` |
| `/api/csa/emea/api/v2/teams/users/me/conversations` (chemin seul) | `UNKNOWN` |

Dans le bytecode, `classify` commence par `isChatHost(...)` / `isFileHost(...)`. **Sans hôte, ces
deux gardes se ferment et tout retombe sur `UNKNOWN`**, quel que soit le chemin.

**D'où mon hypothèse : quelque part sur le trajet `Network.responseReceived` → `classify`, l'URL
est réduite à son chemin — et pas sur toutes les origines, puisque les réponses `WORKER` s'en
sortent.** Regarde du côté de `NetworkObserver` : il manipule `withoutQuery`, `hostOf`, `shortOf`,
et distingue les origines (`TEAMS_TAB`, `SERVICE_WORKER`, `WORKER`) via `NetworkSurvey$Origin`.
Regarde aussi `SurveyPaths`, qui sépare hôte et chemin pour le rapport de relevé : si la même
découpe alimente la classification, c'est le point de bascule.

**Ne prends pas cette hypothèse pour acquise.** Elle repose sur deux points de mesure seulement
(les 2 lignes `WORKER` classées), et sur du bytecode lu de l'extérieur, sans les sources. Elle peut
être fausse, ou n'expliquer qu'une partie du problème. **Va lire le code toi-même, reproduis, et
tire tes propres conclusions** — y compris celle que je me trompe. Si la cause est ailleurs, dis-le
franchement plutôt que de faire coller le code à mon récit.

## Ce que j'attends de toi

1. **Reproduis** — un test qui part d'un événement `Network.responseReceived` réaliste (origine
   `TEAMS_TAB`, URL complète avec hôte et chaîne de requête) et vérifie ce que reçoit `classify`.
   Fais échouer ce test avant de corriger quoi que ce soit.
2. **Remonte le trajet de l'URL** depuis `Network.responseReceived` jusqu'à `classify`, et dis
   **où** l'hôte se perd, en citant fichier et ligne. Si l'hôte ne se perd nulle part, dis-le et
   cherche la vraie cause.
3. **Vérifie l'asymétrie entre origines.** Les réponses `WORKER` et `SERVICE_WORKER` passent-elles
   par le même chemin de code que `TEAMS_TAB` ? Si non, c'est probablement là.
4. **Corrige à la source** : `classify` doit recevoir ce que le navigateur a réellement renvoyé.
   Une rustine qui réessaie avec un hôte deviné traitera le symptôme et masquera le défaut.
5. **Couvre la régression** : un test par famille de `TeamsPayloadKind`, avec l'URL complète, pour
   chacune des trois origines.

## Et pendant que tu y es : le message est trompeur

`NOTHING_OBSERVED — rien d'observé depuis le rattachement` a **deux causes** que rien ne distingue
côté appelant :

- Teams n'a rien servi (l'utilisateur n'a pas ouvert l'écran voulu) → le remède est *un geste
  humain* ;
- Teams a servi mais le runner n'a rien classé → le remède est *un correctif logiciel*.

Le libellé n'affirme que la première, et envoie donc l'utilisateur cliquer dans le vide quand c'est
la seconde. Un message d'erreur est lu par quelqu'un qui doit **corriger** : il doit porter son
action corrective, et celle-ci n'est pas la même dans les deux cas.

Distingue-les — par exemple `NOTHING_SERVED` (`responses == 0`) et `NOTHING_CLASSIFIED`
(`responses > 0 && classified == 0`), ce dernier disant explicitement que le contenu est arrivé mais
n'a pas été reconnu, et que cliquer n'y changera rien. Sers-toi de `diagnostic.observation`, qui
porte déjà les deux compteurs — mais qui n'est pas remonté dans le manque.

## Détails utiles

- Poste : macOS, Chrome/151.0.7922.138, tenant européen (chemins `emea` / `eu`).
- Le relevé anonymisé peut être fourni : hôtes, chemins, origines, types MIME, statuts — **ni
  corps de réponse, ni chaîne de requête, ni en-tête, ni nom de tenant**, identifiants remplacés
  par `{id}`.
- `--releve-teams` est **interactif** : il attend qu'un opérateur tape le numéro de l'étape pendant
  qu'il navigue, puis `fin`. Lancé sans personne au clavier, il se termine en quelques
  millisecondes sur « Rien observé » et écrit un rapport vide. À signaler dans l'aide de la
  commande, ou à faire échouer explicitement quand l'entrée standard n'est pas un terminal.
