# Mini-spec — F-57 / SF-57-03 — Le rappel de journalisation

## Identifiant

`F-57 / SF-57-03`

## Feature parente

`F-57` — Transparence sur le poste de travail

## Statut

`done` — PR #337, mergée le 2026-09-10

## Date de création

2026-09-10

## Branche Git

`feat/SF-57-03-rappel-journalisation`

---

## Objectif

L'Atelier rappelle périodiquement — 2 h par défaut, réglable, **jamais bloquant** — que sur un poste
d'entreprise, les commandes exécutées sont vraisemblablement journalisées par l'employeur.

---

## Comportement attendu

### Cas nominal

1. À l'ouverture de l'Atelier, si aucun rappel n'a été acquitté, ou si le dernier l'a été il y a plus
   que la périodicité choisie, un **bandeau non modal** apparaît en bas à gauche de l'écran :

   > **Sur un poste d'entreprise, vos commandes sont vraisemblablement journalisées**
   > Ce que vous faites exécuter ici passe par votre machine. L'application ne sait pas ce qui
   > l'observe, et ne cherche pas à le savoir — mais un poste fourni par un employeur est presque
   > toujours journalisé. Faites-en le même usage que de votre terminal habituel.
   >
   > `Me le rappeler : [2 h ▾]`   `Compris`

2. « Compris » referme le bandeau et **repart le compteur** : il ne reviendra pas avant la
   périodicité choisie.
3. Le réglage propose **2 h**, **8 h**, **24 h** et **jamais**. Il est retenu d'une session à
   l'autre, dans le navigateur.
4. Choisir « jamais » referme le bandeau et l'empêche de revenir. Le réglage reste modifiable depuis
   le bandeau tant qu'il est ouvert.
5. Le bandeau **ne bloque rien** : ni l'envoi d'une demande, ni l'exécution d'une commande, ni le
   défilement. Aucun focus n'est capturé, aucune touche n'est interceptée.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| `localStorage` refusé (navigation privée verrouillée, quota) | Le bandeau se comporte comme un rappel neuf : il s'affiche, s'acquitte pour la session, et rien n'est écrit. **Aucune erreur à l'écran** |
| Contenu mémorisé illisible ou d'une version antérieure | Relu comme un rappel neuf |
| Périodicité mémorisée hors des valeurs proposées | Ramenée à la valeur par défaut (2 h) |
| Horodatage mémorisé dans le futur (horloge reculée) | Traité comme « acquitté à l'instant » : le bandeau ne se déclenche pas en boucle |

---

## Critères d'acceptation

- [ ] Le bandeau apparaît quand aucun rappel n'a jamais été acquitté.
- [ ] Le bandeau n'apparaît pas quand le dernier acquittement date de **moins** que la périodicité.
- [ ] Le bandeau réapparaît quand le dernier acquittement date de **plus** que la périodicité.
- [ ] « Compris » referme le bandeau et repart le compteur.
- [ ] La périodicité est réglable parmi **2 h / 8 h / 24 h / jamais**, et retenue entre deux sessions.
- [ ] « Jamais » empêche définitivement le rappel de revenir, jusqu'à changement du réglage.
- [ ] Un horodatage dans le futur ne provoque pas d'affichage en boucle.
- [ ] Un `localStorage` indisponible ne casse **rien** : ni au démarrage, ni à l'acquittement.
- [ ] Le texte dit **« vraisemblablement »** — jamais « vous êtes surveillé » : le produit ne sait
      pas, et ne prétend pas savoir.
- [ ] Le texte dit que l'application **ne cherche pas** à savoir ce qui observe le poste.
- [ ] Le bandeau est **non modal** : aucun `MatDialog`, aucun `cdkTrapFocus`, aucun masque.
- [ ] Couleurs et polices exclusivement issues des jetons `--cg-*` du design system ; espacements
      multiples de 4 px.
- [ ] Le bandeau reste lisible sous 480 px de large (pleine largeur, boutons empilés).

---

## Périmètre

### Hors scope (explicite)

- Toute détection de ce qui observe réellement le poste (antivirus, EDR, journalisation).
- Toute persistance **serveur** de la périodicité : c'est un confort de poste, pas une donnée de
  compte (cadrage, décision 6).
