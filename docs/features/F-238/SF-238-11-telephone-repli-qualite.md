# Mini-spec — [F-238 / SF-238-11] Téléphone, repli 2D, qualité, mode présentation

## Identifiant
SF-238-11

## Feature parente
F-238 — Architecture en 3D, itération 1 (cadrage §10.1 T-A10, §14, §17)

## Statut
done

## Date de création
2026-11-15

## Branche Git
`feat/SF-238-11-telephone-repli-qualite`

---

## Objectif

Rendre l'architecture 3D juste partout : grande et lisible au téléphone (défaut connu : schéma petit, libellés de
zones masqués), repli SVG cadré pour chaque cause (table de vérité) avec le fondu T-A10, qualité adaptative et
univers suspendu pendant la scène, et masquage des CIDR / comptes / ARN en mode présentation ; planche itération 1.

## Comportement attendu

### Cas nominal
1. **Téléphone (portrait)** : la caméra choisit l'orientation qui rend le schéma le plus grand (azimut de base 35° ou
   35° + 90°, et le symétrique) ; marge réduite ; libellés de zones **au bord le plus proche de la caméra** ; budget
   d'étiquettes : 16, **zones d'abord** pour les zones porteuses (AZ, VPC, sous-réseaux) puis services, plus petits
   (zones 9 px, services 11 px) ; fiche du service en **volet bas** (4 lignes) ; Δ éclaté 1,4 (déjà).
2. **Table de vérité du repli** (`architecture-render.ts`, pur) : univers en 2D (WebGL absent, avertissement de
   performance, économie de données, 2D choisi, contexte perdu) ⇒ `universe_2d` ; sobre ; mouvement réduit ; graphe
   absent ; échec WebGL de la scène ; qualité adaptative à bout (`perf`) ; « en 2D » (`manual`). Chaque cause ⇒ SVG
   cadré ; la raison est gardée (mesure SF-238-14).
3. **T-A10** : bascule 3D ↔ 2D en fondu croisé 0,4 s (`glisse`), le SVG cadré **sur le même focus** (la cible
   nommée = le focus de la 3D).
4. **Qualité adaptative** (gouverneur F-236, 3 niveaux) dans le moteur d'architecture : Haut → Moyen → Bas (densité
   de pixels, lumière) ; Bas trop lent 5 s ⇒ repli `perf`. **L'univers F-236 est suspendu** (0 image) tant que la
   scène d'architecture est ouverte, et reprend à sa fermeture.
5. **Mode présentation** : libellés conservés ; CIDR (`10.0.•.•/16`), comptes (`•••• 4821`) et ARN masqués dans les
   étiquettes **et** dans le plan (mise en page 3D) ; le bloc `## Écran` (titre) inchangé (F-237 : aucune pièce en
   présentation).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Graphe en cours de lecture | « Je pose le schéma… », pas de repli | — |
| Repli `perf` pendant la scène | fondu vers le SVG, la raison est retenue pour la pièce | — |
| Retour « en 3D » après `perf` ou `webgl` | refusé : le bouton « Voir en 3D » n'apparaît pas | — |
| Libellé sans donnée sensible | inchangé | — |

## Critères d'acceptation
- [x] Chaque cause de repli ⇒ SVG cadré (table de vérité, test).
- [x] Téléphone : le schéma 3-tiers occupe ≥ 60 % de la largeur à 390 × 844 (test) et les 6 libellés de zones
      porteuses sont visibles (banc `/jarvis` : 6/6, 3/6 avant) ; volet bas.
- [x] Présentation : aucun CIDR / ARN / identifiant de compte dans les libellés ni dans le plan (test).
- [x] Univers suspendu pendant la scène (0 image), repris ensuite (banc `/jarvis`, PC et téléphone).
- [ ] Téléphone p50 ≥ 50 i/s sur 60 nœuds : mesuré au banc en rendu logiciel (indicatif) ; mesure sur le téléphone
      du PO à J+7 (SF-238-14).
- [x] Planche itérations 1-2 : `~/.local/share/claude-gateway/captures-f238/iteration-1-2/planche-f238-iterations-1-2.html` ; **visa PO** relayé.

## Périmètre — hors scope
Montrer d'initiative (SF-238-13) ; mesure `UsageEvent` (SF-238-14).

