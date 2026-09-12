# Mini-spec — F-81 / SF-81-03 — Le journal dit quand un runner est en retard

## Identifiant

`F-81 / SF-81-03`

## Feature parente

`F-81` — Le contrat runner ↔ gateway ne peut plus dériver en silence

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-81-03-version-runner-journal`

---

## Objectif

Faire que « le runner de ce poste est-il à jour ? » ait une **réponse** — la version déclarée est
retenue, rendue dans la vue d'ensemble du poste, et une ligne de journal le dit quand elle est plus
ancienne que celle que la gateway distribue.

---

## Ce que la trame d'ouverture porte — vérification faite

La trame `ready` porte bien un champ `runnerVersion` (`ToolDispatcher.readyFrame`), et la gateway
n'en fait **rien** : `onReady` lit `capabilities` et `shell`, jamais la version.

**Mais le champ ne porte pas la version.** `RunnerConnection.runnerVersion()` lit
`Package.getImplementationVersion()` — et le manifeste du fat-jar du runner **ne contient pas**
`Implementation-Version`. La valeur est donc toujours la constante de repli `"0.0.1"`, sur **toutes**
les machines, quelle que soit la date de leur binaire. Le champ existe ; il ne dit rien.

Rendre cette valeur telle quelle dans l'écran reviendrait à afficher une information fausse avec
l'autorité d'une information mesurée. **Le manifeste doit donc porter la version** avant que la
question ait un sens — c'est le « si elle ne la porte pas, fais-la porter » du cadrage, et c'est
strictement le **contenu de la valeur**, pas la forme du contrat.

---

## Comportement attendu

### Cas nominal

1. Le jar du runner porte `Implementation-Version` dans son manifeste (jar mince **et** fat-jar).
2. Le runner déclare cette version dans sa trame `ready`, comme il le fait déjà.
3. `onReady` la retient sur le **poste de la session** — jamais sur un identifiant lu dans la trame —
   via un port étroit, exactement comme `RunnerShellRecorder` pour l'interpréteur.
4. La version est rendue dans `RunnerHostOverviewResponse` et affichée sur la carte du poste.
5. Si la version déclarée est **antérieure** à celle que la gateway distribue elle-même, une ligne
   de journal le dit, une fois, à la connexion.

**Le seuil est la version que la gateway sert.** Pas une constante à tenir à jour : la gateway
empaquette le runner qu'elle distribue (`app.runner.jar-path`, servi par `GET /runner/download`).
« En retard » veut donc dire « plus ancien que ce que je propose au téléchargement » — ce qui est
exactement la question posée, et ce qui ne demande aucune maintenance. Une propriété
`app.runner.min-version` permet de forcer un autre seuil si besoin.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Le runner ne déclare pas de version (runner antérieur) | Rien n'est écrit, rien n'est journalisé, la vue rend `null` |
| La version déclarée est illisible / non numérique | Elle est **retenue et affichée telle quelle**, mais ne déclenche aucune comparaison |
| Le jar distribué est absent ou sans version au manifeste | Aucun seuil : aucune ligne « en retard » n'est jamais écrite |
| La version déclarée est plus **récente** que le seuil | Aucune ligne — un runner en avance n'est pas un incident |
| L'écriture en base échoue | Best-effort : la liaison runner n'est pas coupée pour autant |

---

## Critères d'acceptation

- [ ] Le manifeste du runner porte `Implementation-Version` — jar mince **et** fat-jar distribué.
- [ ] La version déclarée dans `ready` est persistée sur le poste **de la session**.
- [ ] `RunnerHostOverviewResponse` porte `runnerVersion` ; la carte du poste l'affiche.
- [ ] Une ligne de journal (niveau `WARN`) nomme le poste et les deux versions quand le runner est en
      retard ; **aucune** ligne sinon.
- [ ] Une version déclarée **absente** ou **illisible** ne provoque ni erreur, ni ligne, ni écriture
      fautive.
- [ ] **Rien n'est bloqué** : un runner ancien s'appaire, se connecte, exécute ses outils exactement
      comme avant. Test explicite.
- [ ] Isolation : la version est écrite pour le `hostId` de la session authentifiée, jamais pour un
      identifiant lu dans la trame. Test explicite.
- [ ] Migration Liquibase réversible.

---

## Périmètre

### Hors scope — **absolu**

- **Forcer la mise à jour** d'un runner.
- **Refuser** un runner ancien, dégrader ses capacités, ou bloquer quoi que ce soit. Un poste qui
  travaille ne s'arrête pas parce qu'une version a bougé.
- Versionner l'**API** du runner (`/v1/`, négociation).
- La mise à jour automatique du runner sur la machine du client.
- Une politique de numérotation des versions du runner : la version reste celle du projet Maven.

---

## Technique

### Endpoints

Aucun endpoint créé. `GET /runner-hosts/overview` (existant) gagne un champ `runnerVersion`.

### Tables impactées

| Table | Opération | Notes |
|---|---|---|
| `runner_hosts` | `ALTER` — colonne `runner_version varchar(64)` nullable | même forme que `shell` (063/064) |

### Migration Liquibase

- [x] Oui — `076-runner-hosts-runner-version.xml`, avec `rollback`.

### Composants Angular

- `postes.component.html` — une ligne « Runner » dans la liste des faits du poste, à côté de
  « Système » et « Interpréteur ». Aucune couleur ni police nouvelle : la classe `poste__mono`
  existante, déjà employée pour la racine et l'interpréteur.
- `atelier.models.ts` — `runnerVersion?: string | null` (facultatif, comme `shell`).

---

## Plan de test

### Tests unitaires

- [ ] `RunnerHostService.recordRunnerVersion` — la version est écrite sur le poste ; poste inconnu =
      sans effet.
- [ ] Version nulle, vide, ou trop longue → ignorée, aucune écriture.
- [ ] Comparaison de versions : `0.0.1 < 0.2.0 < 0.10.0`, égalité, suffixes (`-SNAPSHOT`) ignorés,
      valeur illisible → aucune comparaison possible.
- [ ] `onReady` appelle l'enregistrement avec le `hostId` **de la session**.
- [ ] `onReady` sans champ `runnerVersion` → aucun appel, aucune erreur.

### Tests d'intégration

- [ ] La vue d'ensemble des postes rend `runnerVersion`.
- [ ] Un runner déclarant une version ancienne **exécute ses outils normalement** — la preuve que
      rien n'est bloqué.

### Tests frontend

- [ ] La carte du poste affiche la version quand elle existe, et n'affiche pas la ligne sinon.

### Isolation utilisateur

- [x] Applicable — la vue d'ensemble est déjà filtrée par `user_id` (F-49) ; l'écriture se fait par
      `hostId` de session, et un test vérifie qu'une trame ne peut pas écrire sur le poste d'un
      autre.

---

## Dépendances

### Subfeatures bloquantes

- `SF-81-01` — **done**. `SF-81-02` — **done**.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

| Préoccupation | Cochée ? | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | **Oui, en lecture seule** | `RunnerCallDispatcher.onReady` écrit sur le `hostId` **de la session runner authentifiée** (`RunnerIdentity`), jamais sur un identifiant lu dans la trame — même règle et même chemin que `recordDeclaredShell` (SF-38-27), inchangés. `RunnerHostOverviewService` reste filtré par `user_id`. Aucun nouveau moyen de résoudre le tenant n'est introduit. |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |

---

## Notes et décisions

**D1 — Le seuil est la version distribuée, pas une constante.**
Une constante dans le code aurait été juste le jour où elle a été écrite, puis fausse. La gateway
sait ce qu'elle sert au téléchargement ; c'est la seule référence qui se met à jour toute seule, au
même rythme que l'image. Si le jar n'est pas empaqueté ou ne porte pas de version, il n'y a
simplement **pas de seuil** — et aucune ligne, plutôt qu'une ligne fausse.

**D2 — `WARN` et pas `ERROR`.**
Un runner en retard n'est pas une panne : il travaille. Le niveau doit attirer l'œil d'un exploitant
sans déclencher d'alerte.

**D3 — La version illisible est affichée quand même.**
Un runner recompilé à la main peut déclarer n'importe quoi. On le montre tel quel — c'est une
information sur ce qui tourne réellement — mais on ne le compare pas, et on ne le juge pas.
