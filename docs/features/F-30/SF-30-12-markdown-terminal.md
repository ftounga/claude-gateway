# Mini-spec — [F-30 / SF-30-12] Le texte de l'agent rendu en Markdown dans le terminal

---

## Identifiant

`F-30 / SF-30-12`

## Feature parente

`F-30` — Atelier, expérience terminal.

## Statut

`done` — livrée le 2026-08-29 (PR #187)

## Date de création

2026-08-29

## Branche Git

`feat/SF-30-12-markdown-terminal`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Afficher le commentaire de l'agent dans le terminal **mis en forme**, au lieu du Markdown brut où
l'on lit aujourd'hui les `**`, les `##` et les backticks.

---

## Contexte

Le terminal interpole le texte de l'agent tel quel, à deux endroits :

- `atelier-terminal.component.html:189` — `<p class="terminal-agent">{{ message.content }}</p>` (historique)
- `atelier-terminal.component.html:306` — `<p class="terminal-agent">{{ live.text }}</p>` (tour en cours)

Le modèle répond en Markdown : l'utilisateur lit donc `**important**`, `## Étape 2` et des blocs de
code encadrés de backticks.

**Rien à écrire côté rendu** : le pipe `markdown` existe depuis F-02 / SF-02-03
(`frontend/src/app/shared/markdown.pipe.ts`) — `marked` pour le rendu, **DOMPurify** pour
l'assainissement, avec un hook qui force `target="_blank"` + `rel="noopener noreferrer"` sur les
liens. Le chat l'utilise déjà sur du contenu de même origine (un LLM). La subfeature consiste à
**réutiliser** ce pipe et à lui donner une tenue compatible avec le fond sombre du terminal.

### Ce que le terminal impose au rendu

Le fil du terminal n'est pas une page de document : les titres géants, les grandes marges et les
listes largement espacées y casseraient la densité qui fait l'intérêt de la vue. La mise en forme
doit rester **discrète** — du gras qui se voit, du code lisible, des listes compactes — et les blocs
de code doivent s'aligner sur l'apparence des sorties de commandes déjà présentes
(`pre.terminal-output`).

---

## Comportement attendu

### Cas nominal

1. L'agent répond avec du Markdown ; le terminal affiche **le rendu**, plus les marqueurs.
2. Gras, italique, code en ligne, blocs de code, listes, titres, liens et citations sont rendus.
3. Les **titres** (`#` → `######`) sont ramenés à une taille proche du texte courant, distingués par
   la graisse et non par l'échelle : un `#` en pleine hauteur écraserait le fil.
4. Les **blocs de code** reprennent l'aspect des sorties de commandes (fond légèrement contrasté,
   police monospace, défilement horizontal propre) et ne débordent jamais de la largeur du fil.
5. Le rendu s'applique **pendant** le tour (texte au fil de l'eau) comme dans l'historique.
6. Les liens s'ouvrent dans un nouvel onglet, sans `opener` — comportement déjà porté par le pipe.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Texte vide ou `null` | Rien n'est affiché (le bloc reste absent), comme aujourd'hui |
| Markdown incomplet en cours de flux (`**` non refermé, bloc de code non clos) | Rendu sans erreur ni exception : `marked` ferme implicitement, l'affichage se stabilise à mesure que le texte arrive |
| HTML dans la réponse du modèle (`<script>`, `onerror`, `javascript:`) | **Neutralisé par DOMPurify** avant affichage — c'est déjà la garantie du pipe, et elle est retestée ici |
| Texte très long sur une seule ligne (chemin, URL, base64) | Coupé proprement, jamais de défilement horizontal du fil entier |

---

## Critères d'acceptation

- [ ] Le commentaire de l'agent est rendu en Markdown dans l'**historique** et pendant le **tour en cours**
- [ ] `**gras**`, `` `code` ``, blocs de code, listes, titres et liens s'affichent mis en forme
- [ ] Le HTML dangereux est neutralisé (test explicite sur `<script>` et sur un attribut `onerror`)
- [ ] Les liens portent `target="_blank"` et `rel="noopener noreferrer"`
- [ ] Aucun élément rendu ne provoque de défilement horizontal du fil
- [ ] Les titres restent à une échelle proche du texte courant (densité du terminal préservée)
- [ ] Suite frontend verte, `npm run build` vert

