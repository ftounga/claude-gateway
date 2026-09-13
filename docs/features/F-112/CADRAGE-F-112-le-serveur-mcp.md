# F-112 — Le serveur MCP : une IA pilote toute l'application

> Cadrage du 2026-09-13, à la demande du PO. **Cadrage seul : la livraison attend le go du PO.**
> Exigence du PO : *« Je ne veux pas de "truc en attendant". Prends le temps de bien cadrer la feature
> et que ce soit une solution aboutie. »*
> **Nécessite un ADR** (ADR-020) : l'application devient **serveur d'autorisation OAuth 2.1** pour ses
> clients MCP, en plus de son authentification actuelle (JWT + Google, décision C2 de `CLAUDE.md`).

## 1. Le besoin

> « À chaque fois que tu as besoin de me faire tester quelque chose, il faut que tu m'envoies ce qu'il
> faut que je teste, je me l'envoie par mail, je récupère ça sur l'autre PC pour le copier dans le
> terminal. […] Le but, c'est que tu sois capable de faire le maximum de choses sur mon site, pas juste
> écrire dans un terminal. […] Que toi, en tant que LLM, tu sois capable de parler avec toute mon
> application et de faire des actions dessus. »

**La réponse standard est MCP** (Model Context Protocol) : l'application expose un **serveur MCP** ;
une IA compatible (Claude Code, Claude Desktop, claude.ai, Codex, tout client MCP) s'y connecte avec
le compte de l'utilisateur et utilise ses **outils**.

**Deux usages, une seule feature :**
- **Pour le PO et l'orchestrateur** : tester et piloter l'application sans copier-coller entre deux PC.
- **Pour les clients du produit** : piloter leur Forge, leur Vigie et leur Radar depuis l'IA qu'ils
  utilisent déjà. C'est un **canal d'accès** au même produit, pas un second produit.

**Gateway-First respecté** : le serveur MCP n'ajoute aucune intelligence. Il expose les capacités
existantes de la gateway, avec les mêmes droits, le même cloisonnement, les mêmes gardes.

## 2. Le standard visé

