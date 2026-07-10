# Design System — claude-gateway

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
- Logo « Claude Proxy » (`frontend/public/claude-proxy-logo.png`) : bouclier hexagonal navy, tête + étincelle orange, bulle de chat, orbite.
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
| **Background** | Crème | `#F5EFE3` | `--cg-bg` / `--cg-cream` | Fond de page |
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
- Fond : `#F5EFE3` (crème, `--cg-bg`)
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
- Ligne hover : fond `#F8FAFC`

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

## Logo & marque (ajout 2026-07-03)

- **Logo de l'application** : `frontend/public/claude-proxy-logo.png` (« Claude Proxy » — bouclier hexagonal, tête + étincelle, bulle de chat, orbite). Utilisé comme **favicon** (`index.html`) et sur la **landing** (nav, hero, footer). Nom de marque affiché : **« Claude Proxy »**.
- **Palette de marque** (dérivée du logo) : navy profond `#0B1020`, orange `#E07B39`, crème `#F5EFE3`. **Depuis F-27 (2026-07-10), cette palette est la charte de toute l'application** (jetons `--cg-*`), et plus seulement de la landing. L'ancien indigo `#4338CA` / cyan `#06B6D4` est abandonné. La landing conserve ses variables SCSS locales (`$brand-navy`/`$brand-orange`/`$brand-cream`), désormais alignées avec la charte globale.
