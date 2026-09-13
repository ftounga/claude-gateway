# Design System (charte répliquée de legalcase : navy #1A3A5C / or #C9973A / fond #F5F6FA) — claude-gateway

Source de vérité pour l'identité visuelle et les règles d'interface du projet.

Tout écran produit dans ce projet doit respecter ce document.
Toute divergence doit être explicitement signalée et validée.

---

## 1 — Identité de marque

**Nom produit** : claude-gateway

**Positionnement visuel** : professionnel, moderne et technique, mais rassurant. Une identité
« SaaS IA » nette et crédible, qui inspire sécurité et maîtrise — adaptée à un proxy LLM destiné
à des consultants exigeants. Sobriété > effets ; lisibilité > densité.

**Logo** :
- Logo « Claude Portal » (`frontend/public/claude-portal-logo.png`) : bouclier hexagonal navy, tête + étincelle orange, bulle de chat, orbite.
- Police du logotype : Space Grotesk

> **Refonte F-27 (2026-07-10)** : l'application adopte l'identité de marque **navy / orange / crème** (issue du logo, auparavant réservée à la landing). L'ancienne charte « Indigo Tech » (`--cg-primary` indigo `#4338CA`, accent cyan `#06B6D4`) est **abandonnée**.

---

## 2 — Palette de couleurs

| Rôle | Nom | Hex | Jeton | Usage |
|------|-----|-----|-------|-------|
| **Primary (structure)** | Navy | `#0B1020` | `--cg-primary` / `--cg-navy` | Header/toolbar, bulle message utilisateur, snackbar info, surfaces structurelles |
| Navy clair | Navy 2 | `#141D33` | `--cg-navy-2` | Dégradés (hero, onboarding) |
| **Accent / Action** | Orange | `#E07B39` | `--cg-accent` / `--cg-orange` | Boutons d'action (`color="primary"`), états actifs, liens, highlights, barres de progression |
| Orange clair | Orange 2 | `#F0954F` | `--cg-orange-2` | Survols, dégradés |
| **Background** |  Gris très clair (thème Blanc & Orange, header clair) | `#F5F6FA` | `--cg-bg` | Fond de page (rév. 2026-07-11 : fini le crème, identité claire/pro façon legalcase) |
| **Surface** | Blanc | `#FFFFFF` | `--cg-surface` | Cartes/surfaces, détachées par l'ombre |
| **Surface** | Blanc | `#FFFFFF` | `--cg-surface` | Cartes, modales, formulaires |
| **Error** | Rouge | `#DC2626` | `--cg-error` | Erreurs, alertes destructives |
| **Success** | Vert | `#16A34A` | `--cg-success` | Validations, statuts positifs |
| **Text principal** | Slate 900 | `#0F172A` | `--cg-text-primary` | Corps de texte, titres |
| **Text secondaire** | Slate 500 | `#64748B` | `--cg-text-secondary` | Labels, sous-titres, placeholders |
| **Divider** | Gris clair | `#E2E8F0` | `--cg-divider` | Séparateurs, bordures |

> Thème Angular Material : palette `primary = orange` (boutons d'action de marque), `tertiary = azure`. Le navy structurel est piloté par les jetons CSS `--cg-*` (custom), pas par la palette Material.

---

## 3 — Typographie

| Usage | Police | Poids | Taille de base |
|-------|--------|-------|----------------|
| Titres h1, h2 | Space Grotesk | 700 | 32px / 24px |
| Titres h3, h4 | Space Grotesk | 600 | 20px / 18px |
| Corps de texte | Inter | 400 | 16px |
| Labels, boutons | Inter | 500 | 14px |
| Données, code | JetBrains Mono | 400 | 14px |
| Texte secondaire | Inter | 400 | 12px |

**Import Google Fonts** :
```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&family=Space+Grotesk:wght@500;600;700&display=swap" rel="stylesheet">
```

---

## 4 — Layout général

```
┌─────────────────────────────────────────────┐
│  HEADER  64px fixe                          │
│  [Logo]  [Nav principale]  [Avatar user]    │
├──────────┬──────────────────────────────────┤
│          │                                  │
│  SIDE    │   CONTENU PRINCIPAL              │
│  NAV     │   padding: 24px                  │
│  240px   │                                  │
│          │                                  │
├──────────┴──────────────────────────────────┤
│  FOOTER  48px  [version]  [mentions légales]│
└─────────────────────────────────────────────┘
```

> Note : l'écran central du produit est une **interface de chat** (liste de conversations à gauche,
> fil de messages au centre, panneau Documents/contexte à droite). Le layout header + sidenav
> ci-dessus s'applique aux écrans de gestion (Documents, Settings, Billing).

