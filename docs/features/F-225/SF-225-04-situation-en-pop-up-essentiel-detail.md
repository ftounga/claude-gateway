# Mini-spec — [F-225 / SF-225-04] Le point de situation en pop-up : « L'essentiel » puis « Détail »

## Identifiant
`F-225 / SF-225-04`

## Feature parente
`F-225` — Le point de situation d'un terminal

## Statut
`in-progress`

## Date de création
2026-11-03

## Branche Git
`feat/SF-225-04-situation-pop-up`

---

## Objectif
En arrivant sur un sujet, savoir d'un coup d'œil où l'on en était et ce qui a été fait : le point de situation devient une fenêtre modale dont le premier onglet dit l'essentiel en 3 à 5 lignes cliquables, le second garde tout le contenu actuel.

## Constat (demande PO du 2026-11-03)
La carte F-225 (SF-225-02/03), posée dans le fil comme un panneau, est jugée trop touffue : chantiers, mails, attentes par chantier, dernier échange, pièces, suites, réglage — tout au même niveau.

## Comportement attendu

### Cas nominal
1. **Pop-up** (`MatDialog`) : `role="dialog"`, `aria-modal`, titre relié (`aria-labelledby`), focus piégé, focus rendu au déclencheur à la fermeture. Fermeture : Échap, croix (`aria-label="Fermer"`), clic en dehors.
2. **Onglet « L'essentiel »** (ouvert par défaut) : 3 à 5 lignes au plus, **dérivées de `GET /api/workspaces/{id}/situation`** (aucun appel modèle) :
   - **Où j'en suis** — le chantier au focus (ou celui du volet) : « Chantier 4 — Migration · Exécution · 5/14 » (+ « et N autres ouverts ») ; sans chantier : « Au fil de l'eau ». → clic : bascule sur ce chantier (F-206) et ferme.
   - **Fait en dernier** — l'essentiel de la dernière réponse (F-126, sinon 1er paragraphe), coupé à 160 car., daté (« il y a 5 h ») ; à défaut, la dernière demande. → clic : le fil, en bas.
   - **Ce qui vous attend** (0 à 2 lignes, par priorité) : plan à valider, diagnostic à planifier, mails/messages pas marqués envoyés (nombre + premier destinataire), attentes ouvertes (nombre + la plus ancienne). → clic : le chantier concerné, sinon l'onglet « Détail ».
   - **Prochaine action** — l'étape suivante du chantier (« Étape 6 : Déployer »), sinon la première suite proposée. → clic : le chantier, sinon le fil.
   Une ligne n'apparaît que si elle a un contenu ; terminal vide : « Rien encore dans ce terminal. »
3. **Onglet « Détail »** : le contenu actuel complet de la carte (`app-terminal-situation`), inchangé — filtre par chantier, [Marquer envoyé]/[Annuler], [Voir], pièces, suites, réglage du seuil. Aucune perte.
4. **Ouverture d'elle-même** : à l'arrivée sur un sujet, **même seuil qu'aujourd'hui** (SF-225-03 : inactivité ≥ seuil réglé, défaut 4 h, depuis le plus récent du dernier message écrit et de la dernière visite sur l'appareil), jamais dans un volet du split, jamais par-dessus un tour, une autorisation ou une question, jamais en lecture seule — donc **pas à chaque navigation** (la visite est notée à l'entrée). La lecture faite pour décider sert la pop-up (pas de seconde lecture).
5. **Rouvrir à tout moment** : bouton icône « Point de situation » toujours visible dans la barre du terminal (tous paliers), entrée du menu ⋯ et `/situation` — tous ouvrent la pop-up (plus de panneau dans le fil).

### Cas d'erreur
| Situation | Comportement attendu |
|-----------|---------------------|
| Lecture en échec (réseau, 404 chez autrui) | La pop-up dit « Le point de situation n'a pas pu être lu. » ; rien ne s'ouvre seul |
| Pop-up déjà ouverte | Un nouveau geste ne l'ouvre pas une seconde fois |
| Terminal sans aucun échange | Une ligne « Rien encore dans ce terminal. » |
| Écran étroit (360 px) | Pop-up pleine largeur moins 16 px de gouttière, sans défilement horizontal |

## Critères d'acceptation
- [ ] La pop-up se ferme par Échap, la croix et un clic dehors ; le focus reste dans la pop-up (Tab/Maj+Tab).
- [ ] Onglets au clavier (←/→), `role=tablist/tab/tabpanel`, `aria-selected`, `aria-controls`.
- [ ] L'essentiel compte entre 1 et 5 lignes, dans l'ordre Où / Fait / Attend / Prochaine.
- [ ] Clic sur une ligne de chantier ⇒ bascule sur ce chantier et ferme ; ligne du fil ⇒ ferme et descend au bas du fil ; ligne « mails » ⇒ onglet Détail.
- [ ] Détail = la carte actuelle complète (tests SF-225-02/03 inchangés et verts).
- [ ] Ouverture d'elle-même sur les mêmes conditions que SF-225-03 ; jamais à la navigation suivante sous le seuil.
- [ ] Bouton de réouverture visible dans la barre à 1366 px et à 360 px.
- [ ] Captures headless 1366×768 et 360 px (Chrome sans `--hide-scrollbars`) dans `docs/features/F-225/captures/`, sans débordement.

## Périmètre — hors scope
- Aucun appel IA (non indispensable : toutes les lignes se dérivent des données existantes).
- Aucun changement backend, aucune migration.
- Le récit rédigé par le modèle (déjà hors périmètre F-225).

## Technique
- Endpoints : `GET /api/workspaces/{id}/situation` (existant, isolé `user_id`), aucun nouveau.
- Tables : aucune. Migration : non applicable.
- Composants Angular : `situation-essentials.ts` (fonction pure), `TerminalSituationDialogComponent` (nouveau), `TerminalSituationComponent` (entrée `preloaded`), `AtelierTerminalComponent` (ouverture modale, bouton de barre, `/situation`).

## Préoccupations transversales
- **Navigation / routing** : coché — aucune route ajoutée ; composants impactés : `AtelierTerminalComponent.openSituationIfIdle` (ouverture d'elle-même à l'entrée), `dispatchPanelCommand` (`/situation`), menu ⋯ de la barre. Vérifiés par tests (ouverture à l'entrée sous/au-dessus du seuil, volet, tour en cours).

## Plan de test
### Unitaires
- [ ] `situationEssentials` : chantier au focus + réponse + plan à valider + étape suivante ⇒ 4 lignes ordonnées, cibles justes ; mails non envoyés ⇒ ligne « attend » ciblant le Détail ; terminal vide ⇒ 1 ligne ; jamais plus de 5 lignes ; volet ⇒ le chantier du volet.
- [ ] Dialogue : onglet Essentiel par défaut, bascule vers Détail, flèches, clic ligne ⇒ `close` avec la cible, croix ⇒ ferme.
- [ ] Terminal : à l'entrée au-delà du seuil ⇒ `MatDialog.open` ; sous le seuil ⇒ non ; `/situation` ⇒ ouvre la pop-up, aucun panneau, aucun tour ; bouton de barre ⇒ ouvre.
### Intégration
- Non applicable côté backend (aucun changement) ; suite front complète verte.
### Isolation utilisateur
- Inchangée : la lecture existante est filtrée `user_id` (404 chez autrui, test SF-225-01).

## Dépendances
SF-225-01/02/03 (done). Aucune question ouverte impactée.
