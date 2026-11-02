# Mini-spec — [F-238 / SF-238-02] Zones AWS conformes

## Identifiant
SF-238-02

## Feature parente
F-238 — Architecture en 3D, itération 0 « Le schéma 2D cadré » (cadrage §4.3, §4.6, §17)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-238-02-zones-aws`

---

## Objectif

Dessiner chaque zone selon la convention officielle des groupes AWS (bordure, trait, fond, icône de groupe, libellé
en haut à gauche) à partir d'un champ `kind`, explicite ou inféré du libellé, avec une imbrication stricte.

## Comportement attendu

### Cas nominal
1. `groups[].kind` ∈ `cloud`, `account`, `region`, `vpc`, `az`, `subnet_public`, `subnet_private`, `asg`,
   `security_group`, `onprem`, `generic`.
2. Sans `kind`, il est **inféré du libellé** (accents et anglais compris : « AWS Cloud », « Région… », « VPC… »,
   « Availability Zone… » / « AZ … » / « Zone de disponibilité… », « Sous-réseau public / privé », « Public /
   Private subnet », « Auto Scaling… », « Security group… », « Centre de données / on-prem… ») ; à défaut `generic`.
   Une **seule note** dit les zones supposées : `NOTE DU RENDU : zones supposées d'après leur libellé (précise
   « kind ») : …` (en-tête `X-Cg-Diagram-Notice`, avec la densité).
3. Styles (table `ZONE_STYLES` de `cadre.js`, source unique de la garde G6) — cadrage §4.3 :
   cloud `#232F3E` continu ; account `#E7157B` continu ; region `#00A4A6` pointillé ; vpc `#8C4FFF` continu ;
   az `#00A4A6` pointillé, sans icône ; subnet_public `#7AA116` fond `#F2F6E8` ; subnet_private `#00A4A6` fond
   `#E6F6F7` ; asg `#ED7100` pointillé ; security_group `#DD344C`, sans icône ; onprem `#7D8998` ; generic
   `#7D8998` pointillé. Libellé 13 px de la couleur de bordure, après l'icône de groupe (32 px) en haut à gauche.
4. Icônes de groupe : PNG officiels livrés par `diagrams` (VPC, sous-réseaux, Auto Scaling, compte, immeuble),
   **non recolorés** ; AWS Cloud et Région (absentes de la lib) : glyphe blanc dessiné sur carré de la couleur de
   bordure.
5. **Imbrication stricte** pour un `kind` explicite : parents autorisés (§4.3). Profondeur maximale 6.

### Cas d'erreur

| Situation | Comportement | Code |
|---|---|---|
| `kind` explicite avec parent non autorisé | Refus : « Zone « X » mal placée … : une Availability Zone se place dans un VPC. Corrige « parent » … » | 422 |
| Security group hors d'un VPC | Refus avec la raison | 422 |
| `kind` inconnu | Refus avec la liste des valeurs | 422 |
| `kind` inféré mal placé (description d'avant F-238) | Accepté, style appliqué, note d'inférence | 200 |

## Critères d'acceptation
- [x] Chaque `kind` rendu avec sa bordure, son trait, son fond, son icône et son libellé en haut à gauche (test sur le SVG).
- [x] Cloud › Région › VPC › AZ › sous-réseau › ASG **accepté** (profondeur 6).
- [x] AZ hors VPC **refusée** avec la raison.
- [x] Inférence ⇒ note.
- [x] G4 (imbrication, marges ≥ 24 px) et G6 (conventions) verts sur les 3 types + « tout » ; les gardes détectent
      un défaut fabriqué.

## Périmètre — hors scope
- Placement des zones (AZ parallèles, bande régionale, grille) : SF-238-03. Routage : SF-238-04.
- Téléchargement du paquet officiel d'icônes au build (voir A1).

## Contraintes de validation

| Champ | Obligatoire | Valeurs | Normalisation |
|---|---|---|---|
| `groups[].kind` | Non | 11 valeurs ci-dessus | trim, minuscules |
| profondeur | — | ≤ 6 | — |

## Technique
- Endpoints : aucun. Tables : aucune. Migration : non applicable.
- Fichiers : `diagram-renderer/cloud.py` (`infer_kind`, `zone_kinds`, `prepare`), `cadre.js` (`ZONE_STYLES`,
  `headerRect`, icônes de groupe), `gardes.js` (G4, G6), `tests/test_cadre_zones.js`, fixture `tout.json`.

## Plan de test
- Unitaires (node:test) : conventions par kind, profondeur 6, refus (AZ hors VPC, VPC à la racine, SG hors VPC,
  kind inconnu), inférence (FR/EN/accents/sigles), description plate d'avant F-238 valide, gardes non aveugles.
- Non-régression : 89 tests Python `test_cloud*.py`, `test_cadre.js`.
- Isolation utilisateur : non applicable (aucune donnée).

## Dépendances
SF-238-01 (mergée avant). Aucune question ouverte.

## Notes et décisions
- **A1** Icônes de groupe prises dans la lib `diagrams` déjà épinglée (0.24.4, dérivée du paquet officiel) au lieu
  d'un téléchargement du paquet AWS au build : aucune dépendance réseau ni URL versionnée à maintenir ; Cloud et
  Région dessinés (glyphes simples, pas des copies d'icônes). Réversible.
- **A2** L'imbrication stricte ne s'applique qu'aux `kind` **explicites** : sinon les descriptions plates d'avant
  F-238 (« Sous-réseau public » à la racine) seraient refusées, contre G7.
- **A3** Le schéma « tout » place l'ASG dans l'AZ (Cloud › Compte › Région › VPC › AZ › ASG = 6 niveaux) : avec un
  compte, un ASG dans un sous-réseau ferait 7 niveaux, au-delà de D6.
- **A4** Une seule note d'inférence (au lieu d'une par zone) : l'en-tête reste court et lisible.