### Header
- Hauteur : 64px, fixe (position sticky)
- Fond : `#0B1020` (navy, `--cg-primary`)
- Logo à gauche, navigation principale, avatar utilisateur à droite
- Ombre portée : `box-shadow: 0 2px 8px rgba(0,0,0,0.12)`

### Side navigation
- Largeur : 240px déployée / 64px rétractée
- Fond : `#FFFFFF`
- Bordure droite : `1px solid #E2E8F0`
- Item actif : fond clair, texte accent, barre gauche `4px solid #E07B39` (orange, `--cg-accent`)

### Contenu principal
- Padding : 24px
- Fond : `#F5F6FA` (gris très clair, `--cg-bg`) ; cartes `#FFFFFF` (`--cg-surface`)
- Largeur max : 1280px, centré

### Footer
- Hauteur : 48px
- Fond : `#FFFFFF`
- Bordure haute : `1px solid #E2E8F0`

---

## 5 — Composants Angular Material

### Boutons

| Type | Composant | Usage |
|------|-----------|-------|
| Action principale | `mat-flat-button color="primary"` | Créer, Sauvegarder, Confirmer, Envoyer |
| Action secondaire | `mat-stroked-button` | Annuler, Retour |
| Action destructive | `mat-flat-button color="warn"` | Supprimer, Archiver |
| Action tertiaire | `mat-button` | Liens, actions mineures |
| Icône seule | `mat-icon-button` | Actions dans les tables, toolbars |

### Cartes
- Composant : `mat-card`
- Border-radius : `8px`
- Ombre : `box-shadow: 0 2px 8px rgba(0,0,0,0.08)`
- Padding interne : `24px`
- Fond toujours `#FFFFFF`

### Formulaires
- Apparence : `outline` sur tous les `mat-form-field`
- Messages d'erreur via `mat-error` uniquement

### Tables
- Composant : `mat-table` avec `matSort` et `mat-paginator` systématiques
- Ligne hover : fond `#F5F6FA`

### Notifications

| Situation | Composant | Couleur |
|-----------|-----------|---------|
| Succès | `MatSnackBar` | Fond `#16A34A`, texte blanc |
| Erreur | `MatSnackBar` | Fond `#DC2626`, texte blanc |
| Info | `MatSnackBar` | Fond `#0B1020` (navy), texte blanc |
| Confirmation destructive | `MatDialog` | — |

Durée par défaut : 4 secondes. Jamais `window.alert()` ou `window.confirm()`.

### Badges et statuts

| Statut | Couleur fond | Couleur texte |
|--------|-------------|---------------|
| Actif / Indexé | `#E8F5E9` | `#16A34A` |
| En attente / Processing | `#FFF8E1` | `#F9A825` |
| Erreur / Failed | `#FFEBEE` | `#DC2626` |
| Archivé / Inactif | `#F5F5F5` | `#64748B` |

---

## 6 — Règles d'espacement

- Unité de base : `8px`
- Espacements autorisés : `4px`, `8px`, `16px`, `24px`, `32px`, `48px`, `64px`
- Pas de valeurs arbitraires

---

## 7 — Icônes

- Bibliothèque : Material Icons (outlined en priorité, filled pour états actifs)
- Taille standard : `24px`

---

## 8 — Ce qui est interdit

- Couleurs hors palette sans validation explicite
- Polices autres que Space Grotesk, Inter, JetBrains Mono
- `window.alert()`, `window.confirm()`, `window.prompt()`
- Espacements non multiples de 4px
- Tables sans pagination
- Formulaires sans `mat-error` pour les erreurs de validation
- Fond coloré sur les cartes

---

## 9 — Palette d'identité des postes (ajout F-49 / SF-49-03, 2026-09-10)

> **Validation explicite** au sens du §8 : ces couleurs sont hors de la table §2, et c'est
> délibéré. Elles ne décrivent aucun rôle applicatif — elles **identifient une machine**.

Un poste (F-48) reçoit une **identité visuelle dérivée de son nom** : une couleur et des initiales,
calculées par fonction pure (`frontend/src/app/shared/host-identity.ts`), **jamais stockées**. La
même machine porte donc la même couleur d'une session à l'autre, d'un écran à l'autre et d'un poste
de consultation à l'autre. Renommer un poste **change** sa couleur : c'est la contrepartie assumée
d'une couleur qui n'est rangée nulle part.

### Les dix tons

Le hachage du nom choisit un **index** dans cette palette fermée — il ne calcule pas une teinte. Un
ensemble fini est la seule façon de **prouver** le contraste sur toutes les valeurs possibles.

