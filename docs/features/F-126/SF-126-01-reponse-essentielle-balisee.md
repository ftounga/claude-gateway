# Mini-spec — [F-126 / SF-126-01] La réponse essentielle, balisée et mise en avant

---

## Identifiant

`F-126 / SF-126-01`

## Feature parente

`F-126` — Terminal : la réponse essentielle mise en avant + questions repérables

## Statut

`ready`

## Date de création

2026-09-17

## Branche Git

`feat/SF-126-01-reponse-essentielle-balisee`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Faire baliser par l'agent la **réponse essentielle** (la réponse directe et courte à la question) au moyen d'une convention robuste, et la **mettre en avant** visuellement dans le fil du terminal (bloc « L'essentiel »), le reste s'affichant en « Le détail » — avec repli gracieux sans marqueur.

---

## Comportement attendu

### Cas nominal

1. **Backend** — `AtelierChatService.buildSystemPrompt` (cibles **RUNNER** et **SANDBOX**) ajoute, en tête du préfixe stable (aux côtés de la discipline SF-119-02, de la doctrine SF-120-01, du style SF-121-03 et du silence SF-125-01, sans les écraser), une consigne non négociable : commencer la réponse par la réponse directe et courte, encadrée par un marqueur dédié `<<essentiel>> … <</essentiel>>`, puis développer le détail dessous.
2. **Frontend** — au rendu d'un message de l'assistant, si le marqueur est présent, le contenu entre `<<essentiel>>` et `<</essentiel>>` est rendu dans un **bloc « L'essentiel »** distinct (liseré or, fond doré léger, texte un peu plus gros, label « L'essentiel »), et le reste dans **« Le détail »** en style normal (comme la maquette validée `docs/features/F-126/design/maquette-terminal-essentiel.html`).
3. Chaque partie (essentiel, détail) est rendue via le pipe `markdown` existant (assainissement DOMPurify + strip `fin-de-tour` inchangé).

### Marqueur retenu

- Ouverture : `<<essentiel>>` ; fermeture : `<</essentiel>>`.
- Détection **tolérante** : insensible à la casse, espaces libres à l'intérieur des chevrons (`<< essentiel >>`).
- **Ne collisionne pas** avec le strip `fin-de-tour` de F-125 (`/<!--\s*fin-de-tour\s*:[\s\S]*?-->/`) : ce n'est pas un commentaire HTML, et le strip ne le cible pas. Le marqueur `<<essentiel>>` est **du contenu à afficher** (découpé, jamais « expliqué »), et n'est **jamais retiré comme métadonnée**.

### Cas d'erreur / dégradés

| Situation | Comportement attendu |
|-----------|---------------------|
| Message **sans** marqueur (ancien message historique, ou l'agent n'en met pas) | S'affiche **normalement**, sans bloc « L'essentiel » — aucune régression (rendu identique à l'existant). |
| Marqueur d'ouverture présent mais **fermeture absente** (troncature / streaming en cours) | Tolérant : tout ce qui suit l'ouverture est traité comme l'essentiel (le marqueur brut n'est **jamais** affiché littéralement) ; pas de détail tant que la fermeture n'est pas arrivée. |
| Essentiel **vide** entre les marqueurs | Aucun bloc « L'essentiel » n'est rendu ; le reste s'affiche normalement (pas de bloc vide). |
| Message contenant un marqueur `fin-de-tour` **et** un marqueur essentiel | Le `fin-de-tour` est strippé (F-125) ; le bloc essentiel est rendu — les deux mécanismes coexistent sans interférence. |

---

## Critères d'acceptation

- [ ] Backend : `buildSystemPrompt` contient la consigne de balisage essentiel (substrings marqueur + « essentiel ») sur la cible **SANDBOX**.
- [ ] Backend : idem sur la cible **RUNNER**.
- [ ] Backend : les consignes préexistantes (discipline SF-119-02, doctrine SF-120-01, style SF-121-03, silence SF-125-01) restent présentes (non-régression de coexistence).
- [ ] Frontend : un message avec marqueur rend un bloc « L'essentiel » **et** un bloc « Le détail » distincts.
- [ ] Frontend : un message **sans** marqueur rend normalement, **sans** bloc « L'essentiel » (repli gracieux).
- [ ] Frontend : le marqueur brut (`<<essentiel>>`/`<</essentiel>>`) n'apparaît jamais dans le DOM rendu.
- [ ] Frontend : le strip `fin-de-tour` (F-125) fonctionne toujours et n'affecte pas le marqueur essentiel (coexistence testée).
- [ ] Charte : bloc « L'essentiel » en jetons `--cg-*` existants (or/gold-ink), **aucune couleur nouvelle** ; contraste AA du label sur le fond doré.

