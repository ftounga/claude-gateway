# Mini-spec — F-89 / SF-89-02 — Le genre de bloc nouveau : carte, moment, liste

## Identifiant

`F-89 / SF-89-02`

## Feature parente

`F-89` — Le volet Teams — le terminal Teams

## Statut

`done` — mergée le 2026-09-13 (PR #465)

## Date de création

2026-09-12

## Branche Git

`feat/SF-89-02-les-blocs-riches`

---

## Objectif

Étendre **ce qu'un terminal sait afficher** : à côté du bloc textuel, trois blocs vérifiables — la
**carte de réunion**, le **moment** (une image posée à côté de la phrase prononcée pendant qu'elle
était affichée) et la **liste** (engagements, mentions) — produits par l'agent, **et qui n'existent
que dans un terminal Teams**.

---

## Contexte

`AtelierTerminalBlock` ne porte que `output: string`. **Ce n'est donc pas de la mise en forme : il
faut étendre le modèle du fil.** Le PO l'a tranché après avoir demandé *« dans le terminal, on verra
des captures d'écran ? »* — la réponse était non, et elle devait devenir oui.

**La règle qui prime sur toutes les autres dans ce volet** : *échouer bruyamment, jamais à moitié
faux*. Un compte rendu plausible et faux est pire qu'un compte rendu qui refuse : on décide dessus.
Cette subfeature traduit cette règle en trois obligations, toutes **vérifiées à l'émission** et non
à l'affichage :

1. **Chaque ligne porte son message source et son lien.** Une ligne sans source n'entre pas dans un
   bloc — elle est refusée, et l'agent reçoit de quoi se corriger.
2. **Ce qui est lu est exact, ce qui est déduit ne l'est pas** : chaque ligne dit son niveau de
   certitude **en toutes lettres** — « explicite » ou « à confirmer ». **Jamais un score.** Un
   chiffre donne une apparence de mesure à une interprétation.
3. **Chaque bloc porte la fenêtre réellement lue et ce qui n'a pas pu l'être** (D4 et §3.3 du
   cadrage). Un trou se voit ; un trou silencieux ne se voit jamais.

---

## Comportement attendu

### Cas nominal

1. Dans un **terminal Teams**, l'agent a lu ce qu'il pouvait lire (outils de F-88). Il rend son
   travail en appelant un **outil de présentation** :

   | Outil | Ce qu'il pose dans le fil |
   |---|---|
   | `teams_meeting_card` | la **carte de réunion** : des sections (« Ce qu'on attend de vous », « Décisions », « Vos engagements »), chacune faite de lignes sourcées |
   | `teams_list` | la **liste** : engagements ou mentions, une seule suite de lignes sourcées |
   | `teams_moments` | les **moments** : une image posée à côté de la phrase prononcée pendant qu'elle était affichée, rapprochées par l'horodatage |

2. La gateway **valide** l'appel (voir « Ce qui est refusé »), construit le bloc, le **relaie au fil
   de l'eau** (événement SSE `card`) et l'**écrit dans la transcription du tour** — donc il survit
   au rechargement, comme tout le reste du fil depuis SF-39-17.
3. Le modèle reçoit en retour un **compte rendu de ce qui a été retenu** : combien de lignes, combien
   explicites, combien à confirmer, et la fenêtre annoncée. C'est ce qui lui permet de se corriger
   lui-même sans qu'on ait à le deviner à l'écran.
4. L'écran l'affiche (SF-89-03).

### Ce qui est refusé, et ce que l'agent reçoit pour se corriger

Tous ces refus sont des **résultats d'outil en erreur** : le tour continue, l'agent est nommé
responsable de la correction, et **aucun bloc n'est posé**.

| Situation | Refus |
|---|---|
| Une ligne **sans message source ni lien** | « chaque ligne doit porter son message source (`messageId`) ou son lien (`webUrl`) » |
| Une ligne **au texte vide** | refusée : une ligne vide sourcée reste une ligne vide |
| **Fenêtre réellement lue absente** (`window` vide) | refusé : un compte rendu sans sa fenêtre laisse croire qu'il couvre tout |
| **Manques non déclarés** (`gaps` absent) | refusé : l'agent doit dire ce qui n'a pas pu être lu, quitte à dire qu'aucun manque n'a été signalé |
| Un **moment sans horodatage ou sans citation** | refusé : c'est l'horodatage qui rapproche l'image de la phrase — sans lui il n'y a pas de moment |
| Un **moment dont l'image est inconnue** ou appartient à quelqu'un d'autre | refusé, et **indiscernables** : on ne dit pas à un compte qu'une image existe ailleurs |
| **Bloc hors gabarit** (trop de lignes, de sections, de moments, texte trop long) | refusé avec les bornes exactes : on ne tronque pas un compte rendu en silence — un engagement perdu par troncature ne se voit pas |
| L'outil est appelé **hors d'un terminal Teams** | refusé : *un terminal de projet reste textuel pour toujours* |

**Le niveau de certitude absent vaut « à confirmer »**, jamais « explicite » : en cas de doute on
penche du côté qui n'affirme rien. Le compte rendu rendu au modèle le dit, pour qu'il puisse préciser.

### La règle non négociable, tenue à deux niveaux

Un **terminal de projet reste textuel pour toujours**. Une sortie de commande est exactement ce que
la machine a répondu, jamais une carte.

1. **Les outils ne sont pas déclarés** hors d'un terminal Teams (garde de SF-89-01,
   `TeamsToolCatalog`) ;
2. **et s'ils sont tout de même appelés**, ils sont refusés. Le second niveau n'est pas
   redondant : la boucle relaie les outils non déclarés plutôt que de les refuser d'emblée, et un
   modèle peut nommer un outil qu'on ne lui a pas donné.

Deux tests le verrouillent, un par niveau.

### Les images d'un moment

Une image vit dans le **stockage d'objets**, sous une clé qui porte son propriétaire et son
terminal : `teams-moments/{userId}/{workspaceId}/{imageId}.{ext}`. L'endpoint de lecture
**reconstruit la clé** à partir de l'utilisateur authentifié — un identifiant reçu du client ne peut
donc jamais atteindre l'image d'un autre.

**D2 — elles vivent avec le compte rendu et sont supprimées avec lui** : supprimer le terminal Teams
efface ses images, dans le même flux. Aucun cache de conversations de clients (D2) : rien d'autre
n'est conservé.

**Qui les dépose** : F-90 (les captures alignées), qui les extrait aux changements de plan sur la
machine et n'en fait remonter que les 20 à 60 retenues. F-89 livre **le dépôt, la lecture et
l'effacement** — donc tout ce qu'il faut pour qu'un moment s'affiche et s'agrandisse — et **aucun
producteur** : il n'y a rien à extraire tant que F-90 n'existe pas, et un endpoint de dépôt ouvert
sans producteur serait une surface offerte pour rien.

---

## Critères d'acceptation

- [ ] Dans un terminal Teams, `teams_meeting_card` pose une carte dans le fil : elle est relayée en
      SSE **et** relue après un rechargement.
- [ ] `teams_list` et `teams_moments` font de même, chacun avec sa forme.
- [ ] **Chaque ligne porte son auteur, son heure, son message source et son lien.** Une ligne sans
      source fait refuser l'appel entier, et aucun bloc n'est posé.
- [ ] **Le niveau de certitude est un mot** — `EXPLICITE` ou `A_CONFIRMER` — et il n'existe **aucun
      champ numérique** de confiance nulle part dans le modèle ni dans les schémas d'outils. Un test
      le prouve en parcourant les schémas.
- [ ] Un niveau absent vaut `A_CONFIRMER`.
- [ ] Chaque bloc porte la **fenêtre réellement lue** et la liste de ce qui **n'a pas pu être lu** ;
      les deux sont **obligatoires** à l'émission.
- [ ] **Sur un terminal de projet, aucun bloc riche ne peut exister** : les outils ne sont pas
      déclarés, et appelés quand même ils sont refusés. Deux tests, un par niveau.
- [ ] Un moment dont l'`imageId` est inconnu — ou appartient à un autre compte — fait refuser
      l'appel, sans distinguer les deux cas.
- [ ] `GET /workspaces/{id}/teams/moments/{imageId}` rend l'image de **son** propriétaire, et `404`
      pour toute autre.
- [ ] Supprimer le terminal Teams efface ses images.
- [ ] Un bloc hors gabarit est **refusé avec ses bornes**, jamais tronqué.
- [ ] La transcription d'un tour reste bornée : un bloc riche compte comme un bloc.

---

## Périmètre

### Hors scope (explicite)

- **L'affichage** : la peau, le rendu des trois blocs, l'agrandissement d'une image → SF-89-03.
- **La production des images** (extraction aux changements de plan, alignement) → F-90.
- **Les outils de lecture** (`teams_read_conversation`, `teams_mentions`…) → F-88.
- L'édition d'un bloc déjà posé, ou sa suppression individuelle.
- Tout bloc riche **hors** terminal Teams — c'est l'interdit de la feature.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `AtelierTurnReport.Block.card` | `null` | Tout bloc antérieur reste un bloc textuel, relu tel quel. |
| `certainty` d'une ligne | `A_CONFIRMER` | Le doute ne devient jamais une affirmation par défaut. |
| `gaps` d'un bloc | — | **Obligatoire** : il n'y a pas de valeur par défaut au silence. |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur / cardinalité max | Format / Valeurs |
|-------|-------------|---------------------------|------------------|
| `title` | Oui | 200 | texte |
| `window` | Oui | 300 | texte — la fenêtre **réellement** lue |
| `gaps[]` | Oui (peut être vide) | 20 × 300 | textes |
| `sections[]` | Oui (carte, liste) | 10 | — |
| `sections[].title` | Oui | 120 | texte |
| `lines[]` (par bloc) | ≥ 1 | **100** au total | — |
| `line.text` | Oui | 500 | texte |
| `line.author` | Non | 120 | texte |
| `line.at` | Non | 40 | horodatage ISO-8601 tel que rendu par l'adaptateur |
| `line.messageId` **ou** `line.webUrl` | **Au moins un** | 200 / 1000 | identifiant opaque / URL `https` |
| `line.certainty` | Non | — | `EXPLICITE` \| `A_CONFIRMER` (défaut) |
| `moments[]` | ≥ 1 | **60** | — |
| `moment.at` | Oui | 40 | horodatage ISO-8601 |
| `moment.quote` | Oui | 500 | texte |
| `moment.imageId` | Non | 64 | doit exister **et** appartenir à l'appelant |

**Pourquoi 100 lignes et 60 moments.** 60 est le haut de la fourchette mesurée au cadrage
(« 20 à 60 images utiles » pour une heure de réunion) ; 100 lignes est largement au-delà d'un compte
rendu lisible — au-delà, ce n'est plus un compte rendu, c'est un export, et il faut le dire plutôt
que de le rendre.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/workspaces/{id}/teams/moments/{imageId}` | Oui | propriétaire du terminal |

### Outils exposés à l'agent (terminal Teams **et** droit Teams uniquement)

`teams_meeting_card`, `teams_list`, `teams_moments` — ajoutés au `TeamsToolCatalog` de SF-89-01, donc
gardés au même endroit et par la même règle.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `atelier_messages` | UPDATE du contenu de `terminal_json` | **aucun changement de schéma** : le relevé de tour y est déjà rangé (SF-39-15), le bloc y gagne un champ |

**Aucune migration Liquibase.** Le bloc riche voyage dans une colonne qui existe déjà et dont le
contenu est du JSON tolérant aux champs inconnus : un bloc antérieur se relit à l'identique, et un
bloc riche relu par un backend antérieur redeviendrait un bloc textuel plutôt que de casser.

### Stockage

`WorkspaceStorage` (abstraction existante, S3 en cluster, mémoire en test), préfixe
`teams-moments/{userId}/{workspaceId}/`. Aucun accès direct à S3 (Provider Independence).

---

## Plan de test

### Tests unitaires

- [ ] `TeamsBlockCards` — une carte valide est construite ; sections, lignes et manques conservés.
- [ ] Refus : ligne sans source ; ligne vide ; `window` vide ; `gaps` absent ; moment sans
      horodatage ; moment sans citation ; dépassement de chaque borne.
- [ ] Certitude : `EXPLICITE` conservée ; absente ⇒ `A_CONFIRMER` ; valeur inconnue ⇒ refus.
- [ ] **Aucun champ numérique de confiance** dans le modèle ni dans les trois schémas d'outils.
- [ ] `TeamsMomentImages` — clé calculée à partir de l'utilisateur, jamais du client ; type déduit
      d'une liste blanche d'extensions ; suppression par préfixe.
- [ ] `AtelierChatService` — sur un **terminal de projet**, l'appel d'un outil de présentation est
      **refusé** et n'écrit **aucun** bloc riche dans la transcription.
- [ ] `AtelierChatService` — sur un terminal Teams, l'appel pose le bloc, le relaie au listener et
      l'écrit dans la transcription.

### Tests d'intégration

- [ ] Un tour de terminal Teams qui appelle `teams_meeting_card` : la carte est dans la réponse
      relue (`terminal_json`) après rechargement.
- [ ] `GET /workspaces/{id}/teams/moments/{imageId}` rend l'image ; celle d'un autre compte → `404`.
- [ ] Supprimer le terminal Teams efface ses images.

### Isolation workspace

- [x] Applicable — la clé de stockage est **reconstruite** à partir du `userId` du contexte de
      sécurité et du workspace relu par `requireOwned` ; un `imageId` reçu du client n'est qu'un
      dernier segment, jamais un chemin.

---

## Dépendances

### Subfeatures bloquantes

- **SF-89-01** (livrée) : le terminal Teams et le catalogue gardé.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés et vérification |
|---|---|---|
| Auth / Principal | Non | `currentUser.requireId()` comme partout |
| Contexte tenant | **Oui** — un nouveau stockage adressé par clé | `TeamsMomentImageService` (clé construite depuis le `userId` du contexte), `TeamsMomentController` (`requireOwned` sur le workspace avant toute lecture), `WorkspaceService.delete` (effacement du préfixe) |
| Plans / limites | Non — aucun droit nouveau : la garde est celle de SF-89-01 | `TeamsToolCatalog` (trois entrées de plus, même garde) |
| Navigation / routing | Non (backend) | — |
| **Transcription et rejeu** (F-30 / F-39 / F-84) | **Oui** | `AtelierTurnReport.Block` gagne un champ **additif** ; `MAX_BLOCKS` inchangé ; un bloc riche compte comme un bloc ; la reprise de F-84 relaie l'événement `card` comme les autres |

---

## Notes et décisions

- **D-89-7 — l'agent compose le bloc, il ne le reçoit pas tout fait.** Un bloc construit
  automatiquement à partir du résultat d'un outil de lecture ne saurait pas dire *ce qu'on attend de
  vous* : c'est une lecture, pas une liste de messages. En le faisant composer par l'agent, on peut
  **exiger** de lui la source de chaque ligne — ce qu'aucune mise en forme automatique ne permettrait.
- **D-89-8 — la validation est à l'émission, jamais à l'affichage.** Un écran qui filtre les lignes
  sans source afficherait un compte rendu amputé sans le dire : exactement le « à moitié faux » que
  le volet interdit. On refuse en amont, et l'agent corrige.
- **D-89-9 — refuser plutôt que tronquer.** Un compte rendu tronqué perd des engagements en
  silence. Les bornes sont dites dans le refus.
- **D-89-10 — pas de champ de score, nulle part.** Ce n'est pas une consigne de rédaction : le champ
  n'existe pas dans le modèle, et un test parcourt les schémas d'outils pour qu'il ne réapparaisse
  jamais.
- **D-89-11 — les images sont livrées sans producteur, et c'est assumé.** F-89 livre le dépôt, la
  lecture et l'effacement ; F-90 livre l'extraction. Ouvrir un endpoint de dépôt public sans
  producteur offrirait une surface pour rien.