| # | Aplat (`solid`) | Encre (`ink`) | Teinte (`tint`) |
|---|-----------------|---------------|-----------------|
| 0 | `#4370A3` | `#386599` | `#E7EFF9` |
| 1 | `#7051B8` | `#5C3DA4` | `#ECE7F9` |
| 2 | `#A348B1` | `#933BA0` | `#F6E7F9` |
| 3 | `#B1487D` | `#A43D70` | `#F9E7F0` |
| 4 | `#B14F48` | `#A4433D` | `#F9E8E7` |
| 5 | `#94633D` | `#865632` | `#F9EFE7` |
| 6 | `#7F6D34` | `#705F29` | `#F9F4E7` |
| 7 | `#597731` | `#4E6C28` | `#F1F9E7` |
| 8 | `#327B57` | `#286C4A` | `#E7F9F0` |
| 9 | `#34777F` | `#2B6C73` | `#E7F7F9` |

### Règles d'emploi — non négociables

- **La couleur ne porte jamais seule l'information.** Le nom du poste reste **écrit** partout où sa
  couleur apparaît. Quand un écran ne l'écrit pas lui-même, la pastille porte `role="img"` et un
  `aria-label` qui le nomme.
- **Contraste AA (4.5:1) garanti sur chacun des dix tons**, dans les trois emplois autorisés :
  blanc sur `solid`, `ink` sur blanc, `ink` sur `tint`. Le test
  `frontend/src/app/shared/host-identity.spec.ts` **recalcule** les ratios : aucun ton ne peut
  entrer dans la palette sans les tenir. Toute autre combinaison est interdite.
- **Sur une surface dont on ne connaît pas la couleur** (barre navy du terminal) : employer la
  **puce** — `ink` sur `tint` — qui apporte sa propre surface.
- **Filet, jamais fond** : la couleur entre par un bord (`border-left`) ou une pastille. Le §8
  interdit le fond coloré sur les cartes, et un aplat teinté derrière du texte remettrait le
  contraste en jeu à chaque ton.
- **Un seul usage** : identifier un poste. Ces couleurs ne qualifient ni un état, ni une action, ni
  un niveau de gravité — l'or de marque (`--cg-accent`) reste réservé aux gestes, et les pastilles
  de statut restent celles du §5.
- **Composant unique** : `app-host-badge` (`shared/host-badge/`). Aucun écran ne recompose la
  pastille à la main.

---

## 10 — État de mission d'un poste (ajout F-60 / SF-60-02, 2026-09-10)

> **Aucune couleur nouvelle ici** : cette section n'ajoute rien à la palette. Elle dit **quelle
> palette existante** l'état de mission emploie, et surtout **laquelle il n'emploie pas**.

Un poste porte, en plus de son identité (§9), un **état de mission** déclaré par son propriétaire :
`En cours`, `En attente`, `Clôturé`. Deux systèmes de couleur se croisent donc sur le même objet, et
ils répondent à deux questions différentes :

| Question | Registre | Palette | Support |
|---|---|---|---|
| *Chez quel client suis-je ?* | **Identité** | §9 — dix tons dérivés du nom | Filet gauche + pastille d'initiales (`app-host-badge`) |
| *Où en est-on ?* | **État** | §5 — pastilles de statut | Pastille de statut (`app-mission-badge`) |

### Correspondance des états

| État | Classe | Couleur | Libellé écrit |
|---|---|---|---|
| En cours | `.badge--success` | `#E8F5E9` / `#16A34A` | « En cours » |
| En attente | `.badge--warning` | `#FFF8E1` / `#F9A825` | « En attente » |
| Clôturé | `.badge--neutral` | `#F5F5F5` / `#64748B` | « Clôturé » |

### Règles d'emploi — non négociables

- **Deux registres, jamais mélangés.** Aucun ton de §9 ne qualifie un état ; aucune couleur de
  statut n'identifie une machine. Deux systèmes qui se disputent la même surface deviennent
  illisibles tous les deux — c'est le piège inscrit au cadrage de F-60.
- **Une pastille à côté du nom, jamais un second aplat.** L'état n'ajoute ni fond de carte, ni
  deuxième filet : le filet gauche reste celui de l'identité, et il reste seul.
- **La couleur ne porte jamais seule l'information.** La pastille écrit **toujours** son libellé.
  L'icône qui l'accompagne est décorative (`aria-hidden`) et ne remplace jamais le texte.
- **Composant unique** : `app-mission-badge` (`shared/mission-badge/`), qui n'expose **aucune**
  entrée permettant de masquer le libellé. Aucun écran ne recompose la pastille à la main, et aucun
  ne pose de couleur d'état en ligne.
