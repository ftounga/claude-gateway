# Mini-spec — F-237 / SF-237-05 — La galerie holographique

## Identifiant
`F-237 / SF-237-05` · Feature parente `F-237` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-237-05-galerie`
Cadrage : §8.1, §8.6, §8.7, §12 (présentation). Contrat importé de SF-237-01 (`GET /api/jarvis/pieces`) ; état de
SF-237-04.

## Objectif
> Les pièces s'affichent pour de vrai sur l'écran Jarvis — éventail holographique sur PC, grille en repli 2D, une
> pièce plein écran au téléphone — et se parcourent au toucher et à la voix avec le même réducteur.

## Comportement attendu
- `JarvisPieceStore` (signaux) : `nav`, `pieces`, `selected`, `dispatch(action)` (porte unique), `openGallery(scope,
  query)` (lit l'index, annule la lecture précédente), `show(scope, pieces)`, `reset()`. **Présentation** : toute
  ouverture et toute action refusées (`PRESENTATION`), sauf fermer ; la galerie se ferme en entrant en présentation.
- `JarvisPieceClient` : `fetch` sous JWT des chemins passerelle de l'index ; binaire ⇒ URL d'objet révoquée au
  changement de galerie et à la destruction. Jamais d'URL de stockage.
- `JarvisPieceGalleryComponent` :
  - **éventail** (3D rendue, ni sobre ni mouvement réduit) : cartes de verre sur un arc de 110° (perspective CSS
    au-dessus de la scène), ≤ 12 visibles, l'arc suit la sélection, « +n » au-delà ; héros avancé ×1,6, autres en
    latent (0,18) ; transition 500 ms `glisse` ;
  - **grille** (repli 2D) : mêmes cartes, mêmes actions ;
  - **téléphone** : grille 2 colonnes, puis une pièce plein écran ; balayage gauche / droite = suivante /
    précédente, vers le bas = retour ;
  - carte : index (ce que le PO dit), vignette ou pictogramme du type, titre, catégorie et type **écrits** ; teinte :
    Entrée crème, Preuve vert, Production orange (§23, aucune couleur nouvelle) ;
  - panneau héros : titre, vignette, description existante, « Sur le poste — hors ligne » pour un dépôt injoignable,
    « d'après la date » pour un rattachement déduit (WINDOW) ; fil d'Ariane « Sujet › Galerie › 2/6 ».
- Écran Jarvis : bouton « pièces du sujet » (toucher, hors présentation) ; ordres vocaux locaux (§5.3) appliqués
  **avant** le cerveau rapide, refus dit en une phrase.

### Cas d'erreur
- Index injoignable ⇒ « Je n'arrive pas à lire les pièces pour le moment. » ; galerie inchangée.
- Action hors bornes / non applicable ⇒ phrase courte, état inchangé.
- Mode présentation ⇒ rien n'est rendu.

## Critères d'acceptation
- [ ] Le toucher (carte, ‹ ›, Galerie, ✕) et la voix produisent les mêmes états.
- [ ] Repli 2D et téléphone fonctionnent sans 3D.
- [ ] Présentation : aucune pièce rendue, ouverture refusée (test).
- [ ] Vignettes chargées sous JWT, révoquées.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Navigation / routing | Non | aucune route ; état interne à `/jarvis` |
| Contexte tenant | Non | lecture seule par les routes SF-237-01/02, déjà isolées |

## Plan de test
`jarvis-piece-gallery.spec.ts` : éventail (arc, 12 visibles, centrage, latent), disposition (fan / grille /
téléphone), balayages, ouverture depuis l'index puis toucher sur le réducteur, panneau (description, « d'après la
date », hors ligne), présentation (refus, rien rendu), requête de l'index. Non-régression : suite front complète.

## Hors périmètre
Visionneuses par type (SF-237-06) ; panneau SVG des schémas (SF-237-07) ; ancrage au nœud et filaments dans la scène
three.js (voir arbitrage) ; outil `afficher` (SF-237-08).

## Arbitrage (réversible)
L'éventail est rendu en **perspective CSS au-dessus de la scène 3D** plutôt que dans le graphe three.js ancré au nœud
du chantier avec filaments `L-piece` : même lecture pour le PO (arc, héros, latent), aucune texture GPU à gérer, et
le même composant sert le repli 2D et le téléphone. L'ancrage au nœud reste possible plus tard sans toucher au
réducteur ni à la galerie.
