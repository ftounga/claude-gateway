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
- [ ] Chaque cause de repli ⇒ SVG cadré (table de vérité, test).
- [ ] Téléphone : le schéma 3-tiers occupe ≥ 60 % de la largeur à 390 × 844 et tous les libellés de zones porteuses
      sont visibles (banc `/jarvis`) ; volet bas.
- [ ] Présentation : aucun CIDR / ARN / identifiant de compte dans le DOM ni dans le plan (test).
- [ ] Univers suspendu pendant la scène (0 image), repris ensuite (test).
- [ ] Téléphone p50 ≥ 50 i/s sur 60 nœuds : mesuré au banc en rendu logiciel (indicatif) ; mesure sur le téléphone
      du PO à J+7 (SF-238-14).
- [ ] Planche itération 1 ; **visa PO** (relayé).

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
