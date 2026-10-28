# Mini-spec — [F-220 / SF-220-09] Le tableau du client, refonte visuelle

## Identifiant
SF-220-09

## Feature parente
F-220 — Le tableau de bord du client

## Statut
Done

## Date de création
2026-10-28

## Branche Git
`feat/SF-220-09-refonte-visuelle`

---

## Objectif
Remplacer les grosses cartes du tableau du client par une **liste dense et calme** (une ligne par chantier, détail dépliable en place), sans qu'aucune information ni aucun geste ne disparaisse.

## Contexte (besoin PO, 2026-10-28)
« Le dashboard qu'on a mis en place, je le trouve vraiment grossier. Les pancartes au niveau des différents chantiers… avoir de grosses pancartes comme ça, ça fait pas très joli. […] Fais une refonte et propose autre chose. Ça doit rester joli, mais agréable. »

## Comportement attendu

### Cas nominal
- **Contrat inchangé** : mêmes données (`GET /api/runner-hosts/{hostId}/chantiers`), même regroupement par sujet (`subjectBlocks`, SF-220-08), mêmes routes de gestes.
- **En haut** : titre, résumé en une ligne ; une barre d'outils : filtres en **chips** discrets (point de couleur + libellé + nombre ; `aria-pressed`), bouton **[Sélectionner]** (mode sélection, `aria-pressed`) et [Relire].
- **Un panneau par sujet** (fond blanc, filet 1 px, pas de filet coloré) :
  - **en-tête sur une ligne** : chevron + nom (bouton `aria-expanded`), **pastilles chiffrées** par couleur (icône + nombre ; libellé court en texte accessible et en info-bulle), ancienneté du plus dormant (mise en avant **orange doux** — icône orange, texte or-encre — seulement si dormant), lien **[Terminal]** (`/atelier/{ws}`, `aria-label` « Ouvrir le terminal du sujet X ») ;
  - repli mémorisé inchangé (`cg_client_board_collapsed`).
