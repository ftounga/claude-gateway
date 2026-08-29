# Mini-spec — [F-30 / SF-30-13] Ligne vivante « travail en cours » dans le terminal

---

## Identifiant

`F-30 / SF-30-13`

## Feature parente

`F-30` — Atelier, expérience terminal. Complète l'indicateur d'activité de SF-30-02 et le décompte de
fin de tour de SF-30-05.

## Statut

`ready`

## Date de création

2026-08-29

## Branche Git

`feat/SF-30-13-ligne-vivante-backend` puis `feat/SF-30-13-ligne-vivante-front`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Montrer, **à l'endroit où l'utilisateur regarde défiler la sortie**, qu'un tour est en cours et où il
en est : ce que l'agent fait à l'instant, combien d'étapes sont derrière lui, ce qu'il a consommé, et
depuis combien de temps.

---

## Contexte

Pendant un run, la seule marque d'activité est un spinner de **16 px accompagné d'un chrono**, dans
la **barre du haut** (`atelier-terminal.component.html:10`). L'utilisateur, lui, a les yeux au bas du
fil, là où la sortie s'écrit. Entre deux commandes longues, rien ne bouge dans son champ de vision :
il ne sait pas si ça travaille ou si c'est figé — c'est exactement ce qui a été rapporté le
2026-08-29 (« on aurait dit que le terminal ne répond même pas », alors constaté comme un défaut
distinct, corrigé par SF-30-11).

**Forme retenue par l'owner le 2026-08-29** : une **ligne vivante en bas du fil**, plutôt qu'un
bandeau collant. Contenu retenu : **les trois** — action en cours, nombre d'étapes déjà faites, et
tokens consommés — aux côtés du chrono.

### Ce qui est déjà disponible, et ce qui manque

| Élément | Source | État |
|---|---|---|
| Chrono | `elapsedLabel`, déjà calculé | **disponible** |
| Action en cours | dernier bloc de `streaming.blocks` sans résultat | **disponible côté écran**, aucun appel à ajouter |
| Nombre d'étapes | nombre de blocs portant une commande ou un outil | **disponible côté écran** |
| Tokens consommés | `GET /v1/sessions/{id}` → `usage` | **à relayer** : le backend ne le lit qu'en **fin** de tour |

Le fournisseur rapporte un **cumul depuis l'ouverture de la session** ; `AtelierSessionService`
mémorise déjà la base de comparaison sur le workspace (`agentInputTokens`, `agentOutputTokens`) pour
n'imputer que le **delta** du tour (SF-30-04, F-36 / SF-36-02). La même soustraction donne le compteur
à afficher.

---

## Comportement attendu

### Cas nominal

1. Dès qu'un tour démarre, une ligne apparaît **à la suite de la sortie** : spinner animé, libellé de
   l'action en cours, nombre d'étapes, tokens, chrono.
2. Le libellé suit l'agent : `bash: mvn clean install`, `lecture de src/App.tsx`… — c'est le dernier
   outil annoncé dont la sortie n'est pas encore arrivée.
3. Le nombre d'étapes s'incrémente à chaque commande ou outil du tour.
4. Le compteur de tokens progresse **pendant** le run, relevé au plus une fois toutes les
   `progress-interval` secondes (défaut **5 s**).
5. La ligne **disparaît** dès la fin du tour ; le décompte définitif reste porté par la ligne de coût
   de SF-30-05.
6. Le bouton « Interrompre » de la barre du haut reste le seul point d'arrêt : la ligne informe, elle
   n'agit pas.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucune action encore annoncée (début du tour) | La ligne s'affiche quand même : spinner, « démarrage… », 0 étape, chrono. Ne rien montrer laisserait croire à un blocage — c'est le défaut qu'on corrige |
| Relevé d'usage en échec (panne, 5xx) | **Avalé** : le dernier compteur connu reste affiché, le run n'est ni interrompu ni ralenti. Un comptage manqué ne doit rien casser — même règle que le décompte de fin de tour |
| Relevé inférieur au précédent (session remplacée) | Le compteur ne recule pas : borné au dernier connu |
| Tour interrompu (F-32) ou plafond atteint (F-36) | La ligne disparaît comme pour une fin normale ; le message dédié prend le relais |
| Onglet rouvert pendant un run | La ligne réapparaît à la reprise du flux, avec les étapes déjà reçues |

---

## Critères d'acceptation

- [ ] Une ligne d'activité est visible **en bas du fil** pendant tout le tour, et absente sinon
- [ ] Elle porte : spinner animé, action en cours, nombre d'étapes, tokens consommés, chrono
- [ ] Le libellé d'action suit le dernier outil annoncé sans résultat
- [ ] Le compteur de tokens progresse pendant le run (relevé borné à un par intervalle configuré)
- [ ] Un échec de relevé n'interrompt pas le run et n'efface pas le dernier compteur
- [ ] Le compteur ne recule jamais
- [ ] La ligne disparaît à la fin du tour, y compris sur interruption et sur plafond atteint
- [ ] Suites backend et frontend vertes

