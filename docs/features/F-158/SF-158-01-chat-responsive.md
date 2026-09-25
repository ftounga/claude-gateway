# Mini-spec — F-158 / SF-158-01 Chat responsive

## Identifiant

`F-158 / SF-158-01`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-158-01-chat-responsive`

---

## Objectif

Rendre l'écran Chat (`chat.component`) utilisable sur téléphone (~400 px) : sous 819 px, la grille
`280px 1fr` passe en une colonne, la barre latérale (liste des conversations) devient un tiroir plein
écran repliable, le fil de conversation prend toute la largeur, aucun scroll horizontal, cibles
tactiles ≥ 44 px. **Le desktop (≥ 820 px) reste strictement inchangé.**

---

## Comportement attendu

### Cas nominal

- **≥ 820 px (desktop, inchangé)** : grille `280px 1fr`, barre latérale toujours visible à gauche, fil
  et composer à droite ; sélecteur de modèle 220 px ; aucun bouton de tiroir visible.
- **≤ 819 px (téléphone)** :
  - `.chat-layout` passe à une seule colonne ; le fil de conversation et le composer occupent toute la
    largeur.
  - La barre latérale est masquée par défaut et devient un **tiroir plein écran** (superposé,
    `position: absolute; inset: 0`) ouvert par un **bouton liste** ajouté dans la barre d'outils
    (visible uniquement sous 819 px).
  - Le tiroir se ferme via un **bouton × dans son en-tête** (visible uniquement sous 819 px), en
    **sélectionnant une conversation**, ou en cliquant **« Nouvelle »**.
  - La barre d'outils reste sur une ligne sans débordement : le titre s'ellipse, le sélecteur de modèle
    est rétréci ; aucun scroll horizontal.
  - Les blocs de code / tableaux markdown larges restent dans leur conteneur `overflow-x:auto` déjà
    existant (`.markdown-body pre`) ; la **page** ne défile jamais horizontalement.
  - Cibles tactiles (boutons d'icône, bouton Envoyer, éléments de liste, bouton tiroir) ≥ 44 px.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Aucune conversation | Le tiroir affiche l'état vide existant (« Aucune conversation… ») ; le bouton tiroir reste opérant. |
| Rotation / redimensionnement ≥ 820 px alors que le tiroir mobile était ouvert | Les règles desktop reprennent (barre latérale visible en colonne, tiroir/boutons mobiles masqués par CSS) ; aucun état incohérent. |

Aucun cas d'erreur réseau/HTTP nouveau : SF purement d'affichage, aucune logique métier touchée.

---

## Critères d'acceptation

- [ ] À ≥ 820 px, le rendu est identique à aujourd'hui (grille `280px 1fr`, sélecteur 220 px, pas de
      bouton tiroir).
- [ ] À ≤ 819 px, `.chat-layout` est en une seule colonne et le fil occupe toute la largeur.
- [ ] À ≤ 819 px, la barre latérale est masquée par défaut et s'ouvre en tiroir plein écran via le
      bouton liste de la barre d'outils.
- [ ] Le tiroir se ferme via le bouton ×, la sélection d'une conversation, ou « Nouvelle ».
- [ ] Aucun scroll horizontal de page à 400 px (grille en 1 colonne, largeurs fixes 220/180 px
      neutralisées, barre d'outils sur une ligne avec titre ellipsé).
- [ ] Cibles tactiles ≥ 44 px sous 819 px (boutons d'icône, bouton Envoyer, bouton tiroir).
- [ ] Charte respectée : jetons `--cg-*` uniquement, aucune couleur/police hors `DESIGN_SYSTEM.md`.
- [ ] Budget SCSS F-117 respecté (règles mobile en feuille dédiée `chat-mobile.component.scss` pour ne
      pas alourdir la feuille principale déjà proche du seuil d'avertissement).
- [ ] `npm run build` vert ; tests de composant verts.

---

## Plan de test minimal

- **Unitaire / composant (`chat.component.spec.ts`)** :
  - `mobileSidebarOpen` initial à `false`.
  - `toggleMobileSidebar()` bascule l'état.
  - `selectConversation()` referme le tiroir (`mobileSidebarOpen` = `false`).
  - `startNewConversation()` referme le tiroir.
  - Non-régression : les tests existants (envoi, markdown, upload, export, fichiers, bibliothèque)
    restent verts.
- **Intégration / build** : `npm run build` vert au premier plan.
- **Isolation utilisateur** : sans objet — SF purement d'affichage, aucun accès aux données, aucun
  endpoint touché ; l'isolation `user_id` en vigueur est inchangée.

---

## Tables / endpoints / composants impactés

- **Tables** : aucune.
- **Endpoints** : aucun.
- **Composants** :
  - `frontend/src/app/chat/chat.component.ts` (signal `mobileSidebarOpen` + fermetures)
  - `frontend/src/app/chat/chat.component.html` (bouton tiroir, bouton ×, liaisons de classe)
  - `frontend/src/app/chat/chat-mobile.component.scss` (**nouvelle feuille dédiée** ajoutée à
    `styleUrls`)
  - `frontend/src/app/chat/chat.component.spec.ts` (tests)

---

## Préoccupation transversale — Navigation / routing

Déclencheur coché : **repli d'affichage de la barre latérale**. Analyse d'impact (exigée par CLAUDE.md) :

- **Aucune nouvelle route, aucun guard, aucune redirection.** La même URL sert les deux tailles.
- Chemins de navigation liste ↔ fil concernés, tous conservés :
  - `startNewConversation()` (bouton « Nouvelle ») → reste joignable ; referme le tiroir sur mobile.
  - `selectConversation()` (clic sur un élément de liste) → reste joignable ; referme le tiroir.
  - `confirmDelete()` (bouton corbeille par élément) → inchangé, reste joignable dans le tiroir.
  - Bascule du tiroir (nouveau bouton, mobile uniquement) → ouvre/ferme la barre latérale ; sans effet
    sur le routage.
- Les autres préoccupations transversales (**Auth / Principal**, **Contexte tenant**, **Plans /
  limites**) ne sont **pas** touchées (SF purement d'affichage).

---

## Périmètre — Hors scope (explicite)

- Le desktop (≥ 820 px) : strictement inchangé.
- Les autres écrans de F-158 (SF-158-02 → 09) : non touchés (agents parallèles).
- Toute logique métier, tout endpoint, toute migration, tout DTO, tout protocole runner.
- L'installabilité PWA (F-152) et les notifications (F-153).
- Mise à jour de `docs/PRODUCT_SPEC.md` (étape 6, groupée séparément).