## Valeurs initiales
Sans objet.

## Contraintes de validation
| Champ | Règle |
|---|---|
| étiquettes téléphone | ≤ 16 |
| particules téléphone | ≤ 400 |

## Technique
Endpoints, tables : aucun. Fichiers : `architecture-render.ts` (nouveau, pur), `architecture-stage-director.ts`,
`architecture-layout-3d.ts`, `engine/architecture-scene-3d.ts`, `architecture-stage.component.ts`,
`jarvis-piece-architecture.component.ts`, `jarvis-piece-viewer.component.ts`, `jarvis-piece-gallery.component.ts`,
`jarvis-engine.contract.ts` + `engine/jarvis-engine.ts` (`hold`), `jarvis-stage-3d.component.ts`,
`jarvis.component.html`.

## Plan de test
### Tests unitaires
Table de vérité ; masquage ; orientation téléphone (taille projetée) ; étiquettes zones d'abord ; gouverneur ⇒ repli ;
`hold` de l'univers.
### Tests d'intégration
Banc `/jarvis` 390 × 844 et 1920 × 1080 (captures, largeur occupée, libellés visibles) ; planche.
### Isolation utilisateur
Non applicable (aucune donnée nouvelle).

## Préoccupations transversales
- Navigation / routing : non. Consigne : non. Tenant, Auth, Plans : non.

## Dépendances
SF-238-10 (visionneuse, `PieceNav.arch`) : livrée juste avant. Questions ouvertes : aucune.

## Notes et décisions
- **D-11-1 — cause racine du défaut téléphone** : les libellés de zones partageaient le plafond de création des
  étiquettes des services (16 au téléphone) ; avec 10 services, seules les 6 premières zones (AWS Cloud, Région, VPC,
  AZ A, AZ B, public A) avaient un libellé, les autres n'existaient pas. Toutes les zones ont désormais le leur ; le
  plan choisit les 16 visibles (zones qui structurent d'abord).
- **D-11-2 — schéma petit** : en portrait, la vue d'ensemble se pose **de face** (azimut 0° ou 90°, celui qui rend le
  schéma le plus grand) et plus haute (55°) : un schéma vu en diagonale (35°) perd 30 à 40 % de largeur. Limite
  physique : un schéma carré n'est jamais plus large que l'écran ; au-delà, « zoome » / le pincement.
- **D-11-3** Libellés de zones courts au téléphone (« AZ A », « Privé B ») ; complets dans la fiche et le 2D.
- **D-11-4** Au téléphone, les zones passent avant les services au dé-chevauchement (un service masqué se lit à son
  icône et au toucher).
- **D-11-5** Univers F-236 suspendu (0 image) pendant la scène : mesuré au banc (images figées pendant la scène,
  reprises après).
- **D-11-6** p50 ≥ 50 i/s au téléphone : non mesurable ici (rendu logiciel sans GPU) ; la qualité adaptative et la
  mesure `a_2d_p` (SF-238-14) le diront à J+7 sur le téléphone du PO.

## Retours du PO (2026-11-16) intégrés avant fusion
- **R1 — gestes obligatoires** (cadrage P4) : glisser (souris ou doigt) = pivoter ; molette et **pincement à deux
  doigts** (écran tactile PC et téléphone) = zoomer / dézoomer ; boutons « + », « − », **« Recentrer »** (et la voix :
  « recentre », « recadre ») = vue d'ensemble sans focus, orbite et zoom remis à zéro. Lecture des pointeurs pure
  (`jarvis-piece-gestures.ts`, tests) ; banc `/jarvis` : rotation, pincement et recentrage simulés (souris, CDP
  tactile à deux doigts), captures avant / après.
- **R2 — trop lumineux** : au repos, lueur sobre — bloom 0,75 → 0,30 (0,65 seulement quand Jarvis allume quelque
  chose, montée et descente glissées), halos et anneaux sous les icônes à 30 %, filaments au repos atténués, halo des
  liaisons 0,4 → 0,2 ; icônes AWS toujours à pleine opacité (couleurs officielles, aucune lueur par-dessus) ;
  libellés sur verre navy 92 %. La forte lueur est réservée à la narration, aux flux et au héros.
