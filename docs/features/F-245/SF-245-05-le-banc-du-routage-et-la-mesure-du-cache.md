# Mini-spec — F-245 / SF-245-05 Le banc du routage et la mesure du cache

## Identifiant
`F-245 / SF-245-05`

## Feature parente
`F-245` — Le fil central de Jarvis (cadrage `CADRAGE-F-245-le-fil-central-de-jarvis.md`, §5, §7, §8)

## Statut
`done` (2026-11-04) · **Estimation :** 0,5 j · **Branche :** `feat/SF-245-05-banc-routage-cache` · **Dépend de :** SF-245-03

## Objectif
Le routage du fil Jarvis est mesuré au banc, la compaction cesse de compter les échanges rapides, et le cache du fil Jarvis se mesure en prod.

## Comportement attendu

### Cas nominal
1. **Banc CONVERSATION** : 8 scénarios `ROUTAGE_F245` (T01 → T08) — salut avec un sujet en vue, tour d'horizon, perso, chantier nommé, chantier évident par le sujet en vue, demande ambiguë, courrier (F-242), « Où en est Helios ? » (réponse sans délégation). Le jeu passe de 59 à 67 scénarios.
2. **Destination contrôlée sans modèle** : chaque tour porte `expectTarget` — `aucun_sujet` (rien ne part dans un sujet), `jarvis` (une délégation va au fil Jarvis), `cible` (une délégation ne va pas au fil Jarvis). Une violation est un **défaut grave**, à zéro obligatoire dans la porte : « échange sans tâche envoyé dans un sujet », « demande générale hors du fil Jarvis », « demande ciblée envoyée au fil Jarvis ».
3. **Compaction (§5 b)** : `AtelierCompactionService.replayable()` exclut `brain = FAST`, sur tous les espaces (estimation, résumé, messages récents gardés), comme le rejeu de la boucle.
4. **Mesure** : `docs/ops/mesure-fil-jarvis.md` — `cache_write / entrée` du fil Jarvis contre les sujets, échanges `FAST` écrits dans un sujet (cible 0), issue des lignes-liens ; requêtes bornées par `user_id`, sur 7 jours.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Tour sans `expectTarget` | non contrôlé (scénarios existants inchangés) | — |
| Réponse dite (pas de délégation) | jamais un défaut de destination (la trace va au fil Jarvis, SF-245-02) | — |
| Fil sans aucun message `FAST` | liste rendue telle quelle (aucun changement) | — |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `expectTarget` | `aucun_sujet` \| `jarvis` \| `cible` \| absent |
| porte du banc | ≥ 90 % de scénarios réussis, zéro défaut grave (inchangée) |

## Critères d'acceptation
- [x] Le banc contient les 8 scénarios de routage et contrôle la destination (unitaire) ; un défaut de destination fait échouer la porte (unitaire).
- [ ] Le banc passe à 90 % au moins, avec zéro défaut grave — **à lancer avant le déploiement** (banc réel, cerveau rapide v14 ; coût modèle).
- [x] La compaction d'un sujet n'inclut plus de message `FAST` (unitaire).
- [x] La requête de mesure est documentée dans `docs/ops` (bornée par `user_id`).

## Plan de test
- **Unitaires** `JarvisConversationBenchTest` (+3 : 8 scénarios, contrôle de destination, porte) ; `AtelierCompactionServiceTest` (+2 : `replayable()` sans `FAST`, pas de compaction déclenchée par des `FAST`) ; `JarvisFastBrainPinTest` (taille du jeu 67).
- **Isolation** : les requêtes de mesure filtrent `user_id` et joignent `workspaces` sur le même `user_id`.
- **Banc réel** (porte de déploiement) : `POST` du banc CONVERSATION par l'admin.
- Suite complète backend.

## Impact
Aucune table, aucun endpoint. Backend : `JarvisConversationBench` (`Turn.expectTarget`, `targetDefect`, zéros), `JarvisFastBrainBenchService`, `AtelierCompactionService.replayable`, `conversation-scenarios.json`. Docs : `docs/ops/mesure-fil-jarvis.md`.

**Préoccupations transversales** : aucune (ni auth, ni tenant, ni plans, ni navigation).

## Arbitrages
- **Contrôle de destination sans modèle** (comme la marque 📌 de F-241) plutôt que par le juge : déterministe et gratuit. Réversible.
- **T02 / T03 en `expect: any`** : le cerveau rapide peut répondre seul à partir de l'univers ; une réponse reste dans le fil Jarvis. Seule une délégation hors du fil Jarvis est fautive. Réversible.
- **Exclusion `FAST` sur tous les espaces** (cadrage §5 b) : corrige une erreur existante ; la compaction peut se déclencher un peu plus tard dans un sujet qui avait des échanges rapides (jamais rejoués : justesse inchangée).

## Hors périmètre
Un « nouveau départ » automatique (F-117) ; tout levier de coût avant la mesure J+7.