- Un rappel en dehors de l'Atelier : c'est le seul écran où des commandes s'exécutent.
- Un réglage dans l'écran Réglages : le bandeau se règle depuis le bandeau, là où on le rencontre.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|---|---|---|
| `intervalHours` | `2` | Défaut du cadrage (décision 7) |
| `acknowledgedAt` | `null` | Aucun rappel acquitté ⇒ le bandeau s'affiche à la première ouverture |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|---|---|---|---|---|---|
| `intervalHours` | Oui | — | `2`, `8`, `24`, ou `null` (jamais) | Non | Toute autre valeur ⇒ `2` |
| `acknowledgedAt` | Non | — | horodatage en millisecondes | Non | Non numérique ⇒ `null` ; postérieur à l'instant ⇒ ramené à l'instant |
| `version` | Oui | — | `1` | Non | Toute autre valeur ⇒ état neuf |

---

## Technique

### Endpoint(s)

Aucun. Aucun appel réseau : le rappel est entièrement local au navigateur.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `WorkstationNoticeService` (`core/services`) — état, périodicité, mémoire `localStorage`
- `WorkstationNoticeComponent` (`atelier/notice`) — le bandeau, non modal
- `AtelierComponent` — affiche le bandeau quand il est dû

---

## Plan de test

### Tests unitaires

- [ ] `WorkstationNoticeService` — jamais acquitté ⇒ dû.
- [ ] `WorkstationNoticeService` — acquitté il y a 1 h avec une périodicité de 2 h ⇒ non dû.
- [ ] `WorkstationNoticeService` — acquitté il y a 3 h avec une périodicité de 2 h ⇒ dû.
- [ ] `WorkstationNoticeService` — périodicité « jamais » ⇒ jamais dû, même sans acquittement.
- [ ] `WorkstationNoticeService` — `acknowledge()` repart le compteur.
- [ ] `WorkstationNoticeService` — la périodicité est relue à la construction suivante.
- [ ] `WorkstationNoticeService` — périodicité mémorisée invalide ⇒ 2 h.
- [ ] `WorkstationNoticeService` — horodatage futur ⇒ non dû, pas de boucle.
- [ ] `WorkstationNoticeService` — `localStorage` qui lève en lecture **et** en écriture ⇒ aucun
      throw, comportement de rappel neuf.
- [ ] `WorkstationNoticeComponent` — le texte porte « vraisemblablement » et « ne cherche pas ».
- [ ] `WorkstationNoticeComponent` — « Compris » émet l'acquittement.
- [ ] `WorkstationNoticeComponent` — changer la périodicité la transmet au service.

### Tests d'intégration

- [ ] `AtelierComponent` — le bandeau est présent dans le DOM quand le rappel est dû, absent sinon.

### Isolation workspace

- [x] Non applicable — raison : aucune donnée serveur n'est lue ni écrite. L'état vit dans le
      navigateur du poste, et ne contient ni identifiant de compte, ni identifiant de poste, ni jeton.

---

## Dépendances

### Subfeatures bloquantes

- `SF-53-01` (guide d'accueil) — statut : done — fournit le patron de mémoire locale réutilisé ici

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

| # | Décision | Pourquoi |
|---|---|---|
| D1 | Bandeau **non modal**, en bas à gauche | Le haut porte le rappel d'autorisation en attente (F-47, `z-index` 1000), le haut-droit le guide (F-53, 950), le bas-droit la bulle d'aide (F-54), le bas-centre la saisie du terminal. Le bas-gauche est le seul emplacement libre — et un rappel de responsabilité n'a aucune raison de couvrir quoi que ce soit. |
| D2 | Le réglage vit **dans** le bandeau | On règle une gêne là où on la rencontre. L'enterrer dans l'écran Réglages garantirait que personne ne le trouve, et transformerait un rappel utile en agacement subi. |
| D3 | « Jamais » est proposé | Un rappel qu'on ne peut pas éteindre n'est plus un rappel, c'est une nuisance — et la première réaction serait de fermer l'écran. Le laisser désactivable, c'est le rendre crédible. |
| D4 | Mémoire **locale**, pas serveur | La périodicité dépend du poste où l'on travaille, pas du compte. Et le patron existe déjà (F-53, décision 5). |
| D5 | « vraisemblablement », pas « vous êtes surveillé » | Le produit ne **sait** pas. Un verdict serait faux dans les deux sens : affirmer la surveillance là où il n'y en a pas, ou rassurer là où il y en a une. |
