# Mini-spec — F-162 / SF-162-03 — La visibilité de la compaction et du `recall`

> Base : `project-governance/templates/subfeature-template.md`.
> Cadrage de référence (fait foi) : `docs/features/F-162/CADRAGE-F-162-rappel-a-la-demande.md` (§4, SF-162-03).
> SF précédentes livrées : SF-162-01 (`recall`), SF-162-02 (résumé de compaction ancré).

---

## Identifiant

`F-162 / SF-162-03`

## Feature parente

`F-162` — Le rappel à la demande de l'historique (se souvenir sans tout rejouer)

## Statut

`ready`

## Date de création

2026-09-29

## Branche Git

`feat/SF-162-03-flux-compaction-recall`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Rendre **visibles** dans le terminal deux mécanismes aujourd'hui muets — la **compaction**
(barre de progression indéterminée pendant, marqueur « Conversation compactée · N tours résumés »
après) et le **`recall`** (indicateur « Recherche dans l'historique… » pendant, marqueur
« Détail rappelé · tour N » après) — via des **événements de flux** (back, mécanisme
`AtelierProgressListener` existant) et un **rendu terminal** (front), sans rien changer au contenu
envoyé au modèle.

---

## Comportement attendu

### Cas nominal

**Compaction (back)**

1. La compaction est déclenchée dans `AtelierChatService.runLoop` autour du tour : `compactIfOversized`
   (auto, seuil 120 K) avant de bâtir la requête, et `compactNow` (filet réactif sur un 400
   « prompt too long »). Elle est **exécutée** par `AtelierCompactionService`.
2. Quand — et **seulement quand** — une compaction va réellement avoir lieu (il y a des tours anciens
   à résumer), `AtelierCompactionService` émet, via le listener passé par la boucle, une transition
   **« démarrée »** (état indéterminé : c'est un appel de synthèse unique, pas un pourcentage) juste
   avant l'appel de synthèse.
3. À la fin, il émet une transition **« terminée »** portant le **nombre de tours résumés** N
   (nombre de messages `USER` dans la tranche résumée — même notion de « tour » que `recall` /
   SF-162-02).
4. Le contrôleur SSE (`AtelierChatController`) relaie ces deux transitions comme un événement de flux
   `compaction` (`{ running: true }` puis `{ running: false, summarizedTurns: N }`), bufférisé et
   rejouable comme les autres (`plan`, `card`, …).

**Recall (back)**

5. L'étape déjà émise par `recall` (SF-162-01) est **spécialisée** : `stepFor` mappe `recall` sur une
   étape de type `recall` (et non plus le type générique `search`), afin que l'écran affiche
   « Recherche dans l'historique… » pendant l'exécution.
6. Après l'exécution de `recall`, si des extraits sont trouvés, un événement de flux `recall`
   (`{ repere: "tour N" }` ou « tours N, M ») est émis pour afficher le **repère du/des tour(s)
   retrouvé(s)**. Aucun extrait → pas d'événement (le message neutre reste dans le résultat d'outil).

**Front**

7. Pendant l'état « compaction en cours » : une **barre de progression indéterminée** (animée),
   bien visible dans la zone du tour vivant, libellée « Compaction de la conversation… ».
8. À la fin : la barre disparaît et un **marqueur** « Conversation compactée · N tours résumés »
   reste dans le flux du tour vivant.
9. `recall` : l'étape « Recherche dans l'historique « query » » s'affiche pendant (via `chat-steps`),
   et le marqueur « Détail rappelé · tour N » s'affiche à réception de l'événement `recall`.

### Cas d'erreur / limites

