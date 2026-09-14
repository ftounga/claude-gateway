# Mini-spec — F-112 / SF-112-01 Le serveur MCP (Streamable HTTP sur `/api/mcp`)

## Identifiant

`F-112 / SF-112-01`

## Feature parente

`F-112` — Le serveur MCP : une IA pilote toute l'application

## Statut

`ready`

## Date de création

2026-09-14

## Branche Git

`feat/SF-112-01-serveur-mcp`

---

## Objectif

Exposer un serveur MCP en transport **Streamable HTTP** à l'adresse `/api/mcp`, sur sa propre chaîne
de sécurité, avec négociation de version, découverte des outils et un premier outil de session, sans
ouvrir aucune autre route.

---

## Comportement attendu

### Cas nominal

1. Un client MCP authentifié (jeton porteur de la plateforme, en attendant l'OAuth de SF-112-02)
   ouvre une session sur `POST /api/mcp` : la requête `initialize` négocie la révision de protocole
   la plus haute commune (SDK 0.18.4 : jusqu'à `2025-11-25`, repli `2025-06-18` / `2025-03-26` /
   `2024-11-05`).
2. `tools/list` renvoie les outils exposés par le serveur avec leurs annotations
   (`readOnlyHint`, etc.) et leurs schémas d'entrée/sortie. Le serveur de la fondation expose **un**
   outil : `session_info` (lecture, sans périmètre de domaine) — il prouve la chaîne complète
   auth → contexte tenant → outil.
3. `tools/call` sur `session_info` renvoie l'identité de l'utilisateur du jeton (`user_id`, courriel,
   rôle) et l'adresse du serveur, en champ structuré. Aucune donnée d'un autre utilisateur.
4. Le contenu MCP (identité) est résolu **depuis le contexte d'authentification** (SecurityContext),
   jamais depuis un paramètre d'outil.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Requête MCP sans jeton | Refus, contexte vide, pas de session | 401 |
| Jeton invalide / expiré | Refus | 401 |
| Origine non autorisée (protection anti-rebinding DNS) | Refus | 403 |
| Requête JSON-RPC malformée | Erreur JSON-RPC renvoyée par le SDK | 200 (corps erreur JSON-RPC) |
| Outil inconnu appelé | Erreur JSON-RPC « outil inconnu » | 200 (corps erreur JSON-RPC) |

---

## Critères d'acceptation

- [ ] `POST /api/mcp` répond à `initialize` et négocie une révision de protocole prise en charge.
- [ ] `tools/list` renvoie au moins l'outil `session_info` avec ses annotations et son schéma.
- [ ] `tools/call` `session_info` renvoie l'identité **du jeton présenté**, jamais celle d'un autre.
- [ ] Sans jeton, `/api/mcp` renvoie 401 (chaîne de sécurité dédiée `/mcp/**`).
- [ ] La chaîne `/mcp/**` est **séparée** de la chaîne principale et n'ouvre aucune autre route ;
      toutes les routes existantes gardent leur authentification (test de non-régression).
- [ ] Le contexte tenant (`user_id`) est résolu depuis l'authentification, pas depuis un argument.
- [ ] ADR-020 est écrit dans `docs/ADR.md`.
- [ ] La matrice de compatibilité est documentée (révision retenue, replis, cible 2026-07-28).

---

## Périmètre

### Hors scope (explicite)

- L'OAuth 2.1 (serveur de ressources et d'autorisation) : **SF-112-02**.
- Les jetons personnels, l'accès par poste, le journal MCP, l'écran « IA connectées » : **SF-112-03**.
- Les outils de domaine (postes, terminaux, Vigie/Radar, pages, courriel, compte, admin) : **SF-112-04→07**.
- Les ressources et prompts MCP, la page « Connecter une IA », le test de bout en bout : **SF-112-08**.
- La mesure de la matrice avec de vrais clients (Claude Code/Desktop/claude.ai/Codex) : réalisée en
  bout de chaîne (SF-112-08) ; ici la matrice est documentée d'après la prise en charge du SDK.

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs |
|-------|-------------|------------------|
| Endpoint MCP (interne MVC) | Oui | `/mcp` (context-path `/api` → URL publique `/api/mcp`) |
| Révision de protocole | Négociée | SDK 0.18.4 : `2025-11-25` … `2024-11-05` |
| Origine acceptée | Oui | validateur d'origine (anti-rebinding) sur la chaîne MCP |

---

## Technique

### Dépendances

- `io.modelcontextprotocol.sdk:mcp-spring-webmvc:0.18.4` (amène `mcp-core`, `mcp-json`,
  `mcp-json-jackson2`). Version fixée en readiness : c'est la plus récente ligne `0.x` stable qui
  prend en charge la révision `2025-11-25` (la révision `2026-07-28` exige la ligne `2.x`, non
  retenue — voir ADR-020).

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST/GET/DELETE | `/api/mcp` | Oui (jeton porteur) | USER |

### Tables impactées

Aucune (pas de migration en SF-112-01).

### Composants

- `McpServerConfig` — construit le `WebMvcStreamableServerTransportProvider` (endpoint `/mcp`,
  validateur d'origine), le `McpSyncServer` (capacités outils, `session_info`) et publie la
  `RouterFunction` du transport.
- `McpSecurityConfig` — chaîne de sécurité `@Order` dédiée `securityMatcher("/mcp/**")`, stateless,
  réutilise `JwtAuthenticationFilter` (interim avant OAuth), `denyAll` par défaut.
- `McpTransportContextFactory` / `McpSessionContext` — porte l'identité authentifiée dans le contexte
  de transport MCP, lu par les outils.
- `SessionInfoTool` — l'outil `session_info` (lecture).

---

## Plan de test

### Tests unitaires

- [ ] `SessionInfoTool` — construit le résultat structuré à partir de l'identité fournie.

### Tests d'intégration

- [ ] `initialize` sur `/api/mcp` négocie une révision prise en charge (200 + `protocolVersion`).
- [ ] `tools/list` contient `session_info` avec ses annotations.
- [ ] `tools/call` `session_info` renvoie l'identité du jeton A (et non celle de B).
- [ ] `/api/mcp` sans jeton → 401.
- [ ] Non-régression : une route existante (`/api/me`) garde son auth ; un appel `/api/mcp` ne donne
      accès à aucune autre route.

### Isolation utilisateur

- [ ] Applicable — `session_info` renvoie l'identité du jeton présenté ; deux jetons distincts
      donnent deux identités distinctes.

---

## Dépendances

### Subfeatures bloquantes

- Aucune (première subfeature de F-112).

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`. La révision de protocole retenue est tranchée dans ADR-020.

---

## Notes et décisions

- Le jeton porteur accepté en SF-112-01 est le JWT de la plateforme (interim). SF-112-02 ajoute les
  jetons d'accès OAuth 2.1 liés à la ressource `/api/mcp` (audience), et SF-112-03 les jetons
  personnels ; la chaîne `/mcp/**` reste la même, seule la validation du porteur s'enrichit.
- Le transport Streamable HTTP du SDK sert `/api/mcp` sur Spring MVC via une `RouterFunction` ; il
  reste hors de la chaîne principale grâce à `securityMatcher`.
