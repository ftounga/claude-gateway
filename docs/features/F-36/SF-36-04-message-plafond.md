# Mini-spec — [F-36 / SF-04] Message « plafond du run atteint » et lien vers le rachat (frontend)

---

## Identifiant

`F-36 / SF-04`

## Feature parente

`F-36` — Plafond de dépense &amp; facturation au coût réel

## Statut

`done` — livrée le 2026-08-26 (PR #169)

## Date de création

2026-08-26

## Branche Git

`feat/SF-36-04-message-plafond`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Dire à l'écran, dans le fil du Terminal, qu'un tour s'est arrêté sur **le plafond de dépense de ce
run** — message distinct du quota mensuel épuisé — avec l'action utile (relancer) et un lien vers le
rachat de tokens.

---

## Contexte

SF-36-01 pose un plafond de dépense par session et relaie `budgetReached` dans l'événement SSE `done`
(et dans la transcription persistée). Sans écran, l'utilisateur voit un tour qui s'arrête au milieu
sans explication — le pire des deux mondes : la protection agit et personne ne le sait.

« Ce run a atteint son plafond » n'est pas « votre quota mensuel est épuisé » (D6 du cadrage) : le
premier se règle en relançant (la sandbox garde son état, le run suivant repart d'un plafond neuf), le
second en attendant la période suivante ou en rachetant des tokens.

---

## Comportement attendu

### Cas nominal

1. Un run s'arrête sur son plafond : le flux se clôt par `done` (jamais `error`) avec
   `budgetReached: true`.
2. Le tour reste affiché avec sa réponse partielle, sa transcription et son coût — il a eu lieu.
3. Sous le tour apparaît un bloc dédié : « Plafond de dépense de ce run atteint », suivi de
   l'explication (le travail est conservé, relancer poursuit dans la même sandbox) et d'un lien
   **Racheter des tokens** menant à l'écran de facturation.
4. Au **rechargement** de la page, le tour est relu depuis l'historique et le bloc réapparaît (le
   drapeau est porté par la transcription persistée).
5. Un tour normal, ou un tour interrompu, n'affiche **jamais** ce bloc.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `budgetReached` absent du `done` (backend antérieur) | Traité comme `false` — aucun bloc, aucun message trompeur |
| Tour à la fois interrompu et au plafond | Les deux mentions s'affichent : ce sont deux faits distincts |
| Historique écrit avant F-36 (pas de `budgetReached`) | Traité comme `false` |
| Clic sur le lien de rachat | Navigation vers `/billing` (route existante, F-21) |

---

## Critères d'acceptation

- [x] `budgetReached: true` dans `done` ⇒ un bloc dédié s'affiche sous le tour, avec un libellé qui
      parle de **ce run** et non du quota mensuel.
- [x] Le bloc porte une action menant à l'écran de facturation (`/billing`).
- [x] Le tour conserve sa réponse, sa transcription, ses fichiers modifiés et son coût.
- [x] Le drapeau est **restauré au rechargement** depuis la transcription persistée.
- [x] Un tour nominal ou interrompu n'affiche pas le bloc.
- [x] Couleurs, polices et espacements conformes à `docs/DESIGN_SYSTEM.md` (variables de la charte,
      aucune valeur en dur) ; aucun `window.alert/confirm/prompt`.

---

## Périmètre

### Hors scope (explicite)

- Un écran de rachat dédié (F-21 existe déjà : on y renvoie).
- L'affichage du montant du plafond ou du coût en dollars (le montant rapporté est arrondi au cent et
  ne dirait rien d'actionnable).
- La modification du plafond depuis l'écran.

---

## Technique

### Endpoint(s)

Aucun. Le champ `budgetReached` est déjà servi par le backend (SF-36-01).

### Composants Angular

- `AtelierTerminalComponent` — nouveau bloc d'information sous le tour + sortie `openBilling`.
- `AtelierComponent` — lit `budgetReached` du `done` et de l'historique, relaie la navigation.
- `atelier.models.ts` / `atelier.types.ts` — champ additif `budgetReached`.

### Préoccupations transversales

| Préoccupation | Impact | Composants vérifiés |
|---------------|--------|---------------------|
| **Navigation / routing** | Aucune route ajoutée ni guard modifié : réutilise `/billing` par le `goToBilling()` déjà présent dans `AtelierComponent` (utilisé par le gating Gold) | `app.routes.ts` (inchangé), `AtelierComponent.goToBilling`, `AtelierTerminalComponent` |

### Migration Liquibase

- [x] Non applicable

---

## Plan de test

### Tests unitaires (Karma/Jasmine)

- [x] `atelier.component.spec` — `done` avec `budgetReached: true` ⇒ le tour ajouté porte le drapeau.
- [x] `atelier.component.spec` — `done` sans le champ ⇒ drapeau `false`.
- [x] `atelier.component.spec` — historique portant `budgetReached` ⇒ drapeau restauré.
- [x] `atelier-terminal.component.spec` — le bloc s'affiche si et seulement si le drapeau est vrai, et
      l'action émet `openBilling`.

### Tests d'intégration

- Sans objet (composant de présentation, aucun appel réseau ajouté).

### Isolation utilisateur

- [x] Non applicable — aucun accès aux données ; le drapeau provient du flux du workspace déjà ouvert.

---

## Dépendances

### Subfeatures bloquantes

- `SF-36-01` — **done** (elle produit le drapeau).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **A-1 — relancer d'abord, racheter ensuite.** Le rachat de tokens n'augmente le plafond d'un run que
  si c'est le **quota restant** qui bornait ; quand c'est le plafond par run (le cas courant), c'est
  relancer qui débloque. Le message dit donc d'abord ce qui marche, et propose le rachat en second —
  inverser inviterait à payer pour un blocage que le paiement ne lève pas.
- **A-2 — pas de montant affiché.** Le coût rapporté est arrondi au cent et le plafond dérive du quota
  restant : afficher « 2,00 $ » donnerait un chiffre sans prise, et exposerait une mécanique de coût
  fournisseur dans une interface facturée en tokens.
