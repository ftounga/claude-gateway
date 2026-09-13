# Mini-spec — F-98 / SF-98-01 — La colonne des postes

## Identifiant

`F-98 / SF-98-01`

## Feature parente

`F-98` — La Forge, refondue (cadrage : `CADRAGE-F-98-la-forge-refondue.md` §3 D1, D4, D5 ; maquette
validée `maquette-forge-refondue.html`)

## Statut

`done`

## Date de création

2026-09-13

## Branche Git

`feat/SF-98-01-colonne-des-postes`

---

## Objectif

Remplacer la pile verticale des cartes de poste par une **colonne des postes** à gauche (une ligne
par poste, groupée par ce qu'elle demande, filtrable) et **un seul poste ouvert** à droite, désigné
par l'URL `/forge/:hostRef`, sous un **bandeau de synthèse de la flotte**.

---

## Comportement attendu

### Cas nominal

1. **Bandeau de flotte** (en tête, une ligne) : « Forge », « ● *N* postes en ligne sur *M* »
   (postes réels non clôturés), « ● *N* autorisation(s) attend(ent) » (affiché seulement si > 0,
   tous postes et terminaux du poste confondus), « *n* / 4 terminaux vivants » avec la phrase de ce
   que ça engage (§11), puis les boutons existants (Voir travailler, Mosaïque — fusionnés en
   SF-98-04 —, Rafraîchir en icône) et « + Connecter un poste » (action principale).
2. **Colonne** (288 px) :
   - **Filtre** « Filtrer les postes et projets » : cherche, sans casse ni accents, dans le nom du
     poste **et** les noms de ses projets. Un poste qui ne correspond que par des projets reste
     listé et affiche « *k* projet(s) trouvé(s) » à la place du compte.
   - **Groupes par attention**, dans cet ordre, un groupe vide n'étant pas rendu :
     *À regarder* (au moins un projet ou le terminal du poste en `AWAITING_APPROVAL`) ·
     *En ligne* · *Hors ligne* · *Sans machine* (le poste « Hébergé », toujours présent) ·
     *Missions clôturées (n)* — repli **fermé** à l'ouverture, qui s'ouvre d'un clic.
   - **Ligne de poste** : pastille d'initiales (`app-host-badge`), nom écrit, point + libellé daté
     (« en ligne · vu il y a 12 s », F-97), à droite le drapeau « *k* attend » (§12, « En attente »)
     ou le nombre de projets. Le poste sélectionné porte un fond de survol et un **filet gauche de
     sa couleur** (§9). Le poste « Hébergé » n'a ni point ni couleur d'identité.
   - En bas : « + Connecter un poste ».
3. **Sélection dans l'URL** : cliquer une ligne navigue vers `/forge/<id>` (`/forge/heberge` pour le
   poste « Hébergé »), en conservant les paramètres de requête. `/forge` sans poste ouvre, **sans
   changer l'URL**, le premier poste *À regarder*, sinon le premier *En ligne*, sinon le premier
   non clôturé, sinon « Hébergé ». Passer d'un poste à l'autre **ne recrée pas** l'écran (une seule
   route, un `matcher` pour `forge` et `forge/:hostRef`) : pas de rechargement, pas de clignotement.
4. **Détail** : la carte existante du poste sélectionné, **inchangée** (elle sera découpée en
   onglets par SF-98-02). Les notices existantes (aucun poste, toutes missions clôturées, accès
   refusé, gateway injoignable) restent.
