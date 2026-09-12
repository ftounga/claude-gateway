# Mini-spec — F-74 / SF-74-02 — « Terminal du poste » sur la carte, et le terminal qui se nomme

## Identifiant

`F-74 / SF-74-02`

## Feature parente

`F-74` — Un terminal au niveau du poste

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-74-02-terminal-du-poste-ecran`

---

## Objectif

Mettre le terminal du poste **à un clic** depuis sa carte sur l'accueil de la Forge, et faire en
sorte qu'une fois ouvert, il **se nomme** pour ce qu'il est — sans rien apprendre de nouveau à
l'utilisateur (P2).

---

## Comportement attendu

### Cas nominal

1. L'utilisateur est sur `/forge`. Sur la carte d'un poste — **une machine**, jamais « Hébergé » —,
   à côté d'« Ajouter un projet », un bouton **« Terminal du poste »**.
2. Il clique. L'écran appelle `POST /api/runner-hosts/{id}/terminal` (SF-74-01), reçoit
   l'identifiant, et **navigue vers `/atelier/{id}`**.
3. C'est **le terminal habituel** : même écran, même barre, mêmes réglages, sa conversation et son
   historique. Rien de nouveau (P2).
4. Il tape `git clone …`. La porte de confirmation est **armée** (P4) — elle demande, comme partout
   ailleurs depuis F-73 / SF-73-02.
5. De retour sur `/forge`, la carte du poste montre la **pastille de vie** (`app-live-badge`) à
   côté du bouton tant que l'onglet vit, et le compteur « Terminaux vivants : n / 4 » de l'en-tête
   **l'inclut** (P3).

### Le terminal ouvert se nomme

Dans la liste latérale de l'Atelier, il apparaît avec l'icône `terminal` (et non `folder_zip`) et,
en dessous, la **pastille du poste** comme n'importe quel projet de cette machine. Le menu
« Supprimer le projet » **ne s'y affiche pas** (D6) : ce n'est pas un projet, et le supprimer
comme tel produirait une recréation silencieuse au clic suivant.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| L'appel échoue (réseau, gateway muette) | Message court en `snackBar`, **aucune navigation**. On reste sur la carte, rien n'est perdu. |
| Le poste n'existe plus (404) | Même message, et l'écran se **rafraîchit** — la carte a vieilli. |
| Le plafond de quatre terminaux vivants est atteint | C'est l'écran du terminal qui le dit, **comme pour un projet** (F-70) : le chemin n'est pas dupliqué ici. |
| Poste « Hébergé » | **Le bouton n'existe pas.** Ce n'est pas une machine (P6). |

---

## Critères d'acceptation

- [ ] La carte d'un poste **réel** porte un bouton « Terminal du poste » ; la carte « Hébergé » ne
      le porte pas.
- [ ] Le clic appelle `POST /runner-hosts/{id}/terminal` **une seule fois** (bouton désactivé
      pendant l'appel) puis navigue vers `/atelier/{id}`.
- [ ] Un échec d'appel affiche un message et **ne navigue pas**.
- [ ] Quand `hostTerminalLive` est vrai, la pastille `app-live-badge` s'affiche à côté du bouton.
- [ ] Dans la liste latérale de l'Atelier, un terminal de poste porte l'icône `terminal` et **pas**
      le menu de suppression.
- [ ] Le bouton est atteignable au clavier et porte un `aria-label` qui nomme le poste.
- [ ] **Aucune couleur nouvelle** : le bouton est un `mat-stroked-button` de la carte, la pastille
      de vie est celle de F-70, et le filet d'identité du poste (§9) est inchangé.

---

## Périmètre

### Hors scope (explicite)

- Un raccourci vers le terminal du poste ailleurs que sur la carte (barre latérale, fil d'Ariane).
- Renommer ou supprimer le terminal d'un poste depuis l'écran.
- Tout changement de l'écran de terminal lui-même : il est **repris tel quel** (P2).

---

## Technique

### Endpoint(s) consommés

| Méthode | URL | Rôle |
|---------|-----|------|
| POST | `/api/runner-hosts/{hostId}/terminal` | retrouver ou créer le terminal du poste (SF-74-01) |
| GET | `/api/runner-hosts/overview` | `hostTerminalId`, `hostTerminalLive` (SF-74-01) |

### Composants Angular

- `PostesComponent` (`/forge`) — le bouton, l'appel, la navigation, l'état d'attente.
- `postes.component.html` / `.scss` — le bouton dans `poste__card-actions`, la pastille de vie.
- `AtelierComponent` + `atelier.component.html` — icône `terminal`, menu de suppression masqué.
- `core/models/atelier.models.ts` — `RunnerHostOverview.hostTerminalId` / `hostTerminalLive`,
  `WorkspaceSummary.hostTerminal`.
- `core/services/*` — la méthode d'appel, dans le service qui porte déjà les postes.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable — aucun changement de schéma côté frontend.

---

## Plan de test

### Tests unitaires (Jasmine / Karma)

- [ ] `PostesComponent` — le bouton est rendu sur un poste réel, absent sur « Hébergé ».
- [ ] `PostesComponent` — le clic appelle le service puis navigue vers `/atelier/{id}`.
- [ ] `PostesComponent` — un échec affiche un message et **ne navigue pas**.
- [ ] `PostesComponent` — `hostTerminalLive` vrai ⇒ pastille de vie rendue.
- [ ] `AtelierComponent` — un workspace `hostTerminal` porte l'icône `terminal` et pas le menu de
      suppression.

### Isolation workspace

- [x] Applicable — aucun identifiant d'utilisateur ne transite par l'écran ; l'isolation est celle
      de la gateway (SF-74-01), qui vérifie l'appartenance du poste avant tout.

---

## Dépendances

### Subfeatures bloquantes

- `SF-74-01` — statut : à livrer **avant**.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés et vérification |
|---|---|---|
| Auth / Principal | Non | aucun nouveau chemin d'authentification |
| Contexte tenant | Non | l'écran n'envoie qu'un `hostId` déjà affiché ; la gateway tranche |
| Plans / limites | **Oui** — plafond F-70 | l'en-tête « Terminaux vivants : n / 4 » et l'écran du terminal sont **inchangés** : ils comptent des terminaux, pas des projets. Vérifié par le test existant de `LiveTerminalService`. |
| Navigation / routing | **Oui** — une navigation de plus vers `/atelier/:id` | **aucune route nouvelle** : `/atelier/:id` existe depuis SF-30-10 et reçoit ici un identifiant de workspace comme tout autre. Chemins vérifiés : `/forge` → `/atelier/:id` (nouveau clic), `/atelier` (liste), fil d'Ariane inchangé. |
| Design system | **Oui** | Aucun quatrième registre de couleur : identité (§9) sur le filet de carte, statut (§5 / §10) sur les pastilles de mission, vie (§11) sur `app-live-badge`. Le bouton reprend le gabarit d'« Ajouter un projet ». |

---

## Notes et décisions

Voir `F-74-cadrage.md` — P2 (rien de nouveau à apprendre), D6 (il n'entre pas dans les gestes de
projet), et la table des registres de couleur.
