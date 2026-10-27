# Mini-spec — [F-220 / SF-220-03] L'écran du tableau du client

## Identifiant
SF-220-03

## Feature parente
F-220 — Le tableau de bord du client

## Statut
Done

## Date de création
2026-10-27

## Branche Git
`feat/SF-220-03-ecran-tableau-client`

---

## Objectif
Ouvrir la page d'un client de la Forge sur un onglet « Chantiers » qui montre d'un coup d'œil ce qui reste à faire, qui le débloque, depuis quand ça dort — et qui permet d'ouvrir, de lancer en autonome (un ou plusieurs d'un coup) et de clore.

## Comportement attendu

### Cas nominal
- **Onglet « Chantiers »**, premier onglet d'une machine et **onglet d'ouverture** (décision PO) ; badge = nombre de chantiers ouverts (lu à l'ouverture de l'onglet). « Projets » passe à `?onglet=projets` ; le poste « Hébergé » garde Projets seul.
- **Contrat importé de SF-220-02** : `GET /api/runner-hosts/{hostId}/chantiers` (`ClientBoard`) ; lancement : `POST /api/workspaces/{ws}/journey/chantiers/{n}/autonomy` (F-219) ; ouvrir : `POST …/journey/chantiers/{n}/focus` (F-206) puis `/atelier/{ws}` ; clore : `POST …/journey/chantiers/{n}/close` (F-206).
- **En-tête** : trois compteurs-filtres — « L'outil peut avancer » (vert), « Ça attend votre décision » (orange), « On attend quelqu'un » (gris-bleu) — plus « Dormants » ; un clic filtre, un second clic retire le filtre.
- **Trois groupes**, dans cet ordre, chacun titré de sa couleur, de son icône et de son libellé : vert d'abord (dominant), puis ma décision, puis quelqu'un d'autre. Un groupe vide n'est pas montré.
- **Carte de chantier** : pastille « qui débloque » (icône + libellé + couleur), indicateur « En cours » ou « En autonome — étape k/n » en plus ; sujet (ou « Terminal du poste »), « Chantier N », titre ; phase ; anneau d'avancement (faites / total) ; **ancienneté** (« dormant depuis 6 j » mise en avant, sinon « actif aujourd'hui » / « il y a 1 j ») ; la phrase `detail` ; pour « quelqu'un » : à qui et depuis quand ; pour une question : son texte ; prochaine étape ; « Pour partir en autonome : » + raisons (deux au plus, « +n ») si pas prêt ; coût de la course autonome si présent.
- **Gestes** : [Ouvrir] (focus puis terminal) ; [Répondre] à la place d'Ouvrir quand une question attend ; [Lancer en autonome] si prêt et rien ne tourne ; menu ⋮ → [Clore le chantier] (confirmation `MatDialog`).
- **Multi-sélection** : une case sur chaque chantier **prêt** qui ne tourne pas. Barre de sélection : « k sélectionné(s) » + [Lancer en autonome (k)] + [Annuler]. Si `k` dépasse les places libres (`maxParallel − running`) : bouton désactivé et refus écrit (« Quatre chantiers autonomes au maximum : 1 en cours, 3 places libres. »). Sinon lancement **l'un après l'autre** ; bilan par snackbar (« 3 lancés » / « 2 lancés, 1 refusé : … ») ; le tableau se relit.
- **Mise à jour** : relecture toutes les 15 s tant que l'onglet du navigateur est visible ; aucune relecture masqué.
- **Téléphone** (< 600 px) : cartes en une colonne, compteurs sur deux lignes, barre de sélection collée en bas.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Lecture du tableau en échec | message « Le tableau n'a pas pu être lu. » + [Réessayer] ; la page du poste reste utilisable | 4xx/5xx |
| Lancement refusé (pas prêt, sujet occupé, limite, hors ligne) | le message du serveur, par chantier, en snackbar | 409 |
| Focus en échec à l'ouverture | le terminal s'ouvre quand même (le focus est un confort) | 4xx |
| Clôture en échec | snackbar avec le message du serveur, carte inchangée | 400 |
| Aucun chantier ouvert | « Aucun chantier ouvert chez ce client. » + rappel : un chantier naît en mode Guidé dans un sujet | 200 |