---

## Périmètre

### Hors scope (explicite)

- La stylisation des **questions utilisateur** et le **rail de navigation** (→ SF-126-02).
- Toute persistance / migration / endpoint : le marqueur voyage dans le contenu du message déjà persisté.
- La peau du terminal Teams pour ce bloc : SF-126-01 vise le rendu du commentaire de l'agent tel que déjà rendu (`.terminal-agent`) ; l'adaptation Teams si nécessaire reste dans les règles de peau existantes (aucune couleur nouvelle).
- Toute réimplémentation d'une capacité IA (Provider-First) : on **demande** à l'agent de baliser, on ne synthétise rien côté Gateway.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune. Aucune migration.

### Composants Angular

- `frontend/src/app/atelier/terminal/essential.ts` (nouveau) — fonction pure `splitEssential(content)` → `{ essential: string | null, detail: string }`.
- `atelier-terminal.component.ts` — méthode exposant le découpage au template.
- `atelier-terminal.component.html` — rendu conditionnel bloc « L'essentiel » / « Le détail » à la place du seul `<p class="terminal-agent">`.
- `atelier-terminal.component.scss` — styles `.terminal-essential*` / `.terminal-detail*` (jetons `--cg-*`).

### Backend

- `AtelierChatService.java` — nouvelle constante `ESSENTIAL_ANSWER_DOCTRINE`, appended dans `buildSystemPrompt` après `CARD_SILENCE_DOCTRINE`.

---

## Plan de test

### Tests unitaires (backend)

- [ ] `AtelierChatServiceSystemPromptTest` — consigne essentiel présente sur SANDBOX (marqueur + « essentiel »).
- [ ] `AtelierChatServiceSystemPromptTest` — consigne essentiel présente sur RUNNER.
- [ ] `AtelierChatServiceSystemPromptTest` — coexistence : les 4 consignes préexistantes restent présentes.

### Tests unitaires (frontend)

- [ ] `essential.spec.ts` — `splitEssential` : marqueur présent → essentiel + détail ; absent → `essential=null`, détail=tout ; ouverture sans fermeture → tout en essentiel ; casse/espaces tolérés ; essentiel vide → `null`.
- [ ] `atelier-terminal.component.spec.ts` (ou spec dédiée) — message avec marqueur rend `.terminal-essential` + `.terminal-detail` ; message sans marqueur rend `.terminal-agent` seul (pas de `.terminal-essential`) ; marqueur brut absent du DOM.
- [ ] `markdown.pipe.spec.ts` — non-régression : le strip `fin-de-tour` fonctionne toujours (déjà couvert), et `splitEssential` n'altère pas ce strip.

### Isolation workspace

- [ ] Non applicable — aucune donnée n'est lue/écrite ; la subfeature agit sur le prompt système et le rendu client.

---

## Préoccupations transversales

| Préoccupation | Impactée ? | Analyse |
|--------------|-----------|---------|
| Auth / Principal | Non | Aucun changement d'authentification. |
| Contexte tenant | Non | Aucun accès données ; `buildSystemPrompt` reçoit déjà `userId`/`workspace`. |
| Plans / limites | Non | Aucun gate touché. |
| **Navigation / routing** | Non | Aucune route (le rail de navigation est SF-126-02). |

Composants du rendu du fil impactés : **le seul point de rendu du commentaire de l'agent** est `atelier-terminal.component.html` (`.terminal-agent`, ligne ~562), partagé par tous les terminaux (projet, poste, Teams, lecture seule, mosaïque) via le même composant `AtelierTerminalComponent`. Le rendu en streaming (`live.text`, ligne ~704) reçoit le même découpage tolérant (fermeture absente → essentiel en cours, jamais de marqueur brut).

---

## Dépendances

### Subfeatures bloquantes

- F-125 (SF-125-01) — **Done** : strip `fin-de-tour`. On garantit la non-collision.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Forme du marqueur (drapeau)** : `<<essentiel>> … <</essentiel>>` retenu plutôt qu'un commentaire HTML (`<!-- -->`) pour ne PAS entrer dans la famille du strip `fin-de-tour`, et plutôt qu'un préfixe de ligne (`> essentiel:`) qui se confondrait avec une citation Markdown. Robuste, tolérant (casse + espaces), et sans signification Markdown parasite une fois découpé.
- Le découpage se fait sur le **contenu brut** avant le pipe `markdown` ; chaque moitié repasse par `markdown` (donc DOMPurify + strip `fin-de-tour`).
</content>
