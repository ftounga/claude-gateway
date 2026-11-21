# Mini-spec — [F-275 / SF-275-02] L'outil `action_rapide` (backend)

## Identifiant

`F-275 / SF-275-02`

## Feature parente

`F-275` — Actions rapides : lancer un workflow configuré d'un clic (cadrage `CADRAGE-F-275-actions-rapides.md`)

## Statut

`in-progress`

## Date de création

2026-11-21

## Branche Git

`feat/SF-275-02-outil-action-rapide`

---

## Objectif

Donner à l'agent du terminal un outil `action_rapide` qui ajoute, liste, modifie et supprime les actions rapides personnalisées, pour qu'une phrase (« ajoute une action rapide Point hebdo dans reporting-securite, envoyée directement ») suffise à les configurer.

---

## Comportement attendu

### Cas nominal

- Outil déclaré dans tout terminal du propriétaire **hors terminal Teams et fil Jarvis** (`QuickActionService.offeredIn`), définition littérale stable (cache F-134), placée parmi les outils de la gateway.
- Entrée : `operation` ∈ `ajouter | lister | modifier | supprimer` ; `libelle`, `phrase`, `sujet` (nom, chemin ou id d'un sujet du poste de ce terminal ; `""` ou `"ici"` = aucun : le terminal où l'on clique), `portee` ∈ `sujet | poste | compte`, `clic` ∈ `deposer | envoyer`, `suggestion_jours` (tableau de `lundi`…`dimanche`, `jours_ouvres`), `suggestion_moment` ∈ `matin | apres_midi | journee`, `sans_suggestion` (booléen), `action` (id ou libellé de l'action visée, pour `modifier` / `supprimer`).
- `ajouter` → `QuickActionService.create` ; `modifier` → `update` (champs fournis seulement ; `sujet: ""` retire la cible) ; `supprimer` → `delete` ; `lister` → la liste fusionnée du terminal (perso puis skills), une ligne par action : libellé, phrase, sujet, clic, portée, suggestion, dernière exécution.
- L'action visée par `action` est cherchée **parmi les actions personnalisées visibles dans ce terminal** : id exact, sinon libellé (casse et espaces ignorés).
- Résultat rendu au modèle : une ligne qui dit ce qui est rangé et que l'action apparaît au bouton « Actions rapides » de la saisie (sans recharger).
- Scène Jarvis : `action_rapide` → `GENERIQUE`, libellé « Action rapide rangée » ; étiquette d'étape = opération + libellé (`SceneStepLabels`).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Terminal Teams / fil Jarvis | outil non déclaré ; appel forcé ⇒ erreur « pas ouvert ici » | — (résultat d'outil `is_error`) |
| `operation` absente ou inconnue | erreur « operation : ajouter, lister, modifier ou supprimer » | — |
| `action` introuvable ou ambiguë (modifier / supprimer) | erreur qui liste les libellés connus | — |
| Sujet inconnu, ambigu, d'un autre poste | message de `resolveSubject` | — |
| Jour / moment / portée / clic inconnu, libellé déjà pris, plafond | message de `QuickActionService` | — |

---

## Critères d'acceptation

- [ ] CA1 `ajouter` avec `sujet: reporting-securite`, `clic: envoyer` range l'action (cible résolue sur le poste) et le dit.
- [ ] CA2 `lister` rend les actions perso et celles des skills, avec la dernière exécution.
- [ ] CA3 `modifier` par libellé change seulement les champs fournis ; `sujet: ""` retire la cible ; `sans_suggestion` retire la suggestion.
- [ ] CA4 `supprimer` par libellé supprime ; libellé inconnu ⇒ erreur lisible, rien ne change.
- [ ] CA5 L'outil n'est pas déclaré au terminal Teams ni au fil Jarvis ; un appel y est refusé.
- [ ] CA6 Isolation : l'outil n'écrit que sous le `user_id` du tour et ne voit que les actions visibles dans ce terminal (une action d'autrui n'est jamais désignable).
- [ ] CA7 Garde du registre des scènes verte (`JarvisSceneRegistryGuardTest`).

---

## Périmètre

### Hors scope (explicite)

- L'écran (SF-275-03). Doctrine dans la consigne système (la description de l'outil suffit ; rien de volatil n'entre au préfixe).
- Modifier un déclencheur de skill par l'outil (on ajoute une action perso de même phrase, qui le masque).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| portée | POSTE si poste, sinon SUJET | `QuickActionService.defaultScope` |
| clic | DEPOSER | défaut |
| user_id / terminal | ceux du tour | jamais lus dans l'entrée de l'outil |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| operation | Oui | — | ajouter, lister, modifier, supprimer | — | minuscules, accents ignorés |
| libelle / phrase | ajouter : oui | 60 / 2000 | non vide | libellé parmi les visibles | (SF-275-01) |
| suggestion_jours | Non | 7 | lundi…dimanche, jours_ouvres | — | ISO 1..7 |
| action | modifier / supprimer : oui | — | id ou libellé visible ici | — | casse, espaces |

---

## Technique

### Endpoint(s)

Aucun (outil de l'agent).

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `quick_actions` | INSERT / SELECT / UPDATE / DELETE | via `QuickActionService` |

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

- Registre des scènes `jarvis-scene-registry.json` (entrée `action_rapide`).

---

## Plan de test

### Tests unitaires

- [ ] `QuickActionToolExecutorTest` — ajouter (sujet résolu, clic, jours), lister, modifier par libellé (champs fournis, retrait de cible / suggestion), supprimer, action introuvable, opération inconnue, terminal Teams / Jarvis refusé, catalogue absent ailleurs.

### Tests d'intégration

- [ ] `AtelierChatServiceQuickActionToolTest` — un tour où le modèle (bouchon) appelle `action_rapide ajouter` : l'action est rangée pour le `user_id` du tour et visible au `GET` ; l'outil figure dans les outils déclarés du terminal, pas dans ceux du terminal Teams.

### Isolation workspace

- [ ] Applicable — un appel dans le terminal d'Alice ne voit ni ne désigne une action de Bob (même libellé) ; tout ce qui est écrit porte le `user_id` du tour.

---

## Dépendances

### Subfeatures bloquantes

- SF-275-01 — statut : done

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- Préoccupation transversale : aucune (ni auth, ni tenant nouveau : le terminal du tour est déjà `requireOwned`).
