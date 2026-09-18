# Mini-spec — F-125 / SF-125-06a — Le modèle ne comptabilise plus

## Identifiant
`F-125 / SF-125-06a`

## Feature parente
`F-125` — La tenue de la carte du poste : silencieuse, robuste, jamais dans la réponse

## Statut
`in-progress`

## Date de création
2026-09-18

## Branche Git
`feat/SF-125-06a-modele-ne-comptabilise-plus`

---

## Objectif
Retirer du paquet `savoir-durable` (prompt injecté + gabarits) toute obligation faite au modèle
d'émettre un marqueur `fin-de-tour` et de tenir une comptabilité de promotion/dette, en gardant la
fonction utile : répondre à la question et **écrire le durable dans la carte** au fil du tour.

---

## Comportement attendu

### Cas nominal
Au démarrage, `GovernancePackageSeeder` re-sème le paquet `savoir-durable` avec un contenu modifié :
`regles.md` (qui rejoint la consigne système à chaque tour), `GOUVERNANCE.md`, `plan-dashboard.md`,
les gabarits `STATE.md` / `PLAN-ACTION.md` et `explique.md` n'instruisent plus le modèle de poser un
marqueur `fin-de-tour` ni de déclarer promotion/dette. Le contenu diffère de la version stockée → la
version du paquet est **incrémentée** (mécanisme `updateIfChanged` existant). Le rôle du modèle,
énoncé par le paquet, se limite à : **répondre + ranger le durable dans le bon fichier de carte**.

### Cas d'erreur
| Situation | Comportement attendu |
|-----------|---------------------|
| Le texte de règles ne cite plus l'un des 3 invariants racine (`clonage/*`) | Le semeur **renonce** entièrement (garde-fou existant `ruleMissingFrom`) — la section « Où se rangent les dépôts » est donc **conservée** |
| Le texte de règles dépasse la borne `MAX_RULES_LENGTH` | Le semeur renonce (garde-fou existant) — le retrait de contenu ne fait que **réduire** la taille |
| Une ressource du paquet devient absente/illisible | Tout ou rien : le semeur renonce (garde-fou existant) |
| Contenu identique à la version stockée (redémarrage) | Aucune écriture, aucun incrément (idempotence existante) |

---

## Critères d'acceptation
- [ ] Aucun fichier du paquet `savoir-durable` (règles ni gabarits) n'instruit le modèle d'**émettre
      un marqueur `fin-de-tour`** ni de **déclarer / compter promotion ou dette** dans sa réponse.
- [ ] `regles.md` conserve : le principe « le travail est jetable, le savoir est durable », la carte
      du poste et ses fichiers, la règle « ranger le durable dans le bon fichier au fil du tour », la
      règle des livrables, et les **3 invariants racine** (`clonage/depot-dans-repos`,
      `clonage/projet-sans-git`, `clonage/note-hors-depot`).
- [ ] `GOUVERNANCE.md`, `plan-dashboard.md`, `STATE.md`, `PLAN-ACTION.md`, `explique.md` reformulés :
      plus d'exigence de marqueur ni de comptabilité imposée au modèle.
- [ ] Le suivi de la promotion/dette est décrit comme un **effet de bord serveur** (à partir des
      fichiers réellement écrits), pas un rituel du modèle.
- [ ] `GovernancePackageSeeder` re-sème la nouvelle version (test : contenu changé → version bumpée ;
      règles sans instruction de marqueur).
- [ ] Non-régression : F-126 (`<<essentiel>>`) intact ; discipline F-119 / doctrines F-120/121/125
      (`buildSystemPrompt`) intactes ; `STATE.md` conserve sa section `## Statut` (parsing F-95) ;
      `PLAN-ACTION.md` ne porte **aucune** case `- [ ]` (pas de dette dès le dépôt).

---

## Périmètre

### Hors scope (explicite)
- Le comportement des contrôles de fin de tour (`JugeFinDeTourControl`,
  `PromotionDetteBloquanteControl`) → **SF-125-06b**.
- Le fond de la cartographie (F-119) et la distinction question/action (F-120) : inchangés.
- Retirer la carte du poste : non. On garde la carte, on retire le rituel de comptabilité imposé.

---

## Technique

### Endpoint(s)
Aucun.

### Tables impactées
Aucune. Le paquet est re-semé au démarrage (pas de migration de données ; version incrémentée en base
par le mécanisme existant).

### Migration Liquibase
- [x] Non applicable

### Composants impactés
- `backend/src/main/resources/governance/savoir-durable/regles.md` (→ consigne système, chaque tour)
- `backend/src/main/resources/governance/savoir-durable/GOUVERNANCE.md`
- `backend/src/main/resources/governance/savoir-durable/plan-dashboard.md`
- `backend/src/main/resources/governance/savoir-durable/STATE.md`
- `backend/src/main/resources/governance/savoir-durable/PLAN-ACTION.md`
- `backend/src/main/resources/governance/savoir-durable/explique.md`
- `GovernancePackageSeeder` (versionnage/re-seed : **mécanisme existant, non modifié**)

### Préoccupations transversales
Aucune (Auth / tenant / plans / routing non touchés). Le paquet ne porte pas de `user_id` ; il est
re-semé identiquement pour tous. La consigne système est construite par `buildSystemPrompt` pour le
couple `(userId, workspaceId)` du tour — inchangé.

---

## Plan de test

### Tests unitaires
- [ ] `GovernancePackageSeederTest.firstPassCreatesAndPublishes` : les règles semées ne contiennent
      **plus** l'instruction de marqueur `fin-de-tour` (assertion inversée) et conservent « le travail
      est jetable, le savoir est durable ».
- [ ] `GovernancePackageSeederTest` : le gabarit `STATE.md` ne prescrit plus de comptabilité de
      promotion/dette bloquante (assertion mise à jour) et garde sa section `## Statut`.
- [ ] `GovernancePackageSeederTest.theProjectMapTemplateCarriesNoDebt` : `PLAN-ACTION.md` sans
      `- [ ]` (non-régression, inchangé).
- [ ] `GovernancePackageSeederTest.theRulesDocumentCitesTheThreeInvariants` /
      `theRulesStayUnderTheLimit` : verts (non-régression des garde-fous).
- [ ] `GovernancePackageSeederTest.changedContentBumpsTheVersion` : vert (re-seed → version bumpée).

### Tests d'intégration
- [ ] `GovernanceSeededPackageIntegrationTest` : le paquet se sème (v1 sur base propre), règles
      contiennent le principe, 11 fichiers, 5 contrôles (inchangé — 06a ne touche pas les contrôles).

### Isolation workspace
- [x] Non applicable — le paquet est un contenu produit, sans `user_id` ; la construction de la
      consigne reste bornée au couple `(userId, workspaceId)` du tour (inchangé).

---

## Notes et décisions
- Le marqueur `<<essentiel>>` (F-126) est du **contenu**, distinct du marqueur `fin-de-tour`
  (métadonnée) : il **reste** intact.
- SF-125-06b enlèvera ensuite la dépendance des contrôles au marqueur ; 06a ne touche que le
  contenu du paquet (prompt + gabarits).
- Le « prompt » visé par le cadrage est, en pratique, `regles.md` : `buildSystemPrompt` n'émet aucune
  obligation de marqueur en dur — l'exigence venait uniquement du paquet injecté.
