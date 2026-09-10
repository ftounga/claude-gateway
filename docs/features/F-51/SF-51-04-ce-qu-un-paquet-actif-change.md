# Mini-spec — F-51 / SF-51-04 — Ce qu'un paquet actif change

## Identifiant

`F-51 / SF-51-04`

## Feature parente

`F-51` — Catalogue de gouvernance

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-51-04-regles-et-controles`

---

## Objectif

Faire enfin **agir** un paquet actif : ses règles rejoignent la consigne système du projet, et ses
contrôles se branchent sur les deux crochets posés par F-50.

---

## Comportement attendu

### Les règles

1. À chaque tour, la consigne système du projet gagne un bloc
   `--- Règles de gouvernance (paquets actifs) ---`, composé des règles des paquets **actifs sur ce
   projet**, dans l'ordre d'activation, chacune sous le nom de son paquet.
2. Le bloc est placé **après** les conventions du projet (`CLAUDE.md`) et **avant** le catalogue de
   skills : les conventions du projet restent ce qu'on lit en premier ; la gouvernance les complète,
   elle ne les remplace pas.
3. Sans paquet actif, **rien** n'est ajouté — la consigne est celle d'avant, à l'octet près.
4. Le bloc est **borné** : au-delà, il est coupé avec une mention explicite. Une gouvernance
   bavarde ne doit pas chasser les conventions du projet hors de la fenêtre.

### Les contrôles

5. Deux contrôles de gouvernance sont enregistrés auprès de F-50 — un par crochet
   (`AFTER_FILE_WRITE`, `END_OF_TURN`). Chacun résout les paquets actifs sur le projet du contexte et
   interroge, **dans l'ordre du catalogue**, les contrôles serveur qu'ils citent.
6. Le **premier verdict bloquant l'emporte** ; les suivants ne sont pas interrogés. C'est la règle
   déjà posée par F-50 : un blocage vaut **une** correction.
7. Un identifiant de contrôle que le produit ne fournit plus est **ignoré** — le paquet a pu être
   publié avant un changement de version, et punir l'utilisateur pour cela n'aurait aucun sens.
8. **Sans paquet actif, aucune lecture n'est faite** : le crochet rend « passe » immédiatement.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Projet illisible / activation introuvable | Aucune règle ajoutée, aucun contrôle interrogé. La boucle continue comme avant — une gouvernance en panne ne condamne pas le projet d'un utilisateur |
| Un contrôle lève | **Ignoré**, comme le veut F-50 (`AtelierCheckpointRunner`) |
| Contexte sans utilisateur ni projet | « Passe » : on n'invente pas un propriétaire pour aller chercher des règles |
| Règles vides ou blanches | Le paquet ne contribue rien au bloc ; s'il est le seul actif, aucun bloc n'apparaît |

---

## Critères d'acceptation

- [ ] Un projet sans paquet actif produit **exactement** la consigne système d'avant.
- [ ] Un projet avec un paquet actif porteur de règles voit apparaître le bloc, nommé, après les
      conventions du projet.
- [ ] Deux paquets actifs apparaissent tous les deux, dans l'ordre d'activation.
- [ ] Un paquet actif **sans** règles n'ajoute pas de titre vide.
- [ ] Le bloc est coupé à sa borne, avec une mention lisible.
- [ ] Le contrôle d'écriture interroge les contrôles cités par les paquets actifs, et **seulement**
      ceux-là.
- [ ] Le premier verdict bloquant est rendu ; les contrôles suivants ne sont pas appelés.
- [ ] Un identifiant inconnu du registre est ignoré sans faire échouer les autres.
- [ ] Sans paquet actif, le crochet rend « passe » **sans aucune lecture en base**.
- [ ] Les règles ne sont lues que pour le couple `(userId, workspaceId)` du tour.

---

## Périmètre

### Hors scope (explicite)

- Écrire des contrôles : **aucun** n'est livré ici, le registre reste vide. F-52 apportera les
  premiers.
- Les écrans → SF-51-05 / SF-51-06.
- Rendre les crochets configurables autrement que par un paquet : F-50 l'interdit, F-51 ne l'ouvre
  pas.

---

## Impacts

### Tables

**Aucune migration.**

### Endpoints

**Aucun.** Cette subfeature ne change que ce qui se passe *pendant* un tour.

### Composants

| Composant | Changement |
|---|---|
| `ProjectRulesSource` (atelier) | **créé** — l'interface minimale que la boucle consulte ; `NONE` par défaut, exactement comme `AtelierCheckpointRunner.none()` (F-50). Elle évite de faire dépendre `AtelierChatService` du module gouvernance |
| `AtelierChatService.buildSystemPrompt` | ajoute le bloc de règles quand il y en a |
| `GovernanceRulesProvider` (governance) | **créé** — implémente `ProjectRulesSource` : compose, nomme et borne le bloc |
| `GovernanceWriteCheckpoint`, `GovernanceEndOfTurnCheckpoint` | **créés** — les deux `AtelierCheckpoint` qui délèguent aux contrôles des paquets actifs |
| `GovernanceActivationService` | une lecture interne de plus, déjà bornée à l'utilisateur |

---

## Arbitrages de cette subfeature

| # | Sujet | Décision | Motif | Réversible |
|---|---|---|---|---|
| C1 | Où placer le bloc de règles | **Après** `CLAUDE.md`, **avant** les skills | Les conventions du projet sont ce que l'utilisateur a écrit pour ce projet précis ; la gouvernance les complète. Les mettre avant reviendrait à dire qu'un paquet prime sur le projet | oui |
| C2 | Gouvernance en panne | La boucle continue **sans** règles ni contrôles | Même repli que F-50 : un bogue de gouvernance ne condamne pas le travail d'un utilisateur, qui n'a rien pour le débrayer | oui |
| C3 | Une interface plutôt qu'une dépendance directe | `ProjectRulesSource` | Reprend le geste de F-50. `AtelierChatService` n'a pas à connaître le catalogue ; et les tests de la boucle continuent de se monter sans module gouvernance | oui |
| C4 | Borne du bloc | 12 000 caractères, mention de coupe | La consigne système entière a déjà sa borne (`SYSTEM_MAX_CHARS`) : sans borne propre, la gouvernance pourrait, à elle seule, en consommer la totalité et faire disparaître les conventions du projet **en silence** | oui |

---

## Plan de test minimal

### Unitaires

- `GovernanceRulesProviderTest` : aucun paquet → `null` ; un paquet → bloc nommé ; deux paquets →
  ordre d'activation ; paquet sans règles → rien ; borne et mention de coupe ; aucune lecture quand
  il n'y a pas d'activation.
- `GovernanceCheckpointTest` : contrôles des paquets actifs interrogés ; identifiant inconnu ignoré ;
  premier blocage rendu et suivants non appelés ; contexte sans projet → « passe » ; erreur de lecture
  → « passe ».
- `AtelierChatServiceGovernanceRulesTest` : la consigne système porte le bloc, placé après les
  conventions ; sans source de règles, la consigne est inchangée.

### Intégration

Le comportement bout en bout d'un contrôle bloquant est déjà couvert par F-50
(`AtelierChatServiceEndOfTurnCheckpointTest`) : cette subfeature ne change pas ce que fait un blocage,
seulement **qui** est interrogé. Les tests portent donc sur la sélection des contrôles et la
composition des règles.

### Isolation

- Les règles et les contrôles sont résolus à partir du **couple `(userId, workspaceId)` du contexte**,
  et le test vérifie qu'un projet d'un autre utilisateur ne rapporte **aucune** règle ni contrôle.

---

## Contraintes de validation

Borne du bloc de règles : 12 000 caractères (mention de coupe explicite). Les règles d'un paquet sont
déjà bornées à 8 000 caractères à la publication (SF-51-01). Aucune question ouverte impactée.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Analyse |
|---|---|---|
| Auth / Principal | non | Aucun changement ; le contexte porte déjà l'utilisateur du tour |
| **Contexte tenant** | **oui** | Deux nouveaux points de lecture des activations. Composants concernés : `GovernanceRulesProvider` et les deux contrôles, qui reçoivent `(userId, workspaceId)` du contexte de F-50 — lequel est construit **après** `requireOwned` dans la boucle et le documente. Aucun autre composant ne change de façon de résoudre le tenant |
| Plans / limites | non | Aucun quota, aucun gate nouveau. Le temps passé dans un contrôle reste pris sur le budget de tour, comme F-50 l'a établi |
| Navigation / routing | non | Aucun écran |