5. **Ancien fragment** : `/forge#poste-<id>` (fil d'Ariane F-68, liens collés) **redirige** vers
   `/forge/<id>` en remplaçant l'entrée d'historique. Le fil d'Ariane du terminal pointe désormais
   directement `/forge/<id>`.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| `/forge/<id>` d'un poste inconnu (supprimé, lien recopié) | la sélection par défaut s'applique, aucune erreur | — |
| `#poste-<id>` d'un poste inconnu | redirigé vers `/forge/<id>`, puis sélection par défaut | — |
| Filtre sans aucune correspondance | « Aucun poste ni projet ne correspond. » ; le poste ouvert reste affiché | — |
| Poste ouvert masqué par le filtre | le détail reste affiché (le filtre ne ferme rien) | — |
| Poste clôturé ouvert par l'URL, repli fermé | le détail s'affiche ; le repli s'ouvre pour montrer la ligne | — |
| Vue refusée (403) / injoignable | notices existantes, pas de colonne | 403 / — |
| Aucune machine | colonne réduite à « Sans machine », détail = notice « Aucun poste connecté » + carte « Hébergé » | — |

---

## Critères d'acceptation

- [ ] `/forge` montre la colonne et **un seul** poste ouvert ; la page ne grandit plus avec le
      nombre de postes.
- [ ] Les groupes suivent l'ordre *À regarder › En ligne › Hors ligne › Sans machine › Clôturés* ;
      un poste en attente d'autorisation est dans *À regarder* avec « *k* attend ».
- [ ] Le bandeau dit « *N* autorisation(s) attend(ent) » sans aucun clic dès qu'un terminal attend.
- [ ] Le filtre trouve un poste par son nom **et** par le nom d'un de ses projets, et dit combien
      de projets correspondent.
- [ ] `/forge/<id>` ouvre ce poste ; cliquer une ligne navigue vers `/forge/<id>` ; `/forge` ouvre
      le poste par défaut sans changer l'URL.
- [ ] `/forge#poste-<id>` mène à `/forge/<id>` ; `/forge/supervision`, `/forge/mosaique` et
      `/postes` répondent comme avant.
- [ ] Le libellé daté de chaque ligne vient de `HostPresenceService` (F-97) et avance sans requête.
- [ ] Aucune couleur hors `DESIGN_SYSTEM.md` : tons §9 par `app-host-badge` et filet, pastilles §5.

---

## Périmètre

### Hors scope (explicite)

- L'en-tête du poste et ses onglets (SF-98-02), la grille de tuiles (SF-98-03).
- La fusion Voir travailler / Mosaïque (SF-98-04).
- Les textes réécrits, la relecture de la carte au retour en ligne, l'écran téléphone
  liste → détail (SF-98-05). À < 820 px, la colonne s'empile au-dessus du détail.
- Toute évolution des blocs de la carte (carte, intégrité, Teams, mission, suppression, coupure).
- Tout changement de gateway : les endpoints lus sont inchangés.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| filtre | vide | non retenu : il ne survit pas à la page |
| repli des clôturées | fermé | ouvert d'office si le poste ouvert est clôturé |
| poste ouvert | paramètre `hostRef` | à défaut : À regarder › En ligne › non clôturé › Hébergé |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `hostRef` (URL) | Non | identifiant de poste, ou `heberge` | inconnu ⇒ sélection par défaut |
| fragment | Non | `poste-<id>` | tout autre fragment ignoré |
| filtre | Non | texte libre | minuscules, accents retirés, espaces bordants retirés |

---

## Technique

### Endpoint(s)

Aucun. Lecture inchangée de `GET /api/runner-hosts/overview` (sondage 15 s).

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `postes/forge-fleet.ts` (nouveau) — fonctions pures : référence d'un poste, attentes, filtre,
  groupes, poste par défaut.
- `postes/forge-rail/forge-rail.component.*` (nouveau) — la colonne, présentationnelle.
- `PostesComponent` — bandeau, maître–détail, sélection par l'URL, redirection du fragment.
- `app.routes.ts` — `forge` et `forge/:hostRef` réunies par un `matcher` (après `forge/supervision`
  et `forge/mosaique`).
- `AtelierTerminalComponent` — le fil d'Ariane pointe `/forge/<id>`.

### Préoccupations transversales

- **Navigation / routing : oui.** Composants impactés et vérifiés :
  - `app.routes.ts` : `forge`, `forge/:hostRef` (nouveau), `forge/supervision`, `forge/mosaique`,
    `postes` (redirection) — ordre vérifié par test de route ;
  - fil d'Ariane F-68 : `AtelierTerminalComponent.crumbs` (pointe `/forge/<id>`),
    `ForgeBreadcrumbComponent` (inchangé, lien « Forge ») ;
  - ancien fragment `#poste-<id>` : redirigé par `PostesComponent` ;
  - `ShellComponent.forgeActive` (`/forge/...` reste actif) ;
  - liens vers `/forge` : `AtelierComponent` (retour après suppression), `BillingComponent`,
    `GovernanceComponent`, `SupervisionComponent`, `MosaiqueComponent`, dialogue d'appairage (relit
    la vue à la fermeture, sans lien) — inchangés, `/forge` reste valide.
- Auth / Principal : non. Contexte tenant : non. Plans / limites : non.

---

## Plan de test

### Tests unitaires (frontend)

- [ ] `forge-fleet.spec.ts` — groupes dans l'ordre, attentes comptées (projets + terminal du poste),
      filtre par nom de poste / de projet / sans accents, poste par défaut dans les quatre cas.
- [ ] `forge-rail.component.spec.ts` — rendu des groupes, drapeau « attend », compte, « projet(s)
      trouvé(s) », filet de couleur de la ligne sélectionnée, repli des clôturées, émission de la
      sélection et du filtre.
- [ ] `PostesComponent` — un seul détail rendu ; `/forge/<id>` ouvre ce poste ; poste inconnu ⇒
      défaut ; clic ⇒ navigation `/forge/<id>` ; fragment `#poste-<id>` ⇒ `/forge/<id>` avec
      `replaceUrl` ; bandeau : postes en ligne, autorisations en attente ; les tests existants de la
      carte (mission, suppression, coupure, carte, intégrité, Teams, dossiers) restent verts.
- [ ] `app.routes` — `/forge`, `/forge/<id>`, `/forge/supervision`, `/forge/mosaique` résolvent vers
      le bon écran (non-régression par ancien chemin).
- [ ] `AtelierTerminalComponent` — le niveau « chez qui » pointe `/forge/h1`.

### Isolation workspace

- [x] Non applicable — aucune donnée nouvelle ; la vue lue part du JWT, inchangée.

---

## Dépendances

### Subfeatures bloquantes

- F-97 (`HostPresenceService`, statut daté) — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Une route à `matcher`** plutôt que deux routes : deux configurations distinctes feraient
  détruire et recréer l'écran à chaque changement de poste (spinner, relectures de cartes).
- **`/forge` ne réécrit pas l'URL** : sur téléphone (SF-98-05), `/forge` est la liste et
  `/forge/<id>` le détail — la même URL sert les deux tailles.
- **Groupe « Sans machine »** pour le poste « Hébergé » : la maquette ne le montre pas ; il reste une
  ligne comme les autres, sans point ni couleur, entre les postes et les clôturés.
- **Grille de 4 px (§6)** : colonne 288 px (290 dans la maquette), pastille 32 px (34).
- **Filtre en `input` natif** : ce n'est pas un formulaire (aucune validation, aucune erreur) ; il
  reprend le champ de recherche de la maquette plutôt qu'un `mat-form-field`.