---

## Périmètre

### Hors scope (explicite)

- Le rendu Markdown des **messages de l'utilisateur** (`terminal-request`) : ce qu'il a tapé doit
  s'afficher tel qu'il l'a tapé.
- Les **sorties de commandes** (`pre.terminal-output`) : ce sont des flux bruts, les interpréter
  serait un contresens — une accolade ou une étoile dans un log n'est pas du Markdown.
- La **coloration syntaxique** des blocs de code : gain esthétique réel, mais elle ajoute une
  dépendance (highlight.js ou équivalent) et un choix de thème ; à instruire pour elle-même.
- La copie d'un bloc de code en un clic : le chat a `copy-block` (F-22), l'apporter ici est une
  subfeature distincte.
- Le rendu Markdown du chat (F-02), inchangé.

---

## Valeurs initiales

Sans objet — aucune donnée, aucune valeur par défaut introduite.

---

## Contraintes de validation

| Élément | Contrainte |
|---|---|
| Contenu rendu | Toujours passé par `renderMarkdown()` (marked + DOMPurify), jamais par un `[innerHTML]` direct |
| Attributs conservés | `target` uniquement (déjà déclaré `ADD_ATTR` dans le pipe) |
| Largeur | Tout bloc large (code, tableau) défile **dans son propre conteneur**, jamais dans le fil |

---

## Technique

### Endpoint(s)

Aucun. Changement strictement frontend : le texte arrive déjà par le flux existant.

### Tables impactées

Aucune.

### Migration Liquibase

Aucune.

### Composants Angular (si applicable)

- `atelier/terminal/atelier-terminal.component.html` — les deux `terminal-agent` passent en
  `[innerHTML]="… | markdown"`.
- `atelier/terminal/atelier-terminal.component.ts` — import de `MarkdownPipe`.
- `atelier/terminal/atelier-terminal.component.scss` — habillage `.terminal-agent` : titres ramenés à
  l'échelle du fil, listes compactes, `code`/`pre` alignés sur `terminal-output`, `overflow-x` porté
  par les blocs larges. Uniquement des jetons `--cg-*` du design system.

---

## Plan de test

### Tests unitaires

- Le DOM du composant contient bien `<strong>` pour `**gras**` et `<code>` pour `` `code` ``
  (historique **et** tour en cours).
- Un texte contenant `<script>alert(1)</script>` ne produit aucun élément `script` dans le DOM.
- Un `<img onerror=…>` est rendu sans l'attribut `onerror`.
- Un lien Markdown produit un `<a target="_blank" rel="noopener noreferrer">`.
- Texte vide → aucun bloc `terminal-agent` dans le DOM.

### Tests d'intégration

Sans objet — aucun changement backend, aucun endpoint touché.

### Isolation workspace

Sans objet : aucun accès aux données n'est ajouté ni modifié. Le contenu affiché est celui du tour
déjà chargé pour ce workspace, dont la possession est vérifiée côté backend (inchangé).

---

## Dépendances

### Subfeatures bloquantes

Aucune. `MarkdownPipe` (F-02 / SF-02-03) est livré et utilisé en production par le chat.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

**Décision — réutiliser le pipe du chat, ne pas en écrire un second.** Le contenu a la même origine
(un LLM) et donc le même profil de risque ; deux chemins de rendu voudraient dire deux politiques
d'assainissement à maintenir, et un jour deux comportements. Provider-First s'applique aussi à
l'intérieur du produit.

**Décision — titres ramenés à l'échelle du texte.** Le Markdown standard donne aux titres une taille
qui a du sens dans un document. Dans un fil de terminal dense, un `##` en grande police repousse la
sortie hors de l'écran. La hiérarchie est donc portée par la graisse et la couleur, pas par la taille.
