# Testing Strategy — claude-gateway

## Principe

Chaque subfeature doit être testée avant d'être considérée Done.
Les tests ne sont pas optionnels.
Un plan de test minimal est défini dans la mini-spec avant le début du dev.

---

## Pyramide de tests

```
          [ E2E ]           ← peu nombreux, flux critiques uniquement
        [ Intégration ]     ← endpoints, jobs, pipeline IA
      [ Unitaires ]         ← logique métier, transformations, règles
```

---

## 1 — Tests unitaires

**Périmètre :** Logique métier dans les services, transformations de données, règles de validation.

**Ce qui doit être testé :**
- Toutes les méthodes de service contenant une logique métier
- Les cas nominaux ET les cas d'erreur
- Les règles de validation (format, présence, cohérence)
- Les transformations entité ↔ DTO

**Ce qui n'est pas testé en unitaire :**
- Les controllers (testés en intégration)
- Les repositories JPA (testés en intégration)
- La configuration Spring

**Framework :** JUnit 5 + Mockito (backend) | Jasmine + Jest (frontend Angular)

**Exemple de structure :**
```java
@Test
void createCaseFile_shouldThrowException_whenWorkspaceNotFound() { ... }

@Test
void createCaseFile_shouldPersistAndReturnDto_whenValid() { ... }
```

---

## 2 — Tests d'intégration

**Périmètre :** Endpoints REST, pipeline IA, jobs asynchrones, requêtes SQL.

**Ce qui doit être testé :**
- Chaque endpoint exposé : nominal + cas d'erreur
- Les codes HTTP retournés (200, 201, 400, 403, 404, 409, 500)
- Le contenu des réponses (structure du JSON)
- **L'isolation workspace** : un utilisateur du workspace A ne doit pas voir les données du workspace B
- Les migrations SQL : la base de test doit être à jour

**Framework :** Spring Boot Test + `@SpringBootTest` + TestContainers (PostgreSQL)

**Règle isolation workspace — test obligatoire :**
```java
@Test
void getCaseFile_shouldReturn403_whenCaseFileBelongsToAnotherWorkspace() { ... }
```

**Jobs asynchrones :**
- Tester le changement d'état du job (PENDING → RUNNING → DONE)
- Tester le cas d'échec (FAILED + `error_message` renseigné)
- Ne pas tester le LLM réel en intégration → utiliser un mock du provider

---

## 3 — Tests E2E

**Périmètre :** Flux complets critiques, de l'UI jusqu'à la base de données.

**Flux à couvrir en priorité (V1) :**
1. Login OAuth → création workspace → redirection dashboard
2. Création dossier → upload document → lancement analyse → consultation résultat
3. Réponse à une question IA → déclenchement nouvelle synthèse

**Framework :** Cypress (Angular) ou Playwright

**Règle :** Les E2E ne remplacent pas les tests d'intégration. Ils valident les flux utilisateur critiques uniquement.

---

## 4 — Couverture des cas d'erreur

Pour chaque subfeature, les cas d'erreur suivants doivent être testés si applicables :

| Cas | Code attendu | Exemple |
|-----|-------------|---------|
| Données invalides | 400 | champ obligatoire absent |
| Non authentifié | 401 | token absent ou expiré |
| Accès interdit | 403 | mauvais workspace |
| Ressource absente | 404 | dossier inexistant |
| Conflit | 409 | slug workspace déjà pris |
| Résultat partiel | 207 | upload multi-fichiers avec certains fichiers rejetés |
| Erreur serveur | 500 | exception non gérée (testée via mock) |

**207 Multi-Status — règles de test :**

Utiliser 207 quand une opération porte sur plusieurs éléments et que le résultat varie par élément (succès partiel).

- La réponse doit contenir un tableau avec le statut de chaque élément traité
- Tester le cas nominal : tous les éléments réussis → préférer 200/201 au 207
- Tester le cas mixte : certains éléments réussis, d'autres rejetés → 207 avec détail par élément
- Tester le cas d'échec total : tous les éléments rejetés → 207 ou 400 selon la sémantique métier (à préciser dans la mini-spec)
- Vérifier que les éléments rejetés incluent un motif explicite dans le corps de la réponse

```java
// Exemple de structure de réponse 207
[
  { "filename": "contrat.pdf",  "status": "STORED",  "documentId": "uuid-1" },
  { "filename": "image.png",    "status": "REJECTED", "error": "MIME type non autorisé" }
]
```

---

## 5 — Tests spécifiques au pipeline IA

Le pipeline IA est asynchrone. Les tests ne doivent pas appeler le LLM réel.

**Stratégie :**
- Mocker le provider LLM dans les tests d'intégration
- Tester le découpage en chunks (logique déterministe)
- Tester la persistance des résultats d'analyse (structure, champs attendus)
- Tester la progression du job (`analysis_jobs`)
- Tester le déclenchement de la synthèse enrichie après réponse avocat

---

## 6 — Données de test

**Règles :**
- Toujours utiliser des données de test isolées (pas de données partagées entre tests)
- Nettoyer les données après chaque test (ou utiliser des transactions rollback)
- Ne jamais utiliser de données de production pour les tests

