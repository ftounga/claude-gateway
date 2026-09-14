# F-117 — Le contexte d'un terminal ne déborde jamais

> Cadrage du 2026-09-14, sur l'audit mémoire/contexte. **Cadrage seul : livraison sur go du PO (donné).**

## 1. Le constat — le risque n°1

> « On garde toujours le même terminal ouvert ; il n'y aura pas un problème de contexte au fil du temps ? »

Audit (prouvé, base de prod) : dans la boucle maison (terminal de poste/projet), **le TEXTE de la
conversation est renvoyé en entier au modèle à chaque tour, sans borne**. Seuls sont bornés les
résultats d'outils (élagage `clear_tool_uses` à 200 K, garde 3) et les trajectoires (5 derniers tours) —
**jamais le texte user/assistant**. Il n'existe **aucune compaction/résumé automatique** ; la seule
remise à zéro est le **« nouveau départ » MANUEL**, jamais utilisé (**0 sur 18 terminaux en prod**).
Au dépassement de la fenêtre du modèle, l'appel échoue en **400 « prompt too long » NON rejouable**
(`AgentRetryPolicy` ne retente que 429/529) → le tour **meurt sans filet**.

Fait mesuré : le terminal le plus actif porte déjà ~31 K tokens de texte rejoué en 3 jours, en
croissance linéaire (~925 tokens permanents par tour). Un terminal toujours ouvert casse à terme.

## 2. Ce qu'on livre — trois défenses, comme Claude Code

### SF-117-01 — La compaction automatique de l'historique
Quand le texte rejoué d'un fil dépasse un seuil (défaut : déclenché **avant** la fenêtre réelle du
modèle, marge de sécurité configurable), les **tours anciens sont résumés** en un bloc de contexte
compact (un appel modèle dédié, borné, via `AIProvider`), et les tours récents gardés **intégralement**.
Le résumé remplace le texte ancien dans ce qui est **rejoué au modèle** ; **l'affichage garde tout**
(rien n'est perdu à l'écran). Marqueur « conversation résumée jusqu'ici » visible dans le fil.
- Réutilise la frontière de fil existante (`chatThreadStartedAt`) : la compaction pose une frontière
  **automatique** avec un résumé injecté, au lieu d'un reset sec.
- Le cache de prompt reste efficace (le résumé devient un préfixe stable).

### SF-117-02 — Le dépassement de fenêtre ne casse plus le tour
`callWithRetry` / `AgentRetryPolicy` détectent le **400 « prompt too long »** : au lieu d'échouer, on
**déclenche une compaction (SF-117-01) puis on relance** le même tour une fois ; si ça dépasse encore,
message clair (« conversation trop longue : j'ai résumé l'historique et je reprends »). Plus jamais
d'échec dur silencieux au débordement.

### SF-117-03 — Le nouveau départ visible, et suggéré
Le « nouveau départ » cesse d'être invisible : action claire dans l'en-tête du terminal, et **suggestion
automatique** (non bloquante) au-delà d'un seuil de taille de fil (« cette conversation est longue —
repartir propre ? »). Complète la compaction (qui, elle, ne demande rien).

## 3. Ce que l'audit a aussi trouvé (inclus)

### SF-117-04 — Nettoyage des sessions hébergées et fuites mémoire
- **Reaper des sessions Managed Agents** : un `@Scheduled` expire par âge (utilise `agentSessionStartedAt`,
  déjà stocké mais jamais lu) et `forgetSession` **termine** la session fournisseur (best-effort), pour
  éviter les conteneurs orphelins.
- **Fuites mémoire ciblées** : éviction de `McpRateLimiter.hits` (copier `HelpRateLimiter.evictStale`) ;
  borne/purge du `Set` interne de `AtelierSessionService.syncedOutputs`.

## 4. Découpage & ordre
SF-117-01 (compaction) → SF-117-02 (repli sur 400) → SF-117-03 (nouveau départ visible) → SF-117-04
(sessions + fuites). Migration si un état de compaction doit être persisté (numéro au premier libre >106).

## 5. Préoccupations transversales
- **Contexte tenant : oui** — compaction et résumés isolés par `user_id` + `host_id`/workspace.
- **Plans/coût** : la compaction consomme un petit appel modèle (borné) — sur le quota, rare.
- Composants : `AtelierChatService`, `AnthropicAgentProvider`, `AgentRetryPolicy`, `AtelierThreadService`,
  `AtelierSessionService`, `McpRateLimiter`, frontend terminal.

## 6. Hors périmètre
La fenêtre 1 M beta (à évaluer à part) ; le streaming (F-116) ; l'effort (F-118).