---

## Périmètre

### Hors scope (explicite)

- Le **bandeau collant** en bas d'écran, écarté par l'owner au profit de la ligne dans le fil.
- Le **coût en euros** en direct : le rapport d'usage (F-16) et la ligne de coût (SF-30-05) portent
  déjà cette lecture, et une estimation qui bouge à l'écran inviterait à la lire comme une facture.
- Une barre de progression : rien ne permet de connaître le nombre total d'étapes d'un tour, et une
  progression inventée est pire qu'aucune.
- Le rythme du polling des events, inchangé.

---

## Valeurs initiales

| Réglage | Valeur | Raison |
|---|---|---|
| `app.atelier.agent.progress-interval` | `PT5S` | Un relevé toutes les 5 s suffit à donner le sentiment que ça avance, sans multiplier les appels au fournisseur pendant un run |

---

## Contraintes de validation

| Élément | Contrainte |
|---|---|
| Intervalle de relevé | Durée ≥ 1 s ; valeur nulle, négative ou absente ⇒ **relevé désactivé** (aucun appel), la ligne restant affichée sans compteur de tokens |
| Tokens affichés | Entier ≥ 0, **monotone croissant** sur la durée d'un tour |
| Libellé d'action | Tronqué à l'affichage, jamais sur plusieurs lignes : la ligne doit rester une ligne |

---

## Technique

### Endpoint(s)

Aucun endpoint nouveau. Le flux existant `POST /api/workspaces/{id}/agent/stream` gagne un type
d'event **additif** : `progress`, portant `{ tokens }`. Un client qui l'ignore se comporte comme
avant.

### Tables impactées

Aucune. La base de comparaison du tour (`workspaces.agent_input_tokens` / `agent_output_tokens`) est
**lue**, jamais écrite en cours de tour : l'imputation reste faite une seule fois, en fin de tour.

### Migration Liquibase

Aucune.

### Composants Angular (si applicable)

- `atelier/terminal/atelier-terminal.component.html` — la ligne vivante, après le tour en cours.
- `atelier/terminal/atelier-terminal.component.ts` — libellé d'action, nombre d'étapes, tokens reçus.
- `atelier/terminal/atelier-terminal.component.scss` — habillage (jetons `--cg-*`, spinner braille).
- `core/services/atelier.service.ts` + `atelier.types.ts` — event `progress` du flux.

---

## Plan de test

### Tests unitaires

**Backend**

- Un battement de polling déclenche **au plus un** relevé par intervalle (deux battements rapprochés
  ⇒ un seul appel au fournisseur).
- Le compteur relayé est le **delta** depuis la base du tour, jamais le cumul de session.
- Un relevé en échec est avalé : le run se poursuit et se termine normalement.
- Un relevé inférieur au précédent ne fait pas reculer le compteur.
- Intervalle nul ⇒ **aucun** appel de relevé (non-régression : flux strictement identique à avant).

**Frontend**

- La ligne est présente pendant un tour, absente une fois le tour terminé.
- Le libellé montre le dernier outil sans résultat ; sans action, « démarrage… ».
- Le nombre d'étapes suit le nombre de blocs.
- Un event `progress` met à jour les tokens affichés ; un flux sans `progress` n'affiche pas de
  compteur et ne casse rien.

### Tests d'intégration

Le test d'intégration du flux vérifie qu'un event `progress` est bien émis et que sa forme est
additive (les clients existants continuent de fonctionner).

### Isolation workspace

Le relevé porte sur **la session du workspace possédé par l'utilisateur du contexte de sécurité** :
`AtelierSessionService` a déjà résolu le workspace par `requireOwned` avant d'ouvrir la session, et
aucun identifiant ne vient du client. Aucun accès aux données n'est ajouté ; la lecture de la base de
comparaison passe par le même `requireOwned`.

---

## Dépendances

### Subfeatures bloquantes

Aucune. SF-30-04 (session persistante, base de comparaison) et SF-30-11 (borne de tour) sont livrées.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

**Décision — action et étapes calculées à l'écran, pas relayées en plus.** Le flux annonce déjà
chaque outil (`action`) et chaque sortie (`action_result`) : le libellé et le compteur d'étapes s'en
déduisent sans un octet de plus sur le réseau. Seuls les tokens exigeaient un appel, parce que le
fournisseur ne les met pas dans le flux d'events.

**Décision — un relevé borné dans le temps, pas à chaque battement.** Le polling des events tourne
vite ; y accrocher un appel d'usage multiplierait les requêtes au fournisseur pour un chiffre qui
n'a d'intérêt qu'à l'échelle de la seconde. L'intervalle est configurable, et le mettre à zéro rend
le comportement strictement identique à celui d'avant cette subfeature.

**Décision — le compteur ne recule pas.** Le cumul du fournisseur peut retomber (session remplacée).
Un compteur qui recule à l'écran donne l'impression que le travail est défait ; il est donc borné au
dernier connu, comme le delta l'est déjà à zéro dans le décompte de fin de tour.