**Fixtures recommandées :**
- Workspace de test dédié
- Utilisateur de test avec rôle `LAWYER`
- Dossier de test vide + dossier de test avec documents

---

## 7 — Plan de test minimal (mini-spec)

Avant de démarrer le dev, le plan de test de la mini-spec doit spécifier :

```
Tests unitaires :
- [ ] Cas nominal : [description]
- [ ] Cas d'erreur : [description]

Tests d'intégration :
- [ ] Endpoint X → 201 avec payload valide
- [ ] Endpoint X → 400 avec champ manquant
- [ ] Endpoint X → 403 si workspace différent

Isolation workspace :
- [ ] Oui / Non applicable
```

Ce plan est validé avant le démarrage du dev et reviewé dans la PR.


---

## Quand jouer la suite complète (2026-09-10)

**Mesure qui motive cette règle** : la suite backend compte **1 844 tests** et dure environ
**3 minutes**, le frontend 1 064 tests pour 50 secondes. Rejouée à chaque étape d'une subfeature —
pendant le développement, avant la review, avant la release —, elle devenait le **premier poste de
temps** d'une livraison, sans rien vérifier de plus la deuxième et la troisième fois : c'est le même
code.

| Moment | Ce qu'on joue |
|---|---|
| Pendant le développement | **les tests du périmètre touché** (`-Dtest=…`, `--include`), aussi souvent qu'on veut |
| **Review checklist** | **la suite complète, une fois** — c'est le point de vérité |
| Release checklist | **rien de plus**, on cite le résultat de la review |

**Deux conditions pour que la seconde exécution soit inutile** : aucun commit n'a été ajouté entre
la review et la release, et la branche n'a pas été rebasée. Si l'une des deux tombe, on rejoue —
la règle supprime une redondance, pas une vérification.

**Ce qui ne change pas** : un test rouge reste bloquant, l'isolation `user_id` reste exigée, et le
plan de test de la mini-spec reste écrit avant le développement.

## Vitesse de livraison — règles opposables (2026-11-07)

**Mesure qui motive cette mise à jour** : la suite a grossi — **939 classes de test backend** (131 au
1er septembre), **420 specs** frontend. La suite backend complète dure **~18 min** seule et **28 à 31 min**
quand plusieurs agents la jouent en même temps sur le poste. La règle du 2026-09-10 ci-dessus existait,
mais le script des vagues ordonnait la suite complète à chaque subfeature : des features ont pris 1 à
3 jours. Ces règles priment sur toute consigne contraire d'un prompt, d'un script ou d'une mémoire.

| # | Règle | Comment |
|---|---|---|
| V1 | **Pendant le dev : tests ciblés seulement** | `./mvnw -q test -Dtest='Classe1Test,Classe2*Test' -Dsurefire.failIfNoSpecifiedTests=false` ; `npx ng test --watch=false --include=src/app/<zone>` |
| V2 | **Suite complète : UNE fois par subfeature**, à la review checklist, après `git fetch` + rebase sur `origin/main` | Rouge → corriger, rejouer **les classes rouges seulement**, puis une dernière suite complète. Jamais trois suites de suite « pour voir ». |
| V3 | **Toute commande lourde passe par `scripts/build-slot.sh`** (2 créneaux par poste) | `scripts/build-slot.sh ./mvnw -q test` ; `scripts/build-slot.sh npx ng test --watch=false`. Au-delà de 2, on attend son tour au lieu de ralentir tout le monde. |
| V4 | **Au premier plan, toujours** | Jamais de build en tâche de fond attendu par notification : les agents calent (11 h perdues le 2026-11-04). |
| V5 | **Garde anti-calage** | L'orchestrateur surveille chaque agent ; aucun fichier écrit pendant 30 min (`find -mmin -30`) = agent calé → il reprend la main. |
| V6 | **Worktree supprimé après merge** | `target/` + `node_modules/` d'un worktree ≈ 1 Go : 114 Go saturés le 2026-11-05, plus aucune commande ne répondait. |
| V7 | **Un seul déploiement par vague**, jamais par subfeature | Le déploiement coûte ~20 min ; il reconstruit le runner seulement si `runner/` a changé (cache d'image). |
| V8 | **Bancs Jarvis : inchangés** | 2 passages verts avant merge + 1 tour en prod, dès qu'un prompt ou un modèle Jarvis change (décision PO, ne pas alléger). Rien quand aucun prompt ne bouge. |
| V9 | **Banc à vraie voix avant toute mise en ligne de Jarvis** (F-256, décision PO du 2026-11-11) | Dès qu'une PR touche l'écoute, la voix, le cerveau rapide ou l'affichage de `/jarvis` : `frontend/scripts/banc-jarvis-voix/lancer.sh --build`, au **premier plan**, **seul** sur le poste (latences mesurées : pas de suite Maven en parallèle) et **une session de transcription à la fois** (compte ElevenLabs partagé avec la prod). ≈ 15 min, ≈ 0,5 € par passage. |