- **Où l'état s'écrit** : sur `/postes`, **toujours**, pour les trois valeurs — c'est l'écran de
  référence, celui où l'on compare des missions. Ailleurs (liste des projets de la Forge, en-tête
  du terminal), **seulement** quand l'état n'est pas « En cours » : la norme y reste silencieuse,
  et l'absence n'est pas ambiguë puisque l'écran de référence, lui, écrit tout.

---

## 11 — Signe de vie d'un terminal (ajout F-70 / SF-70-02, 2026-09-12)

> **Aucune couleur ici non plus**, et cette fois c'est la décision elle-même : ce registre répond
> **sans couleur**. Trois systèmes de couleur cohabitent déjà (§5 statut, §9 identité du poste,
> §10 état de mission) ; un quatrième les rendrait tous illisibles.

Un terminal **vit** quand son onglet est ouvert et que sa place est tenue au registre
(F-70 / SF-70-01). Le PO a tranché le signe : **une pastille et le mot « connecté »**, le **même**
dans la barre du terminal et sur la carte du poste.

| Question | Registre | Palette | Support |
|---|---|---|---|
| *Chez quel client suis-je ?* | **Identité** | §9 — dix tons dérivés du nom | Filet gauche + `app-host-badge` |
| *Où en est-on ?* | **État de mission** | §5 — pastilles de statut | `app-mission-badge` |
| *Est-ce que ça vit maintenant ?* | **Vie** | **aucune** — encre de la surface | `app-live-badge` |

### Règles d'emploi — non négociables

- **Aucune couleur propre.** Le point est peint en `currentColor` : il hérite de l'encre de la
  surface qui le porte, et reste lisible sur la barre navy du terminal comme sur une carte blanche.
  Il ne dispute sa place à aucun des trois autres registres.
- **Le mouvement dit la vie, le mot la nomme.** La pastille pulse (2 s) *et* le libellé est
  **toujours** écrit — aucune entrée du composant ne permet de n'afficher que le point. Sous
  `prefers-reduced-motion`, la pulsation disparaît ; le point et le mot restent.
- **Le mot change selon l'endroit, la pastille jamais.** Dans la barre du terminal : « connecté ».
  Sur la carte d'un poste : « Terminal connecté », ou « N terminaux connectés » — parce que le mot
  « connecté » y est déjà pris par l'état du **runner**, et que deux « connecté » côte à côte pour
  deux choses différentes ne renseignent personne.
- **Ni fond, ni filet.** Le §8 interdit les aplats colorés sur les cartes, et le filet gauche
  appartient à l'identité du poste (§9). Le signe de vie n'entre que par son point.
- **Ce que ça engage se lit à côté.** Quatre flux vivants sont **quatre consommations simultanées** :
  l'écran d'accueil de la Forge écrit « Terminaux vivants : n / 4 » et la phrase qui l'explique. Un
  garde-fou de dépense qu'on ne découvre qu'en le heurtant n'en est pas un.
- **Composant unique** : `app-live-badge` (`shared/live-badge/`). Aucun écran ne recompose la
  pastille à la main, aucun ne pose de couleur en ligne dessus.

---

## 12 — Ce qui attend une décision (ajout F-76 / SF-76-02, 2026-09-12)

> **Aucune couleur nouvelle, et surtout pas un quatrième registre.** Cette section ne fait que dire
> quelle palette **existante** sert à signaler qu'un terminal attend une autorisation — et pourquoi
> ce signal doit être plus franc que les autres.

Un terminal vivant (§11) porte désormais un **aperçu** : ce qu'il fait, et ses dernières lignes.
Quatre états seulement — *inactif*, *réfléchit*, *exécute*, **attend votre autorisation**. Le
dernier n'est pas un état comme les autres : c'est le seul qui **réclame un geste**, et c'est celui
qui, le 2026-09-08, est resté douze heures invisible (F-47).

| Question | Registre | Palette | Support |
|---|---|---|---|
| *Chez quel client suis-je ?* | **Identité** | §9 — dix tons dérivés du nom | Filet gauche + `app-host-badge` |
| *Où en est la mission ?* | **État de mission** | §5 — pastilles de statut | `app-mission-badge` |
| *Est-ce que ça vit maintenant ?* | **Vie** | **aucune** — encre de la surface | `app-live-badge` |
| *Est-ce que ça attend quelque chose de moi ?* | **Décision attendue** | §5 — **« En attente »** (`#FFF8E1` / `#F9A825`) | `app-terminal-preview` |

### Règles d'emploi — non négociables

- **Aucune couleur nouvelle.** L'attente d'autorisation emprunte la pastille **§5 « En attente »**,
  celle qui sert déjà partout dans l'application à dire qu'on attend quelque chose. Les quatre tons
  de §5 restent les quatre tons de §5 ; §9 n'est pas touché.
