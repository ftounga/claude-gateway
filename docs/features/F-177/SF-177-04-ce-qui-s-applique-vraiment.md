# Mini-spec — F-177 / SF-177-04 — Ce qui s'applique vraiment

## Identifiant
`F-177 / SF-177-04` — feature parente `F-177` (cadrage, décision D6). Branche :
`feat/SF-177-04-ce-qui-s-applique`. Statut : `in-progress`. Date : 2026-10-06.

## Objectif
Sur l'écran Gouvernance, voir pour un poste ce qui s'applique **réellement** : règles du client, skills
(avec leur origine) et paquets activés **avec leur retard de dépôt**, remis à jour en un clic.

## Comportement attendu

### Cas nominal
1. **`GET /api/governance/hosts/{hostRef}/effective`** (même garde que l'écran : accès Forge ; poste
   résolu possédé) → `{hostRef, hostRules, subjects[], skills[], packages[]}` :
   - **paquets** : pour chaque activation, les empreintes `governance_deposited_files` du poste donnent la
     version réellement déposée → `A_JOUR` (« À jour : fichiers en v17 »), `EN_RETARD` (« En retard :
     fichiers en v2 à v6, paquet en v17 ») ou `JAMAIS_DEPOSE` (aucune empreinte) ;
   - **règles du poste** : `GOUVERNANCE.md` lu à la racine (présent + extrait borné 4 000 car., absent,
     injoignable, sans machine) ;
   - **règles des sujets** : `GOUVERNANCE.md` de chaque sujet **tel que lu au dernier tour** (cache de
     consigne) — aucun aller-retour par sujet ; « pas encore lu » si le cache n'est pas amorcé ;
   - **skills** : ceux du poste (racine, via le catalogue SF-177-03 et le terminal du poste) puis ceux de
     chaque sujet (arborescence en cache), avec `source` = `PAQUET` (chemin déposé par un paquet, nommé)
     ou `CLIENT`.
2. **Écran** : section « Ce qui s'applique vraiment » sous les paquets actifs du poste choisi
   (`/gouvernance`, aucune route nouvelle) : paquets et retard (icône, phrase), [Remettre à jour] /
   [Déposer] qui **rejouent le dépôt existant** (`POST …/{packageId}/apply`, F-96 : fichiers modifiés
   localement conservés), règles (extraits), skills (`/nom · origine · paquet/client`).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Poste d'un autre compte | 404 (« introuvable », jamais « interdit ») |
| Machine éteinte | règles du poste « injoignable », le reste s'affiche |
| Paquet retiré du catalogue | ignoré dans la liste |
| Lecture en échec côté écran | message « n'a pas pu être lu », le reste de l'écran intact |

## Critères d'acceptation
- [x] L'écran signale un poste « en retard » (CAGIP : fichiers v2–v6, paquet v17) et « jamais déposé » (EDENRED).
- [x] [Remettre à jour] rejoue le dépôt existant, puis l'état se relit.
- [x] Règles poste/sujets et skills avec origine visibles.
- [x] Isolation : 404 pour un autre compte.

## Plan de test
- `GovernanceEffectiveServiceTest` (4) : jamais déposé, à jour, en retard (une version / un fichier en retard).
- `GovernanceEffectiveApiIntegrationTest` (3) : EDENRED jamais déposé ; CAGIP en retard (message exact) ;
  404 autre compte.
- Front `governance-effective.component.spec` (4) : retard + jamais déposé + boutons ; règles et skills
  avec origine ; [Remettre à jour] → `apply` + relecture + événement ; libellés. `governance.component.spec`
  adapté (service espion).
- Non-régression : suites complètes back (5 650) et front.

## Impacts
Backend : `GovernanceEffectiveService`, `GovernanceEffectiveController` (route ajoutée sous
`/governance/hosts`), `GovernanceEffectiveView` ; `SkillCatalogService.nameOf` rendu public. Aucune table.
Front : modèles, `GovernanceService.getEffective`, `GovernanceEffectiveComponent`, écran Gouvernance.

### Préoccupations transversales
- **Contexte tenant** : ✔ — `GovernanceHostScope.require` (poste possédé), activations / empreintes /
  projets lus sous `user_id` ; cache de consigne lu par `user_id + workspace_id`.
- **Navigation** : ✔ — aucune route ajoutée ; section dans `/gouvernance` existante (chemins inchangés).

## Hors périmètre
Mise à jour automatique (D5 : jamais d'écriture dans le dos) ; édition des règles depuis l'écran (SF-177-02).

## Arbitrages (réversibles)
- Section dans la carte du poste plutôt qu'un onglet séparé (même information, un écran de moins).
- Règles et skills des sujets lus depuis le cache de consigne (coût nul, « tel que lu au dernier tour »)
  plutôt qu'une lecture machine par sujet.
- Retard calculé sur les empreintes de fichiers (vérité du disque) et non sur la version d'activation.