| Situation | Comportement attendu |
|-----------|---------------------|
| Émission d'un événement de flux en échec (listener qui lève) | **Best-effort strict** : capturé et ignoré ; la compaction et le `recall` ne doivent **jamais** échouer à cause de l'affichage |
| Compaction déclenchée mais rien à résumer (`splitIndex == 0`, fil déjà court) | **Aucun** événement `compaction` (ni « démarrée » ni marqueur) : rien ne s'affiche |
| Appel de synthèse en échec (best-effort, fil inchangé) | « démarrée » a pu être émise → « terminée » émise avec **N = 0** : la barre disparaît, **pas** de marqueur |
| `recall` sans extrait trouvé | Aucun événement `recall` : pas de marqueur « Détail rappelé » |
| Mode synchrone (`chat`, listener `NOOP`) ou backend/écran antérieur | Événements ignorés (méthodes `default` neutres, handlers optionnels) : comportement d'avant, aucune régression |
| `prefers-reduced-motion: reduce` | La barre est **non animée** (état statique lisible), le marqueur reste inchangé |

---

## Critères d'acceptation

- [ ] `AtelierProgressListener` porte deux transitions de compaction (`onCompactionStarted`,
      `onCompactionDone(int summarizedTurns)`) et une de rappel (`onRecalled(String repere)`),
      toutes **`default` neutres** (le mode synchrone et les tests existants restent valides).
- [ ] `AtelierCompactionService` émet « démarrée » **uniquement** quand une compaction a effectivement
      lieu (après la garde `splitIndex == 0`), et « terminée » avec **N = nombre de tours `USER`
      résumés** en succès, **N = 0** en best-effort (résumé blanc ou appel en échec).
- [ ] L'émission des événements de compaction est **best-effort** : une exception du listener n'empêche
      **jamais** la compaction (test dédié).
- [ ] `stepFor(recall)` produit une étape de type `recall` (spécialisée), portant le mot-clé.
- [ ] Après un `recall` avec extraits, un événement `recall` porte le **repère du/des tour(s)**
      retrouvé(s) ; aucun extrait → aucun événement.
- [ ] `AtelierChatController` relaie `compaction` (démarrée/terminée) et `recall` en événements SSE,
      via le mécanisme `turn.publish` existant (bufférisés / rejouables).
- [ ] `atelier.service.ts` route les événements SSE `compaction` et `recall` vers des handlers
      **optionnels** ; un backend qui ne les émet pas ne change rien.
- [ ] Le terminal affiche : une **barre de progression indéterminée** pendant la compaction, un
      **marqueur** « Conversation compactée · N tours résumés » après, un marqueur « Détail rappelé ·
      tour N » après un `recall`.
- [ ] **Design system** : jetons `--cg-*` uniquement (barre en `--cg-accent`), **aucune couleur/police
      nouvelle** ; espacements sur la grille de 4 px (`--cg-space-*`) ; `prefers-reduced-motion` :
      barre non animée.
- [ ] **Responsive mobile** : rien ne déborde à 390 px, le rail en tête (SF-158-22) et l'auto-scroll
      (SF-158-23) ne sont pas cassés ; budget de style respecté (feuille dédiée si besoin).
- [ ] **Préfixe stable / cache** : aucun contenu envoyé au modèle n'est modifié — les événements sont
      **de l'affichage** ; rien de volatil n'entre dans le contexte (test de non-régression du rejeu).
- [ ] **Non-régression** : boucle d'agent, compaction (SF-162-02), `recall` (SF-162-01), rendu du fil,
      auto-scroll, rail — tous les tests existants restent verts.

---

## Périmètre

### Hors scope (explicite)

