# Mini-spec — [F-142 / SF-142-01] Diagrammes Mermaid dans les pages (F-109)

## Identifiant

`F-142 / SF-142-01`

## Feature parente

`F-142` — Des diagrammes exacts dans les livrables (diagramme-as-code, pas d'images IA)

## Statut

`ready`

## Date de création

2026-09-22

## Branche Git

`feat/SF-142-01-mermaid-dans-les-pages`

---

## Objectif

> En une phrase : rendre en diagramme, dans une page F-109, les blocs Mermaid qu'un agent y
> dépose depuis une description d'architecture — le code Mermaid restant conservé et éditable.

---

## Comportement attendu

### Cas nominal

1. L'agent, à qui l'outil `page_publish` est donné (F-109 / SF-109-02), reçoit dans son guide de
   conception la consigne d'écrire un **bloc Mermaid** pour un schéma : `<pre class="mermaid">…</pre>`
   (flowchart, sequenceDiagram, ou **`architecture-beta`** pour une architecture cloud/on-prem).
2. Il publie la page ; le HTML stocké **contient le code Mermaid tel quel** (éditable, republiable).
3. À la lecture (`PageService.html`, servie par le ticket d'écran **et** par un lien de partage), la
   gateway **injecte un runtime Mermaid client-side** dans le HTML servi : le chargeur de la lib depuis
   `cdnjs` (déjà autorisé par la CSP des pages) + une initialisation `securityLevel:'strict'` + un rendu
   par diagramme.
4. Le navigateur, dans l'`iframe` bac-à-sable de la page, exécute le runtime : chaque bloc devient un
   **SVG**. Le code source reste dans le HTML stocké (rendu = à l'affichage seulement).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Bloc Mermaid syntaxiquement invalide | **Repli gracieux** : le bloc est remplacé par un encart montrant **le code** + un message court ; la page ne casse pas, les autres diagrammes se rendent. |
| Bibliothèque Mermaid non chargée (hors ligne / CDN injoignable) | Repli gracieux identique (code + message « bibliothèque non chargée ») ; la page reste lisible. |
| Page sans aucun bloc Mermaid | **Aucune transformation** — les octets servis sont **identiques** à ceux stockés (non-régression stricte). |
| HTML sans `</body>` mais avec un bloc Mermaid | Le runtime est **ajouté à la fin** du document (repli d'insertion). |
| HTML déjà porteur du runtime (marqueur `<!--cg-mermaid-->`) | **Idempotent** : pas de double injection. |

---

## Critères d'acceptation

- [ ] CA1 — Une page dont le HTML stocké contient `<pre class="mermaid">flowchart …</pre>` est servie
      (via `PageService.html`) avec le chargeur Mermaid `cdnjs` **et** le marqueur `<!--cg-mermaid-->`,
      **et** le code Mermaid d'origine est **toujours présent** (éditable).
- [ ] CA2 — Un bloc `architecture-beta` est détecté et le runtime injecté (le rendu couvre tout type de
      diagramme Mermaid ; flowchart et sequenceDiagram idem).
- [ ] CA3 — Le runtime injecté porte un **repli gracieux** (bloc `try`/`catch` + `.catch` de promesse
      qui affiche le code + un message) — vérifié par la présence de cette logique dans le HTML servi.
- [ ] CA4 — Une page **sans** bloc Mermaid est servie **octet pour octet identique** à ce qui est stocké
      (non-régression).
- [ ] CA5 — Double passage du runtime = une seule injection (idempotence sur `<!--cg-mermaid-->`).
- [ ] CA6 — Sécurité : l'initialisation Mermaid est en `securityLevel:'strict'` (Mermaid assainit le SVG,
      pas de HTML arbitraire dans les libellés, pas de handler `click`) ; **la CSP des pages est
      inchangée** (le script vient de `cdnjs`, déjà autorisé ; aucun `connect-src` requis).
- [ ] CA7 — Le guide de conception (`PageToolCatalog.DESIGN_GUIDE`) explique à l'agent comment émettre un
      bloc Mermaid, les trois types, la règle **factuelle** (F-119 : ne dessiner que l'établi, marquer le
      supposé), et qu'il **ne doit pas** ajouter lui-même le chargeur (la gateway le câble).
- [ ] CA8 — Isolation `user_id` (+ `host_id`) **inchangée** : l'injection opère sur le contenu déjà
      résolu par le service sous son filtre propriétaire ; aucun nouvel accès aux données.

---

## Périmètre

### Hors scope (explicite)

- Le rendu Mermaid **dans les slides PPTX** (F-129) → **SF-142-02**.
- Les **icônes cloud officielles** AWS/Azure/GCP (lib `diagrams` + graphviz, sandbox) → **SF-142-03**.
  `architecture-beta` rend la **structure** avec des icônes génériques ; les jeux d'icônes iconify de
  Mermaid ne sont **pas** chargés (ils exigeraient un `fetch` réseau, bloqué par `connect-src 'none'`).
- Les images illustratives IA → **SF-142-04**.
- Un **éditeur** interactif de diagrammes (on produit et on affiche ; l'édition passe par le code).
- Le **pré-rendu SVG côté serveur** : écarté (voir Notes) — la CSP autorise déjà le client-side.

---

## Contraintes de validation

| Champ | Règle |
|-------|-------|
| Détection d'un bloc Mermaid | `class="mermaid"` (guillemets simples ou doubles) **ou** un bloc clôturé ` ```mermaid … ``` `. |
| Conversion des blocs clôturés | ` ```mermaid\n…\n``` ` → `<pre class="mermaid">…</pre>` avec **échappement HTML** du code. |
| Version Mermaid | épinglée exacte (`cdnjs`, build UMD `window.mermaid`). |
| Insertion du runtime | avant le dernier `</body>` (insensible à la casse) ; sinon ajout en fin. |
| Idempotence | marqueur `<!--cg-mermaid-->` ; présent ⇒ aucune transformation. |

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint. Le contenu est servi par les routes existantes :
`GET /pages/{id}/content` (authentifié) et `GET /p/{token}/` (route publique de lecture/partage) —
toutes deux passent par `PageService.html(...)`, **seul point de câblage**.

### Tables impactées

Aucune. Aucune migration Liquibase.

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

**Aucun.** Une page F-109 est un document HTML servi par le backend et affiché dans un `iframe`
bac-à-sable (`PageFrameComponent`) ; l'app Angular ne rend jamais le contenu de la page. Le rendu Mermaid
vit **dans le HTML servi** (exécuté par le navigateur dans l'`iframe`). `ng build` est joué en
non-régression, sans changement de code frontend.

### Nouveaux fichiers / fichiers modifiés (backend)

- **Nouveau** `PageMermaidRuntime.java` — détection, conversion des blocs clôturés, injection idempotente
  du runtime client-side (chargeur `cdnjs` + init `strict` + rendu par diagramme + repli gracieux).
- **Modifié** `PageService.html(...)` — applique `PageMermaidRuntime.render(...)` au contenu servi ;
  `exportPlace(...)` l'applique aussi pour que les **livrables exportés** (ZIP) se rendent.
- **Modifié** `PageToolCatalog.DESIGN_GUIDE` — consigne d'émission d'un bloc Mermaid (types + factuel +
  « ne câble pas le chargeur toi-même »).

---

## Plan de test

### Tests unitaires (`PageMermaidRuntimeTest`)

- [ ] CA1 — HTML avec `<pre class="mermaid">flowchart` → sortie contient le script `cdnjs` mermaid, le
      marqueur `<!--cg-mermaid-->`, et **toujours** le code source d'origine.
- [ ] CA2 — HTML avec `architecture-beta` → runtime injecté.
- [ ] CA3 — La sortie contient la logique de repli gracieux (`catch` + message + affichage du code).
- [ ] CA4 — HTML sans mermaid → sortie **byte-identique** à l'entrée.
- [ ] CA5 — Deux passages = un seul runtime (idempotence).
- [ ] CA6 — L'init contient `securityLevel:'strict'`.
- [ ] Conversion — un bloc ` ```mermaid ` avec un `<` dans le code → `<pre class="mermaid">` avec `&lt;`.
- [ ] Insertion — sans `</body>`, le runtime est en fin ; avec `</body>`, il est **avant**.

### Tests d'intégration / service (`PageServiceTest`)

- [ ] `html(...)` d'une page publiée avec un bloc Mermaid → contenu servi porte le runtime (le code
      stocké, lui, reste intact — vérifié via `PageStore`).
- [ ] `html(...)` d'une page **sans** Mermaid → contenu servi identique au stocké.

### Isolation utilisateur

- [x] Applicable — inchangée : `PageService.html` résout déjà le contenu sous `findByIdAndUserId` ;
      l'injection n'ajoute aucun accès aux données ni ne franchit le filtre propriétaire. Couverte par les
      tests d'isolation existants de `PageServiceTest` (une page d'un autre compte reste introuvable).

---

## Dépendances

### Subfeatures bloquantes

- `F-109` (SF-109-01→06) — **Terminée** : outil `page_publish`, politique de contenu, routes de lecture.

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Préoccupations transversales — analyse d'impact

| Préoccupation | Impactée ? | Composants |
|--------------|-----------|------------|
| Auth / Principal | Non | Aucun changement d'auth ; routes inchangées. |
| Contexte tenant (`user_id`/`host_id`) | Non | `PageService.html` conserve son filtre `findByIdAndUserId` ; l'injection est un post-traitement du contenu déjà résolu. |
| Plans / limites | Non | Aucun quota ni gate touché. |
| Navigation / routing | Non | Aucune route ajoutée/modifiée (front comme back). |
| **Sécurité / CSP / XSS** | **Oui** | `PageContentPolicy` (CSP) **inchangée** — `cdnjs` déjà autorisé ; Mermaid en `securityLevel:'strict'` (assainissement SVG conservé). Le contenu reste servi en origine opaque, `iframe` sandbox, `connect-src 'none'`. |

---

## Notes et décisions

- **D1 — Voie de rendu : client-side Mermaid via `cdnjs`, injecté côté serveur au moment de servir.**
  La CSP des pages (`PageContentPolicy`) autorise **déjà** les scripts depuis `cdnjs`/`jsDelivr` et
  `'unsafe-inline'` : le rendu client-side est donc possible **sans toucher la sécurité**. Le pré-rendu
  SVG serveur (moteur Mermaid côté JVM) est écarté : plus lourd, inutile ici. **Drapeau** : si un jour la
  CSP fermait `cdnjs`, il faudrait embarquer la lib en asset local ou basculer sur un pré-rendu serveur.
- **D2 — Point de câblage unique : `PageService.html(...)`.** Les deux routes de lecture (écran + partage)
  y passent ; une seule couture, aucune divergence. Appliqué aussi à `exportPlace(...)` pour que les
  **livrables exportés** (le ZIP que le PO remet) se rendent hors ligne.
- **D3 — Le HTML stocké reste pristine.** L'injection n'a lieu qu'à la **livraison** ; le code Mermaid
  reste éditable et republiable tel quel (aucun aller-retour du runtime dans le stockage).
- **D4 — Repli gracieux par diagramme.** Chaque bloc est rendu isolément ; une erreur n'affecte que son
  bloc (code + message), jamais la page.
- **D5 — Runner : NON.** Le rendu est dans la page (navigateur) ; aucun outil runner, aucune mise à jour
  du runner.
- **D6 — Factuel (F-119).** Consigne (pas de garde dure) : l'agent ne dessine que l'établi et marque le
  supposé.