- **Le libellé est toujours écrit** — « **Attend votre autorisation** » — et aucune entrée du
  composant ne permet de n'afficher que la couleur. Un point ambre n'a pas suffi le 8 septembre et
  ne suffira pas davantage demain.
- **Franchement, mais sans aplat.** Le §8 interdit le fond coloré sur les cartes, et le filet gauche
  appartient à l'identité du poste (§9). Le signal entre par **la pastille écrite** et par le
  **liseré de l'aperçu** qui passe à l'ambre — jamais par un fond, jamais par le filet d'identité.
- **Ce qui attend passe devant.** Là où plusieurs terminaux s'affichent côte à côte, celui qui
  attend une décision est **placé en tête** et **compté en en-tête**. Un signal qu'il faut chercher
  n'est pas un signal.
- **Les dernières lignes sont en JetBrains Mono** (§3), une ligne par ligne, sans repli : un aperçu
  qui change de hauteur à chaque rafraîchissement fait bouger la page qu'on survole du coin de l'œil.
- **Composant unique** : `app-terminal-preview` (`shared/terminal-preview/`), à deux densités —
  `card` (3 lignes, accueil de la Forge) et `tile` (6 lignes, vue de supervision). Aucun écran ne
  recompose l'aperçu à la main, aucun ne pose de couleur en ligne dessus.
- **On regarde, on n'écrit pas.** L'aperçu ne porte ni champ de saisie, ni bouton d'envoi : écrire
  dans une tuile est hors périmètre (F-76), et le terminal qui reçoit ce qu'on tape est à un clic.

---

## 13 — Un terminal en lecture seule (ajout F-83 / SF-83-01 et SF-83-02, 2026-09-12)

> **Aucune couleur nouvelle.** Cette section ne fait que dire **quelle surface existante** porte un
> terminal qu'on regarde sans y écrire, et **quel budget de chrome** l'entoure.

F-76 avait montré des **aperçus** — quelques lignes, dans une carte. F-83 montre **le terminal**,
avec le contenu réel de son flux. La règle est donc celle d'un terminal, pas celle d'une carte.

| Question | Registre | Palette | Support |
|---|---|---|---|
| *Est-ce bien un terminal ?* | **Surface de terminal** | §2 — `--cg-navy-2` (`#141D33`) | `app-atelier-terminal` `[readOnly]` |
| *Chez quel client suis-je ?* | **Identité** | §9 — dix tons dérivés du nom | Filet gauche + `app-host-badge` |
| *Est-ce que ça attend quelque chose de moi ?* | **Décision attendue** | §5 — « En attente » (`#FFF8E1` / `#F9A825`) | Mention écrite dans le flux + anneau de la tuile |

### Règles d'emploi — non négociables

- **Le fond d'un terminal en lecture seule est `--cg-navy-2`** (`#141D33`), et il est **vérifié par
  test**. On doit *reconnaître* un terminal, pas découvrir un composant. Le jeton existe dans la
  table §2 : rien n'est ajouté à la palette.
- **§8 n'est pas contourné.** L'interdiction du « fond coloré » vise les **cartes** ; un terminal
  n'en est pas une, et il peint son fond depuis F-30. Une tuile de mosaïque **est** un terminal.
- **Un seul composant**, et c'est le terminal lui-même (`AtelierTerminalComponent` en `[readOnly]`).
  Aucun écran ne recompose une transcription à la main : deux rendus divergeraient à la première
  retouche.
- **Le chrome est un budget, pas une conséquence.** Là où plusieurs terminaux s'affichent ensemble,
  tout ce qui n'est pas du flux se réduit à **une ligne d'en-tête de page (24 px)** et **une ligne
  par tuile (20 px)**. Mesuré : **au moins 85 % de la hauteur utile revient aux flux** (90 % relevé
  à quatre tuiles sur 800 px), et c'est un **test** qui le tient.
- **On regarde, on n'écrit pas.** Un terminal en lecture seule ne porte ni champ de saisie, ni
  bouton d'envoi, ni bouton de décision. Ce qui attend une autorisation garde son **libellé écrit**
  (§12) et perd ses boutons : décider est un geste du terminal entier, à un clic.

---

## 14 — La liaison Teams (ajout F-87 / SF-87-03, 2026-09-12)

> **Aucune couleur nouvelle, et aucun quatrième registre.** Cette section dit seulement quelles
> palettes **existantes** l'indicateur de liaison emploie — et pourquoi l'état « relié » n'en emploie
> aucune.