- **Pas de persistance** de la compaction ni du `recall` en base (`atelier_messages` inchangée) : ce
  sont des événements **transitoires du tour**. La « persistance » du marqueur est celle du flux vivant
  (il reste affiché jusqu'au rechargement du fil), pas une nouvelle table.
- **Pas de bouton « compacter maintenant »** (SF-162-04), pas de filet utilisateur (SF-162-05), pas de
  sémantique (SF-162-06).
- **Pas de modification** de la logique de compaction (seuils, garde, best-effort, frontière) ni de la
  requête `recall` : seule la **visibilité** est ajoutée.
- **Pas d'affichage dans les tuiles lectrices** de la mosaïque (`LiveTurnView`) : les handlers sont
  optionnels, l'absence n'est pas une régression ; parité mosaïque reportée si besoin.
- **Aucun nouveau réglage `@ConfigurationProperties`** (piège des deux constructeurs).

---

## Valeurs initiales

Aucune entité, aucune table, aucune colonne, aucune migration.

| Réglage | Valeur | Règle |
|---------|--------|-------|
| Avancement compaction | **indéterminé** (démarré → terminé) | pas de pourcentage : appel de synthèse unique |
| N (tours résumés) | `count(USER)` dans la tranche résumée | même notion de « tour » que `recall` / SF-162-02 |
| Repère `recall` | « tour N » (ou « tours N, M ») | tours distincts des extraits trouvés |

---

## Technique

### Endpoint(s)

Aucun endpoint HTTP nouveau. De nouveaux **événements SSE** (`compaction`, `recall`) sur le flux
existant `POST /api/workspaces/{id}/chat/stream`.

### Tables impactées

Aucune. `atelier_messages` en lecture seule (déjà lue par compaction et `recall`).

### Migration Liquibase

- [x] Non applicable — aucune table ni colonne nouvelle.

### Composants impactés

**Backend**

- `AtelierProgressListener` — 3 méthodes `default` neutres (`onCompactionStarted`,
  `onCompactionDone(int)`, `onRecalled(String)`).
- `AtelierCompactionService` — surcharges `compactIfOversized`/`compactNow`/`doCompact` acceptant un
  listener (les signatures existantes délèguent en `NOOP`, non-régression) ; émission des transitions
  dans `doCompact`, best-effort (try/catch).
- `AtelierChatService` — `runLoop` passe le `listener` aux deux appels de compaction ; `stepFor`
  spécialise `recall` (type `recall`) ; `recall(...)` reçoit le listener et émet `onRecalled` sur
  extraits trouvés.
- `AtelierChatController` — anonyme `AtelierProgressListener` : relaie `compaction`/`recall` via
  `turn.publish` ; records `StreamCompaction`, `StreamRecall`.

**Frontend**

- `core/models/atelier.models.ts` — handlers optionnels `onCompaction?`, `onRecalled?` + types
  d'événement.
- `core/services/atelier.service.ts` — routage des événements SSE `compaction` et `recall`.
- `atelier/atelier.component.ts` — handlers (streamChat + attachHandlers) : alimentent l'état du tour
  vivant (`AtelierExecStreamingItem`).
- `atelier/atelier.types.ts` — champs `compaction` / `recall` sur `AtelierExecStreamingItem`.
- `atelier/terminal/atelier-terminal.component.{ts,html}` + `atelier-terminal-compaction.component.scss`
  (feuille dédiée existante) — barre de progression + marqueurs.

### Points de câblage

- Émission back : `AtelierCompactionService.doCompact` (start avant synthèse, done après) ;
  `AtelierChatService.recall` (repère après extraits).
- Relais SSE : `AtelierChatController` (`turn.publish("compaction"|"recall", …)`).
- Réception front : `atelier.service.ts` `handleEvent` (`event === 'compaction' | 'recall'`).

---

## Plan de test

### Tests unitaires — backend

- [ ] `AtelierCompactionService` : compaction réelle → `onCompactionStarted` **une fois** puis
      `onCompactionDone(N)` avec N = tours `USER` résumés (listener capteur).
- [ ] `splitIndex == 0` (rien à résumer) → **aucune** émission.
- [ ] Résumé blanc / appel de synthèse en échec → `onCompactionDone(0)` (barre effacée, pas de marqueur).
- [ ] Best-effort : un listener qui **lève** dans `onCompactionStarted`/`onCompactionDone` ne fait pas
      échouer la compaction (le fil est quand même compacté / laissé intact selon le cas).
- [ ] `stepFor(recall)` → étape de type `recall` (spécialisée), pas `search`.
- [ ] `AtelierChatService.recall` : extraits trouvés → `onRecalled` porte le repère de tour ; aucun
      extrait → **aucun** `onRecalled` (listener capteur, via `chatStreaming`).

### Tests d'intégration / non-régression — backend

- [ ] `AtelierChatServiceCompactionTest` et `AtelierCompactionServiceTest` : tous verts (déclenchement,
      best-effort, incrémental, estimateur, gabarit inchangés).
- [ ] `AtelierChatServiceRecallTest` : isolation, bornage, repère, cas d'erreur inchangés ; le rejeu au
      modèle **n'est pas modifié** par les événements (préfixe stable).

### Tests unitaires — frontend

- [ ] `atelier.service` : un événement SSE `compaction` (running true/false) appelle `onCompaction` ;
      `recall` appelle `onRecalled` ; un handler absent ne casse rien.
- [ ] `atelier-terminal.component` : à l'état « compaction en cours » la barre de progression est
      **rendue réellement** (pas seulement une classe déclarée en CSSOM) ; à l'état terminé le marqueur
      « Conversation compactée · N tours résumés » est présent avec N.
- [ ] `atelier-terminal.component` : marqueur « Détail rappelé · tour N » présent à réception du recall.
- [ ] `prefers-reduced-motion` : la règle d'animation est neutralisée (test de rendu, pas seulement
      présence de classe).