## Critères d'acceptation
1. Ouvrir un poste machine sans `?onglet=` montre l'onglet Chantiers ; `?onglet=projets` montre les projets ; « Hébergé » reste sur Projets.
2. Les chantiers sont groupés Outil → Ma décision → Quelqu'un, chaque pastille porte icône + libellé (jamais la couleur seule).
3. Un chantier dormant affiche « dormant depuis N j » mis en avant.
4. [Ouvrir] appelle le focus du chantier puis navigue vers `/atelier/{ws}`.
5. [Lancer en autonome] appelle le lancement F-219 ; un 409 affiche le message du serveur.
6. Sélectionner trois chantiers prêts avec une place libre → bouton désactivé + refus écrit ; avec quatre places → trois lancements successifs puis bilan.
7. [Clore] demande confirmation (`MatDialog`) puis clôt ; annuler ne clôt rien.
8. Le filtre « Dormants » ne garde que les chantiers dormants ; recliquer retire le filtre.
9. Aucune couleur hors `DESIGN_SYSTEM.md` ; espacements multiples de 4 ; pas de `window.confirm`.

## Périmètre
### Hors scope (explicite)
- La pastille dans la vue d'un sujet (SF-220-04) ; reporter, rappel des dormants (SF-220-05).
- Répondre à la question **dans** le tableau (arbitrage 7 : on ouvre le terminal où la carte de question existe déjà).
- La mosaïque : une course autonome ne prend pas de place de terminal vivant (F-219, arbitrage 4) et n'apparaît donc pas dans « Voir travailler » ; la progression se suit sur le tableau (relu toutes les 15 s).

## Valeurs initiales
| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| filtre | aucun | tous les chantiers |
| sélection | vide | effacée après un lancement |

## Contraintes de validation
| Champ | Règle |
|-------|-------|
| sélection | uniquement des chantiers `ready` sans course en cours ; au plus `maxParallel − running` pour lancer |

## Technique
### Endpoint(s) consommés
| Méthode | URL |
|---------|-----|
| GET | `/api/runner-hosts/{hostId}/chantiers` |
| POST | `/api/workspaces/{ws}/journey/chantiers/{n}/autonomy` |
| POST | `/api/workspaces/{ws}/journey/chantiers/{n}/focus` |
| POST | `/api/workspaces/{ws}/journey/chantiers/{n}/close` |

### Migration Liquibase
- [x] Non applicable

### Composants Angular
- `core/models/client-board.models.ts` — contrat + fonctions pures (libellés, icônes, ancienneté, groupes, places libres).
- `core/services/client-board.service.ts` — lecture du tableau.
- `JourneyService.closeChantier` — clore le chantier N.
- `postes/client-board/client-board.component.*` — l'onglet.
- `postes/forge-tabs.ts` — onglet `chantiers`, ouverture par défaut.

### Couleurs (DESIGN_SYSTEM §2, aucune nouvelle)
- Outil : `--cg-success` ; Ma décision : `--cg-orange` ; Quelqu'un : `--cg-text-secondary` (gris-bleu). La couleur porte l'icône, le filet gauche de la carte et une teinte dérivée (`color-mix` avec `--cg-surface`) ; le texte reste en encre principale (AA).

## Préoccupations transversales
- **Navigation / routing : oui.** Onglet par défaut d'un poste machine change (Projets → Chantiers). Chemins vérifiés : `/forge/:hostRef` (ouvre Chantiers), `/forge/:hostRef?onglet=projets|carte|gouvernance|activite|pages|videos|presentations` (inchangés), `selectTab('projets')` met désormais `?onglet=projets` et `selectTab('chantiers')` le retire ; « Hébergé » (`/forge/heberge`) reste sur Projets ; liens existants vers `?onglet=` inchangés ; `/atelier/:id` (ouvrir) inchangé. Tests `forge-tabs.spec.ts` et `postes.component.spec.ts` ajustés.
- Auth, tenant, plans/limites : non (lectures existantes, isolation serveur).

## Plan de test
### Tests unitaires
- `client-board.models.spec.ts` : libellés d'ancienneté, groupes, places libres, refus écrit.
- `client-board.component.spec.ts` (service simulé) : groupes et ordre, pastille icône + libellé, dormant, filtre, Ouvrir (focus + navigation), Lancer (409 → message), multi-sélection (refus au-delà des places, lancements successifs), Clore (confirmation, annulation), erreur de lecture.
- `forge-tabs.spec.ts` : onglet Chantiers par défaut, Hébergé sur Projets.
### Tests d'intégration
- Backend déjà couvert (SF-220-02). `postes.component.spec.ts` : l'onglet s'ouvre par défaut.
### Isolation utilisateur
- [x] Non applicable côté écran — aucun identifiant de compte envoyé ; isolation au serveur (SF-220-02, F-219).

## Dépendances
- SF-220-01, SF-220-02 — Done (PR #1315, #1316). F-219, F-206 — Done.

## Notes et décisions
- Arbitrage : après un lancement multiple, **on reste sur le tableau** (progression relue toutes les 15 s) au lieu de basculer vers la mosaïque, qui ne montre que les terminaux ouverts.
- Arbitrage : « Quelqu'un d'autre » en gris-bleu plutôt qu'en ambre, pour rester distinct de l'orange « ma décision ».
