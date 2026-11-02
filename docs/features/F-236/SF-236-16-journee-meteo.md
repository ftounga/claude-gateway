# Mini-spec — [F-236 / SF-236-16] Tableau de bord de la journée + météo des chantiers

## Identifiant
SF-236-16

## Feature parente
F-236 — Jarvis en 3D (cadrage §12.1, §13 NF-1 / NF-2, §15 ; itération 3 « Le cockpit PC »)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-16-journee-meteo`

---

## Objectif

Savoir en 3 s où l'on en est (« Aujourd'hui ») et repérer d'un coup d'œil les chantiers en souffrance (météo), dans
le panneau gauche du cockpit et en 3D, sans aucun appel ni tour.

## Comportement attendu

### Cas nominal

1. **« Aujourd'hui »** (`hud/jarvis-day-board.ts`, pur) — cinq chiffres comptés dans l'univers déjà lu et
   l'ambiance hors tour : **postes connectés** `n/m` (hébergé exclu), **chantiers en cours** (non clos),
   **attentes** (chantiers ouverts qui attendent quelqu'un), **à envoyer** (univers + messages finis pendant le
   tour, comme le compteur du HUD), **alertes** (poste muet A1 + veille qui sonne A5).
2. **Toucher un chiffre** ⇒ **« Montrer » local** : l'écran résout le filtre (`resolveShow`) sur l'univers — postes
   ⇒ `connectes`, chantiers ⇒ `etapes-en-cours`, attentes ⇒ `chantiers-bloques`, à envoyer ⇒ `a-envoyer`,
   alertes ⇒ `deconnectes` (si poste muet) — et la 3D le dessine exactement comme un « Montrer » de l'agent
   (S20, T-M). **Aucun tour, aucun appel.** Le chiffre reste enfoncé ; retouché ⇒ effacé ; un tour qui démarre
   l'efface ; un « Montrer » de l'agent prime.
3. **Météo des chantiers** (`hud/jarvis-weather.ts`, pur) — table de vérité, dans l'ordre : clos ⇒ ☀ « terminé » ;
   attend quelqu'un et rien ne bouge ⇒ ⛈ « bloqué — attend X » ; attend mais avance (vivant ou étape en cours) ⇒
   ⛅ « attend X » ; vivant ou étape ⇒ ☀ « avance » ; sinon 🌫 « sans mouvement ».
4. **Liste météo** dans le panneau gauche, du plus grave au plus serein, une ligne par chantier (icône nommée, nom,
   « client · sujet », mot) ; survol ⇒ filament vers le losange.
5. **Ciel en 3D** : un petit ciel (SVG statique) au-dessus de chaque losange de chantier affiché, enfant de son
   étiquette (masqué avec elle), mis à jour quand l'univers est relu.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Univers non lu | Aucun chiffre, aucune ligne météo (sections absentes). |
| Chiffre à zéro (aucun élément) | Bouton désactivé, rien à montrer. |
| Alertes sans poste muet (veille seule) | Chiffre affiché, non touchable. |
| Filtre qui ne résout rien (univers changé) | `Montrer` local vide, chiffre non enfoncé. |
| Chantier sans étiquette affichée (borne T5) | Pas de ciel 3D (la liste météo le montre). |

---

## Critères d'acceptation

- [x] CA1 — Chiffres = univers (tests sur la fixture et un univers riche : hébergé exclu, clos exclus, messages du
  tour comptés, alertes A1 + A5).
- [x] CA2 — Toucher un chiffre ⇒ « Montrer » local **sans tour** (`streamChat` jamais appelé ; test du composant
  Jarvis) ; chaque filtre retrouve ses éléments (test).
- [x] CA3 — Règles de météo testées (table de vérité, 9 cas) ; tri du plus grave au plus serein.
- [x] CA4 — Rouge seulement pour « alertes » > 0 et l'orage ; jamais sans alerte (test).
- [x] CA5 — Captures `cockpit-journee`, `cockpit-meteo` non vides, sans erreur console.

## Contraintes de validation

| Élément | Règle |
|---|---|
| Teintes | postes vert (éteint si 0) ; attentes et à envoyer cyan ; alertes rouge si > 0 ; zéro ⇒ éteint |
| Ciel | soleil vert, nuage cyan, orage rouge, brume crème 60 % ; 20 px |

## Plan de test

- Unitaires : `hud/jarvis-day-board.spec.ts` (météo 9 cas + tri ; chiffres ; filtres ; alertes).
- Composant : `hud/jarvis-cockpit.component.spec.ts` (chiffres, émission, enfoncé, ligne météo, filament) ;
  `jarvis.component.spec.ts` (« Montrer » local sans tour, retouché, autre filtre).
- Visuel : banc `cockpit-journee`, `cockpit-meteo`.
- Isolation utilisateur : sans objet (univers déjà filtré par `user_id` côté passerelle, aucun appel nouveau).

## Périmètre

### Impacté
`hud/jarvis-weather.ts`, `hud/jarvis-day-board.ts`, `hud/jarvis-cockpit.component.{ts,html,scss}`,
`jarvis-layout-3d.ts` (`weather` des chantiers), `engine/jarvis-universe-3d.ts` (ciel), `jarvis-labels.scss`,
`jarvis.component.{ts,html}` (`showLocal`), banc (`cockpit-journee`, `cockpit-meteo`), DESIGN_SYSTEM §23.

### Hors scope (explicite)
- « Veilles du jour » : lues par `GET /api/jarvis/watches` à l'ouverture, elles arrivent avec l'horloge des veilles
  (SF-236-25).
- Aucun endpoint, aucune table, aucun appel IA, aucune consigne touchée.

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Navigation / routing | Non | Aucun chiffre ne navigue ; « Montrer » local seulement. |

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Orage en **rouge** (alerte de chantier). | Un chantier bloqué sans mouvement est un signal d'alerte ; le mot « bloqué » est écrit. N13 (rouge du plan 3D) inchangé : le ciel est du DOM. | Orage gris (peu visible). | Oui. |
| A2 | Chantier clos ⇒ soleil « terminé ». | Quatre temps seulement (cadrage) ; le mot distingue. | Cinquième temps. | Oui. |
| A3 | « Veilles du jour » reportées à SF-236-25. | Elles exigent un relevé `watches` à l'ouverture, livré avec l'horloge. | Appel ajouté ici. | Oui. |
| A4 | « Montrer » local tenu par l'écran (`localShow`), l'agent prime. | Même rendu S20, aucun chemin parallèle. | Écrire dans le bus F-234 (hors périmètre §19). | Oui. |
| A5 | Ciel en DOM (CSS2D, SVG statique) plutôt qu'en particules. | Net, lisible, aucune allocation par image. | Sprite WebGL. | Oui. |