- [ ] Non-régression : rail en tête (`terminal-rail-en-tete.spec.ts`), auto-scroll, blocs d'étapes
      (`chat-steps.spec.ts`) restent verts.

### Isolation workspace / utilisateur

- [x] Applicable indirectement — `recall` et la compaction lisent déjà `atelier_messages` filtré
      `workspace_id` + `user_id` (SF-162-01/02). Cette SF **n'ajoute aucun accès données** : elle
      relaie des transitions d'affichage. Aucun nouveau chemin d'accès à isoler.

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés |
|--------------|-------------|---------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non (aucun nouvel accès données ; réutilise `user_id` + `workspace_id` déjà résolus) | — |
| Plans / limites | Non | — |
| Navigation / routing | **Oui — flux SSE** : nouveaux événements sur le contrat de flux existant | **Émission** : `AtelierCompactionService`, `AtelierChatService`, `AtelierChatController` · **Contrat/transport** : `atelier.service.ts` (`handleEvent`), `atelier.models.ts` (`AtelierStreamHandlers`) · **Rendu** : `atelier.component.ts`, `atelier.types.ts`, `atelier-terminal.component.{ts,html}`, `atelier-terminal-compaction.component.scss`. Chaque événement est **additif** (handler optionnel, méthode `default`) : aucun chemin de flux existant (action/text/plan/card/email/page/done/steer/attach) n'est modifié ; le rejeu (`attach`/fenêtres) transporte les nouveaux événements sans allowlist (`SseTurnSubscriber` générique). |

---

## Dépendances

### Subfeatures bloquantes

- **SF-162-01** (livrée) : l'étape `recall` à spécialiser et le comptage de tours.
- **SF-162-02** (livrée) : la notion de « tour résumé » (numérotation cohérente).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Préfixe stable / cache de prompt** : les événements de flux sont **purement d'affichage**. Aucun
  n'entre dans le contexte envoyé au modèle — la règle mémoire « préfixe stable = rien de volatil » est
  respectée par construction (aucune modification de `buildReplayMessages` ni des consignes).
- **Best-effort** : conformément à F-117, l'émission ne peut jamais faire échouer la compaction. Chaque
  appel de listener est gardé (try/catch, log debug).
- **Avancement indéterminé** : la compaction est **un** appel de synthèse ; il n'y a pas de progression
  mesurable → barre indéterminée, pas de pourcentage (cadrage §4.SF-162-03).
- **Gateway-First / Provider-First** : inchangés — aucun moteur IA, aucune dépendance directe Anthropic ;
  la synthèse passe toujours par `AiAgentProvider`.
- **Piège des constructeurs** : aucun `@ConfigurationProperties` touché ; les surcharges de service
  utilisent `AtelierProgressListener.NOOP` par défaut.
- **Piège CSSOM** : les tests de rendu vérifient le **rendu réel** (élément présent, animation
  neutralisée sous reduced-motion), pas la seule présence d'une règle CSS.