- **Une ligne par chantier**, séparée par un filet 1 px :
  - case de sélection (si sélectionnable), **visible au survol / au focus ou en mode sélection** ;
  - un **bouton-ligne** (`aria-expanded`, `aria-controls`) : point de couleur « qui débloque ? » (libellé en texte accessible + info-bulle), titre, mini-barre + fraction `12/19`, puis en gris secondaire tronqué : la course autonome en cours (« En autonome — étape k/n »), sinon la question, sinon la prochaine étape ; à droite l'ancienneté (orange doux seulement si dormant) ; un point pulsé « En cours » si un tour vit ;
  - **gestes au survol / au focus** à droite, en icônes avec info-bulle et `aria-label` : [Lancer en autonome], [Avancer jusqu'au blocage], [Ouvrir] / [Répondre], [⋯] (tous les gestes : ouvrir, lancer, avancer, reporter 1/3/7 j, clore).
- **Clic (ou Entrée / Espace) sur la ligne** : elle se déplie **en place** ; **un seul déplié à la fois** (chantier ou attente). Le détail montre : qui débloque (pastille icône + libellé + phrase), la question, « Chantier N · phase », la prochaine étape, auprès de qui et depuis quand, **toutes** les raisons qui empêchent de partir en autonome, le dernier geste, les étapes qui resteront, le coût de la course ; puis les gestes complets en boutons texte.
- **Clavier** : Tab parcourt case → ligne → gestes ; **↑ / ↓** passent d'une ligne à l'autre.
- **Hors chantier** : sous un petit intertitre « Hors chantier · n », des lignes plus légères, sans barre : icône de couleur (libellé accessible), description, puis en secondaire « Auprès de X · depuis N j » ou la phrase de déblocage, « à relancer » si dû, ancienneté à droite ; gestes au survol ([Marquer demandé], [Marquer fait], [Ouvrir le sujet]) ; déplier montre « bloque », la pastille complète et les mêmes gestes en boutons texte.
- **Sélection** : dès qu'un chantier est sélectionné, une **barre d'action flottante** en bas (inchangée dans son contenu : nombre, places libres ou refus, Annuler, Lancer (k), Avancer (k)).
- **Reportés** : section repliée en bas (inchangée), lignes allégées.
- **Téléphone (< 600 px)** : la ligne passe sur deux lignes (point + titre / avancement + prochaine étape + ancienneté) ; les icônes de survol cèdent la place au seul menu **[⋯]**, toujours visible ; les cases n'apparaissent qu'en mode sélection ; pas de débordement horizontal.
- **Écrans tactiles** (`hover: none`) : comme le téléphone pour les gestes (menu [⋯] visible).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Lecture du tableau en échec | inchangé : message + [Réessayer] | 4xx/5xx |
| Geste refusé | inchangé : message du serveur en snackbar | 4xx |
| La ligne dépliée disparaît à la relecture | plus rien de déplié, aucune erreur | 200 |
| `localStorage` indisponible | inchangé (SF-220-08) | — |

## Critères d'acceptation
1. Un chantier est une ligne (pas une carte) : point « qui débloque » avec libellé accessible, titre, `done/total`, prochaine étape (ou question, ou course en cours), ancienneté.
2. Cliquer la ligne la déplie (`aria-expanded="true"`) et montre ce qui manque (toutes les raisons), le dernier geste, ce qui restera, le coût et les gestes complets ; en déplier une autre replie la première.
3. Lancer, avancer, ouvrir/répondre, reporter (1/3/7 j) et clore restent accessibles depuis la ligne (icônes au survol/focus ou menu ⋯) et depuis le détail.
4. Une attente hors chantier est une ligne légère sous « Hors chantier · n », avec marquer demandé / fait / ouvrir le sujet.
5. Les cases de sélection n'apparaissent qu'au survol/focus ou en mode [Sélectionner] ; une sélection fait apparaître la barre flottante ; la sélection et le lancement groupé sont inchangés.
6. Ancienneté dormante en orange doux (icône `--cg-orange`, texte `--cg-gold-ink`), jamais en aplat rouge.
7. Aucune couleur hors `DESIGN_SYSTEM.md` ; couleurs vives seulement en points / pastilles ; espacements multiples de 4.
8. À 390 px et 1440 px : aucun débordement horizontal ; à 390 px les gestes sont dans [⋯].
9. Tous les comportements testés avant restent verts (tests de structure DOM adaptés).

## Périmètre
### Hors scope (explicite)
- Toute modification d'API ou de backend.
- Le regroupement, l'ordre des sujets et des chantiers, les filtres (SF-220-08) : inchangés.
- Le tableau des chantiers d'un sujet (F-208) et la pastille dans le sujet (SF-220-04).
- Glisser-déposer, tri manuel, raccourcis clavier globaux.

## Valeurs initiales
| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| ligne dépliée | aucune | une seule à la fois |
| mode sélection | désactivé | remis à zéro au changement de poste |

## Contraintes de validation
Aucune nouvelle.

## Technique
### Endpoint(s)
Aucun nouveau.

### Tables impactées
Aucune.

### Migration Liquibase
- [x] Non applicable

### Composants Angular
- `postes/client-board/client-board.component.{ts,html,scss}` — liste dense, ligne dépliable, gestes au survol, mode sélection, navigation ↑/↓.
- `postes/client-board/client-board.component.spec.ts` — tests de structure adaptés, nouveaux tests (déplier, un seul déplié, mode sélection, menu ⋯, clavier).

## Préoccupations transversales
- Auth, contexte tenant, plans / limites, navigation / routing : **non** (affichage seul ; liens `/atelier/{ws}` existants).

## Plan de test
### Tests unitaires
- `client-board.component.spec.ts` : ligne (point + libellé accessible, fraction, prochaine étape, ancienneté) ; déplier / un seul déplié / `aria-expanded` ; détail (raisons complètes, coût, dernier geste, restera) ; gestes (icônes, menu ⋯, détail) ; mode sélection (cases visibles, barre flottante) ; ↑/↓ ; hors chantier en ligne ; tous les tests de comportement existants conservés.
- `client-board.models.spec.ts` : inchangé (logique pure non touchée).
### Tests d'intégration
- Backend inchangé.
### Vérification visuelle
- Captures Chrome headless avant/après en 1440×900 et 390×844 (sans `--hide-scrollbars`), `scrollWidth` contrôlé.
### Isolation workspace
- [x] Non applicable — affichage seul.

## Dépendances
### Subfeatures bloquantes
- SF-220-03 → SF-220-08 — Done.
### Questions ouvertes impactées
- Aucune.

## Notes et décisions
1. **Panneau blanc par sujet, filets entre les lignes** : sur le fond gris de la page, un panneau par sujet garde le regroupement lisible sans cadre par chantier.
2. **Orange doux pour la dormance** (au lieu de l'aplat rouge) : demandé par le PO ; texte en or-encre (`--cg-gold-ink`) pour tenir l'AA, l'orange de marque n'étant que sur l'icône.
3. **Case native** (`input type=checkbox`, `accent-color: --cg-orange`) au lieu de `mat-checkbox` : 16 px au lieu d'une cible de 40 px qui alourdirait chaque ligne ; libellé accessible conservé.
4. **Toutes les raisons « pour partir en autonome »** dans le détail (avant : deux et « +n autres ») : la place le permet, rien n'est caché.
