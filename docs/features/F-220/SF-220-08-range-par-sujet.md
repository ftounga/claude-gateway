# Mini-spec — [F-220 / SF-220-08] Le tableau du client, rangé par sujet

## Identifiant
SF-220-08

## Feature parente
F-220 — Le tableau de bord du client

## Statut
Done

## Date de création
2026-10-28

## Branche Git
`feat/SF-220-08-range-par-sujet`

---

## Objectif
Ranger le tableau du client par **sujet** (un bloc repliable par sujet, avec ses compteurs, son chantier le plus dormant et un lien vers son terminal), au lieu de mélanger les chantiers de tous les sujets dans trois groupes de couleur.

## Contexte (besoin PO, 2026-10-28)
« Je viens de regarder le dashboard. Il n'est pas facile à utiliser. Les chantiers sont un peu partout. J'aurais préféré afficher mes chantiers par sujet, c'est plus logique comme ça. »

## Comportement attendu

### Cas nominal
- **Contrat inchangé** : `GET /api/runner-hosts/{hostId}/chantiers` porte déjà `workspaceId`, `workspaceName` et `hostTerminal` sur chaque chantier (SF-220-02) et sur chaque attente hors chantier (SF-220-07). Le regroupement se fait **côté écran** (fonction pure `subjectBlocks`) : aucune route, aucune donnée nouvelle.
- **Un bloc par sujet** (le terminal du poste compte comme un sujet, nommé « Terminal du poste »). Un sujet n'a de bloc que s'il a au moins un chantier **non reporté** ou une attente hors chantier ; sinon il n'est pas affiché.
- **En-tête de bloc** (un `h3` qui contient un **bouton** `aria-expanded` / `aria-controls`, plus un lien à côté) :
  - chevron + **nom du sujet** ;
  - **compteurs par couleur** non nuls — chantiers et attentes hors chantier confondus — chacun avec son **icône et son libellé** (« l'outil avance », « votre décision », « on attend » ; jamais la couleur seule) ;
  - **ancienneté** de l'élément le plus dormant du sujet (« dormant depuis 6 j » mis en avant, sinon « actif aujourd'hui » / « il y a 1 j ») ;
  - lien **« Ouvrir le terminal »** (`/atelier/{ws}`), hors du bouton (pas d'interactif imbriqué).
- **Corps du bloc** :
  - les chantiers du sujet, **outil → votre décision → quelqu'un**, puis le plus dormant d'abord (ordre du serveur conservé dans chaque couleur), avec **les mêmes cartes** qu'avant (la ligne d'identité ne répète plus le nom du sujet : « Chantier N · phase ») ;
  - **en dessous**, « Hors chantier (n) » : les attentes hors chantier **de ce sujet** (votre décision puis quelqu'un), mêmes cartes et gestes qu'en SF-220-07. La section séparée en bas de page disparaît.
- **Ordre des sujets** (calculé sans filtre, pour que les blocs ne sautent pas quand on filtre) :
  1. d'abord les sujets qui ont **au moins un élément « votre décision »** (chantier non reporté ou attente hors chantier) ;
  2. puis, à rang égal, le sujet dont l'élément le **plus dormant** a le plus grand nombre de jours d'inactivité ;
  3. puis le nom du sujet (ordre alphabétique français), pour un ordre stable.
- **Repli** : chaque bloc se replie et se déplie ; l'état est mémorisé **par sujet** (identifiant du sujet) dans `localStorage`, clé `cg_client_board_collapsed` (tableau JSON des sujets repliés, 200 au plus), lu et écrit en `try/catch`. Par défaut, un bloc est déplié. Sans `localStorage` (navigation privée, accès refusé), tout reste déplié et le repli fonctionne le temps de la page.
- **Filtres de l'en-tête inchangés** : ils filtrent **à l'intérieur** des blocs (chantiers et attentes) et masquent les blocs devenus vides. Les compteurs d'un en-tête de bloc restent ceux du sujet, sans filtre (ce que le sujet contient).
- **Compteurs globaux** de l'en-tête, **gestes** (ouvrir, répondre, lancer en autonome, avancer jusqu'au blocage, reporter, clore, marquer fait / demandé, ouvrir le sujet) et **multi-sélection** inchangés ; la sélection traverse les blocs (et survit au repli d'un bloc).
- **Reportés** : la section « Reportés (k) » reste **à part**, repliée, en bas du tableau (voir Notes, décision 1).
- **Téléphone** (< 600 px) : une colonne ; en-tête de bloc compact (nom puis compteurs et ancienneté repliés sur la ligne suivante, lien réduit à son icône avec `aria-label`).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `localStorage` absent, plein ou qui lève | `try/catch` : tout déplié, repli mémorisé en mémoire seulement, aucun message | — |
| Valeur stockée illisible (JSON cassé, pas un tableau) | ignorée, tout déplié | — |
| Filtre qui vide tous les blocs | « Rien pour ce filtre. » | 200 |
| Seuls des chantiers reportés | aucun bloc, « Tous les chantiers ouverts sont reportés. » + section Reportés | 200 |
| Lecture du tableau en échec | inchangé (SF-220-03) : message + [Réessayer] | 4xx/5xx |

## Critères d'acceptation
1. Deux chantiers du même sujet (couleurs différentes) et un chantier d'un autre sujet → deux blocs ; dans le bloc, le chantier « outil » précède celui « votre décision ».
2. L'en-tête d'un bloc écrit son nom, ses compteurs non nuls avec icône et libellé, l'ancienneté du plus dormant (« dormant depuis 6 j ») et un lien vers `/atelier/{ws}`.
3. Un sujet avec une décision attendue passe avant un sujet plus dormant sans décision ; entre deux sujets sans décision, le plus dormant passe d'abord.
4. Une attente hors chantier s'affiche **dans le bloc de son sujet**, sous ses chantiers ; un sujet qui n'a que des attentes a son bloc ; il n'y a plus de section « Hors chantier » globale.
5. Cliquer l'en-tête replie le bloc (`aria-expanded="false"`, cartes masquées) et l'écrit dans `localStorage` ; un nouveau rendu relit l'état ; un `localStorage` qui lève ne casse rien.
6. Le filtre « votre décision » masque les blocs sans élément « votre décision » et, dans les autres, ne garde que ces éléments.
7. Un sujet dont le seul chantier est reporté n'a pas de bloc ; le chantier est dans « Reportés ».
8. La multi-sélection de deux chantiers de sujets différents lance les deux (inchangé).
9. Aucune couleur hors `DESIGN_SYSTEM.md` ; espacements multiples de 4 ; une colonne sous 600 px.

## Périmètre
### Hors scope (explicite)
- Toute modification de l'API ou du backend (le sujet est déjà dans le contrat).
- Replier / déplier tout d'un clic ; trier les sujets à la main ; épingler un sujet.
- Le tableau des chantiers **d'un** sujet (F-208) : inchangé.
- Le badge de l'onglet (nombre de chantiers ouverts) : inchangé.

## Valeurs initiales
| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| bloc de sujet | déplié | sauf s'il est mémorisé replié |
| section « Reportés » | repliée | inchangé (SF-220-06) |

## Contraintes de validation
| Champ | Règle |
|-------|-------|
| `cg_client_board_collapsed` | tableau JSON de chaînes ; 200 identifiants au plus (les plus récents gardés) ; toute autre forme ignorée |

## Technique
### Endpoint(s)
Aucun nouveau. Consommés (inchangés) : `GET /api/runner-hosts/{hostId}/chantiers` et les gestes de SF-220-03/06/07, SF-219-05.

### Tables impactées
Aucune.

### Migration Liquibase
- [x] Non applicable

### Composants Angular (si applicable)
- `core/models/client-board.models.ts` — `SubjectBlock`, `subjectBlocks(board, filter)` (regroupement, ordre des sujets, ordre interne, compteurs, plus dormant).
- `postes/client-board/client-board.component.{ts,html,scss}` — blocs repliables, repli mémorisé, lien vers le terminal, hors chantier dans le bloc.

## Préoccupations transversales
- **Navigation / routing : non** — lien vers `/atelier/{ws}`, route existante (déjà utilisée par [Ouvrir] et [Ouvrir le sujet]) ; aucune route, aucun guard modifié.
- Auth, contexte tenant, plans / limites : non (affichage seul, aucune donnée nouvelle).

## Plan de test
### Tests unitaires
- `client-board.models.spec.ts` : `subjectBlocks` — regroupement par sujet, ordre interne (outil → décision → quelqu'un), attentes dans le bloc, ordre des sujets (décision d'abord, puis plus dormant, puis nom), compteurs non filtrés, filtre qui masque les blocs vides, reportés exclus, sujet sans rien d'ouvert absent.
- `client-board.component.spec.ts` : en-tête (nom, compteurs icône + libellé, ancienneté, lien), repli (`aria-expanded`, cartes masquées, `localStorage` écrit et relu), `localStorage` qui lève, attentes dans le bloc, filtre, multi-sélection à travers les blocs ; tests existants adaptés (groupes → blocs).
### Tests d'intégration
- Backend inchangé (couvert par SF-220-02/07).
### Isolation workspace
- [x] Non applicable — affichage seul, aucune donnée nouvelle ; isolation au serveur (SF-220-02).

## Dépendances
### Subfeatures bloquantes
- SF-220-03, SF-220-06, SF-220-07, SF-219-05 — Done.
### Questions ouvertes impactées
- Aucune.

## Notes et décisions
1. **Reportés à part** (pas dans leur bloc) : reporter un chantier, c'est le **mettre de côté** ; le remettre dans le bloc de son sujet ramènerait le bruit que le PO veut ôter, et ferait apparaître des blocs « vides » pour des sujets dont tout est reporté (contraire à « les sujets sans rien d'ouvert ne sont pas affichés »). Chaque ligne de « Reportés » dit déjà son sujet.
2. **« Le plus dormant » inclut les attentes hors chantier** : un sujet qui n'a que des attentes a aussi une ancienneté, et une attente oubliée doit faire remonter son sujet.
3. **Ordre des sujets sans filtre** : les blocs ne changent pas de place quand on filtre ; le filtre ne fait que masquer.
4. **Compteurs du bloc = non nuls seulement** : un « 0 » sur trois couleurs par sujet alourdirait l'en-tête ; le libellé court (`UNBLOCKER_SHORT`, déjà utilisé par SF-220-04) tient sur une ligne.
5. **Plus de titres de groupe de couleur dans un bloc** : chaque carte porte déjà sa pastille (icône + libellé) ; l'ordre outil → décision → quelqu'un est conservé.
