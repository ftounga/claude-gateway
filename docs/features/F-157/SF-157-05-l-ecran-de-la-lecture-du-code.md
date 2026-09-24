# Mini-spec — F-157 / SF-157-05 — L'écran de la lecture du code

## Identifiant
`F-157 / SF-157-05` — feature parente `F-157` — dépend de **SF-157-01** → **04**

## Objectif
Permettre à l'administrateur de **désigner le dépôt**, de **lire le code**, et de **demander une
hypothèse** sur une capacité précise — en sachant **ce que ça coûte**.

## Ce que l'écran ajoute au diagnostic de F-156
1. Un champ **« lire le code dans ce projet »** : l'identifiant du terminal ouvert sur le dépôt.
   Laissé vide, le diagnostic est **exactement celui de F-156** — gratuit, sans lecture.
2. Avec un projet désigné, les constats sont **enrichis** : *branchement vérifié* ou **débranchée**,
   avec le fragment manquant et le fichier.
3. Un bouton **« comprendre »** par constat, qui demande **une hypothèse** sur **cette** capacité —
   et qui **annonce qu'il va coûter** avant de le faire.
4. L'hypothèse est rendue **marquée comme telle**, avec **ce qu'elle a coûté** : jetons et modèle.

## La frontière visuelle qui compte
Un constat mesuré et une hypothèse **ne doivent pas se ressembler**. Le constat porte un chiffre et
une preuve ; l'hypothèse porte un avertissement et un coût. Les afficher pareil ferait développer
sur une supposition — le défaut que tout F-157 s'emploie à éviter.

## Comportement attendu
| Cas | Comportement |
|---|---|
| Aucun projet désigné | diagnostic de F-156, **aucune mention de lecture**, aucun coût |
| Projet désigné mais **pas le dépôt** | message **nommé** : *« ce projet n'est pas le dépôt »*, le diagnostic reste rendu **sans** enrichissement |
| Projet d'autrui | **404** — indiscernable |
| Non-administrateur | **403** sur les deux routes |
| Hypothèse indisponible | message nommé ; **le constat reste**, inchangé |

## Critères d'acceptation
- [ ] `POST /admin/diagnostic` accepte un **`workspaceId` facultatif** ; sans lui, comportement
      **inchangé** (non-régression vérifiée).
- [ ] Dépôt non reconnu → **le diagnostic est quand même rendu**, avec un message dédié, et **aucun
      constat enrichi** — un refus de lecture ne doit pas priver du diagnostic gratuit.
- [ ] `POST /admin/diagnostic/hypothesis` rend une hypothèse pour **une** capacité, **403** hors admin.
- [ ] L'écran **avertit du coût avant** de demander une hypothèse.
- [ ] L'hypothèse est **visuellement distincte** d'un constat, avec son avertissement et son coût.
- [ ] **ISOLATION** : `workspaceId` passe par `requireOwned` ; 404 pour un projet d'autrui.
- [ ] **Design system** : variables `--cg-*`, `MatSnackBar`, aucun `window.confirm`.

## Hors scope
La persistance des hypothèses · le déclenchement automatique · toute modification du code.

## Technique
| Élément | Changement |
|---|---|
| `DiagnosticReport` | `sourceRead` (booléen), `sourceNote` (le refus, le cas échéant) |
| `ProductDiagnosticService` | lecture facultative, refus **avalé et dit** |
| `ProductDiagnosticController` | `workspaceId` facultatif ; route `/hypothesis` |
| `admin-diagnostic.component` | le champ, le bouton « comprendre », l'encart d'hypothèse |

**Aucune migration.**

## Plan de test
- [ ] Sans `workspaceId` → rapport identique à F-156 (non-régression).
- [ ] Dépôt non reconnu → rapport rendu + `sourceNote`, constats **non** enrichis.
- [ ] Avec dépôt lu → constats enrichis (`sourceRead` vrai).
- [ ] `/hypothesis` : **403** hors admin, 404 projet d'autrui, hypothèse rendue avec son coût.
- [ ] Écran : champ, avertissement de coût, hypothèse distincte, échec dit.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | **oui** | une route nouvelle `POST /admin/diagnostic/hypothesis`, gardée par la **définition unique** (`AdminService.assertAdmin`), comme `/admin/diagnostic` et `/admin/bilans`. Aucun endpoint existant modifié — `/admin/diagnostic` gagne un **paramètre facultatif**, son comportement sans lui est inchangé. |
| **Contexte tenant** | **oui** | `workspaceId` passe par `SourceReader` → `WorkspaceService.requireOwned` ; le reste du rapport garde les lectures `user_id` de F-156. |
| **Plans / limites** | **oui** | la route `/hypothesis` **consomme des jetons** — la seule du diagnostic. Garde **ADMIN**, une capacité par appel, coût rendu. `/admin/diagnostic` reste **gratuit**. |
| **Navigation / routing** | **non** *(vérifié)* | `app-admin-diagnostic` existe déjà dans la page `/admin` ; on l'enrichit. Aucune route Angular, aucun guard. |
