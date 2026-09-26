# Mini-spec — [F-158 / SF-158-23] À l'ouverture d'un terminal, aller au fond (dernier message) — mobile et desktop

## Identifiant

`F-158 / SF-158-23`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`ready`

## Date de création

2026-09-26

## Branche Git

`feat/SF-158-23-aller-au-fond-terminal-mobile`

---

## Objectif

> En une phrase : que fait cette subfeature ?

À l'entrée dans un terminal de l'Atelier, la vue se place **directement sur le dernier message** (fond du fil) et **continue à suivre le nouveau contenu**, sur **mobile** comme sur **desktop**.

---

## Comportement attendu

### Cas nominal

- **À l'ouverture** d'un terminal sur une conversation existante (longue ou courte), la vue affiche immédiatement le **dernier message** — plus besoin de défiler à la main pour atteindre le bas.
- **Pendant un tour** (nouveau contenu qui arrive : réponse, sortie de commande, sous-agents), la vue **suit** le contenu et reste ancrée au bas, exactement comme avant sur desktop.
- Le saut au fond est **instantané** (pas d'animation longue) et fonctionne quel que soit l'élément réellement défilant : le conteneur `.terminal-scrollback` sur desktop, la **fenêtre** (`window` / `document.scrollingElement`) sur mobile (≤ 819 px), où `.terminal-scrollback` n'est pas le scroller.

### Cause racine (confirmée)

`atelier-terminal.component.ts`, `ngAfterViewChecked()` faisait : `el.scrollTop = el.scrollHeight` sur `.terminal-scrollback`. Sur **desktop** cet élément est bien le conteneur défilant (`overflow-y:auto`) → l'auto-scroll marche. Sur **mobile** (≤ 819 px), `.terminal-scrollback` n'est **pas** le scroller (`scrollHeight === clientHeight`) — c'est la **fenêtre** qui défile —, donc `el.scrollTop = el.scrollHeight` est un **no-op** et la vue reste en haut du fil.

### Approche retenue — sentinelle + `scrollIntoView`

Une **sentinelle** (`<div #bottomSentinel>`) est posée en **toute fin de `.terminal-thread`** (dernier enfant du fil). Le suivi appelle `sentinel.scrollIntoView({ block: 'end' })` : `scrollIntoView` défile **l'ancêtre défilant quel qu'il soit** (fenêtre sur mobile, `.terminal-scrollback` sur desktop). C'est le même mécanisme déjà éprouvé dans ce composant (`scrollToQuestion`, `revealPendingAsk`). Le déclencheur (changement de `scrollHeight`) est conservé — il change quand le contenu grandit, y compris sur mobile.

### Cas d'erreur / cas limites

| Situation | Comportement attendu |
|-----------|---------------------|
| Sentinelle absente du DOM (vue pas encore montée) | Aucun appel, aucun crash (`?.`) |
| `scrollHeight` inchangé (aucun contenu nouveau) | Aucun défilement forcé (garde `!== lastScrollHeight`) |
| Contenu asynchrone (images, markdown) qui grandit après le 1er rendu | Chaque évolution de hauteur re-déclenche le saut → l'ancrage au fond **tient** une fois le contenu posé |
| `prefers-reduced-motion` | Saut **instantané** (pas de `behavior:'smooth'`) → rien à respecter, aucune animation ajoutée |

---

## Critères d'acceptation

> Chaque critère est vérifiable.

- [ ] La sentinelle `#bottomSentinel` est le **dernier enfant** de `.terminal-thread`.
- [ ] À l'entrée (1er rendu avec messages), `scrollIntoView({block:'end'})` est appelé **sur la sentinelle** (saut au fond garanti).
- [ ] À l'arrivée de nouveau contenu (message ajouté → `scrollHeight` change), `scrollIntoView` est appelé **de nouveau** sur la sentinelle (suivi du flux).
- [ ] Le saut est **instantané** (aucun `behavior:'smooth'` ; aucune règle `scroll-behavior:smooth` sur le scroller — vérifié).
- [ ] **Non-régression desktop** : le suivi du flux fonctionne toujours (même chemin de code, `scrollIntoView` sur la sentinelle défile `.terminal-scrollback`).
- [ ] La sentinelle `scrollIntoView` de l'invite courante (`revealPendingAsk`, `.ask`) et le saut depuis le rail (`scrollToQuestion`) sont **inchangés**.
- [ ] Le rail « Vos questions » en tête du fil (SF-158-22) est **inchangé** ; « aller au fond » = aller au dernier message (tout en bas), le rail restant accessible en défilant vers le haut.

---

## Périmètre

### Hors scope (explicite)

- Le comportement « ne suivre que si déjà au fond » (ne pas contrarier un utilisateur qui a défilé vers le haut) : **non traité** ici — on conserve à l'identique la sémantique actuelle (suivi forcé sur changement de hauteur) pour éviter toute régression ; seul le **no-op mobile** est corrigé.
- Toute animation « smooth » du suivi.
- Le desktop (déjà fonctionnel) au-delà de la non-régression.
- Tout backend / endpoint / migration / DTO.

---

## Technique

### Endpoint(s)

Aucun. **Pur frontend, display-only.**

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Non applicable

### Composants Angular

- `AtelierTerminalComponent` (`frontend/src/app/atelier/terminal/`) — `atelier-terminal.component.html` (ajout de la sentinelle en fin de `.terminal-thread`) + `atelier-terminal.component.ts` (`@ViewChild('bottomSentinel')`, `ngAfterViewChecked` → `scrollIntoView`, helper `scrollToBottom`).

---

## Préoccupations transversales

| Préoccupation | Impactée ? | Composants |
|--------------|-----------|------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |

Aucune préoccupation transversale déclenchée : changement local au seul composant terminal, sans nouvel accès aux données ni route.

---

## Plan de test

### Tests (Karma, ChromeHeadless)

Fichier DOM-réel `terminal-largeur-reelle.spec.ts` (hôte ancré à `document.body` → `scrollHeight` réel) :

- [ ] **Saut au fond à l'entrée** : avec des messages, au 1er rendu, `scrollIntoView` est appelé sur l'élément portant `.terminal-bottom-sentinel` (rouge-avant : l'ancien `scrollTop=` ne touche pas la sentinelle / vert-après).
- [ ] **Suivi du nouveau contenu** : ajout d'un message → `scrollIntoView` rappelé sur la sentinelle.
- [ ] **Structure** : la sentinelle est le dernier enfant de `.terminal-thread`.

Fichier `atelier-terminal.component.spec.ts` :

- [ ] **Non-régression** : `scrollToQuestion` appelle toujours `scrollIntoView` sur l'ancre (test existant conservé).

### Isolation utilisateur / workspace

- [ ] Non applicable — aucun accès aux données (composant de présentation, pur affichage).

---

## Dépendances

### Subfeatures bloquantes

- SF-158-22 (rail en tête du fil) — **Done** (compatibilité vérifiée, non modifiée).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Choix approche 2 (sentinelle) plutôt que la détection de scroller (approche 1)** : `scrollIntoView` délègue au navigateur le choix de l'ancêtre défilant (fenêtre ou conteneur), sans brancher explicitement sur la largeur ni relire `overflow` — plus robuste et cohérent avec les deux `scrollIntoView` déjà présents dans le composant.
- **Instant, pas smooth** : aucun `scroll-behavior:smooth` dans les 15 feuilles du terminal (vérifié) → `scrollIntoView` est instantané ; conforme à « saut instantané préférable à l'entrée » et à « respecter `prefers-reduced-motion` » (aucune animation introduite).
- **Sémantique de suivi inchangée** : le garde `scrollHeight !== lastScrollHeight` et le déclenchement à chaque évolution de hauteur sont conservés → pas de boucle de détection (le scroll ne change pas `scrollHeight`), et l'entrée est couverte par le passage de hauteur 0 → contenu.