La barre du terminal porte un **indicateur de liaison Teams**. Il répond à une cinquième question —
*est-ce que le produit sait lire Teams en ce moment ?* — et il y répond **sans rien ajouter à la
palette**.

| Question | Registre | Palette | Support |
|---|---|---|---|
| *Chez quel client suis-je ?* | **Identité** | §9 — dix tons dérivés du nom | `app-host-badge` |
| *Où en est la mission ?* | **État de mission** | §5 — pastilles de statut | `app-mission-badge` |
| *Est-ce que ça vit maintenant ?* | **Vie** | **aucune** — encre de la surface | `app-live-badge` |
| *Est-ce que ça attend quelque chose de moi ?* | **Décision attendue** | §5 — « En attente » | `app-terminal-preview` |
| *Le produit sait-il lire Teams ?* | **Liaison** | **aucune**, ou §5 | `app-teams-link-badge` |

### Les trois états, et la palette de chacun

| État | Palette | Libellé écrit |
|---|---|---|
| relié | **aucune** — `currentColor`, comme §11 | « Teams relié » |
| navigateur non détecté | §5 `.badge--neutral` (`#F5F5F5` / `#64748B`) | « Teams : navigateur non détecté » |
| Teams a changé | §5 « En attente » (`#FFF8E1` / `#F9A825`), la palette de §12 | « Teams a changé » |

### Règles d'emploi — non négociables

- **Rien n'est ajouté à la palette.** L'état normal — relié — ne porte **aucune** couleur : il prend
  l'encre de la barre, exactement comme le signe de vie (§11). Les deux autres empruntent les
  pastilles de statut **déjà existantes** de §5. Un cinquième registre rendrait les quatre autres
  illisibles.
- **« Teams a changé » emprunte l'ambre de §12**, et c'est délibéré : c'est le seul état où quelque
  chose est réellement à faire côté produit, et où **plus aucun compte rendu ne sera produit** tant
  que ce ne sera pas fait. Un état qui bloque la production se lit comme tel.
- **Le libellé est toujours écrit.** Aucune entrée du composant ne permet de n'afficher que la
  pastille — même règle qu'en §10, §11 et §12.
- **L'indicateur ne porte AUCUNE action** : ni bouton, ni lien. C'est une fenêtre sur un état. La
  seule réparation possible — lancer le navigateur avec son port de débogage — appartient à
  l'utilisateur ; **la commande exacte est écrite dans l'infobulle**, jamais exécutée par un bouton
  qui ne pourrait pas tenir sa promesse.
- **Rien n'est affiché quand il n'y a rien à dire.** Sur un projet sans machine, ou sans runner
  connecté, l'indicateur **n'apparaît pas** : une pastille « navigateur non détecté » permanente
  serait du bruit sur un écran qui n'a jamais parlé de Teams.
- **Composant unique** : `app-teams-link-badge` (`shared/teams-link-badge/`). Aucun écran ne
  recompose l'indicateur à la main, aucun ne pose de couleur en ligne dessus.

---

## 15 — Le compte rendu dans le fil (ajout F-89 / SF-89-03, 2026-09-12)

> **Aucune couleur nouvelle, et aucun cinquième registre.** Cette section dit comment un **compte
> rendu** se lit dans un terminal — et pourquoi le basculement vers Teams est **typographique**, pas
> chromatique.

Le terminal Teams est un terminal comme les autres : même mécanique, même surface, même barre. Ce
qui change est **ce qu'il affiche** — des blocs riches (carte de réunion, moments, liste) — et **la
façon de les lire**.

### Le basculement est typographique

| | Terminal de projet | Terminal Teams |
|---|---|---|
| Surface | `--cg-primary` | `--cg-primary` — **la même** |
| Flux | `--cg-font-mono` | `--cg-font-body` |
| Blocs | texte uniquement | texte **+** carte, moments, liste |

**Pourquoi pas une couleur.** La charte porte déjà quatre registres — identité (§9), mission (§10),
vie (§11), décision attendue (§12) — et §14 a posé la règle : *un cinquième rendrait les quatre
autres illisibles*. La typographie dit la même chose, et le dit plus juste : **un compte rendu est de
la prose, pas une sortie de shell**, et le monospace y affirmerait « ceci est exactement ce que la
machine a répondu » — ce qui serait faux.

**Pourquoi la surface ne change pas.** On doit **reconnaître un terminal** (§13), pas découvrir un
écran. Ce qui bascule est le contenu, jamais le cadre.

### Le bloc lui-même

