# Mini-spec — [F-236 / SF-236-04] L'univers et le graphe de liaisons

## Identifiant
SF-236-04

## Feature parente
F-236 — Jarvis en 3D (cadrage §8, itération 1 « Jarvis s'allume »)

## Statut
Livrée (PR à la suite de cette mini-spec)

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-04-univers-liaisons`

---

## Objectif

Dessiner l'univers du PO en 3D autour du cœur — clients, sujets, chantiers, chapelet d'étapes, personnes — relié par
des liaisons lumineuses dont l'état (latent, contexte, actif, allumé) dit de quoi Jarvis parle, avec des étiquettes
nettes et un sujet qui se touche comme dans la constellation 2D.

## Comportement attendu

### Cas nominal

1. **Disposition** (`jarvis-layout-3d.ts`, pur, déterministe — hachage FNV-1a des clés) :
   - clients sur une orbite : PC = anneau légèrement incliné vu en surplomb (18°) ; téléphone = ellipse **dressée**
     (plus haute que large) qui occupe le portrait ;
   - sujets en anneau autour de leur client (téléphone : client courant + sujets cités) ;
   - chantiers autour du sujet courant (ou cité) ; **chapelet** d'étapes (≤ 12 perles, faites = pleines, l'étape
     courante nommée) autour du chantier focalisé ; personnes attendues (L-attente) ou citées près de leur client.
2. **Graphe** (`jarvis-link-graph.ts`, pur) — familles L-poste, L-sujet, L-chantier, L-étape, L-attente (pointillée,
   couleur de l'attente), L-interlocuteur ; règles :
   - chemin du sujet courant (cœur → client → sujet) **actif** ; autres sujets du client courant en **contexte** ;
     le reste **latent** (la structure se devine) ;
   - nœud cité (lecteur F-234 `lit` / `said`, ≤ 3, D2) : **allumé** de bout en bout jusqu'au cœur, son chapelet et ses
     attentes aussi ;
   - « Montrer » : les éléments demandés allumés, tout le reste latent ;
   - veille : tout latent ; poste muet = liaison et client éteints (gris bleuté).
3. **Moteur** (`engine/jarvis-universe-3d.ts`) : un `InstancedMesh` par forme (sphère, lune, losange, perle, carré),
   filaments `LineSegments2` à largeur en pixels en dégradé source → cible, halos ; positions et opacités
   **interpolées** (relecture de l'univers sans saut ; montée plus vive que la retombée — T-L1 / T-L2) ; un nœud
   nouveau naît à la place de son parent ; dérive lente de l'univers (1 tour / 10 min), arrêtée pendant l'écoute et
   sur un focus ; l'univers s'écarte de 15 % quand le grand cerveau se déploie.
4. **Étiquettes** CSS2D (DOM) dans un calque au-dessus de l'écran : Space Grotesk 600 capitales (§23, V2), bornées
   (PC 48, téléphone 16 : allumés > actifs > clients > contexte) ; un **sujet est un vrai bouton** (≥ 44 px, clavier,
   `aria-label`) : le toucher = « passe sur X » (`touchSubject`, D12) ; client, chantier, personne ne naviguent pas
   (D9 : ils se regardent — caméra en SF-236-05).
5. **Caméra** : vue d'ensemble ajustée au plus petit champ, portrait compris (`jarvis-camera-rules.ts`), focale fixe
   55°, aucun roulis.
6. En 3D, la carte SVG de la constellation 2D s'efface (le fil d'Ariane, équivalent accessible, reste) ; en 2D, rien
   ne change.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Univers absent ou vide | Cœur seul, aucune liaison, aucune étiquette. |
| Plus de 3 nœuds cités | Les 3 premiers (priorité du lecteur) sont allumés. |
| Chantier à 40 étapes | 12 perles. |
| Plus d'étiquettes que la borne | Les moins importantes sont masquées. |
| Relecture de l'univers (`done`) | Les nœuds restants glissent vers leur nouvelle place, les nouveaux naissent du parent. |
| Capture headless dont la première image est en retard | Le script refait la capture (≤ 3 fois). |

---

## Critères d'acceptation

- [x] CA1 — Mêmes entrées ⇒ mêmes positions (test).
- [x] CA2 — Chemin du focus allumé de bout en bout, voisins en contexte, reste latent ; « Montrer » ; veille (tests sur
  le plan).
- [x] CA3 — ≤ 3 nœuds allumés (test).
- [x] CA4 — Univers vide ⇒ cœur seul (test).
- [x] CA5 — Bornes : étiquettes PC 48 / téléphone 16 (code + banc) ; appels de dessin de l'univers ≤ 7 (5 formes +
  liaisons + halos).
- [x] CA6 — Captures portrait / paysage : vue d'ensemble, nœud cité, « Montrer » (`docs/features/F-236/captures/univers/`).
- [x] CA7 — Toucher un sujet = le choisir ; client / chantier ne naviguent pas (test de l'écran).
- [ ] CA8 — i/s cibles tenues : mesure sur appareils réels à faire (qualité adaptative en garde-fou).

## Périmètre

### Hors scope (explicite)
- Caméra qui va vers le nœud cité, gestes, retour automatique, entrée cinématique (SF-236-05).
- Impulsions le long des liaisons, liaisons tickets / veilles / essaim / passation / fichiers (itération 2, scènes).
- Inspecteur de nœud (SF-236-19).
- Aucun appel réseau ajouté (l'univers est celui que le bus F-234 lit déjà), aucune consigne touchée.

## Contraintes de validation
- Chapelet ≤ 12 ; nœuds allumés ≤ 3 ; étiquettes ≤ 48 / 16.

---

## Technique

| Fichier | Rôle |
|---|---|
| `stage3d/jarvis-layout-3d.ts` | Pur — disposition déterministe, `stableHash`, `UniverseTouch`. |
| `stage3d/jarvis-link-graph.ts` | Pur — liaisons, états, règles, `stateAlpha`. |
| `stage3d/jarvis-camera-rules.ts` | Pur — focale, vue d'ensemble. |
| `stage3d/jarvis-stage-director.ts` | `universeView`, `sameScreen` ; plan : `universe`. |
| `stage3d/engine/jarvis-universe-3d.ts` | Moteur — formes instanciées, filaments, halos, étiquettes CSS2D. |
| `stage3d/engine/jarvis-engine.ts` | Univers, caméra d'ensemble, dérive, écart. |
| `stage3d/jarvis-stage-3d.component.ts` | Calque d'étiquettes, sortie `touched`. |
| `stage3d/jarvis-banc-scenes.ts` | Scènes du banc (dev, chargées seulement par la page du banc) : `univers`, `univers-cite`, `univers-montrer`. |
| `jarvis.component.{ts,html,scss}`, `styles.scss` | Signaux de l'univers (lus), calque d'étiquettes, `touched3d`, carte 2D effacée en 3D, style des étiquettes. |
| `scripts/jarvis-3d-captures.sh` | Capture refaite (≤ 3) si la première image n'est pas encore posée. |

Endpoints, tables, migration : aucun.

## Plan de test
- `jarvis-layout-3d.spec.ts` : déterminisme, hachage, vide, PC / téléphone, ellipse dressée, chapelet borné, distance.
- `jarvis-link-graph.spec.ts` : familles, chemin actif, contexte, cité allumé + chapelet + attente, D2, « Montrer »,
  veille, teintes, paliers, vide.
- `jarvis-stage-3d.component.spec.ts` : univers suivi, nœud touché remonté.
- `jarvis.component.spec.ts` : sujet ⇒ choisi ; client / chantier ⇒ rien.
- Visuel : 6 captures (3 scènes × téléphone / PC).
- Isolation : sans objet (univers déjà filtré `user_id` côté serveur, aucun appel ajouté).

## Dépendances
SF-236-03 (livrée, #1461).

## Préoccupations transversales

| Préoccupation | Cochée ? | Composants impactés |
|---|---|---|
| Navigation / routing | **Oui** | Toucher un sujet 3D appelle `touchSubject` (même chemin que la constellation, `?sujet=` inchangé) ; client / chantier / personne ne naviguent jamais ; aucune route ni paramètre ajouté. Test de l'écran. |
| Auth / tenant / limites | Non | — |

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Téléphone : orbite des clients **dressée** (ellipse plus haute que large). | Un anneau horizontal en portrait impose une caméra lointaine (cœur minuscule) ; l'ellipse occupe la hauteur. | Anneau unique pour tous les formats. | Oui. |
| A2 | Clients répartis régulièrement dans l'ordre de leur clé + décalage haché (pas un angle haché pur). | Deux clients ne se chevauchent jamais ; la carte reste stable. | Angle 100 % haché (collisions). | Oui. |
| A3 | Seul le nœud cité est « allumé » ; les nœuds traversés par son chemin sont « actifs », les liaisons du chemin allumées. | D2 (≤ 3 nœuds allumés) et « chemin allumé de bout en bout » ensemble. | Tout le chemin allumé (D2 violé). | Oui. |
| A4 | Interpolation exponentielle (montée τ 160 ms, retombée τ 300 ms, déplacement τ 350 ms) plutôt qu'une courbe par liaison. | Des dizaines de liaisons changent à la fois : une constante de temps est interruptible par nature et n'alloue rien. | Un `Tween` par liaison. | Oui. |
| A5 | Les scènes du banc et l'univers de test vivent dans `jarvis-banc-scenes.ts`, importé par la seule page du banc. | La donnée de test ne part pas en production. | Banc dans le morceau `/jarvis`. | Oui. |
