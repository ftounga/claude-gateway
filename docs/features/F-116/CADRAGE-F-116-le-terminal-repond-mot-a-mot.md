# F-116 — Le terminal répond mot à mot, comme Claude Code

> Cadrage du 2026-09-14, sur l'audit performance. **Cadrage seul : livraison sur go du PO (donné).**

## 1. Le constat

> « L'application est vraiment lente ; Claude Code répond bien plus vite. »

Audit (fichier:ligne prouvés) : l'appel au modèle est **non streamé**. `AnthropicAgentProvider.callWithRetry`
fait `.retrieve().body(JsonNode.class)` → attend la réponse **complète** de chaque étape (6 à 17 s), et
`AtelierChatService` n'émet `onText`/`onAction` qu'**après** le retour. Mesure isolante : un tour à
**1 étape sans outil = 17 s de silence**. C'est **~100 % de l'écart de ressenti** avec Claude Code.

Ce qui est déjà sain (à ne pas casser) : cache de prompt (préfixe stable + point glissant, corps
identique au retry), transport runner en WebSocket push, édition de contexte intra-tour. Le débit total
(jetons) ne changera pas ; c'est le **temps avant le premier affichage** qu'on attaque.

## 2. Ce qu'on livre

Le **vrai streaming token-par-token** de l'appel modèle dans la boucle maison (ce que SF-84-05 avait
proposé, jamais livré). Toute la plomberie SSE vers le frontend existe déjà (`onText`, `onAction`,
`onOutput` streament) : le seul maillon non streamé est `AnthropicAgentProvider.nextTurn`.

- **Backend** : une variante de `nextTurn` qui consomme le flux SSE d'Anthropic (`stream:true`,
  événements `content_block_delta`), émet les **deltas de texte** via `listener.onText` au fil de l'eau,
  puis **reconstitue le même `AgentTurn`** à la fin (blocs `tool_use`, `usage`, **blocs de raisonnement
  signés** préservés, comptage cache identique — `toTurn`).
- **Invariants non négociables** : cache de prompt inchangé (mêmes `cache_control`), reprise F-84 et
  précisions SF-84-06 inchangées, retry `429/529` inchangé, décompte d'usage et report de tour
  identiques, sortie bash toujours streamée. Le streaming **n'altère pas** ce qui est persisté ni
  facturé, seulement **quand** le texte apparaît.
- **Frontend** : le texte de l'agent s'affiche caractère par caractère dans la ligne vivante (il le sait
  déjà via `onText` ; vérifier qu'aucun tampon n'attend la fin) ; respecte le repli long-polling
  fenêtré derrière un proxy (SF-84-04) — derrière Netskope, le flux est regroupé par fenêtres, ce qui
  reste bien plus fluide qu'aujourd'hui.
- **Repli** : si le fournisseur refuse le streaming ou coupe en cours, on retombe proprement sur l'appel
  complet (drapeau `APP_ATELIER_STREAMING`, défaut activé).

## 3. Découpage

| SF | Titre | Contenu |
|---|---|---|
| SF-116-01 | L'appel modèle en flux | `nextTurn` streamé (SSE Anthropic), deltas de texte émis au fil de l'eau, `AgentTurn` reconstitué à l'identique (tool_use, usage, raisonnement signé, cache), repli non streamé, drapeau de config. Tests : équivalence du turn reconstitué vs non streamé, cache préservé, retry, raisonnement signé conservé. |
| SF-116-02 | L'affichage au fil de l'eau | Le terminal (projet, poste, Teams, mosaïque) affiche le texte dès le premier delta ; aucun tampon d'attente ; cohérence avec la reprise par curseur et le repli proxy. Tests frontend. |

## 4. Préoccupations transversales
- **Auth/tenant/plans** : non (aucun changement de droits ni de données). **Coût** : inchangé.
- Composants : `AnthropicAgentProvider`, `AtelierChatService`, `AtelierChatController`,
  `atelier.service.ts`, `atelier-terminal.component`.

## 5. Hors périmètre
Le débit total (nombre de jetons, durée de calcul) ; l'effort de réflexion (F-118) ; le chemin Managed
Agents (déjà streamé côté fournisseur).
