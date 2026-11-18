# Mini-spec — [F-272 / SF-272-02] Ordres d'écran vérifiés par la passerelle

## Identifiant
`F-272 / SF-272-02`

## Feature parente
`F-272` — Jarvis : corrections de la démo du 17/11

## Statut
`in-progress`

## Date de création
2026-11-18

## Branche Git
`feat/SF-272-02-ordres-ecran-verifies`

---

## Objectif
Ce que le cerveau rapide reçoit d'un ordre d'écran (`afficher`) est ce qui va apparaître, ou le vrai refus : plus de
« Voici… » suivi d'un « Pardon » de l'écran.

## Cause racine (prouvée)
`JarvisFastBrainService.checkShow` ne vérifiait que l'action `univers` (F-253). Démo du 17/11 : 00:01:22 « Voici… »
puis l'écran `show_refused piece NOT_FOUND` (la pièce lue dans data-platform, cherchée par l'écran dans « Terminal du
poste ») ; 00:03:00 « en 3D » → `show_refused vue NOT_APPLICABLE` (aucun schéma ouvert : `resolveArch`).

## Comportement attendu

### Cas nominal
1. **Pièces nommées** (`piece`, `galerie`… avec `pieces`) : confrontées à l'index des pièces du compte (lecture
   fraîche, `user_id`, `requireOwned`). Cherchées dans le sujet de la portée, sinon le sujet en vue ; puis (demande
   seulement) dans le sujet que l'outil `pieces` vient de lire. Le sujet trouvé est **écrit dans la portée** de l'ordre
   relayé (l'écran n'interroge plus son propre sujet). Pièces introuvables retirées (« ok (n non montrées) »).
2. **Galerie par portée** : refus « rien à montrer : aucun schéma d'architecture dans « X » ; propose d'en dessiner un »
   quand rien ne correspond ; refus « aucun sujet en vue » sans sujet ni date.
3. **Pilotage d'architecture** (`vue`, `focus`, `flux`, `replier`, `deplier`, `eclater`, `rassembler`) sans ligne
   « Architecture « … » » dans le bloc `## Écran` : le schéma d'architecture le plus récent du sujet est ouvert
   **d'abord** (ordre `piece` relayé avant l'ordre de vue, l'écran attend le graphe — SF-238-10) ; résultat
   « ok (schéma « titre » ouvert, vue 3d) ». Recette « montre l'archi en 3D » = ouvrir la pièce `diag:` puis vue 3d.
4. Un refus dans l'appel relance le modèle **une fois** (comme l'annonce suspendue F-263) : il choisit une pièce de la
   liste rendue ou dit le vrai refus.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Pièce absente partout | refus + pièces disponibles du sujet (≤ 5, même genre d'abord) |
| Aucun schéma d'architecture pour une vue | refus « propose d'en dessiner un » |
| Sujet d'un autre compte dans la portée | traité comme inconnu (aucune pièce lue), refus |
| `courriel:` / `agenda:` | non vérifiés (ouverts par la passerelle), inchangés |
| Lecture en échec | l'ordre passe tel quel (comportement d'avant) |
| Banc du cerveau rapide (`bench`) | pas de vérification (aucune donnée) |

## Critères d'acceptation
- [ ] `piece diag:x` lu dans data-platform alors que l'écran est sur un autre sujet : ordre relayé avec
      `portee.sujet = data-platform`, résultat « ok ».
- [ ] `piece` d'un identifiant inexistant : aucun ordre relayé, refus listant les pièces disponibles.
- [ ] `vue 3d` sans schéma ouvert, schéma présent : deux ordres relayés (`piece diag:…` puis `vue 3d`).
- [ ] `vue 3d` sans schéma ouvert ni schéma : refus « propose d'en dessiner un ».
- [ ] `vue 3d` avec la ligne Architecture dans `## Écran` : relayé tel quel.
- [ ] Galerie `type architecture` d'un sujet sans schéma : refus « rien à montrer ».
- [ ] Isolation : un sujet de Bob dans la portée d'Alice → refus, aucune pièce de Bob lue.
- [ ] Après un refus, un second appel au modèle a lieu (au plus une fois).

## Plan de test
### Tests unitaires
`JarvisShowVerifierTest` (service des pièces simulé) : tous les cas ci-dessus. `JarvisFastBrainServiceTest` : ordres
relayés dans l'ordre, relance après refus.
### Tests d'intégration
Banc à vraie voix F-256, scénario `demo-1711` (« présente-moi l'architecture », « en 3D ») : 0 refus d'écran, 0
« Pardon », « Voici » toujours avec image ; 2 passages, boucle legacy.
### Isolation utilisateur
`JarvisShowVerifierTest` : sujet d'un autre compte (`requireOwned` lève) ⇒ refus.

## Tables / endpoints / composants impactés
Aucune table, aucun endpoint. `JarvisShowVerifier` (nouveau), `JarvisFastBrainService` (boucle d'outil),
`JarvisPieceService.listFresh` (lecture qui contourne le cache de 60 s).

## Préoccupations transversales
Aucune (pas d'auth, de tenant, de plan, de route). Préfixe de cache : **inchangé** (aucune consigne ni description
d'outil modifiée — seul le résultat d'outil, dans le message).

## Hors périmètre
Retenir la phrase déjà dite avant l'appel d'outil (la porte de phrases la diffuse au fil de l'eau) ; les vérifications
de navigation dans une galerie déjà ouverte (`selection`, `aller`…).

## Dépendances
SF-272-01 (même feature, indépendante).