| Élément | Registre | Palette |
|---|---|---|
| Le bloc | **surface de carte** | §2 — `--cg-surface` (le blanc des cartes de §5) |
| Titre du bloc | Space Grotesk 600, 18 px | encre `--cg-text-primary` |
| Titre de section | Inter 600, 14 px, filet sous le titre | filet `--cg-divider` |
| Ligne | Inter 400, 14 px | encre `--cg-text-primary` |
| Source d'une ligne (auteur, heure, certitude, lien) | 12 px | `--cg-text-secondary`, lien `--cg-accent` |
| Heures et identifiants | `--cg-font-mono` | — |

### Règles d'emploi — non négociables

- **Un terminal de projet reste textuel pour toujours.** Une sortie de commande est exactement ce que
  la machine a répondu, jamais une carte. Les blocs riches n'existent que dans le terminal Teams —
  et un bloc qui y arriverait malgré tout est rendu **en texte**, jamais masqué : masquer ferait
  disparaître une information sans le dire. Trois verrous le tiennent (deux côté gateway, un ici),
  chacun sous test.
- **Chaque ligne porte son auteur, son heure, et un lien vers son message.** Une affirmation qu'on ne
  peut pas ouvrir d'un clic n'a pas sa place dans un compte rendu. Les liens s'ouvrent dans un
  nouvel onglet, `rel="noopener noreferrer"`.
- **Ce qui est incertain se lit comme incertain — par la TYPOGRAPHIE.** Italique, et la mention
  « à confirmer » **écrite en toutes lettres**. **Aucun pictogramme d'avertissement, aucune couleur
  d'alerte** : un triangle jaune dirait « danger » là où la ligne dit seulement « je l'ai déduit ».
  Un test vérifie qu'aucune icône n'apparaît dans une carte.
- **Jamais un score, jamais un pourcentage.** Deux mots, et rien d'autre — « explicite » ou
  « à confirmer ». Un chiffre donnerait une apparence de mesure à une interprétation. Le champ
  n'existe pas dans le modèle, et un test parcourt les schémas d'outils pour qu'il ne réapparaisse
  jamais.
- **La densité d'un compte rendu, pas d'un tableau de bord.** Pas de cadres imbriqués, pas de
  pastilles, pas de colonnes de chiffres. Une hiérarchie de trois niveaux — titre, section, ligne —
  et **ce qu'on attend du lecteur en premier** : « qu'est-ce qu'on attend de moi » doit sauter aux
  yeux en trois secondes, **sans faire défiler**.
- **Ce qui a été lu et ce qui ne l'a pas été sont TOUJOURS visibles, jamais repliés.** En pied de
  bloc, en texte secondaire — mais présents sans un geste : *un trou qu'il faut déplier est un trou
  qu'on ne voit pas*. Une liste de manques vide se lit « aucun manque signalé », **jamais** « tout a
  été lu ».
- **L'image d'un moment est posée À CÔTÉ de la phrase**, jamais en galerie de bas de page : c'est
  l'alignement qui fait la valeur. Un moment **sans image reste un moment** — la phrase et l'heure
  suffisent ; une image qui ne charge pas **le dit**, à sa place.
- **Une image s'agrandit d'un clic — et c'est le geste de §13**, celui de la mosaïque
  (F-83 / SF-83-03), qu'on n'invente pas deux fois : un clic agrandit, un second rend l'image à sa
  place, **Échap** ferme. C'est un **état d'écran**, jamais une adresse. Le libellé du bouton dit
  **l'état** (« Agrandir l'image de 14:32 »), jamais une icône seule.
- **Le point d'entrée est un bouton de carte, comme les autres.** « Terminal Teams » vit à côté de
  « Terminal du poste », avec la même apparence et la même pastille de vie (§11). **Sans le droit, il
  n'y a pas de bouton** — ni grisé, ni menant à un refus : un bouton qui mène à un 403 n'est pas une
  porte, c'est un piège.

---

## 16 — La Forge : colonne et détail (ajout F-98, 2026-09-13)

> **Aucune couleur nouvelle.** La Forge refondue range ce qui existait ; tout vient de §2, §5, §9,
> §10, §11 et §12. Maquette validée : `docs/features/F-98/maquette-forge-refondue.html`. Les
> dimensions de la maquette sont **arrondies à la grille de 4 px** (§6) : 290 → 288, 34 → 32, 9 → 8.

La Forge était une pile de cartes : chaque poste empilait onze blocs, et la page grandissait en
*postes × (blocs + projets)*. Elle devient un **maître–détail** : une colonne des postes, un seul
poste ouvert.

### Le bandeau de la flotte