- **Spécification MCP 2026-07-28** (dernière publiée) : cœur **sans état** (chaque requête porte sa
  version, l'identité et les capacités du client dans `_meta` ; plus de `Mcp-Session-Id`), en-têtes
  `Mcp-Method` / `Mcp-Name`, **requêtes à plusieurs allers-retours** (`input_required` pour une
  interaction en cours d'appel), extension **Tasks** (`tasks/get`, `tasks/update`) pour le travail
  long, listes avec `ttlMs` / `cacheScope`.
- **Transport : Streamable HTTP**, à l'adresse `https://portal.ng-itconsulting.com/api/mcp`.
- **Compatibilité** : les clients du marché n'adoptent pas une révision le jour de sa sortie. SF-112-01
  établit la **matrice réelle** (Claude Code, Claude Desktop, claude.ai, Codex) et sert aussi la
  révision précédente (2025-11-25) tant qu'un client principal ne parle que celle-là. **Aucun choix de
  révision n'est figé avant cette mesure.**
- **Implémentation** : SDK Java officiel MCP et son intégration Spring (serveur Streamable HTTP sur
  Spring MVC, module de sécurité MCP pour le serveur de ressources OAuth). Versions exactes fixées en
  readiness après vérification de leur prise en charge de la révision visée.

## 3. L'authentification : OAuth 2.1, comme le prévoit le standard

**Aucun jeton ne transite par une conversation.** L'utilisateur se connecte **dans son navigateur**,
avec son compte habituel (courriel/mot de passe ou Google), et **consent**.

- **L'API MCP est un serveur de ressources OAuth 2.1** : métadonnées de ressource protégée
  (RFC 9728, `/.well-known/oauth-protected-resource`), refus `401` avec `WWW-Authenticate` qui pointe
  vers elles, jetons d'accès liés à cette ressource (RFC 8707), audience vérifiée.
- **L'application devient serveur d'autorisation** (Spring Authorization Server, dans le backend) :
  métadonnées (RFC 8414), code d'autorisation **avec PKCE obligatoire**, `iss` dans la réponse
  (RFC 9207), **Client ID Metadata Documents** (mode recommandé par la spécification) et
  **enregistrement dynamique** conservé pour les clients qui en dépendent encore, jetons d'accès courts
  (15 min), jetons de rafraîchissement **rotatifs** et révocables, clés de signature en rotation.
- **Écran de consentement** dans l'application : le nom et l'éditeur du client IA, les **périmètres**
  demandés en français clair, **les clients (postes) accessibles** (§4), la durée.
- **Jetons personnels** pour ce qui n'a pas de navigateur (script, intégration continue) : créés dans
  les réglages, **affichés une seule fois**, stockés **hachés**, périmètres, postes, **expiration
  obligatoire** (90 jours au plus), révocables.
- **ADR-020** consigne ce choix et ce qu'il ne change pas : la connexion à l'application reste celle
  d'aujourd'hui ; OAuth ne sert qu'aux clients MCP.

## 4. Ce qu'une IA peut voir et faire : périmètres et postes

**Périmètres** (demandés par le client, accordés par l'utilisateur, jamais plus larges que ses droits) :

| Périmètre | Ouvre |
|---|---|
| `postes:lire` | postes, statut, versions, projets, carte, gouvernance (lecture) |
| `postes:agir` | vérification guidée, mise à jour du runner, activation d'un espace |
| `terminaux:ecrire` | écrire dans un terminal, préciser, interrompre, lire les tours |
| `radar:lire` / `radar:ecrire` | Vigie et Radar en lecture / synchroniser, donner une nouvelle, clore, lier |
| `pages` | publier, lister, lire une page |
| `courriel` | m'envoyer un courriel (F-110) |
| `compte:lire` | quota, abonnement, consommation |
| `admin` | outils d'administration — **rôle ADMIN seulement** |

**Accès par client — le point de confiance.** Ce qu'un outil renvoie **part chez le fournisseur de
l'IA connectée** (Anthropic, OpenAI…), qui n'est pas forcément celui que le client final a accepté.
Donc :
- **l'accès MCP se donne poste par poste**, au consentement et dans l'en-tête du client ;
- un poste **nouvellement créé n'est pas accessible** par les connexions existantes tant que
  l'utilisateur ne l'y ajoute pas ;
- à l'ajout, l'écran rappelle : « les données de ce client seront transmises à <fournisseur de l'IA> ;
  vérifiez que votre client l'autorise ».

## 5. Les outils

Chaque outil porte ses **annotations** (`readOnlyHint`, `destructiveHint`, `idempotentHint`), un
**schéma d'entrée et de sortie** structuré, et une description qui dit ce qu'il fait et ce qu'il ne
fait pas.

| Domaine | Outils |
|---|---|
| **Postes et Forge** | `postes_lister`, `poste_detail` (statut daté, version du runner, espaces, carte, intégrité), `poste_verifier` (vérification guidée), `poste_mettre_a_jour_runner` (F-111), `projets_lister`, `projet_detail`, `carte_lire`, `gouvernance_etat` |
| **Terminaux** | `terminaux_lister`, `terminal_ecrire` (lance un tour → **tâche**), `tour_suivre` (événements depuis un curseur : étapes, outils, texte, fin), `tour_preciser` (SF-84-06), `tour_interrompre`, `autorisations_en_attente` |
| **Vigie et Radar** | `radar_synchroniser` (→ tâche), `radar_resume`, `radar_sujets`, `radar_sujet`, `radar_donner_nouvelle`, `radar_clore_sujet`, `radar_lier_projet`, `radar_preparer_relance`, `radar_couverture` |
| **Pages** | `page_publier`, `pages_lister`, `page_lire` |
| **Courriel** | `courriel_m_envoyer` (destinataire résolu par la gateway, F-110) |
| **Compte** | `compte_consommation`, `compte_abonnement` |
| **Administration** (ADMIN) | `admin_utilisateurs`, `admin_codes_acces_emettre`, `admin_quota_crediter`, `admin_sante` (déploiement, pods, version) |

**Le travail long** (un tour d'agent, une synchro) est une **tâche** : l'outil rend tout de suite un
identifiant ; `tasks/get` ou `tour_suivre` donnent l'avancement ; le client peut annuler. Rien n'est
bloqué pendant huit minutes.

**Ressources MCP** (lecture par référence) : pages publiées, export Markdown du Radar, rapports de
relevé Teams, journal d'un tour.

**Prompts MCP** (gabarits proposés à l'utilisateur dans son IA) : « Atelier de test d'un poste »,
« État de mes clients ce matin », « Préparer ma réponse au manager sur un sujet ».

## 6. Les gardes

1. **Une IA ne s'autorise jamais elle-même.** Les commandes sur la machine d'un client (porte de
   confirmation, ADR-019) et les écritures dans Microsoft 365 (F-108) **restent à valider par un humain
   dans l'application**. `autorisations_en_attente` les liste avec le lien direct ; **aucun outil ne les
   accorde**. Le coupe-circuit (SF-38-08) s'applique aux tours lancés par MCP.
2. **Mêmes droits, même cloisonnement** : chaque outil appelle les services existants avec l'identité
   du jeton ; isolation `user_id` et `host_id` ; périmètres et postes du jeton vérifiés **avant** le
   service.
3. **Les données renvoyées sont des données, pas des consignes** : tout contenu venu de Teams, d'un
   terminal ou d'une page est renvoyé en champ structuré marqué `untrusted`, jamais dans la description
   d'un outil ; la description de chaque outil le rappelle à l'IA cliente.
4. **Pas de secret en sortie** : les outils ne renvoient ni jeton, ni mot de passe, ni cookie, ni clé ;
   un filtre de sortie masque les secrets reconnus (même règle que le journal).
5. **Limites** : par jeton, 60 appels par minute et 10 tours simultanés ; au-delà, refus nommé. La
   consommation des tours tombe sur le quota de l'utilisateur, comme dans l'application.
6. **Journal MCP** : client, jeton, outil, poste, paramètres **sans contenu**, résultat, durée ;
   consultable par l'utilisateur ; l'origine « MCP » apparaît aussi dans le fil du terminal (« tour
   lancé depuis Claude Code »).
7. **Révocation immédiate** d'une connexion ou d'un jeton, avec effet sur les tâches en cours.

## 7. Ce que voit l'utilisateur

- **Réglages → « IA connectées »** : les connexions actives (client, date, périmètres, postes, dernier
  usage), les jetons personnels, le journal, **Révoquer**.
- **Connecter une IA** : l'adresse du serveur et le pas-à-pas exact pour Claude Code
  (`claude mcp add --transport http claude-gateway https://portal.ng-itconsulting.com/api/mcp`),
  Claude Desktop et claude.ai (connecteur personnalisé), Codex ; vérification de la connexion en un clic.
- **Dans l'en-tête d'un client** : « accessible par : Claude Code (vous) » et le réglage par poste.
- **Dans un terminal** : les tours lancés par MCP portent leur origine.

## 8. Le droit et le prix

Inclus dans tous les plans qui ouvrent la Forge ou la Vigie : c'est un canal d'accès, pas une option.
Les outils de chaque domaine suivent le droit du domaine (Forge, Vigie). Le rôle ADMIN a tout
(F-107 §3 bis). La consommation des tours reste sur le quota.

## 9. Découpage

| SF | Titre | Contenu |
|---|---|---|
| SF-112-01 | Le serveur MCP | Streamable HTTP sur `/api/mcp`, révision 2026-07-28 et matrice de compatibilité mesurée avec Claude Code, Claude Desktop, claude.ai et Codex, négociation de version, découverte des outils, `ttlMs`, tests de conformité avec l'outillage officiel ; ADR-020 |
| SF-112-02 | L'autorisation OAuth 2.1 | Serveur de ressources (RFC 9728, 8707, audience) ; serveur d'autorisation embarqué (RFC 8414, PKCE, RFC 9207, CIMD + enregistrement dynamique, rafraîchissement rotatif, révocation, rotation des clés) ; écran de consentement ; tests de sécurité (jeton d'une autre ressource refusé, PKCE absent refusé, redirection non déclarée refusée, périmètre élargi refusé) |
| SF-112-03 | Jetons personnels, accès par poste, journal | Jetons hachés à expiration obligatoire ; accès MCP poste par poste avec l'avertissement fournisseur ; limites ; journal MCP (migration au-dessus du dernier numéro sur main) ; écran « IA connectées » |
| SF-112-04 | Les outils Postes et Forge | §5 ligne Postes et Forge, annotations, schémas de sortie, filtres de secrets |
| SF-112-05 | Les outils Terminaux | Tours en tâches, `tour_suivre` par curseur (réutilise le rejeu de F-84), préciser, interrompre, autorisations en attente **sans les accorder**, origine MCP dans le fil |
| SF-112-06 | Les outils Vigie et Radar | §5 ligne Vigie et Radar, synchro en tâche |
| SF-112-07 | Pages, courriel, compte, administration | §5 lignes correspondantes ; outils ADMIN gardés par le rôle |
| SF-112-08 | Ressources, prompts, connexion guidée | Ressources et prompts MCP ; page « Connecter une IA » avec les pas-à-pas vérifiés ; **test de bout en bout** : un client MCP réel se connecte par OAuth, lit les postes, écrit dans un terminal, suit le tour, voit une autorisation en attente sans pouvoir l'accorder |

**Ordre** : 01 → 02 → 03 → (04 ∥ 05 ∥ 06 ∥ 07) → 08.

**Dépendances** : F-109 (pages), F-110 (courriel) et F-111 (mise à jour du runner) pour les outils
correspondants — en livraison dans la vague en cours.

## 10. Préoccupations transversales

- **Auth / Principal : oui, majeure.** Nouveau serveur d'autorisation et nouveau type de principal (jeton
  OAuth ou personnel, avec périmètres et postes). Composants : `SecurityConfig` (chaînes de filtres
  séparées pour `/api/mcp`, `/oauth2/*`, `/.well-known/*` et le reste, **sans rien ouvrir ailleurs**),
  `CurrentUser`, `OAuth2ClientConfig` (connexion Google inchangée), tous les services appelés par les
  outils. **Test de non-régression** : toutes les routes existantes gardent leur authentification ; un
  jeton MCP n'ouvre **aucune** route hors `/api/mcp`.
- **Contexte tenant : oui** — `user_id` et `host_id` résolus depuis le jeton, jamais depuis un paramètre
  d'outil.
- **Plans / limites : oui** — droits Forge et Vigie, quota, limites par jeton.
- **Navigation : oui** — réglages « IA connectées », écran de consentement, page de connexion guidée.

## 11. Risques, écrits

- **Transmission à un autre fournisseur d'IA** : réglée par l'accès poste par poste et l'avertissement
  (§4). Le produit ne peut pas garantir ce que l'IA connectée fait des données : c'est dit.
- **Injection de consignes** par des données client (un message Teams qui « ordonne » quelque chose à
  l'IA) : contenus marqués non fiables (§6.3) ; l'impossibilité de s'auto-autoriser (§6.1) borne le
  pire cas.
- **Évolution rapide du standard** : matrice de compatibilité mesurée (SF-112-01) et tests de
  conformité rejoués à chaque montée de SDK.
- **Surface d'attaque** d'un serveur d'autorisation : composant éprouvé (Spring Authorization Server),
  aucune implémentation maison de la cryptographie ou des flux.

## 12. Hors périmètre

- Permettre à une IA d'accorder une autorisation de commande ou d'écriture.
- Un client MCP intégré à l'application (l'application qui appelle d'autres serveurs MCP).
- Un annuaire public du serveur MCP (publication dans les catalogues des éditeurs) : après usage réel.

## Sources

- Spécification MCP 2026-07-28 : https://blog.modelcontextprotocol.io/posts/2026-07-28/ et
  https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization
- Serveur MCP Streamable HTTP avec Spring : https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html
- Sécurité MCP avec Spring : https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html
