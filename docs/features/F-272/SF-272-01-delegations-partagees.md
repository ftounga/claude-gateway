# Mini-spec — [F-272 / SF-272-01] Délégations partagées entre les pods

## Identifiant
`F-272 / SF-272-01`

## Feature parente
`F-272` — Jarvis : corrections de la démo PO du 17/11

## Statut
`in-progress`

## Date de création
2026-11-18

## Branche Git
`feat/SF-272-01-delegations-partagees`

---

## Objectif
La demande reformulée par le cerveau rapide atteint toujours le tour Opus qu'elle vise, quel que soit le pod qui
l'exécute, et aucune ligne-lien « ↗ … en cours » ne reste figée.

## Cause racine (prouvée, démo du 17/11 00:00→00:05)
`JarvisDelegationRegistry` est en mémoire, clé `user|sujet|chantier`. Le cerveau rapide fait `offer` sur le pod A ;
le navigateur ouvre le tour Opus (`streamChat`, via JARVIS) qui fait `take` (`AtelierChatService` ~l.3708) — sur le
pod B dans 2 délégations sur 3 : `take` rend `null`, Opus reçoit la transcription brute (« Coupe… » au lieu de
« Dessine… »), `brain` reste NULL, la ligne-lien du fil Jarvis n'est jamais close.

## Comportement attendu

### Cas nominal
1. `route()` (cerveau rapide, pod A) range l'offre en base `jarvis_delegations` : `user_id`, `delegation_id`, sujet,
   chantier, demande reformulée, libellé, ligne-lien.
2. Le tour Opus du fil cible (pod B) la **prend** une seule fois : `UPDATE … SET taken_at WHERE taken_at IS NULL`
   (atomique, tous pods). Il joint la reformulation à son message (inchangé), `brain = DELEGATED`, et termine la
   ligne-lien à sa fin (inchangé).
3. Les délégations « en cours » (bloc du cerveau rapide « je regarde encore… ») se lisent en base : visibles depuis
   n'importe quel pod, closes à la fin du tour (`finished_at`).
4. Balayage chaque minute (idempotent, chaque pod) : offre jamais prise après 2 min → close, ligne-lien « échec » ;
   prise mais jamais finie après 2 h (pod arrêté) → close, ligne-lien « interrompu » ; lignes > 7 jours purgées.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Base indisponible à l'offre | repli en mémoire (comportement d'avant), jamais d'exception vers le cerveau rapide |
| Base indisponible à la prise | `null` (le tour part avec la transcription, comme avant), journal WARN |
| Deux tours concurrents sur deux pods | un seul obtient la reformulation |
| Offre d'un autre compte / autre chantier | jamais prise (`user_id`, `subject_id`, `chantier` dans la requête) |
| Suppression du compte | ses délégations purgées (`JarvisMailPurge.purgeUser`) |

## Critères d'acceptation
- [ ] Offerte sur une instance du registre, la reformulation est prise par une **autre** instance (test multi-instance).
- [ ] Deux prises concurrentes : exactement une gagne.
- [ ] Isolation : autre compte, autre chantier → `null` ; `running` d'un autre compte vide.
- [ ] Une délégation prise puis finie n'est plus « en cours » sur aucune instance.
- [ ] Une offre jamais prise est close par le balayage et sa ligne-lien passe à « échec » ; une prise jamais finie après
      2 h → « interrompu ».
- [ ] Sans magasin (tests unitaires existants), le registre en mémoire est inchangé.

## Plan de test
### Tests unitaires
Tests existants du registre en mémoire (`JarvisFastContextBuilderTest`, `JarvisFastBrainServiceTest`,
`JarvisFastBrainProvisionalTest`, `AtelierChatServiceVoiceTurnTest`) inchangés et verts.
### Tests d'intégration
`JarvisDelegationStoreIntegrationTest` (contexte Spring, H2) : deux registres = deux pods sur la même base ;
prise croisée, prise concurrente, en cours partagé, balayage (offre orpheline, prise périmée), purge.
### Isolation utilisateur
Même test : Bob ne prend ni ne voit rien d'Alice ; purge d'Alice sans toucher Bob.

## Tables / endpoints / composants impactés
- Table **nouvelle** `jarvis_delegations` (migration `272-01-jarvis-delegations.xml`), sans clé étrangère (purge
  explicite), unicité `(user_id, delegation_id)`, index `(user_id, subject_id, created_at)`.
- `JarvisDelegationRegistry` (magasin branché par setter), `JarvisDelegationStore`, `JarvisDelegation`,
  `JarvisDelegationRepository`, `AtelierChatService` (clôt la délégation à la fin du tour).
- Aucun endpoint.

## Préoccupations transversales
- Auth / Principal : non. Contexte tenant : non (le `user_id` vient de l'appelant, comme avant). Plans / limites : non.
  Navigation : non.

## Hors périmètre
- `live()` (précision d'un tour vivant) lit toujours `LiveTurnRegistry`, en mémoire par pod : une précision dite
  pendant un tour qui tourne sur l'autre pod ouvre une délégation au lieu d'une précision (pas observé dans la démo).
- Le contenu de la reformulation et la consigne : inchangés.

## Dépendances
Aucune.

## Notes et décisions
- **D1** Table dédiée plutôt que les lignes-liens d'`atelier_messages` : une délégation vers le fil Jarvis lui-même n'a
  pas de ligne-lien, et la reformulation n'a rien à faire dans un message affiché.
- **D2** Le registre garde son API et son repli en mémoire (setter, pas de second constructeur — mémoire « deux
  constructeurs »).
