# Mini-spec — [F-121 / SF-121-06] Outil MultiEdit (éditions atomiques groupées)

## Identifiant

`F-121 / SF-121-06`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison)

## Statut

`ready`

## Date de création

2026-09-23

## Branche Git

`feat/SF-121-06-multi-edit`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Ajouter un outil `multi_edit(path, edits[])` qui applique **plusieurs** remplacements exacts à
**un** fichier en **une seule** lecture + écriture et de façon **atomique** (tout-ou-rien : si un
seul `old_string` échoue, le fichier n'est **pas** modifié), déclaré sur les **deux** cibles
d'exécution (hébergé SANDBOX + poste RUNNER), avec la même fiabilité que `edit_file`.

---

## Comportement attendu

### Cas nominal

1. Le modèle appelle `multi_edit` avec `path` et un tableau `edits`, chaque entrée portant
   `old_string`, `new_string` et un `replace_all` optionnel (défaut `false`).
2. La gateway lit **une seule fois** le contenu complet du fichier (sur RUNNER : `read_file`, avec
   repli tranches binaires `read_file_bytes` pour les fichiers > 512 Kio — mécanique SF-121-22
   réutilisée ; sur SANDBOX : `workspaceService.readFile`).
3. Les éditions sont appliquées **en séquence, en mémoire** : chaque édition voit le résultat de la
   précédente (mêmes règles que Claude Code). Chaque remplacement réutilise la logique **pure**
   existante `AtelierFileText.replace` (remplacement littéral, `old_string` unique sauf
   `replace_all`, échec si introuvable ou ambigu, échec si `old==new`).
4. Si **toutes** les éditions réussissent, le résultat complet est écrit **une seule fois**
   (`write_file`, avec repli tranches `write_file_bytes` si le résultat déborde 512 Kio).
5. Le modèle reçoit `Fichier modifié : <path> (N remplacement(s))` où N est le total cumulé.
6. L'écran affiche l'appel comme une écriture (`write`) sur `path` — l'éditeur ouvert se rafraîchit,
   exactement comme pour `edit_file` (aucun nouvel affichage).

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| `edits` absent, non-tableau ou vide | Erreur rendue au modèle : « Paramètre requis manquant ou vide : edits ». **Rien n'est écrit.** | erreur outil |
| `old_string` absent/vide dans une entrée | Erreur « old_string manquant » préfixée du n° d'édition. **Rien n'est écrit.** | erreur outil |
| `old_string` introuvable dans l'état courant | Erreur « Édition n°k : texte introuvable … Aucune modification n'a été appliquée (tout ou rien). » **Rien n'est écrit.** | erreur outil |
| `old_string` présent plusieurs fois sans `replace_all` | Erreur « Édition n°k : texte trouvé X fois … » **Rien n'est écrit.** | erreur outil |
| `old_string == new_string` dans une entrée | Erreur « Édition n°k : aucune modification demandée … » **Rien n'est écrit.** | erreur outil |
| `path` absent | Paramètre requis manquant : path (comportement `edit_file`). | erreur outil |
| Fichier introuvable | Erreur métier propagée au modèle (comme `edit_file`). | erreur outil |
| Échec de transport runner (lecture/écriture) | « non concluant » (SF-119-04), rien n'est réputé écrit. | erreur outil |
| Runner antérieur à F-110 sur gros fichier | Échec propre (`unsupported_tool`), **jamais de corruption** (hérité SF-121-22). | erreur outil |

---

## Critères d'acceptation

- [ ] `multi_edit` est déclaré dans la panoplie sur les **deux** cibles (SANDBOX + RUNNER), après `edit_file`.
- [ ] `AtelierFileText.applyEdits` applique les éditions en séquence et rend le contenu + total des remplacements.
- [ ] **Atomicité** : si une édition (parmi plusieurs) échoue, aucune écriture n'est émise et le fichier reste inchangé (vérifié sur SANDBOX par mock `writeFile` jamais appelé, et sur RUNNER par `writeFile` jamais appelé).
- [ ] Sur RUNNER, un `multi_edit` réussi effectue **une** lecture puis **une** écriture (réutilise `editFileOnRunner`/`AtelierFileText`) ; les gros fichiers (> 512 Kio) passent par le repli tranches SF-121-22.
- [ ] Les éditions séquentielles sont observées : l'édition 2 opère sur le résultat de l'édition 1.
- [ ] `multi_edit` déclenche la même mécanique transversale qu'`edit_file` : progression `write`, cible d'audit = `path`, marque de mutation du tour, garde/rappel de fraîcheur (SF-121-19), point de contrôle après écriture (F-50), défaut de permission (askBeforeEdit).
- [ ] `multi_edit` est **retiré** en mode Réponse/Plan (outil mutant) — non ajouté à la liste blanche `ANSWER_PLAN_TOOLS`.
- [ ] Aucun changement de protocole runner ; aucune migration ; aucun composant frontend nouveau.
- [ ] Cache de prompt (F-134) préservé : aucun contenu volatil ajouté au préfixe système ; la description d'outil est stable.
- [ ] Provider Independence : passe par l'interface `AIProvider`/panoplie, aucun modèle en dur.

---

## Périmètre

### Hors scope (explicite)

- Édition multi-**fichiers** en un appel (multi_edit = un seul `path`, comme Claude Code).
- Toute expression régulière dans `old_string` (remplacement littéral, cohérent `edit_file`).
- Nouvel outil côté runner (aucun `multi_edit` runner ; on réutilise read/write déjà au contrat).
- Bouton/écran frontend (réutilise le rendu `write` d'`edit_file`).
- Modification de la politique de permission (SF-121-02) au-delà du défaut hérité d'`isFileWrite`.

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `path` | Oui | chemin relatif projet (comme `edit_file`) | — |
| `edits` | Oui | tableau **non vide** d'objets | — |
| `edits[].old_string` | Oui | non vide ; littéral ; unique dans l'état courant sauf `replace_all` | — |
| `edits[].new_string` | Oui | littéral (peut être vide pour supprimer) | — |
| `edits[].replace_all` | Non | booléen, défaut `false` | — |

Notes :
- Chaque édition est validée par `AtelierFileText.replace` (source unique de vérité du remplacement).
- L'atomicité tient parce que tout est appliqué **en mémoire** ; l'écriture n'a lieu qu'après succès de **toutes** les éditions.

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint : `multi_edit` est un **outil d'agent**, exposé dans la panoplie du tour Atelier.

### Tables impactées

Aucune. Aucun état persistant nouveau.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

Aucun nouveau. L'appel est rendu par l'affichage `write` existant du terminal Atelier (comme `edit_file`).

### Composants backend impactés (analyse d'impact — préoccupation transversale)

Cette subfeature ajoute un **outil mutant** ⇒ elle touche les points d'accroche transversaux des
écritures de fichier. Composants vérifiés/adaptés :

- `AtelierFileText` — nouvelle méthode pure `applyEdits(content, List<EditSpec>)` + record `EditSpec`.
- `AtelierChatService` :
  - `fileTools` — déclaration `multi_edit` (deux cibles).
  - `callRunner` — dispatch `case "multi_edit"` → `multiEditOnRunner` (réutilise le cœur d'`editFileOnRunner`).
  - `runnerOutcome` — `case "multi_edit"` (aligné sur `edit_file`).
  - `executeToolOnStorage` — `case "multi_edit"` (SANDBOX).
  - `stepFor` — `multi_edit` → étape `write` (rafraîchissement fichier).
  - `auditTarget` — `multi_edit` → `path`.
  - `isFileWrite` — inclut `multi_edit` ⇒ **checkpoint F-50, garde/note fraîcheur SF-121-19, défaut de permission `askBeforeEdit`, marque de mutation** couvrent `multi_edit` sans autre modification.
  - `applyWriteCheckpoint` — extraction du contenu candidat pour `multi_edit` (concaténation des `new_string`).
  - `seedFreshnessFromHistory` — via `isFileWrite`, `multi_edit` amorce la fraîcheur.
- `AtelierFileFreshness` — `refuseWrite`/`noteWrite` traitent `multi_edit` comme une **édition ciblée** (mêmes messages qu'`edit_file`).
- `RunnerToolGateway` / protocole runner — **inchangés** (réutilise `readFile`/`writeFile`/`readFileBytes`/`writeFileBytes`).
- Mode Réponse/Plan (`ANSWER_PLAN_TOOLS`) — `multi_edit` **non** ajouté ⇒ retiré du mode plan (outil mutant).

Aucune préoccupation **Auth / tenant** nouvelle : `multi_edit` s'exécute sous le même `userId`/
`workspaceId` que `edit_file`, via `executeToolOnStorage(userId, workspaceId, …)` (SANDBOX) et la cible
RUNNER résolue par le workspace. Aucune nouvelle résolution de tenant.

---

## Plan de test

### Tests unitaires — `AtelierFileTextTest`

- [ ] `applyEdits` — nominal : deux éditions distinctes, contenu final correct, total = 2.
- [ ] `applyEdits` — **séquentiel** : l'édition 2 opère sur le résultat de l'édition 1.
- [ ] `applyEdits` — `replace_all` sur une entrée : compte tous les remplacements de cette entrée.
- [ ] `applyEdits` — **atomicité** : la 2ᵉ édition introuvable ⇒ exception préfixée du n°, contenu source inchangé (aucune écriture ne dépend d'un retour partiel).
- [ ] `applyEdits` — liste `edits` vide ⇒ exception explicite.
- [ ] `applyEdits` — `old==new` dans une entrée ⇒ exception (héritée de `replace`).

### Tests service — SANDBOX (`AtelierChatServiceTest`)

- [ ] `multi_edit` réussi ⇒ `workspaceService.readFile` appelé une fois, `writeFile` appelé une fois avec le contenu final, message « N remplacements ».
- [ ] `multi_edit` dont une édition échoue ⇒ `writeFile` **jamais** appelé (atomicité), erreur rendue au modèle.

### Tests service — RUNNER (`AtelierChatServiceRunnerTargetTest`)

- [ ] `multi_edit` réussi ⇒ **une** `readFile` puis **une** `writeFile` sur le runner, message final.
- [ ] `multi_edit` dont une édition échoue ⇒ `writeFile` runner **jamais** appelé, erreur non corruptrice.
- [ ] `multi_edit` déclaré dans la panoplie RUNNER (et SANDBOX) — assertions `containsExactly` mises à jour.

### Tests mode (`AtelierChatServiceModeTest`)

- [ ] `multi_edit` présent en ACT (deux cibles) ; **absent** en ANSWER_PLAN.

### Isolation workspace

- [x] Applicable — `multi_edit` accède aux fichiers via `userId` + `workspaceId` du tour, comme
  `edit_file` (aucun nouveau chemin d'accès aux données). Couvert par les mêmes gardes que
  `edit_file` (mêmes appels `executeToolOnStorage`/cible RUNNER résolue par le workspace).

---

## Dépendances

### Subfeatures bloquantes

- `SF-121-22` (repli gros fichier) — Done (réutilisé pour RUNNER).
- `SF-121-19` (fraîcheur) — Done (couvre `multi_edit` via `isFileWrite`).

### Questions ouvertes impactées

- [ ] Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **D1 — Réutiliser le moteur pur.** Toute la sémantique de remplacement reste dans
  `AtelierFileText.replace` ; `applyEdits` ne fait que la boucler. Une seule vérité, testable, aucune
  divergence entre `edit_file` et `multi_edit`.
- **D2 — Aucune mise à jour runner.** On réutilise `read_file`/`write_file` (+ tranches SF-121-22)
  déjà au contrat. Un runner installé n'a rien à mettre à jour.
- **D3 — Atomicité par construction.** Les éditions s'appliquent en mémoire ; l'unique écriture n'a
  lieu qu'après succès de toutes. Aucun mécanisme transactionnel externe nécessaire.
- **D4 — Transversal via `isFileWrite`.** En rangeant `multi_edit` dans `isFileWrite`, il hérite
  automatiquement du checkpoint F-50, de la fraîcheur SF-121-19, du défaut de permission et de la
  marque de mutation — pas de logique dupliquée.
- **Cache F-134** : la description d'outil est fixe, aucun contenu volatil au préfixe système.
- **F-119 / Provider Independence / Gateway-First** : inchangés — pur transport, aucune capacité IA
  réimplémentée, aucun modèle en dur.