- **Une ligne**, surface `#FFFFFF`, filet bas `--cg-divider`. Titre « Forge » en Space Grotesk 600.
- Trois faits, en texte secondaire avec le **chiffre en encre principale** : postes en ligne (point
  `--cg-success`), **autorisations qui attendent** (point et chiffre ambre §12 `#F9A825`, affiché
  seulement s'il y en a), terminaux vivants « *n* / 4 » **et** la phrase de ce qu'ils engagent (§11).
- À droite : les portes de la Forge (`mat-stroked-button`), « Rafraîchir » en `mat-icon-button`, et
  « Connecter un poste » en action principale (`mat-flat-button color="primary"`).

### La colonne des postes (`app-forge-rail`)

| Élément | Règle |
|---|---|
| Colonne | **288 px**, surface `#FFFFFF`, filet droit `--cg-divider` ; pleine largeur sous 820 px |
| Filtre | champ de recherche natif, fond `--cg-bg`, filet `--cg-divider`, rayon 8 px, filet `--cg-accent` au focus |
| Titre de groupe | JetBrains Mono 11 px, capitales espacées, `--cg-text-secondary` |
| Ligne de poste | grille *pastille 32 px · nom et statut · compte*, rayon 8 px, survol `--cg-bg` |
| Pastille | `app-host-badge` (§9), jamais recomposée ; « Hébergé » : icône `cloud` sur le gris §5 `#F5F5F5` |
| Statut | point 8 px (`--cg-success` en ligne, `--cg-divider` sinon) **et** le libellé daté écrit (F-97) |
| Ligne ouverte | fond `--cg-bg` et **filet gauche de 4 px de la couleur du poste** (`solid` §9) — un filet, jamais un fond ; gris `--cg-text-secondary` pour « Hébergé » |
| Attente | pastille §5 « En attente » (`badge--warning`) écrite « *k* attend » à la place du compte |
| Compte | nombre de projets en JetBrains Mono 12 px ; « *k* projet(s) trouvé(s) » quand le filtre ne retient le poste que par ses projets |

### Le poste ouvert : en-tête et onglets

| Élément | Règle |
|---|---|
| Surface | le détail est posé **sur le fond de page** : ni carte, ni filet d'appartenance — l'identité entre par la pastille et le filet de la ligne ouverte |
| En-tête | `app-host-badge` `lg` (48 px), nom en Space Grotesk 700 20 px ; dessous, en 12 px secondaire : état de mission (§10), pastille de présence écrite (`badge--success` « En ligne » / `badge--neutral` « Hors ligne », « Jamais connecté »), racine et interpréteur en JetBrains Mono encre principale, « vu il y a … » |
| Actions | à droite, `mat-stroked-button` : Terminal du poste, Teams (si droit), menu « ··· » (gestes destructifs, jamais en accès direct) |
| Onglets | texte 14 px 500 `--cg-text-secondary`, actif en encre principale avec **filet bas de 2 px `--cg-orange`** ; filet de la barre `--cg-divider` |
| Résumé d'onglet | fait chiffré en JetBrains Mono 11 px sur `--cg-bg` (« 4 », « 12 faits », « hors ligne ») ; **état** en pastille §5 : « à appliquer » `badge--warning`, « à corriger » `badge--error` |

### Règles d'emploi — non négociables

- **L'ordre dit l'urgence.** *À regarder* (une autorisation attend) › *En ligne* › *Hors ligne* ›
  *Sans machine* › *Missions clôturées* (repli fermé au départ, §10 « se ranger sans disparaître »).
  Un groupe vide n'est pas rendu.
- **Un seul poste ouvert**, désigné par l'URL `/forge/:hostRef`. La couleur d'identité entre par la
  pastille et le filet de la ligne ouverte ; elle ne qualifie jamais un état.
- **Le statut date, il n'affirme pas** : « en ligne · vu il y a 12 s », jamais « Connecté » seul.

---

## Logo & marque (ajout 2026-07-03)

- **Logo de l'application** : `frontend/public/claude-portal-logo.png` (« Claude Portal » — bouclier hexagonal, tête + étincelle, bulle de chat, orbite). Utilisé comme **favicon** (`index.html`) et sur la **landing** (nav, hero, footer). Nom de marque affiché : **« Claude Portal »** (renommé en F-29 SF-29-01 : le terme « Proxy » faisait classer le domaine en catégorie « anonymizer » par les filtres d'entreprise).
- **Palette de marque** (dérivée du logo) : navy profond `#0B1020`, orange `#E07B39`, crème `#F5EFE3`. **Depuis F-27 (2026-07-10), cette palette est la charte de toute l'application** (jetons `--cg-*`), et plus seulement de la landing. L'ancien indigo `#4338CA` / cyan `#06B6D4` est abandonné. La landing conserve ses variables SCSS locales (`$brand-navy`/`$brand-orange`/`$brand-cream`), désormais alignées avec la charte globale.
