# Mini-spec — F-237 / SF-237-13 — Les bancs : SHOW, NAVIGATE, PROOF et le banc de séquences

## Identifiant
`F-237 / SF-237-13` · Feature parente `F-237` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-237-13-bancs-pieces`
Cadrage : §9, §16 (critères 3, 4, 5, 6, 11). Dépend de SF-237-08 → 10.

## Objectif
> Mesurer, avant et après déploiement, que Jarvis **montre quand il faut** (et seulement alors), **navigue** juste,
> et ne **génère jamais** une preuve sans « oui » — par un banc rejouable du cerveau rapide et un banc de séquences
> de l'écran.

## Comportement attendu
- **Jeu `PIECES`** du banc existant (`POST /api/admin/jarvis/fast-brain/bench {set: "PIECES", model}`, ADMIN,
  asynchrone, rapport rangé en base sous `user_id` comme les autres jeux) : `jarvis/pieces-bench.json`, **37 cas**
  (SHOW 13, NAVIGATE 10, PROOF 8, NEUTRAL 6), contexte **fixe** (dont `## Pièces`), écrans nommés (`## Écran`) pour la
  navigation, échanges précédents pour le « oui / non » ; la lecture `pieces` rend un texte **fixe** (aucune base).
- **Jugement déterministe** (`JarvisPiecesBench.evaluate`) sur les ordres `afficher` relayés et la route : action,
  raison (`appui` / `demande`), portée (chantier, catégorie, type), index, identifiants ; proposition (« ? ») quand la
  preuve manque ; délégation attendue ou interdite. **Aucun juge modèle** ⇒ coût = le seul cerveau rapide.
- **Porte** : SHOW, NAVIGATE, PROOF ≥ 90 % ; NEUTRAL : 0 affichage ; 0 délégation de preuve sans « oui » ; jamais
  plus de 3 pièces d'initiative.
- **Banc de séquences** (front, `jarvis-piece-sequence.spec.ts`) : le parcours §9 rejoué pas à pas sur le réducteur
  réel, la voix locale réelle et un cerveau rapide **simulé** ; état exact à chaque pas ; ordres triviaux **locaux**
  (< 100 ms, aucun appel au cerveau rapide, compté) ; toucher ≡ voix ; preuve absente ⇒ rien de montré, puis cartes au
  fil de l'eau ; charge : 1 000 ordres + blocs `## Écran` sous 250 ms.

### Cas d'erreur
- Jeu illisible ⇒ passage `FAILED` (« panne du banc »), comme les autres jeux.
- Délai / erreur du modèle ⇒ délégation d'office ⇒ le cas échoue (compté), jamais ignoré.

## Critères d'acceptation
- [ ] ≥ 30 cas, quatre catégories, contexte fixe.
- [ ] Jugement déterministe testé (portée juste / fausse, index, consentement, neutre).
- [ ] Banc de séquences §9 : 100 % des pas.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Plans / limites | **Oui** | `JarvisFastBrainBenchService` (≤ 5 passages par compte, coût inscrit au journal annexe via `account`) ; aucun juge ⇒ pas d'appel Opus |
| Contexte tenant | Non | jeu fixe, aucune lecture de base ; rapport sous `user_id` (existant) |

## Plan de test
Back : `JarvisPiecesBenchTest` (jeu, jugement, porte, lecture fixe des pièces au banc). Front :
`jarvis-piece-sequence.spec.ts`.

## Hors périmètre
Banc de rendu i/s galerie ouverte sur matériel réel (mesure SF-237-14 / J+7) ; banc vivant contre le modèle réel
dans la CI (exige la clé : lancé en prod par l'admin, après déploiement).
