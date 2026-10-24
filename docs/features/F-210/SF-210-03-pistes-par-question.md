# Mini-spec — F-210 / SF-210-03 — Une piste par question pendant le tour (front)

## Identifiant
`F-210 / SF-210-03` — feature parente `F-210` *Deux questions, deux fils* (cadrage
`CADRAGE-F-210-deux-questions-deux-fils.md`). Branche : `feat/SF-210-03-pistes-par-question`.
Statut : `in-progress`. Date : 2026-10-24. Dépend de SF-210-01/02 (mergées). Aucun endpoint nouveau.

## Objectif
Quand l'agent a découpé le message, le terminal montre, pendant le tour, une piste par question (libellé,
état, dépendance, ses actions) et un bandeau de découpage avec « Fusionner » / « Séparer » qui envoie une
demande visible.

## Comportement attendu

### Cas nominal
1. **Flux** : `atelier.service.ts` lit l'événement SSE `questions` (`{questions, fusion, answers?}`) →
   `onQuestions` ; l'événement `action` garde son champ `question` (nombre) s'il est présent.
2. **État du tour** : `AtelierExecStreamingItem.questions` reçoit l'instantané (remplacé à chaque
   événement) ; chaque bloc vivant porte `question` (via `chatStepsToBlocks`). À la fin du tour,
   l'instantané est gardé sur la réponse (`AtelierThreadItem.questions`, et `answers` pour SF-210-04) ;
   au rechargement, il est relu dans `terminal_json.questions` / `terminal_json.answers`.
3. **Composant `app-terminal-question-tracks`** (standalone, charte existante, aucune couleur ni police
   nouvelle) :
   - **Bandeau** : « Ce message porte N questions » + bouton **Fusionner** ; si fusion : « Fusionnées : raison »
     + bouton **Séparer**. Le bouton remplit la saisie d'une demande explicite et l'envoie (pendant un tour,
     l'envoi devient une précision — interjection — par le mécanisme existant) : jamais silencieux.
   - **Pistes** (pendant le tour) : par question, son numéro, son libellé, son état en français (En cours,
     En attente, Terminée, Non aboutie), « en attente de Q1 » si elle dépend d'une autre et attend, le
     nombre de ses actions et les trois dernières (libellés des blocs rattachés).
   - Dans l'historique : le bandeau seul (avec l'état final de chaque question), boutons masqués en lecture
     seule.
4. **Texte vivant** : les marqueurs `<<question id="N">>` / `<</question>>` ne sont jamais montrés bruts : ils
   deviennent « **N.** » (même rendu que le texte persisté par SF-210-02) ; avec plusieurs blocs, les marqueurs
   d'essentiel internes sont retirés.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Événement `questions` illisible ou sans liste | ignoré (rien n'est rendu) |
| Question à l'état inconnu | affichée « En attente » |
| Tour sans découpage | aucun bandeau, aucune piste (rendu actuel inchangé) |
| Lecture seule | bandeau sans bouton |
| Tour en cours non précisable (`steerable` faux) | boutons désactivés |

## Critères d'acceptation
- [ ] Un SSE `questions` à deux questions affiche deux pistes avec libellé et état ; les actions portant
      `question: 2` apparaissent sous la piste 2.
- [ ] « Fusionner » envoie « Traite ces questions ensemble, en une seule réponse. » ; « Séparer » envoie
      « Sépare ces questions : une réponse par question. » — par le chemin d'envoi normal.
- [ ] Le bandeau reste sur la réponse après la fin du tour et après rechargement (`terminal_json.questions`).
- [ ] Aucun marqueur `<<question` n'apparaît dans le texte vivant.
- [ ] Sans découpage, le DOM du tour est inchangé.

## Plan de test
- **Unitaires** : `question-tracks.spec.ts` (normalisation de l'instantané, état en français, dépendance,
  actions par question, textes de demande), `essential.spec.ts` (marqueurs de question neutralisés),
  `chat-steps.spec.ts` (question propagée), `atelier.service` (parse `questions` et `action.question`).
- **Composant** : `terminal-question-tracks.component.spec.ts` (bandeau, pistes, fusion, boutons, lecture
  seule).
- **Isolation** : aucun appel nouveau ; données venues du flux et du message de l'utilisateur.
- `npx ng test --watch=false --browsers=ChromeHeadless` et `npm run build`.

## Impacts
- **Endpoints** : aucun.
- **Composants** : `core/models/atelier.models.ts`, `core/services/atelier.service.ts`,
  `atelier/atelier.types.ts`, `atelier/atelier.component.ts` (handlers d'envoi et de rebranchement, fin de
  flux, relecture de l'historique), `atelier/terminal/chat-steps.ts`, `atelier/terminal/essential.ts`,
  `atelier/terminal/question-tracks.ts` (nouveau), `atelier/terminal/terminal-question-tracks.component.ts`
  (nouveau), `atelier-terminal.component.{ts,html}`.

### Préoccupations transversales
Aucune (pas d'auth, de tenant, de plan, de navigation).

## Hors périmètre
Une carte de réponse par question (SF-210-04).
