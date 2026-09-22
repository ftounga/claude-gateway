# Mini-spec — [F-142 / SF-142-02] Diagrammes dans les slides (F-129)

## Identifiant

`F-142 / SF-142-02`

## Feature parente

`F-142` — Des diagrammes exacts dans les livrables (diagramme-as-code, pas d'images IA)

## Statut

`ready`

## Date de création

2026-09-22

## Branche Git

`feat/SF-142-02-diagrammes-dans-les-slides`

---

## Objectif

> En une phrase : apprendre à l'agent à insérer un **diagramme diagram-as-code rendu en image PNG**
> dans une **slide PPTX** (deck F-129) — un vrai schéma (flowchart, séquence, architecture) lisible à
> l'ouverture du fichier **et** dans la visionneuse in-app F-129.

---

## Comportement attendu

### Cas nominal

1. L'agent, à qui l'outil `presentation_publish` est donné (F-129 / SF-129-02), produit un `.pptx` sur
   le terminal avec `python-pptx` (skill `pptx`).
2. Pour un schéma d'architecture/flux/séquence, il **écrit le diagramme en Mermaid** (diagram-as-code,
   même doctrine que SF-142-01), le **rend en PNG dans le sandbox** avec `mmdc` (mermaid-cli), puis
   l'**insère dans une slide** via `slide.shapes.add_picture(png, ...)` — avec un **titre** de slide et,
   si utile, une **légende**.
3. Il rend ensuite le deck en images PNG (une par slide, recette SF-129-03 existante) et appelle
   `presentation_publish` avec `title`, `path` du `.pptx` et `slides` ordonnés.
4. La **visionneuse F-129** (SF-129-03) affiche le deck slide par slide ; la slide porteuse du diagramme
   est un PNG comme les autres — **aucune brique nouvelle** côté app.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `mmdc` (mermaid-cli) / chromium absent du sandbox et installation bloquée | **Échec nommé** (jamais de traceback nu) : dire clairement que le moteur de rendu de diagramme est indisponible ; **alternative documentée** — rendre le diagramme dans une **page** (SF-142-01, zéro-install, rendu navigateur) et livrer le deck **sans** l'image (ou avec le **code Mermaid en zone de texte**). Ne pas fabriquer de fausse image. |
| Code Mermaid invalide (rendu échoue) | Échec nommé du rendu de CE diagramme ; ne pas insérer d'image cassée ; livrer le reste du deck, signaler le diagramme en échec. |
| `python-pptx` absente | Cas déjà couvert par le skill `pptx` (import gardé, échec nommé) — inchangé. |

---

## Critères d'acceptation

- [ ] CA1 — Le skill `pptx` (déposé par `GovernancePackageSeeder` sous `.claude/skills/pptx.md`)
      contient une **recette « diagramme dans une slide »** : Mermaid → PNG (`mmdc`) → `add_picture`.
- [ ] CA2 — La recette **nomme l'échec** si le moteur de rendu de diagramme est absent (pas de traceback
      nu) et **documente l'alternative** (page SF-142-01 zéro-install / code Mermaid en zone de texte).
- [ ] CA3 — La recette rappelle la règle **factuelle** (F-119) : ne dessiner que l'établi, marquer le
      supposé — et que le rendu tourne **dans le sandbox/terminal, jamais sur le cluster**.
- [ ] CA4 — Le guide de l'outil `presentation_publish` (`PresentationToolCatalog.GUIDE`) mentionne
      qu'un **diagramme** peut être rendu en image et inséré dans une slide (renvoi au skill `pptx`),
      en cohérence avec le guide Mermaid des pages (SF-142-01).
- [ ] CA5 — `GovernancePackageSeederTest` reste **vert** et couvre le nouveau contenu de la recette
      (Mermaid, `mmdc`/rendu, `add_picture`, échec nommé).
