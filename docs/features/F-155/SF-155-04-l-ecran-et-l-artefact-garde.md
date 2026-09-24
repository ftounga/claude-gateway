# Mini-spec — F-155 / SF-155-04 — L'écran et l'artefact gardé

## Identifiant
`F-155 / SF-155-04` — feature parente `F-155` — dépend de **SF-155-01**, **02**, **03**

## Objectif
Qu'un bilan **reste** — consultable, comparable d'une semaine à l'autre — et qu'il ait **un écran**,
réservé à l'administrateur.

## La demande
> PO, arbitrages du 2026-09-24 : bilan **gardé comme artefact** · **réservé à l'administrateur**.

Un bilan qui n'existe que dans une réponse HTTP est un bilan qu'on ne relit jamais, et qu'on ne peut
pas comparer. Or comparer est **tout l'intérêt** : « le cache est remonté depuis la semaine dernière ».

## Une correction embarquée, et pourquoi
SF-155-03 teste `role == ADMIN` **en direct**. Or `AdminService.assertAdmin()` dit, noir sur blanc,
qu'il ne doit exister **qu'une seule définition** de « qui est admin » — elle accepte aussi le
**super-admin par e-mail**, dont le rôle stocké peut ne pas encore être promu. Avec deux
définitions, le super-admin n'aurait **jamais** de bilan, sans que rien ne le signale. Le
déclencheur reçoit désormais un **booléen** décidé par la définition unique.

## Comportement attendu
1. Un bilan **automatique** (SF-155-03) est **gardé** au moment où il est décidé.
2. Un bilan peut être **demandé** pour un projet : c'est le « proposé d'un clic ». Il est gardé aussi.
3. L'écran d'administration **liste** les bilans, les plus récents d'abord, avec de quoi les
   comparer d'un coup d'œil : projet, date, tours, coût, **part du cache**, nombre de suggestions.
4. Ouvrir un bilan montre **les trois parties** : ce qui a été fait, ce que ça a coûté et où, ce qui
   aurait mieux valu — chaque suggestion avec **sa mesure** et **son gain**.
5. Le bilan dit **combien de suggestions ont été écartées** faute d'impact. Et quand il n'y en a
   aucune : « **rien à signaler** », dit comme une conclusion, pas comme un vide.
6. **Réservé à l'administrateur** : les routes appliquent la **définition unique** ; un non-admin
   obtient **403**, jamais une liste vide qui laisserait croire qu'il n'y a rien.

| Cas d'erreur | Comportement |
|---|---|
| Bilan d'un autre compte | **404 indiscernable** |
| Demande sur un projet non possédé | 404 (`requireOwned`) |
| Demande sur une session sans rien à dire | **aucun bilan créé**, et l'écran le dit |
| Écriture impossible | l'automatique **ne casse pas** le nouveau départ (règle de SF-155-03) |

## Critères d'acceptation
- [ ] Un bilan automatique est **persisté** avec son relevé et ses suggestions.
- [ ] `POST` produit et garde un bilan à la demande ; une session sans matière n'en crée **aucun**.
- [ ] La liste est filtrée `user_id`, la plus récente d'abord, **bornée**.
- [ ] Un non-admin reçoit **403** sur les trois routes.
- [ ] Le détail rend les suggestions **avec leur mesure et leur gain**, et le **nombre d'écartées**.
- [ ] Le déclencheur utilise la **définition unique** de l'admin (super-admin par e-mail compris).
- [ ] **ISOLATION** : `user_id` sur chaque lecture ; `requireOwned` sur la production.
- [ ] **Design system** : variables `--cg-*`, pas de `window.confirm`, `MatSnackBar` pour l'échec.

## Hors scope
Le renvoi vers F-156 (**SF-155-05**) · la comparaison automatique entre deux bilans (l'écran les
met côte à côte, il ne conclut pas) · toute purge automatique.

## Technique
| Élément | Changement |
|---|---|
| Migration `132-session-bilans.xml` | table `session_bilans` |
| `SessionBilan` + repository | l'artefact, filtré `user_id` |
| `SessionBilanStore` | garder, lister, relire |
| `SessionBilanController` | `POST /admin/bilans`, `GET /admin/bilans`, `GET /admin/bilans/{id}` |
| `SessionBilanTriggerService` | garde l'automatique ; reçoit un **booléen** admin |
| `AtelierThreadService` | demande l'admin à la **définition unique** (`AdminService`) |
| `admin/bilans/` (front) | `app-admin-bilans` : liste + détail, **dans** la page `/admin` (patron `app-admin-cost`) |

Le relevé et les suggestions sont gardés en **JSON** dans deux colonnes `text` : ce sont des
**photographies**, pas des données à interroger. Les cinq chiffres de tête (tours, coût, cache,
suggestions, écartées) sont **en colonnes**, parce que c'est ce que la liste trie et compare.

## Plan de test
- [ ] Persistance d'un automatique ; relecture fidèle du relevé et des suggestions.
- [ ] `POST` sur une session avec matière → bilan créé ; sans matière → aucun.
- [ ] Liste ordonnée, bornée, filtrée `user_id`.
- [ ] Non-admin → **403** sur les trois routes.
- [ ] **ISOLATION** : bilan d'un autre compte → 404.
- [ ] Le super-admin **par e-mail** obtient bien un bilan (la correction).
- [ ] Écran : liste, détail, « rien à signaler », nombre d'écartées.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | **oui** | la lecture du rôle passe de `AtelierThreadService` (directe) à `AdminService.assertAdmin`/`isAdmin` — **définition unique**. Endpoints touchés : `POST /workspaces/{id}/restart` (comportement identique pour un non-admin) et les trois routes `/admin/bilans` (nouvelles). Aucun autre endpoint ne change. |
| **Contexte tenant** | **oui** | `SessionBilanRepository` filtre `user_id` sur **toute** méthode ; la production passe par `SessionLedgerService`, donc `requireOwned`. Un bilan n'est jamais lu par son seul identifiant. |
| **Plans / limites** | **oui** | gate **ADMIN** sur les trois routes, par la définition unique. Aucun appel fournisseur. |
| **Navigation / routing** | **non** *(vérifié)* | **aucune route ajoutée** : le tableau de bord `/admin` **compose des sous-composants** (`app-admin-cost`, `app-admin-usage`) plutôt que des routes filles. Le bilan devient `app-admin-bilans` dans la même page — donc aucun guard, aucune redirection, aucun chemin existant touché. *(La mini-spec annonçait une route ; la lecture du code a montré que le patron du projet n'en demande pas.)* |
