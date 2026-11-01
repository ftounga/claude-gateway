# Mini-spec — F-235 / SF-235-04 — La délégation dans le fil (D10, D12, D19)

## Identifiant
`F-235 / SF-235-04` · Feature parente `F-235` · Statut `in-review` · 2026-11-01 · Branche `feat/SF-235-04-delegation-fil`

## Objectif
> Une délégation du cerveau rapide devient un tour Opus normal dans le fil du sujet visé, et chaque échange rapide est
> tracé dans le fil sans jamais être rejoué comme un tour (Opus le reçoit en note au tour suivant).

## Comportement attendu
### Cas nominal
- **Routage (D10, D19)** : `/converse` accepte `subjectId` **facultatif** (Jarvis global). Délégation :
  - sans sujet cible (aucun sujet courant ni sujet nommé) ⇒ pas de délégation au hasard : réponse rapide « Pour quel
    client ou quel sujet, Monsieur ? » ;
  - fil cible avec un tour vivant ⇒ l'annonce devient « J'ajoute ça à la recherche en cours : <label>. » (l'envoi de
    l'écran deviendra une **précision**, chemin `openOrSteer` existant) ;
  - sinon la **demande reformulée** attend le fil cible (`JarvisDelegationRegistry.offer`, 2 min, clé
    `user_id | sujet | chantier`).
- **Le tour Opus délégué** : l'écran ouvre le tour par le chemin existant (`streamChat`, `via=JARVIS`) avec la parole
  du PO **mot pour mot** ; `AtelierChatService` prend (une fois) la reformulation de ce fil et joint au **message** la
  note `[Jarvis — demande reformulée : « … »]` ; la ligne USER est marquée `brain='DELEGATED'`. Consigne système,
  modèle, effort, outils : **inchangés**.
- **Trace (D12)** : une réponse rapide (avec sujet courant) écrit deux lignes dans le fil (`via='JARVIS'`,
  `brain='FAST'`) : la parole et la réponse.
- **Jamais rejouées** : `buildReplayMessages` écarte les lignes `brain='FAST'`.
- **Note au tour suivant** (tapé ou vocal) : les lignes `FAST` de ce fil écrites depuis le dernier message non rapide
  sont jointes au message : `[Échanges avec Jarvis depuis ton dernier tour — réponses rapides, non vérifiées, plus
  récents d'abord : …]`, bornée à 1 500 caractères. Aucune : consigne inchangée à l'octet près.
- **Compagnon (SF-232-03)** : les réponses rapides ne sont pas annoncées (déjà dites), seulement les fins de tour.

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| `subjectId` d'un autre compte | introuvable, aucun appel modèle | 404 |
| `subjectId` absent | conversation globale (contexte sans sujet courant) | 200 |
| Reformulation non prise en 2 min | expirée, ne déteint pas sur un autre tour | — |
| Trace non écrite (panne base) | Jarvis répond quand même (best-effort) | — |

## Critères d'acceptation
- [x] Délégation sans sujet ⇒ question « Pour quel client… » (jamais de choix silencieux).
- [x] Fil vivant ⇒ annonce « J'ajoute ça à la recherche en cours ».
- [x] Le tour Opus délégué porte la reformulation dans son message, la ligne USER `DELEGATED`, la consigne système
  identique à un tour tapé.
- [x] Les lignes `FAST` ne sont jamais rejouées ; elles arrivent en note, plus récentes d'abord.
- [x] Une reformulation n'est prise qu'une fois, jamais par un autre compte ni un autre fil.
- [x] Le compagnon n'annonce pas les réponses rapides.

## Plan de test
- `AtelierChatServiceVoiceTurnTest` : rejeu sans `FAST` + note ; consigne inchangée sans échange ; reformulation +
  `DELEGATED` + système identique ; isolation de la reformulation (compte, fil).
- `JarvisFastBrainServiceTest` : sans sujet ⇒ question ; reformulation offerte au fil ; fil vivant ⇒ « J'ajoute » ;
  réponse rapide écrite, délégation non écrite.
- `JarvisAnnouncementFastBrainTest` : réponse rapide non annoncée.
- Régression : les 70 classes `AtelierChatService*Test` ; `JarvisFastBrainApiIntegrationTest` (migration exécutée).

## Tables / endpoints / composants
- `atelier_messages.brain` (varchar 16, nullable — migration `235-atelier-messages-brain.xml`).
- `POST /api/jarvis/converse` : `subjectId` facultatif.
- `AtelierMessage`, `AtelierChatService` (rejeu, note, reformulation), `JarvisDelegationRegistry`,
  `JarvisFastExchangeWriter` (nouveau), `JarvisFastBrainService`, `JarvisFastBrainController`,
  `JarvisAnnouncementService`.

## Préoccupations transversales
- **Contexte tenant** : `JarvisFastBrainController` (sujet par `findByIdAndUserId`), `JarvisFastExchangeWriter`
  (écrit sous le `user_id` du principal sur un sujet vérifié), `JarvisDelegationRegistry` (clé `user_id`),
  `AtelierChatService.withFastExchanges` (lecture `findByWorkspaceIdAndUserId…`), `JarvisAnnouncementService`
  (filtre `brain`).

## Hors périmètre
Écran (SF-235-05), badge du fil côté terminal et exposition de `brain` dans l'API du fil (SF-235-06), mesure (SF-235-07).

## Arbitrages (par défaut, réversibles)
- Le tour délégué est **ouvert par l'écran** (chemin `streamChat` inchangé, précision/parallèle gérés par
  `openOrSteer`) ; le serveur ne fait que poser la reformulation sur le fil : aucun second chemin de tour.
- Le `label` d'une délégation n'est pas persisté : la ligne USER `DELEGATED` est le lien vers le tour.
- Une délégation qui devient une précision ne consomme pas la reformulation (elle expire en 2 min).
