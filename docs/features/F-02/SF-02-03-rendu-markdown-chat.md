# Mini-spec — [F-02 / SF-02-03] Rendu Markdown des réponses dans le chat

## Identifiant

`F-02 / SF-02-03`

## Feature parente

`F-02` — Chat proxy Claude

## Statut

`ready`

## Date de création

2026-07-03

## Branche Git

`feat/SF-02-03-rendu-markdown-chat`

---

## Objectif

Afficher les réponses de l'assistant en **Markdown rendu** (titres, gras, listes, liens, blocs de code) au lieu du texte brut où l'on voit les balises `##`, `**`, etc., en assainissant le HTML produit pour écarter tout risque XSS.

---

## Contexte

`PRODUCT_SPEC` (F-02) : « Streaming et rendu Markdown = améliorations ultérieures ». Aujourd'hui `chat.component.html` rend le contenu par interpolation brute (`{{ message.content }}`, ligne 99) → les balises Markdown s'affichent littéralement. Cette SF livre le **rendu Markdown** (le streaming est traité séparément, chantier B).

---

## Comportement attendu

### Cas nominal

- Un message **ASSISTANT** dont le contenu est du Markdown est rendu en HTML formaté : titres (`#`…`######`), gras/italique, listes ordonnées/non, liens, `code inline` et blocs ``` ``` ```, citations, tableaux, traits horizontaux.
- Les **blocs de code** et le `code inline` sont en **JetBrains Mono** (design system). Les liens s'ouvrent dans un nouvel onglet (`target="_blank" rel="noopener noreferrer"`).
- Les messages **USER** restent affichés en **texte brut** (pas d'interprétation Markdown de la saisie utilisateur), avec conservation des retours à la ligne.
- Le style des éléments rendus respecte la charte (palette, espacements multiples de 4px, corps Inter).

### Cas d'erreur / limites

| Situation | Comportement attendu |
|-----------|---------------------|
| Contenu contenant du HTML dangereux (`<script>`, `<img onerror>`, `javascript:`…) | **Assaini** par DOMPurify avant affichage : le HTML dangereux est supprimé, jamais exécuté |
| Markdown malformé | Rendu « au mieux » par le parseur, sans exception ni casse de la page |
| Contenu vide | Rien rendu, pas d'erreur |

---

## Critères d'acceptation

- [ ] Une réponse ASSISTANT en Markdown affiche des éléments **HTML rendus** (un `##` devient un `<h2>`, un `**gras**` devient `<strong>`), plus aucune balise Markdown littérale visible.
- [ ] Le code (inline et bloc) s'affiche en **JetBrains Mono** sur fond distinct conforme à la charte.
- [ ] Les liens rendus portent `target="_blank"` et `rel="noopener noreferrer"`.
- [ ] Un contenu contenant `<script>…</script>` ou `<img src=x onerror=…>` est **assaini** : le script n'est pas exécuté (vérifié par test unitaire sur le pipe).
- [ ] Les messages **USER** restent en texte brut (un `**` tapé par l'utilisateur reste littéral), retours à la ligne préservés.
- [ ] Aucune couleur/police hors `DESIGN_SYSTEM.md` n'est introduite.

---

## Périmètre

### Hors scope (explicite)

- **Streaming** des réponses (chantier B, subfeature dédiée).
- **Refonte de la charte** graphique (chantier C, amendement `DESIGN_SYSTEM.md`).
- Coloration syntaxique du code (highlight.js/Prism) : hors scope V1 de cette SF — le code est en police mono sans coloration par langage (évolution possible ultérieure).
- Rendu Markdown ailleurs que dans le chat (ex. écran /ask) : hors scope ici.
- Rendu KaTeX / mathématiques.

---

## Préoccupation transversale

Aucune préoccupation transversale déclenchée : pas de changement d'auth/Principal, pas de contexte tenant, pas de plan/limite, **pas de nouvelle route ni de guard** (modification interne au composant `chat` existant). Changement **100 % frontend**, aucun endpoint ni table impactés.

---

## Technique

### Endpoints / Tables

- Aucun. Frontend uniquement (le contenu Markdown provient déjà de `POST /chat`).

### Migration Liquibase

- [x] Non applicable.

### Dépendances npm ajoutées

| Paquet | Rôle | Justification |
|--------|------|---------------|
| `marked` | Markdown → HTML | Parseur standard, léger, sans dépendance |
| `dompurify` | Assainissement HTML | **Sécurité** : le contenu vient d'un LLM, doit être neutralisé avant `[innerHTML]` |
| `@types/dompurify` (dev) | Types TS | Typage |

> Choix assumé : `marked` + `dompurify` (contrôle explicite de l'assainissement) plutôt que `ngx-markdown` (wrapper plus lourd). Réversible.

### Composants Angular

- **`MarkdownPipe`** (`frontend/src/app/shared/markdown.pipe.ts`, standalone, `name: 'markdown'`) : `transform(md: string): SafeHtml` = `marked.parse(md)` → `DOMPurify.sanitize(html, { ADD_ATTR: ['target'] })` → `DomSanitizer.bypassSecurityTrustHtml(clean)`. Config `marked` : liens `target="_blank" rel="noopener noreferrer"`. Pipe **pur** (mémoïsé).
- **`chat.component.html`** : pour les messages ASSISTANT, remplacer `{{ message.content }}` par `<div class="markdown-body" [innerHTML]="message.content | markdown"></div>` ; conserver le texte brut pour les messages USER (`white-space: pre-wrap`).
- **`chat.component.scss`** : styles `.markdown-body` (titres, listes, `pre`/`code` en JetBrains Mono avec fond `#F8FAFC`/bordure `#E2E8F0`, liens en `#4338CA`, espacements 4px), conformes au design system.

---

## Plan de test

### Tests unitaires (Jasmine)

- [ ] `MarkdownPipe` — `## Titre` → contient `<h2`, `**gras**` → contient `<strong>`, liste `- a` → `<li>`, ``` `code` ``` → `<code>`.
- [ ] `MarkdownPipe` — **sécurité** : `<script>alert(1)</script>` en entrée → la sortie ne contient pas de balise `<script>` exécutable ; `<img src=x onerror=alert(1)>` → attribut `onerror` supprimé.
- [ ] `MarkdownPipe` — liens : `[x](https://e.com)` → `target="_blank"` et `rel` contient `noopener`.
- [ ] `MarkdownPipe` — entrée vide/undefined → chaîne vide, pas d'exception.

### Tests de composant

- [ ] `ChatComponent` — un message ASSISTANT avec `**x**` produit un `<strong>` dans le DOM rendu ; un message USER avec `**x**` reste littéral (pas de `<strong>`).

### Isolation utilisateur

- [x] Non applicable — aucun accès données (rendu d'un contenu déjà autorisé et renvoyé par l'API pour l'utilisateur courant).

---

## Dépendances

### Subfeatures bloquantes

- `SF-02-02` (écran de chat) — Done.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Sécurité d'abord** : le contenu affiché provient d'un LLM ; tout rendu HTML **doit** passer par DOMPurify avant `[innerHTML]`. C'est le point de review bloquant de cette SF.
- **USER en texte brut** : on n'interprète pas le Markdown de la saisie utilisateur (évite qu'un `**` tapé soit transformé, et réduit la surface d'injection). Alignement avec le comportement de claude.ai.
- **Pas de coloration syntaxique** dans cette SF : maîtrise du périmètre et du poids de bundle ; ajout possible plus tard sur la même base.