- [ ] CA6 — `PresentationToolCatalogTest` reste **vert** et couvre la mention diagramme du guide.
- [ ] CA7 — Isolation `user_id` (+ `host_id`) **inchangée** : héritée du package `presentations`
      (garde d'espace `SpaceEntitlementService`), aucun accès aux données ajouté ni modifié.

---

## Périmètre

### Hors scope (explicite)

- **Icônes cloud officielles** AWS/Azure/GCP via lib `diagrams` + graphviz (sandbox) → **SF-142-03**.
- **Images illustratives IA** (OpenAI) → **SF-142-04**.
- Tout **rendu de diagramme côté serveur/cluster** : écarté (voir Notes) — le rendu vit dans le sandbox.
- Toute **modification backend fonctionnelle** (endpoint, service, entité, migration) : non nécessaire
  (voir Notes D1) — le backend F-129 lit/borne/range/sert déjà le `.pptx` et ses slides.
- Un **éditeur** de diagrammes interactif (on produit et on insère ; l'édition passe par le code).

---

## Contraintes de validation

| Champ | Règle |
|-------|-------|
| Moteur de rendu de diagramme (cœur) | **Mermaid** rendu en PNG via `mmdc` (mermaid-cli), dans le **sandbox**. |
| Insertion dans la slide | `python-pptx` `slide.shapes.add_picture(path, left, top, width=…)`. |
| Échec du moteur de rendu | **Nommé**, jamais de traceback nu ; alternative documentée (page SF-142-01 / code en texte). |
| Où tourne le rendu | **Sandbox/terminal uniquement** — aucun composant serveur/cluster (Gateway-First). |
| Doctrine du diagramme | Diagram-as-code (Mermaid), **factuel** (F-119) ; pas d'images IA pour un schéma. |

---

## Technique

### Endpoint(s)

**Aucun** nouvel endpoint ni modification. Le `.pptx` et ses slides-images sont déjà capturés/servis
par F-129 (`presentation_publish`, visionneuse SF-129-03).

### Tables impactées

**Aucune**. Aucune migration Liquibase.

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

**Aucun.** La visionneuse F-129 (SF-129-03) affiche déjà les slides en PNG ; une slide porteuse d'un
diagramme est un PNG comme les autres. `ng build` inchangé (aucun code frontend touché).

### Nouveaux fichiers / fichiers modifiés (backend)

- **Modifié** `backend/src/main/resources/governance/savoir-durable/pptx.md` — ajout d'une section
  « Insérer un diagramme dans une slide » (Mermaid → PNG via `mmdc` → `add_picture`, échec nommé,
  alternative, factuel F-119, sandbox uniquement).
- **Modifié** `PresentationToolCatalog.GUIDE` — courte mention « diagramme dans une slide » (renvoi
  skill `pptx`), alignée sur le guide Mermaid des pages.
- **Modifié (tests)** `GovernancePackageSeederTest` — assertions sur la recette diagramme.
- **Modifié (tests)** `PresentationToolCatalogTest` — assertion sur la mention diagramme du guide.

---

## Plan de test

### Tests unitaires

- [ ] `GovernancePackageSeederTest` — le contenu de `pptx.md` contient la recette diagramme : `Mermaid`,
      `mmdc` (ou terme de rendu), `add_picture`, l'échec nommé, la règle sandbox et le renvoi factuel.
- [ ] `PresentationToolCatalogTest` — `GUIDE` mentionne le diagramme inséré en slide (renvoi skill `pptx`).

### Tests d'intégration / service

- [ ] Aucun endpoint touché → pas de nouveau test d'intégration ; la suite `presentations` existante
      (`PresentationToolExecutorTest`, `PresentationServiceTest`) reste **verte** (non-régression).
- [ ] Démonstration de la chaîne « Mermaid → PNG → slide » : **spécifiée** dans le skill (parties pures /
      recette exécutable dans le sandbox) et **testable** via le contenu seedé ; la slide résultante est
      servie par la visionneuse F-129 **existante** (pas de code nouveau à tester côté app).

### Isolation utilisateur

- [x] Applicable — **inchangée**. La garde `presentation_publish` reste `SpaceEntitlementService`
      (droit d'espace du terminal possédé, `user_id`/`host_id`) ; aucun accès aux données ajouté.
      Couverte par les tests d'isolation existants du package `presentations`.

---

## Dépendances

### Subfeatures bloquantes

- `F-129` (SF-129-01→03) — **Terminée** : skill `pptx`, outil `presentation_publish`, visionneuse.
- `SF-142-01` — **Terminée** : doctrine Mermaid (diagram-as-code) réutilisée.

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Préoccupations transversales — analyse d'impact

| Préoccupation | Impactée ? | Composants |
|--------------|-----------|------------|
| Auth / Principal | Non | Aucun changement d'auth ; aucun endpoint touché. |
| Contexte tenant (`user_id`/`host_id`) | Non | Garde `presentation_publish` inchangée (`SpaceEntitlementService`) ; aucun accès données ajouté. |
| Plans / limites | Non | Aucun quota/gate touché (`PresentationLimits` inchangé). |
| Navigation / routing | Non | Aucune route ajoutée/modifiée (front comme back). |

---

## Notes et décisions

- **D1 — Zéro backend fonctionnel.** Gateway-First : le backend F-129 lit/borne/range/sert déjà le
  `.pptx` et ses slides-images ; un diagramme inséré est un PNG *dans* le `.pptx` et une slide-image de
  plus dans la visionneuse. **Aucun endpoint, service, entité ni migration** n'est nécessaire. Le cœur
  de SF-142-02 est **doctrinal** (skill `pptx` + guide de l'outil).
- **D2 — Rendu du diagramme : sandbox uniquement.** Mermaid → PNG via `mmdc` (mermaid-cli) **sur le
  terminal**, comme la conversion en PNG des slides (SF-129-03). **Aucun composant cluster** ; le cluster
  `legalcase-shared` est à capacité. Si un rendu serveur tentait : **NON** (drapeau, comme SF-129-03).
- **D3 — Échec nommé + alternative.** Si `mmdc`/chromium est absent et l'installation bloquée (poste
  banque), l'agent le **dit** ; alternative : rendre le diagramme dans une **page** (SF-142-01,
  zéro-install, rendu navigateur) ou insérer le **code Mermaid en zone de texte**. Jamais de fausse
  image ni de traceback nu (même doctrine que l'absence de `python-pptx`).
- **D4 — Factuel (F-119).** L'agent ne dessine que l'établi, marque le supposé « (supposé) ».
- **D5 — Runner : NON.** Aucun outil runner, aucune mise à jour du runner (rendu dans le sandbox).
- **D6 — Cohérence SF-142-01 / SF-129-03.** Même diagram-as-code (Mermaid), même « rendu dans le
  sandbox / jamais le cluster », même visionneuse en PNG. On **étend une recette**, on n'invente pas de
  parallèle.
