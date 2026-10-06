# Mini-spec — F-178 / SF-178-01 — Se souvenir de tout le poste

## Identifiant
`F-178 / SF-178-01` — feature parente `F-178` (cadrage, décision D1). Branche :
`feat/SF-178-01-recall-poste`. Statut : `in-progress`. Date : 2026-10-06.

## Objectif
Au terminal du poste, `recall` peut chercher dans les conversations de **tous les sujets du poste**
(portée « poste »), chaque extrait étiqueté `[sujet · date · rôle]`, sans jamais sortir de l'utilisateur
ni du poste.

## Comportement attendu

### Cas nominal
1. **Déclaration** : au terminal du poste (`isHostTerminal`), l'outil `recall` porte un paramètre
   facultatif `portee` (`fil` par défaut | `poste`) et une phrase **fixe** de plus dans sa description
   (condition stable par workspace : cache F-134 préservé). Dans un sujet ou au terminal Teams : schéma
   inchangé (`query` seul).
2. **Portée poste** : ensemble de fils = le terminal du poste + `listByHost(userId, hostId)` (projets du
   poste, hors terminal Teams), refiltré `user_id` + `host_id` côté service.
   - sémantique d'abord (si actif) : `searchAcross(userId, fils, requête, 5)` → pgvector
     `user_id = ? AND workspace_id IN (…)` (borné 200 fils) → relecture `findByUserIdAndWorkspaceIdInAndIdIn` ;
   - repli mot-clé : `searchByContentInWorkspaces(fils, userId, %terme%, 5)`.
3. **Rendu au modèle** : en-tête « N extraits trouvés … dans les conversations du poste (S sujets et le
   terminal du poste) », puis `[data-platform · 2026-09-30 · assistant] extrait…` (600 car. max).
4. **Indicateur à l'écran** : repère existant de SF-162-03 réutilisé —
   « Détail rappelé · poste (S sujets) · data-platform, lzi ». Aucun changement front.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| `portee: poste` envoyé dans un sujet | ignoré : recherche dans le fil seul |
| Aucun extrait | « Aucun extrait trouvé … (S sujets et le terminal du poste) » |
| Sémantique en échec / éteint | repli mot-clé, jamais d'erreur |
| Terminal sans poste (`hostId` nul) | fil seul |

## Contraintes de validation
- `portee` ∈ {`fil`, `poste`} (insensible à la casse ; toute autre valeur = `fil`).
- Bornes : 5 extraits, 600 car. par extrait, 200 fils max en sémantique.

## Critères d'acceptation
- [x] Au poste, « qu'a-t-on décidé pour le jeton Atlantis ? » + `portee: poste` rend l'extrait du sujet
      `data-platform` avec sa date.
- [x] Dans un sujet, `recall` n'expose pas `portee` et ne cherche que dans son fil.
- [x] Un autre utilisateur, ou un autre poste du même utilisateur, n'apparaît jamais.

## Plan de test
- `AtelierChatServiceRecallHostTest` (5) : déclaration conditionnelle ; portée poste = terminal + sujets
  du même user/poste (intrus écartés), étiquettes et repère ; défaut = fil ; `portee` ignorée hors poste ;
  chemin sémantique re-filtré.
- `AtelierRecallRepositoryTest` (+1, H2) : `searchByContentInWorkspaces` et `findByUserIdAndWorkspaceIdInAndIdIn`
  excluent un autre user et un fil hors ensemble.
- Non-régression : `AtelierChatServiceRecallTest` inchangé ; suite complète backend.

## Impacts
Backend : `AtelierChatService` (déclaration + `recallHost`), `AtelierMessageRepository` (+2 requêtes),
`AtelierSemanticRecall` (+`searchAcross` par défaut vide), `AtelierSemanticRecallService`,
`AtelierMessageEmbeddingStore` (+`searchSimilarAcross`). Aucune table, aucune migration, aucun endpoint.

### Préoccupations transversales
- **Contexte tenant** : ✔ — nouveau moyen de lire plusieurs workspaces. Composants : 
  `AtelierMessageEmbeddingStore.searchSimilarAcross` (filtre `user_id`), `AtelierMessageRepository.searchByContentInWorkspaces`
  et `findByUserIdAndWorkspaceIdInAndIdIn` (filtre `user_id`), `AtelierChatService.recallHost` (ensemble résolu
  depuis le terminal possédé via `listByHost(userId, hostId)`, refiltré). `ResolutionMemoryStore` : non touché
  (référence). Tests de non-régression d'isolation : ci-dessus.
- Auth / plans / navigation : non.

## Hors périmètre
Rappel inter-postes ; terminal Teams dans la portée ; écriture dans un autre sujet ; `sujets_etat` (SF-178-02).

## Arbitrages (réversibles)
- Indicateur « N sujets » porté par le **repère existant** (texte du backend) plutôt qu'un nouveau composant.
- Terminal Teams exclu de la portée poste (il a son propre volet, cadrage §7).
- `portee: poste` hors poste **ignorée** (fil) plutôt qu'en erreur : jamais plus large, jamais bloquant.
