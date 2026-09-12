# Mini-spec — F-83 / SF-83-01 — Le terminal, en lecture seule

## Identifiant

`F-83 / SF-83-01`

## Feature parente

`F-83` — La mosaïque : quatre terminaux en même temps

## Statut

`in-progress`

## Date de création

2026-09-12

## Branche Git

`feat/SF-83-01-terminal-lecture-seule`

---

## Objectif

Le **terminal existant** sait s'afficher en **lecture seule** — même flux, même rendu, même fond de
terminal, sans en-tête, sans réglages et sans invite — pour qu'une tuile de mosaïque **réutilise** le
terminal au lieu d'en fabriquer une copie.

---

## Comportement attendu

### Cas nominal

1. `AtelierTerminalComponent` reçoit `[readOnly]="true"`.
2. Il rend **exactement le même flux** qu'en mode complet : les blocs (commande `$` + sortie,
   repli des sorties longues, marqueur de sous-tâche), le commentaire de l'agent, le plan de travail,
   la ligne vivante (action, étapes, tokens, chrono) et le défilement automatique.
3. Il **ne rend plus** : la barre d'en-tête (fil d'Ariane, moteur, pastille de vie, interruption,
   garde, git, actions), le bandeau de refus du cinquième terminal, le réglage « Où s'exécutent les
   outils », l'état du poste, la proposition de runner, le résultat de `git push`, et **l'invite**.
4. Le **fond** du terminal en lecture seule est `var(--cg-navy-2)` — le fond du terminal, pas une
   nouvelle surface. Aucune couleur nouvelle n'est introduite.
5. Une **autorisation attendue** reste visible : au lieu du bloc de décision (qui porte des boutons),
   une mention **écrite** « Attend votre autorisation », la commande concernée, et la phrase qui dit
   où décider — palette **§5 « En attente »** (`#FFF8E1` / `#F9A825`), aucun quatrième registre.
6. Terminal en lecture seule **sans rien à montrer** : une ligne sobre « Au repos — rien ne tourne à
   l'instant », et non l'invitation à saisir une demande (on ne peut rien saisir ici).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| `readOnly` + tentative d'envoi (`submit()`) | **Aucun événement `send` émis** — l'invite n'existe pas, et le composant refuse aussi par le code | — |
| `readOnly` + `liveLimitReached` vrai | Aucun bandeau de refus : cette vue n'ouvre rien, elle ne peut être refusée | — |
| `readOnly` + demande d'autorisation en cours | Mention écrite **sans** bouton « Autoriser » / « Refuser » — décider est un geste du terminal entier | — |
| Aucun bloc, aucun tour | Ligne « Au repos », jamais l'invitation à saisir | — |

---

## Critères d'acceptation

- [ ] `[readOnly]="true"` ⇒ aucun `form.terminal-input`, aucun `.terminal-bar`, aucun
      `.terminal-target`, aucun `.terminal-host-state`, aucun `.terminal-live-limit` dans le DOM.
- [ ] `[readOnly]="true"` ⇒ les blocs, le commentaire de l'agent, le plan et la ligne vivante sont
      rendus **à l'identique** (mêmes classes, mêmes libellés) qu'en mode complet.
- [ ] Le fond du terminal en lecture seule est **exactement** `#141D33` (`--cg-navy-2`), vérifié par
      `getComputedStyle`.
- [ ] `submit()` appelé en lecture seule n'émet **aucun** `send`.
- [ ] Une autorisation attendue est signalée **par un texte** (« Attend votre autorisation ») et
      sans aucun bouton de décision.
- [ ] Aucune régression : le mode complet (défaut `readOnly = false`) rend tout ce qu'il rendait.
- [ ] Suite frontend verte.

---

## Périmètre

### Hors scope (explicite)

- La mosaïque elle-même (SF-83-02) et l'agrandissement d'une tuile (SF-83-03).
- Écrire depuis une tuile — tranché dès F-76, inchangé.
- Toute modification backend : aucune.
- Retirer ou modifier les aperçus de F-76.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|---|---|---|
| `readOnly` | `false` | Le terminal reste, par défaut, le terminal complet : aucun appelant existant ne change |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Unicité | Normalisation |
|---|---|---|---|---|---|
| `readOnly` | Non | — | booléen | — | `?? false` |

Aucun champ utilisateur : cette subfeature n'ajoute ni saisie, ni appel réseau.

---

## Technique

### Endpoint(s)

Aucun. **Aucun appel réseau ajouté, aucun endpoint créé ou modifié.**

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `AtelierTerminalComponent` — entrée `readOnly`, gabarit conditionné, fond `--cg-navy-2`, mention
  d'autorisation en lecture seule, ligne « Au repos ».

---

## Plan de test

### Tests unitaires

- [ ] `atelier-terminal.component.spec` — lecture seule : ni invite, ni barre, ni réglages, ni
      bandeau de plafond.
- [ ] `atelier-terminal.component.spec` — lecture seule : blocs, commentaire, plan et ligne vivante
      rendus comme en mode complet.
- [ ] `atelier-terminal.component.spec` — **le fond est `#141D33`** (verrou de l'exigence du PO).
- [ ] `atelier-terminal.component.spec` — `submit()` en lecture seule n'émet rien.
- [ ] `atelier-terminal.component.spec` — autorisation attendue : texte présent, boutons absents.
- [ ] `atelier-terminal.component.spec` — mode complet inchangé (non-régression).

### Tests d'intégration

Sans objet : aucune route, aucun appel réseau.

### Isolation workspace

- [x] Non applicable — composant de **présentation** : il ne fait aucun appel réseau et ne lit
      aucune donnée. L'isolation est portée par les appels du parent (SF-83-02).

---

## Dépendances

### Subfeatures bloquantes

- `SF-84-02` — statut : `done` (le rebranchement lecture seule existe déjà côté gateway).

### Questions ouvertes impactées

Aucune.

---

## Préoccupations transversales

| Préoccupation | Composants vérifiés un par un | Verdict |
|---|---|---|
| **Plans / limites (plafond F-70)** | `LiveTerminalService` (front) : **non appelé** par ce composant, qui ne fait aucun appel réseau ; `live_terminals` (table) : intouchée ; 409 `terminal_limit_reached` : le bandeau est **masqué** en lecture seule mais son code est inchangé ; `app-live-badge` (pastille) : masquée avec la barre, composant inchangé ; en-tête « n / 4 » de `/forge` : hors de ce composant, intouché | traité |
| **Navigation / routing** | Aucune route ajoutée ni modifiée | traité |
| **Auth / Principal** | Aucun appel, aucun `Principal` | traité |
| **Contexte tenant** | Aucune lecture de données | traité |

---

## Notes et décisions

- **Réutiliser plutôt qu'extraire.** Un composant « transcription » partagé aurait obligé à déplacer
  la moitié de `atelier-terminal.component.scss` et à dupliquer le rendu Markdown de l'agent. Le
  terminal **est** le composant du flux : lui apprendre à se taire coûte moins et, surtout, garantit
  qu'il n'y aura **jamais deux rendus à diverger** — c'est la contrainte posée au cadrage.
- **`--cg-navy-2` et non `--cg-primary`.** Le terminal complet peint son fond en `--cg-primary`
  (`#1A3A5C`) ; le PO a nommé `--cg-navy-2` (`#141D33`) explicitement, et par son hexadécimal. Les
  deux jetons existent déjà : aucune couleur nouvelle. Le fond plus sombre distingue en outre une
  tuile qu'on regarde d'un terminal où l'on écrit. **Réversible** : un jeton dans un fichier SCSS.
