# Mini-spec — F-156 / SF-156-05 — L'écran du diagnostic

## Identifiant
`F-156 / SF-156-05` — feature parente `F-156` — dépend de **SF-156-01** → **04**

## Objectif
Rendre le diagnostic **demandable d'un clic**, **lisible**, et **actionnable** — jusqu'à la ligne à
coller dans la spec.

## La demande
> Cadrage F-156 : *« À la demande, depuis l'espace d'administration. Réservé à l'administrateur. »*
> Et : *« Une suggestion retenue devient une ligne de cette spec, au statut `Candidate`, avec sa
> preuve chiffrée. »* · *« L'application se diagnostique et propose ; elle ne se réécrit pas. »*

## Ce que l'écran rend
1. **Le dénominateur** : période, tours, projets, euros — sans lui, aucun constat n'est rapportable.
2. **Les constats** : ce qui dort, ce qu'on n'a pas tranché, avec **l'endroit** et **la condition**.
3. **La parité** : la table à quatre états, et le **nombre de manques réels**.
4. **La ligne de spec**, prête à coller, pour chaque constat retenu — au statut **`Candidate`**,
   **avec sa preuve chiffrée**.

## Le seuil, appliqué là où il a un sens
- Un constat qui porte un **gain calculé** doit dépasser le **seuil d'impact** ; sinon il est
  **écarté et compté**.
- Un constat **sans gain calculé** (une capacité dormante) est **gardé** : le vérifier ne coûte
  rien, et c'est précisément le gisement — mais il n'annonce **aucun chiffre**.

Cette distinction est le cœur de l'honnêteté du rapport : **on ne chiffre pas ce qu'on n'a pas
mesuré**, et on ne jette pas ce qui est gratuit à vérifier.

## L'auto-modification est écartée
L'écran **écrit la ligne**, l'administrateur la colle. Aucune écriture dans `PRODUCT_SPEC.md`, aucun
commit. *« Une machine qui modifie son propre code sans décision humaine n'est pas un gain de
productivité, c'est une perte de contrôle. »*

## Comportement attendu
| Cas | Comportement |
|---|---|
| Non-administrateur | **403** sur la route, jamais un rapport vide |
| Période sans matière | rapport « rien à observer », et **aucun constat inventé** |
| Tout tourne | « rien à signaler » comme **conclusion**, avec le nombre de capacités actives |
| Durée demandée absurde | ramenée aux bornes, et le rapport le dit |

## Critères d'acceptation
- [ ] `POST /admin/diagnostic?days=N` rend le rapport ; **403** pour un non-admin.
- [ ] La durée est bornée (**1 à 31 jours**, défaut **7**) et corrigée silencieusement **mais dite**.
- [ ] Les constats **avec gain** sous le seuil sont écartés **et comptés**.
- [ ] Les constats **sans gain** sont gardés, **sans chiffre inventé**.
- [ ] Chaque constat retenu porte **sa ligne de spec** au statut `Candidate`, avec sa preuve.
- [ ] L'écran montre dénominateur, constats, parité, et « rien à signaler » quand c'est le cas.
- [ ] **Aucune écriture** dans la spec : la ligne est **à copier**.
- [ ] **ISOLATION** : le rapport ne porte que les données du compte (garanti par SF-156-02/03).

## Hors scope
La **persistance** du rapport · toute écriture dans `PRODUCT_SPEC.md` · tout lancement automatique ·
toute auto-modification.

## Technique
| Élément | Changement |
|---|---|
| `DiagnosticReport` (record) | dénominateur, constats retenus, écartés, parité, lignes de spec |
| `ProductDiagnosticService` | l'enchaînement enquête → verdicts → parité → seuil → lignes |
| `ProductDiagnosticController` | `POST /admin/diagnostic`, garde **définition unique** |
| `admin/diagnostic/` (front) | `app-admin-diagnostic` dans la page `/admin` |

**Aucune migration.**

## Plan de test
- [ ] Rapport complet sur une période avec matière.
- [ ] **403** non-admin ; 401 sans jeton.
- [ ] Durée hors bornes → corrigée, et le rapport le dit.
- [ ] Constat avec gain sous le seuil → écarté et compté ; au-dessus → retenu.
- [ ] Constat sans gain → retenu, **sans chiffre**.
- [ ] Ligne de spec : statut `Candidate`, preuve chiffrée présente.
- [ ] Écran : dénominateur, constats, parité, « rien à signaler », ligne copiable.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | **oui** | une route nouvelle, `POST /admin/diagnostic`, gardée par la **définition unique** (`AdminService.assertAdmin`, super-admin par e-mail compris) — la même que `/admin/bilans`. Aucun endpoint existant modifié. |
| **Contexte tenant** | **oui** | le rapport s'appuie sur `ProductSurveyService` et `ProductDiagnosisService`, dont **toutes** les lectures portent `user_id` (SF-156-02/03). Le contrôleur ne transmet que `currentUser.requireId()`. |
| **Plans / limites** | **oui** | gate **ADMIN**. Aucun appel fournisseur : le diagnostic ne consomme **aucun jeton**. |
| **Navigation / routing** | **non** *(vérifié)* | comme les bilans : `app-admin-diagnostic` est un **sous-composant** de la page `/admin`, pas une route fille. Aucun guard, aucune redirection. |
