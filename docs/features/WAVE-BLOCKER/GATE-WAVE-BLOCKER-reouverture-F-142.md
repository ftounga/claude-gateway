# GATE de vague — WAVE-BLOCKER : rouvrir F-142 et y inscrire les SF retenues

> **Nature** : gate produit **RÉVERSIBLE** (statut d'une ligne de spec + traçage d'arbitrage).
> **Pas** une création de feature : `F-142` existe déjà dans `docs/PRODUCT_SPEC.md` (ligne unique,
> cadrage `docs/features/F-142/CADRAGE-F-142-diagrammes-dans-les-livrables.md`).
> **Date** : 2026-09-27. **Régime** : `ai-skills/autonomous-delivery-wave.md` §Phase 2 — 🟠 orange,
> « décider par défaut + tracer », pas de pause PO.

## 1. Objectif en une phrase
Remettre la ligne `F-142` au statut **En cours** et y **inscrire nommément les subfeatures retenues
et non encore livrées**, afin que la Phase 3 de la vague puisse livrer sur F-142 sans violer la règle
CLAUDE.md « feature marquée Terminée ⇒ plus de SF en attente ».

## 2. Le défaut de gouvernance constaté
La ligne `F-142` porte le statut **Terminée** depuis SF-142-04 (2026-09-22), et ce statut a été
**réaffirmé** après SF-142-12 et SF-142-13 (2026-09-27, PR #971/#973) alors que **deux subfeatures
restent explicitement nommées comme à faire dans le dépôt lui-même** :

| Source dans le dépôt | Ce qui y est écrit |
|---|---|
| `docs/features/F-142/SF-142-12-un-type-inconnu-doit-se-voir.md` §Hors périmètre | « le choix du moteur (**SF-142-11**) » |
| `docs/features/F-142/SF-142-13-le-schema-est-reouvrable-dans-draw-io.md` §Hors scope | « le **choix automatique du moteur** (SF-142-11) » |
| `docs/features/F-142/SF-142-12-…` §Laissé tel quel, volontairement | « la *qualité* des suggestions de `strict` … **l'affiner est une autre subfeature**, pas un correctif glissé dans celle-ci » |

Une feature **Terminée** dont deux SF sont ouvertes est un **statut faux** : il masque du reste-à-faire
et, en vague autonome, il **bloque** (toute nouvelle SF sur F-142 tombe sous « feature close »).
`feat/SF-142-11-drawio` existe comme **branche vide** (aucun commit propre, `git cherry` muet) : le
numéro 11 a été **réservé puis contourné** par SF-142-13, jamais livré.

## 3. L'arbitrage (décidé par défaut, tracé ici)

### 3.1 Décision
`F-142` repasse **Terminée → En cours**, avec **deux** subfeatures inscrites :

| SF | Titre retenu | Pourquoi retenue | État |
|---|---|---|---|
| **SF-142-11** | **Le choix du moteur de diagramme** (`mermaid` / `cloud` / `drawio`) — choisi pour l'agent, pas deviné par lui | Nommée **deux fois** comme hors-scope explicite de 12 et 13 ; sans elle, trois moteurs coexistent et l'agent arbitre au hasard — exactement le défaut de doctrine que SF-142-10 a traité côté recettes | **À faire** (mini-spec à produire par son agent, étape 1) |
| **SF-142-14** | **La suggestion de `strict` doit nommer le bon type** | Reste-à-faire **écrit** dans SF-142-12 (« l'affiner est une autre subfeature ») ; aujourd'hui `aws.managedworkflowsforapacheairflow` → « aws.sf », ce qui n'apprend rien | **À faire** (mini-spec à produire par son agent, étape 1) |

**Numérotation** : `SF-142-11` est **repris tel quel** (numéro réservé et déjà cité dans deux
mini-specs — le renuméroter casserait les renvois) ; le numéro libre suivant est **14**
(01→10, 12, 13 pris ; vérifié `grep -rho "SF-142-[0-9][0-9]" docs/ | sort -u`).

### 3.2 Alternatives écartées
| Alternative | Pourquoi écartée |
|---|---|
| **Laisser F-142 « Terminée » et ouvrir une feature neuve** | Interdit par la consigne de vague (« NE CRÉE AUCUNE feature ») **et** faux sur le fond : le besoin (diagrammes exacts dans les livrables) est celui de F-142, pas un besoin nouveau |
| **Rouvrir sans nommer les SF** | Un statut « En cours » sans reste-à-faire écrit est aussi peu informatif que le « Terminée » qu'il remplace ; la règle CLAUDE.md demande le **statut maintenu à jour**, donc lisible |
| **Retenir aussi la visionneuse `.drawio` dans l'app, l'import d'un `.drawio` existant, les icônes officielles dans le `.drawio`** | Nommés hors scope de SF-142-13 mais **jamais** engagés comme reste-à-faire de F-142 : ce sont des extensions à cadrer avec le PO, pas des dettes. Les retenir gonflerait la feature sans demande |
| **Retenir le redéploiement de l'image `diagram-renderer`** | Ce n'est **pas** une subfeature mais un **drapeau de déploiement** (SF-142-09/12/13 « NON déployé ») ; il relève du déploiement unique de fin de vague, tenu par l'orchestrateur |

### 3.3 Réversibilité
**Totale** : le gate ne touche qu'à `docs/PRODUCT_SPEC.md` (statut + inscription) et ajoute ce
document. **Aucun code, aucune migration, aucun endpoint, aucun écran, aucun secret.** Revenir en
arrière = un `git revert` du commit de doc. Si le PO juge que F-142 doit rester close, remettre
**Terminée** et sortir les deux SF est l'affaire d'une ligne.

## 4. Comportement attendu (ce que le gate produit)
1. `docs/PRODUCT_SPEC.md` ligne `F-142` : colonne statut **En cours** (au lieu de **Terminée**).
2. La même cellule porte, **à la fin**, un encart de réouverture daté qui **nomme les deux SF**
   retenues et **renvoie à ce document** pour l'arbitrage.
3. Une entrée d'historique datée du **2026-09-27** dans le tableau des évolutions.
4. La phrase historique « F-142 reste **Terminée** » de SF-142-13 est **datée** (« était **Terminée**
   à cette date ») : on ne réécrit pas l'histoire, on lève la contradiction.

| Cas d'erreur | Comportement |
|---|---|
| Une des deux SF s'avère déjà livrée | La retirer de l'encart au moment de son étape 6 ; F-142 se referme quand les deux sont livrées |
| Une SF retenue dérive hors périmètre V1 (OCR/RAG/pgvector/Textract) | HALT de son agent, pas de ce gate : aucune des deux ne touche à ce périmètre (rendu de diagrammes, déjà livré) |
| Conflit d'édition sur la ligne F-142 (vague parallèle) | Rebase sur `origin/main` et réappliquer l'encart : il est en **fin** de cellule, donc additif |

## 5. Critères d'acceptation (vérifiables)
- [x] La colonne statut de `F-142` vaut **En cours** et plus **Terminée**.
- [x] `SF-142-11` **et** `SF-142-14` sont nommées dans la cellule F-142 avec leur objet en une phrase.
- [x] Aucune autre ligne de `PRODUCT_SPEC.md` n'est modifiée (diff limité à F-142 + 1 entrée d'historique).
- [x] L'arbitrage (décision, alternatives écartées, réversibilité) est **écrit** et **référencé** depuis la spec.
- [x] Aucun fichier de code, de migration, de configuration ou de secret n'est touché.
- [x] `SF-142-14` est un numéro **libre** (vérifié) et `SF-142-11` conserve le sens que lui donnent SF-142-12/13.

## 6. Plan de vérification minimal
Changement **documentaire pur** — pas de test unitaire ni d'intégration applicable
(`git diff --stat` : `docs/` uniquement). Vérifications exécutées :
1. `grep -c "^| F-142 |" docs/PRODUCT_SPEC.md` = **1** (ligne unique, pas de doublon créé).
2. Le statut lu en colonne 4 commence par `**En cours**`.
3. `SF-142-11` et `SF-142-14` présents dans la cellule F-142.
4. `git diff --stat` ne liste **que** des fichiers sous `docs/`.
5. Isolation `user_id`, `AIProvider`, secrets : **sans objet** (aucun accès données, aucun appel
   fournisseur, aucune clé).

## 7. Hors périmètre de ce gate
La **livraison** de SF-142-11 et SF-142-14 (chacune suivra son propre cycle CLAUDE.md, mini-spec
comprise) · le **redéploiement** de l'image `diagram-renderer` (drapeau de fin de vague) · toute
extension non demandée de F-142 (visionneuse `.drawio`, import `.drawio`, icônes officielles dans le
`.drawio`) · la création de **toute** feature nouvelle.

## 8. Préoccupations transversales
**Aucune cochée.** Pas d'auth/Principal, pas de contexte tenant, pas de plan/limite, pas de
navigation/routing : le changement est une ligne de spécification produit. Composants impactés :
`docs/PRODUCT_SPEC.md` (ligne F-142 + historique) et ce document. Aucun composant applicatif.

## 9. Checklists de gouvernance (artefacts)

### 9.1 Readiness — **PASS**
| Item | Verdict |
|---|---|
| Objectif en une phrase, comportement, ≥ 2 cas d'erreur, critères vérifiables, hors-scope | ✅ §1, §4, §5, §7 |
| Plan de test minimal | ✅ §6 — **changement documentaire pur** : unitaires/intégration/isolation **sans objet**, vérifications de cohérence de spec à la place |
| Contraintes de validation structurantes | ✅ sans objet (aucun champ, aucune saisie) |
| Tables impactées / endpoints / migration Liquibase | ✅ **aucun** |
| Question ouverte `OPEN_QUESTIONS.md` impactée | ✅ aucune |
| Branche depuis `main` à jour, une seule subfeature | ✅ `docs/wave-blocker-reouverture-f-142` créée depuis `origin/main` (`b87c31fb`). Nommage `docs/…` — conforme à l'usage du dépôt pour un changement **documentaire** (cf. `docs/SF-142-13-product-spec`), pas `feat/` |

### 9.2 Review — **PASS** (aucun bloquant)
| Famille | Verdict |
|---|---|
| Sécurité (isolation, données sensibles, rôles, stacktrace) | ✅ **sans objet** — aucun accès données, aucun endpoint, aucun secret dans le diff |
| Cohérence mini-spec | ✅ le diff fait exactement ce que §4 annonce, rien de plus |
| Tests | ✅ **sans objet** (documentaire) — vérifications §6 exécutées et vertes |
| Architecture (logique métier, Liquibase, IA synchrone, build/CI) | ✅ aucun code touché ; `AIProvider` / Gateway-First non concernés |
| Design system | ✅ sans objet (aucun composant frontend) |
| Documentation | ✅ `ARCHITECTURE_CANONIQUE.md` inchangé (aucune table) ; `OPEN_QUESTIONS.md` inchangé ; pas d'ADR (le gate ne décide rien d'architectural) |

### 9.3 Release — **PASS**
| Item | Verdict |
|---|---|
| Suite complète | **Non rejouée, volontairement** : le diff ne touche que `docs/`, et la CI backend ne se déclenche pas sur `docs/**` (cf. `ai-skills/autonomous-delivery-wave.md`). Dernier état connu : suite backend **5081 verte** (SF-142-13, PR #973) |
| Conflit / à jour avec `main` | ✅ branche créée sur `origin/main` du jour |
| Definition of Done, critères d'acceptation | ✅ §5 tous cochés |
| Migration / index / FK | ✅ sans objet |
| Données sensibles dans le diff | ✅ aucune |
| Documentation (contrat API, tables, questions ouvertes, ADR) | ✅ sans objet |
| Post-merge | Statut de la feature parente : c'est **l'objet même** du commit (F-142 → **En cours**). Déploiement : **aucun** (règle de vague : un seul déploiement en fin, par l'orchestrateur) |
