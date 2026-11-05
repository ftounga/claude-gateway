# Mini-spec — F-247 / SF-247-13 Retrouver les conversations

## Identifiant
`F-247 / SF-247-13`

## Feature parente
`F-247` — Le registre d'état (cadrage §4.6 « Le rappel », D11, D14, porte Z10)

## Statut
`done` (2026-11-05) · **Estimation :** 1,5 j (réestimé 0,75 j : outil déclaré en v19 et branché côté cerveau rapide
dès SF-247-10) · **Branche :** `feat/SF-247-13-retrouver-les-conversations` · **Dépend de :** SF-247-12

## Objectif
Ce qui a été dit dans les captures se retrouve : Jarvis le lit par l'outil `conversations` sur tous les sujets du
client, et `recall` d'Opus le trouve en union avec l'historique du sujet — toujours daté par la passerelle.

## Comportement attendu

### Cas nominal
1. **Vectorisation** : chaque ligne écrite (abonné `JarvisCaptureStore.onLines`) est vectorisée **hors du chemin**, dans
   le pool d'embeddings F-162 (`recallEmbeddingProvider`, `atelierEmbeddingExecutor`), PostgreSQL et clé configurée
   seulement ; une ligne sans vecteur reste trouvable par les mots.
2. **Recherche** (`JarvisCaptureSearch`, toujours sous `user_id` + client, ou sujet pour `recall`) : par le sens
   (plus proches voisins `<=>`) si disponible et sans personne demandée ; sinon par les mots, dans une **fenêtre bornée**
   des 2 000 lignes les plus récentes lue par l'index `(user_id, host_id, said_at desc)` ; par la personne (nom résolu
   par la passerelle : interlocuteur connu, variantes, prénom seul s'il désigne sans ambiguïté). 15 lignes au plus.
3. **Outil `conversations`** (`JarvisConversationService implements JarvisConversationLookup`) : `recherche`,
   `interlocuteur`, `client` ; client nommé, sinon celui du sujet, sinon tous (12 au plus). Chaque ligne :
   « capture Teams du 04/11, « Migration SSO » — Rémi Dupont, hier 14:32 : … » (« vers » si approximatif), texte borné
   à 300 car., balisé `<conversations_non_fiables>`. « Rien de capturé … » sinon. Une lecture compte un « qu'a dit X »
   (`jarvis_usage_days.conversation_reads`, écrit hors du chemin de la réplique).
4. **`recall` en union** (D14) : `AtelierChatService.recall` (portée du sujet) ajoute les extraits de captures du sujet
   (`CaptureRecall`, 5 au plus) au résultat, sémantique ou mots-clés ; seuls, s'il n'y a rien dans le fil. Les captures
   ne sont **jamais** écrites dans `atelier_messages`.
5. **Rien en présentation** (D11) : l'outil rend `PRESENTATION`.
6. Mesure (`GET /api/admin/jarvis/state/measure`) : captures par issue, lignes, lectures `conversations`.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Client inconnu / d'un autre compte | « Aucun client connu » |
| Sémantique indisponible (H2, sans clé, fournisseur en échec) | repli sur les mots |
| Client coupé | transcriptions purgées : « Rien de capturé » |
| Panne quelconque | `NONE` (outil) ou extraits vides (`recall`), jamais d'exception |

## Contraintes de validation
15 lignes (outil), 5 (recall), 300 car. par ligne, fenêtre de mots 2 000 lignes, 6 mots porteurs.

## Critères d'acceptation
- [x] « Qu'a dit Rémi ? » : la personne résolue, la ligne datée par la passerelle.
- [x] Mots, client nommé, plusieurs clients.
- [x] Isolation à deux comptes ; rien en présentation ; couper vide.
- [x] `recall` en union, sujet seulement.
- [x] Z10 : 100 000 lignes, récentes / mots / personne ≤ 10 ms p95 (mesuré 3,9 / 4,8 / 1,2 ms), plan sans parcours
  séquentiel, index hnsw présent.

## Plan de test
- Intégration : `JarvisConversationIntegrationTest` (5).
- Charge : `JarvisCaptureSearchPostgresLoadTest` (Testcontainers pgvector, ignoré sans Docker).
- Non-régression : `CaptureReadJobIntegrationTest`, `JarvisStateMeasure*`, `*Recall*`, `JarvisFastBrainStateTest`, `JarvisCost*`.

## Impact
- Migration `247-13-jarvis-capture-lines-embedding.xml` : index `(user_id, workspace_id, said_at)`,
  `jarvis_usage_days.conversation_reads`, colonne `embedding vector(1536)` + index hnsw (PostgreSQL seulement).
- Backend : `atelier/recall/CaptureRecall` (nouveau) ; `AtelierChatService.recall` (union) ;
  `jarvis/state/capture/JarvisCaptureSearch`, `JarvisConversationService` (nouveaux) ; `JarvisStateMeasureService`.
- Aucun endpoint. Consigne et outils du cerveau rapide **inchangés** (préfixe v19 intact).
- Préoccupations transversales : **Contexte tenant** — `RunnerHostService.list(userId)` (clients du compte),
  `JarvisCaptureSearch` / `JarvisCaptureStore` (`user_id` + `host_id`, ou + `workspace_id`), `recall` après
  `requireOwned` du tour.

## Arbitrages (réversibles)
- Prénom seul : rapproché des interlocuteurs dont le nom porte tous les mots dits (3 au plus).
- Mots cherchés dans une fenêtre bornée des 2 000 lignes récentes du client (Z10 tenu à tout volume).
- Le compteur « qu'a dit X » est la lecture de l'outil `conversations`.

## Hors périmètre
L'écran des conversations (SF-247-15 lot 2) ; le rattrapage des vecteurs de lignes antérieures.
