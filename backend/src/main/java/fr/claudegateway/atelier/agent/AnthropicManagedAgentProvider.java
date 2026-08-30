package fr.claudegateway.atelier.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.ai.AnthropicProperties;

/**
 * Implémentation Anthropic de {@link ManagedAgentProvider} (F-28 / Phase 2, ADR-013). Réplique le
 * patron d'{@code AnthropicProvider} : {@link RestClient} sur {@code app.ai.anthropic.base-url}, clé
 * plateforme en en-tête {@code x-api-key}, {@code anthropic-version} + en-tête(s) beta. Le mapping
 * fournisseur est confiné ici ; le domaine ne dépend que de {@link ManagedAgentProvider}. La clé
 * n'est jamais journalisée.
 */
@Component
public class AnthropicManagedAgentProvider implements ManagedAgentProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicManagedAgentProvider.class);

    /** En-tête beta requis par l'API Managed Agents d'Anthropic (valeur documentée, non secrète). */
    static final String MANAGED_AGENTS_BETA = "managed-agents-2026-04-01";

    /** En-tête beta requis par la Files API d'Anthropic (valeur documentée, non secrète). */
    static final String FILES_API_BETA = "files-api-2025-04-14";

    /** Type d'outil « agent toolset » attendu par l'API Agents (valeur documentée). */
    private static final String AGENT_TOOLSET_TYPE = "agent_toolset_20260401";

    /** Type d'outil donnant accès aux outils d'un serveur MCP déclaré (F-31 / SF-31-05). */
    static final String MCP_TOOLSET_TYPE = "mcp_toolset";

    /** Type de credential MCP : un jeton bearer fixe, sans rafraîchissement (F-31 / SF-31-05). */
    static final String MCP_STATIC_BEARER = "static_bearer";

    /** Nom de l'outil qui exécute des commandes : le seul soumis à validation (F-33 / SF-33-01). */
    static final String SHELL_TOOL = "bash";

    /**
     * Raison d'arrêt d'un {@code session.status_idle} qui <b>attend le client</b> (F-33 / SF-33-02) :
     * la session n'a pas fini, elle est en pause sur une demande d'autorisation.
     */
    static final String REQUIRES_ACTION = "requires_action";

    /** Motif du refus automatique de fin de délai : l'agent doit savoir qu'il a été oublié, pas jugé. */
    static final String CONFIRMATION_TIMEOUT_REASON =
            "Aucune réponse de l'utilisateur dans le délai imparti : commande refusée. "
                    + "Propose une autre approche ou attends de nouvelles instructions.";

    /** Borne de sécurité sur le nombre de pages d'events lues par tour de polling. */
    private static final int MAX_EVENT_PAGES = 1000;

    /** Lecture du corps d'erreur pour le diagnostic (F-30 SF-30-08) : statut et type uniquement. */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final AnthropicProperties properties;
    private final AtelierAgentProperties agentProperties;
    private final RestClient restClient;

    public AnthropicManagedAgentProvider(AnthropicProperties properties,
            AtelierAgentProperties agentProperties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.agentProperties = agentProperties;
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
    }

    @Override
    public ManagedEnvironment createEnvironment(EnvironmentSpec spec) {
        Map<String, Object> networking = new java.util.LinkedHashMap<>();
        networking.put("type", "limited");
        networking.put("allow_package_managers", spec.allowPackageManagers());
        if (!spec.allowedHosts().isEmpty()) {
            // Sans cette liste, la politique réseau de l'environnement bloque le serveur MCP que la
            // session déclare elle-même, et toute session Git est refusée en 400 (F-31 / SF-31-07).
            // Absente quand il n'y a rien à autoriser : le corps reste alors celui d'avant.
            networking.put("allowed_hosts", spec.allowedHosts());
        }
        Map<String, Object> body = Map.of(
                "name", spec.name(),
                "config", Map.of(
                        "type", "cloud",
                        "networking", networking));

        JsonNode response = post("/v1/environments", body, "création de l'environnement");
        String id = text(response, "id");
        if (id == null || id.isBlank()) {
            throw new AgentProviderException("Réponse sans identifiant d'environnement du fournisseur d'agents.");
        }
        return new ManagedEnvironment(id);
    }

    @Override
    public ManagedAgentDefinition createAgent(AgentSpec spec) {
        Map<String, Object> body = Map.of(
                "name", spec.name(),
                "model", spec.model(),
                "system", spec.system(),
                "tools", List.of(Map.of(
                        "type", AGENT_TOOLSET_TYPE,
                        "default_config", Map.of("enabled", true))));

        JsonNode response = post("/v1/agents", body, "création de l'agent");
        String id = text(response, "id");
        String version = text(response, "version");
        if (id == null || id.isBlank() || version == null || version.isBlank()) {
            throw new AgentProviderException("Réponse sans identifiant/version d'agent du fournisseur d'agents.");
        }
        return new ManagedAgentDefinition(id, version);
    }

    @Override
    public String uploadFile(String filename, byte[] content) {
        // Part "file" du multipart : ByteArrayResource nommé pour porter le filename dans la requête.
        ByteArrayResource fileResource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new HttpEntity<>(fileResource, octetStreamHeaders()));
        parts.add("purpose", "agent");

        try {
            JsonNode response = restClient.post()
                    .uri("/v1/files")
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    .header("anthropic-beta", FILES_API_BETA)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(JsonNode.class);
            String id = text(response, "id");
            if (id == null || id.isBlank()) {
                throw new AgentProviderException("Réponse sans identifiant de fichier du fournisseur d'agents.");
            }
            return id;
        } catch (RestClientException ex) {
            throw failure("téléversement de fichier", ex);
        }
    }

    @Override
    public ManagedVault createVaultWithBearer(String displayName, String serverUrl, String token) {
        JsonNode vault = post("/v1/vaults", Map.of("display_name", displayName),
                "création du vault de credentials");
        String vaultId = text(vault, "id");
        if (vaultId == null || vaultId.isBlank()) {
            throw new AgentProviderException("Réponse sans identifiant de vault du fournisseur d'agents.");
        }
        // Le jeton n'apparaît QUE dans ce corps de requête : jamais dans un journal, jamais dans une
        // réponse (le fournisseur traite `token` comme un champ write-only).
        Map<String, Object> credential = Map.of(
                "display_name", displayName,
                "auth", Map.of(
                        "type", MCP_STATIC_BEARER,
                        "mcp_server_url", serverUrl,
                        "token", token));
        JsonNode created = post("/v1/vaults/" + vaultId + "/credentials", credential,
                "dépôt de la credential MCP");
        String credentialId = text(created, "id");
        if (credentialId == null || credentialId.isBlank()) {
            throw new AgentProviderException("Réponse sans identifiant de credential du fournisseur d'agents.");
        }
        return new ManagedVault(vaultId, credentialId);
    }

    @Override
    public void deleteVault(String vaultId) {
        try {
            restClient.delete()
                    .uri("/v1/vaults/" + vaultId)
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    .header("anthropic-beta", MANAGED_AGENTS_BETA)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            // Best-effort : le jeton a déjà été retiré de chez nous. Faire échouer le geste de
            // l'utilisateur sur une panne du fournisseur n'y changerait rien.
            log.warn("Suppression du vault {} impossible : {}", vaultId, ex.getClass().getSimpleName());
        }
    }

    @Override
    public ManagedSession createSession(String agentId, String environmentId, List<FileMount> resources,
            RepositoryMount repository, String systemOverride, SessionPermissions permissions,
            McpAccess mcpAccess, SessionBudget budget, DelegationPolicy delegation,
            ModelChoice model) {
        List<Map<String, Object>> mounts = new ArrayList<>();
        for (FileMount mount : resources) {
            mounts.add(Map.of(
                    "type", "file",
                    "file_id", mount.fileId(),
                    "mount_path", mount.mountPath()));
        }
        if (repository != null) {
            // Le jeton est porté par la requête et n'entre jamais dans le conteneur : le proxy git du
            // fournisseur l'injecte en sortie de sandbox (ADR-015). Il n'est jamais journalisé ici.
            mounts.add(Map.of(
                    "type", "github_repository",
                    "url", repository.url(),
                    "authorization_token", repository.authorizationToken(),
                    "mount_path", repository.mountPath(),
                    "checkout", Map.of("type", "branch", "name", repository.branch())));
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("agent", agentReference(agentId, systemOverride, permissions, mcpAccess, delegation, model));
        body.put("environment_id", environmentId);
        body.put("resources", mounts);
        if (mcpAccess != null) {
            // `vault_ids` n'existe qu'à la création : le fournisseur refuse de l'ajouter ensuite.
            body.put("vault_ids", List.of(mcpAccess.vaultId()));
        }
        if (budget != null) {
            // Plafond de dépense dur (F-36 / SF-36-01). Création-seule, comme le vault. Le montant est
            // en unités mineures ET en chaîne : une forme décimale est rejetée par le fournisseur.
            body.put("budget", Map.of(
                    "type", "limit",
                    "max_list_cost", Map.of(
                            "amount", budget.amountAsString(),
                            "currency", budget.currency())));
        }

        JsonNode response = post("/v1/sessions", body, "création de la session");
        String id = text(response, "id");
        if (id == null || id.isBlank()) {
            throw new AgentProviderException("Réponse sans identifiant de session du fournisseur d'agents.");
        }
        return new ManagedSession(id);
    }

    /**
     * Référence de l'agent dans le corps de création de session. Sans aucune surcharge, l'identifiant
     * nu — exactement le corps envoyé avant F-34/F-33, pour que les projets qui n'activent rien ne
     * changent pas de comportement. Avec surcharge, la forme {@code agent_with_overrides}, locale à
     * cette session (l'agent plateforme n'est jamais modifié) :
     *
     * <ul>
     *   <li>{@code system} <b>remplace</b> celui de l'agent — le prompt reçu ici est donc déjà
     *       complet, prompt plateforme inclus (F-34 / SF-34-01) ;</li>
     *   <li>{@code tools} <b>remplace en bloc</b> celui de l'agent — d'où le toolset complet, avec le
     *       même {@code default_config} qu'au provisionnement, et le seul {@code bash} passé en
     *       {@code always_ask} (F-33 / SF-33-01). Ne renvoyer que {@code bash} priverait la session
     *       de ses outils de lecture/écriture.</li>
     *   <li>{@code mcp_servers} déclare le serveur MCP <b>pour cette session seulement</b>
     *       (F-31 / SF-31-05) : l'agent plateforme, commun à tous les utilisateurs, n'en porte aucun,
     *       et un projet d'archive n'en hérite donc jamais. La déclaration s'accompagne
     *       obligatoirement d'une entrée {@code mcp_toolset} dans {@code tools} — sans elle, le
     *       serveur est connecté mais aucun de ses outils n'est offert à l'agent.</li>
     *   <li>{@code multiagent} fait de la session un <b>coordinateur</b> pouvant confier des
     *       sous-tâches à des copies d'elle-même (F-35 / SF-35-01). Ces copies sont des threads de la
     *       même session : elles partagent son conteneur et son {@code budget}.</li>
     * </ul>
     */
    private static Object agentReference(String agentId, String systemOverride,
            SessionPermissions permissions, McpAccess mcpAccess, DelegationPolicy delegation,
            ModelChoice model) {
        boolean hasSystem = systemOverride != null && !systemOverride.isBlank();
        boolean hasPolicy = permissions != null && permissions.askBeforeShellCommands();
        boolean hasMcp = mcpAccess != null;
        boolean hasDelegation = delegation != null && delegation.enabled();
        boolean hasModel = model != null;
        if (!hasSystem && !hasPolicy && !hasMcp && !hasDelegation && !hasModel) {
            return agentId;
        }
        Map<String, Object> reference = new java.util.LinkedHashMap<>();
        reference.put("type", "agent_with_overrides");
        reference.put("id", agentId);
        if (hasModel) {
            // Modèle et effort décidés PAR SESSION (F-28 SF-28-17) : l'agent provisionné garde les
            // siens, et changer la configuration ne demande ni re-provisionnement ni migration.
            reference.put("model", Map.of(
                    "id", model.id(),
                    "effort", Map.of("type", model.effort())));
        }
        if (hasSystem) {
            reference.put("system", systemOverride);
        }
        if (hasPolicy || hasMcp) {
            // `tools` remplace en bloc : le toolset complet est TOUJOURS renvoyé, sinon la session
            // perdrait ses outils de lecture, d'écriture et d'exécution.
            List<Map<String, Object>> tools = new ArrayList<>();
            tools.add(hasPolicy ? askBeforeShellToolset() : fullToolset());
            if (hasMcp) {
                tools.add(Map.of("type", MCP_TOOLSET_TYPE, "mcp_server_name", mcpAccess.serverName()));
            }
            reference.put("tools", tools);
        }
        if (hasMcp) {
            reference.put("mcp_servers", List.of(Map.of(
                    "type", "url",
                    "name", mcpAccess.serverName(),
                    "url", mcpAccess.serverUrl())));
        }
        if (hasDelegation) {
            reference.put("multiagent", Map.of(
                    "type", "coordinator",
                    "agents", selfRoster()));
        }
        return reference;
    }

    /**
     * Roster de sous-agents (F-35 / SF-35-01) : <b>une seule</b> entrée {@code self}, et rien d'autre.
     * Pas d'agent nommé ni d'{@code advisor} sur un autre modèle — il n'y aurait rien à provisionner
     * ni à versionner, et le gain de parallélisme est déjà là (D2 du cadrage).
     *
     * <p><b>Le roster ne porte pas le plafond.</b> Le fournisseur n'accepte <b>qu'une</b> entrée
     * {@code self} — dupliquer l'entrée pour exprimer « trois sous-agents » fait rejeter la création
     * de session. Une entrée de roster est une <i>référence</i>, lançable autant de fois que le
     * coordinateur le décide : le plafond se dit donc dans le <b>prompt</b>
     * ({@link AgentSystemPrompt#withDelegation}), et la dépense reste bornée par le budget de session
     * (F-36), partagé entre tous les fils.</p>
     */
    private static List<Map<String, Object>> selfRoster() {
        return List.of(Map.of("type", "self"));
    }

    /** Toolset complet, tel qu'il est provisionné sur l'agent : aucune politique particulière. */
    private static Map<String, Object> fullToolset() {
        return Map.of(
                "type", AGENT_TOOLSET_TYPE,
                "default_config", Map.of("enabled", true));
    }

    /**
     * Toolset demandant l'autorisation avant chaque commande shell (F-33 / SF-33-01) : tous les
     * outils restent actifs et automatiques, seul {@code bash} bascule en {@code always_ask}. Au
     * déclenchement, la session se met en pause et attend un {@code user.tool_confirmation}.
     */
    private static Map<String, Object> askBeforeShellToolset() {
        return Map.of(
                "type", AGENT_TOOLSET_TYPE,
                "default_config", Map.of(
                        "enabled", true,
                        "permission_policy", Map.of("type", "always_allow")),
                "configs", List.of(Map.of(
                        "name", SHELL_TOOL,
                        "permission_policy", Map.of("type", "always_ask"))));
    }

    @Override
    public String sendUserMessage(String sessionId, String text) {
        // Forme attendue par l'API : un tableau `events`, chaque event portant un `content` en blocs.
        Map<String, Object> event = Map.of(
                "type", "user.message",
                "content", List.of(Map.of("type", "text", "text", text == null ? "" : text)));
        Map<String, Object> body = Map.of("events", List.of(event));
        JsonNode response = post("/v1/sessions/" + sessionId + "/events", body, "envoi du message utilisateur");
        // L'identifiant de CET event borne le tour : la session est persistante (SF-30-04), elle
        // porte donc encore les events des tours précédents, qu'il ne faut ni rejouer ni prendre
        // pour la fin du tour courant (F-30 / SF-30-11).
        String posted = postedEventId(response);
        return posted != null ? posted : lastUserMessageId(sessionId);
    }

    /** Identifiant du premier event renvoyé par la publication, ou {@code null} s'il n'y en a pas. */
    private static String postedEventId(JsonNode response) {
        if (response == null) {
            return null;
        }
        for (JsonNode event : response.path("data")) {
            String id = text(event, "id");
            if (id != null && !id.isBlank()) {
                return id;
            }
        }
        return null;
    }

    /**
     * Repli quand la publication ne rend pas d'identifiant : le <b>dernier</b> {@code user.message}
     * de la session est celui qui vient d'être posté.
     *
     * <p>Ne jamais retomber sur « pas de borne » : ce serait rétablir exactement le défaut corrigé,
     * la relecture du tour précédent. Une borne introuvable vaut mieux qu'une borne absente — le tour
     * échouera franchement en délai plutôt que de rejouer une ancienne réponse.</p>
     */
    private String lastUserMessageId(String sessionId) {
        String found = null;
        String cursor = null;
        for (int page = 0; page < MAX_EVENT_PAGES; page++) {
            JsonNode pageNode = readEventsPage(sessionId, cursor);
            boolean empty = true;
            for (JsonNode event : events(pageNode)) {
                empty = false;
                if ("user.message".equals(text(event, "type"))) {
                    found = text(event, "id");
                }
            }
            cursor = text(pageNode, "next_page");
            if (empty || cursor == null || cursor.isBlank()) {
                break;
            }
        }
        if (found == null) {
            log.warn("Aucune borne d'event trouvée pour le tour : le tour ne lira aucun event.");
        }
        return found;
    }

    @Override
    public void interruptSession(String sessionId) {
        // Même canal que le message utilisateur : un event posté sur la session. L'API ramène la
        // session à une frontière sûre puis émet `session.status_idle` — d'où l'absence de réponse
        // à exploiter ici. L'échec n'est PAS avalé (cf. terminateSession) : une interruption qui
        // n'est pas passée doit être dite, sinon l'utilisateur attend un arrêt qui ne viendra pas.
        Map<String, Object> body = Map.of("events", List.of(Map.of("type", "user.interrupt")));
        post("/v1/sessions/" + sessionId + "/events", body, "interruption de la session");
    }

    @Override
    public SessionRun awaitCompletion(String sessionId, Duration timeout, int maxPolls) {
        // Délégation à la variante 4-args avec écouteur inerte : comportement historique inchangé.
        return awaitCompletion(sessionId, timeout, maxPolls, ManagedEventListener.NOOP);
    }

    @Override
    public SessionRun awaitCompletion(String sessionId, Duration timeout, int maxPolls,
            ManagedEventListener listener) {
        // Sans borne : toute la session est lue. Correct pour une session neuve — et c'est le
        // comportement historique, conservé pour les appelants qui n'ont pas de borne à donner.
        return awaitCompletion(sessionId, timeout, maxPolls, listener, null);
    }

    @Override
    public SessionRun awaitCompletion(String sessionId, Duration timeout, int maxPolls,
            ManagedEventListener listener, String sinceEventId) {
        ManagedEventListener sink = listener == null ? ManagedEventListener.NOOP : listener;
        StringBuilder reply = new StringBuilder();
        Set<String> seen = new HashSet<>();
        // Demandes d'autorisation en attente (F-33 / SF-33-02) : identifiant d'event → échéance de
        // refus automatique. Locale à la boucle : elle seule sait ce qui reste à trancher.
        Map<String, Long> pendingAsks = new java.util.LinkedHashMap<>();
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        for (int poll = 0; poll < maxPolls; poll++) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AgentSessionTimeoutException(
                        "Délai d'attente dépassé sur la complétion de la session (timeout).");
            }
            // Les nouveaux events apparaissent sur la MÊME page tant que le total < limit : chaque tour
            // relit depuis la page 0 et avance jusqu'à une page vide (couvre > 1000 events). La
            // déduplication par id garantit qu'aucun event n'est traité/notifié deux fois.
            String stopReason = null;
            boolean idle = false;
            String cursor = null; // 1re page sans curseur ; ensuite `next_page` de la réponse
            // Borne du tour (F-30 / SF-30-11). Chaque poll relit depuis la page 0 : l'état « borne
            // franchie » est donc local au poll. Sans borne, tout est pris dès la première ligne.
            boolean reached = sinceEventId == null;
            for (int page = 0; page < MAX_EVENT_PAGES; page++) {
                JsonNode pageNode = readEventsPage(sessionId, cursor);
                for (JsonNode event : events(pageNode)) {
                    String id = text(event, "id");
                    if (!reached) {
                        // Tout ce qui précède le message de CE tour appartient aux tours précédents :
                        // le rejouer réémettrait leur réponse et ferait prendre leur fin pour la nôtre.
                        reached = sinceEventId.equals(id);
                        continue;
                    }
                    if (id != null && !seen.add(id)) {
                        continue; // event déjà traité lors d'un poll précédent
                    }
                    String type = text(event, "type");
                    if ("agent.message".equals(type)) {
                        String fragment = extractText(event);
                        reply.append(fragment);
                        sink.onAgentText(fragment);
                    } else if ("agent.tool_use".equals(type) || "agent.custom_tool_use".equals(type)) {
                        sink.onAction(toolName(event), text(event, "tool_use_id"), toolDetail(event),
                                threadId(event));
                        registerPermissionAsk(event, pendingAsks, sink);
                    } else if ("user.tool_confirmation".equals(type)) {
                        // Décision vue dans le flux (postée par nous, par une autre réplique, ou par
                        // un autre onglet) : la demande n'est plus en attente, son échéance tombe.
                        String answered = text(event, "tool_use_id");
                        if (answered != null && pendingAsks.remove(answered) != null) {
                            sink.onConfirmationResolved(answered, confirmationResult(event));
                        }
                    } else if ("agent.tool_result".equals(type) || "agent.mcp_tool_result".equals(type)) {
                        // Sortie de la commande (F-30 SF-30-01) : c'est elle qui fait le rendu terminal.
                        sink.onActionResult(toolName(event), text(event, "tool_use_id"),
                                truncate(extractToolOutput(event), agentProperties.maxToolOutputChars()),
                                isToolError(event), threadId(event));
                    } else if ("session.status_running".equals(type)) {
                        sink.onStatus("running");
                    } else if ("session.status_idle".equals(type)) {
                        idle = true;
                        stopReason = stopReason(event);
                        sink.onStatus("idle");
                    }
                }
                cursor = text(pageNode, "next_page");
                if (idle || cursor == null || cursor.isBlank()) {
                    break; // idle terminal, ou plus de page (fin des events courants)
                }
            }
            // ⚠️ F-33 / SF-33-02 : une session EN ATTENTE d'autorisation est elle aussi `idle`. La
            // traiter comme une fin de run clôturerait le tour sans que la commande soit exécutée,
            // silencieusement. Seul un `idle` NON `requires_action` termine le run.
            if (idle && !isAwaitingClientAction(stopReason)) {
                return new SessionRun(reply.toString(), stopReason);
            }
            // Le silence ne vaut pas autorisation (D3 du cadrage) : passé le délai, on refuse.
            denyExpiredAsks(sessionId, pendingAsks, sink);
            // Battement (F-30 / SF-30-13) : le run continue. Émis APRÈS la sortie sur `idle`, donc
            // jamais pour un tour déjà terminé — et sans donnée, le provider ignorant ce qu'on en fait.
            sink.onPoll();
            sleepBetweenPolls();
        }
        throw new AgentSessionTimeoutException(
                "Nombre maximal de tours de polling atteint sans complétion de la session.");
    }

    @Override
    public void confirmToolUse(String sessionId, String confirmationId, boolean allow, String message) {
        // Même canal que le message utilisateur : un event posté sur la session. L'échec n'est PAS
        // avalé — une autorisation qui n'est pas passée laisserait l'utilisateur devant un agent
        // figé, persuadé d'avoir répondu.
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("type", "user.tool_confirmation");
        event.put("tool_use_id", confirmationId);
        event.put("result", allow ? "allow" : "deny");
        if (!allow && message != null && !message.isBlank()) {
            // Motif relayé tel quel : l'agent peut alors proposer autre chose au lieu de rester bloqué.
            event.put("message", message);
        }
        post("/v1/sessions/" + sessionId + "/events", Map.of("events", List.of(event)),
                "réponse à une demande d'autorisation");
    }

    /**
     * Enregistre un usage d'outil <b>soumis à autorisation</b> et le relaie (F-33 / SF-33-02).
     *
     * <p>L'identifiant renvoyé au fournisseur est celui de l'<b>event</b> ({@code sevt_…}), pas le
     * {@code tool_use_id} du bloc d'outil : c'est le contrat de l'API, et s'en écarter ferait rejeter
     * silencieusement la confirmation.</p>
     */
    private void registerPermissionAsk(JsonNode event, Map<String, Long> pendingAsks,
            ManagedEventListener sink) {
        if (!isPermissionAsk(event)) {
            return;
        }
        String confirmationId = text(event, "id");
        if (confirmationId == null || confirmationId.isBlank()) {
            // Sans identifiant, la demande ne peut pas être tranchée : ne rien afficher vaut mieux
            // qu'une invite dont aucune réponse ne pourra aboutir.
            log.warn("Demande d'autorisation d'outil sans identifiant d'event : ignorée.");
            return;
        }
        pendingAsks.put(confirmationId,
                System.nanoTime() + agentProperties.confirmTimeout().toNanos());
        sink.onConfirmationRequest(toolName(event), confirmationId, toolDetail(event));
    }

    /** Vrai si l'event d'usage d'outil attend une autorisation ({@code evaluated_permission: ask}). */
    private static boolean isPermissionAsk(JsonNode event) {
        String permission = text(event, "evaluated_permission");
        return permission != null && "ask".equalsIgnoreCase(permission.trim());
    }

    /** Décision portée par un event {@code user.tool_confirmation} ({@code allow} par défaut). */
    private static String confirmationResult(JsonNode event) {
        String result = text(event, "result");
        return result == null || result.isBlank() ? "allow" : result;
    }

    /**
     * Refuse les demandes d'autorisation dont le délai est écoulé (F-33 / SF-33-02, décision D3) : le
     * silence ne vaut pas autorisation. Le refus porte un motif explicite — l'agent doit savoir qu'il
     * n'a pas été jugé, mais oublié.
     *
     * <p>L'échec du refus est <b>avalé</b> : il signifie en pratique qu'une réponse humaine est
     * arrivée entre-temps (la demande n'est plus à trancher). Faire échouer un run pour cela serait
     * absurde.</p>
     */
    private void denyExpiredAsks(String sessionId, Map<String, Long> pendingAsks,
            ManagedEventListener sink) {
        if (pendingAsks.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        for (var iterator = pendingAsks.entrySet().iterator(); iterator.hasNext();) {
            var pending = iterator.next();
            if (now < pending.getValue()) {
                continue;
            }
            iterator.remove();
            try {
                confirmToolUse(sessionId, pending.getKey(), false, CONFIRMATION_TIMEOUT_REASON);
            } catch (RuntimeException ex) {
                log.debug("Refus automatique non transmis : la demande avait déjà été tranchée.");
            }
            sink.onConfirmationResolved(pending.getKey(), "timeout");
        }
    }

    /** Vrai si l'état {@code idle} traduit une <b>attente du client</b>, et non la fin du travail. */
    private static boolean isAwaitingClientAction(String stopReason) {
        return stopReason != null && REQUIRES_ACTION.equalsIgnoreCase(stopReason.trim());
    }

    /** Raison d'arrêt d'un {@code session.status_idle} : chaîne, ou objet {@code {type: ...}}. */
    private static String stopReason(JsonNode event) {
        JsonNode node = event == null ? null : event.get("stop_reason");
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isObject() ? node.path("type").asText(null) : node.asText(null);
    }

    @Override
    public List<OutputFile> listOutputs(String sessionId) {
        try {
            JsonNode response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v1/files")
                            .queryParam("scope_id", sessionId).build())
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    // Les deux bêtas sont requises : deux valeurs sur le même en-tête anthropic-beta.
                    .header("anthropic-beta", MANAGED_AGENTS_BETA, FILES_API_BETA)
                    .retrieve()
                    .body(JsonNode.class);
            List<OutputFile> outputs = new ArrayList<>();
            for (JsonNode file : dataArray(response)) {
                String id = text(file, "id");
                String filename = text(file, "filename");
                // Seuls les fichiers réellement téléchargeables sont des sorties : les fichiers d'entrée
                // montés (téléversés purpose=agent) apparaissent avec downloadable=false et
                // provoqueraient un échec au téléchargement — on les ignore.
                boolean downloadable = file.path("downloadable").asBoolean(false);
                if (downloadable && id != null && !id.isBlank()) {
                    outputs.add(new OutputFile(id, filename));
                }
            }
            return outputs;
        } catch (RestClientException ex) {
            throw failure("liste des sorties", ex);
        }
    }

    @Override
    public byte[] downloadFile(String fileId) {
        try {
            byte[] content = restClient.get()
                    .uri("/v1/files/" + fileId + "/content")
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    .header("anthropic-beta", FILES_API_BETA)
                    .retrieve()
                    .body(byte[].class);
            return content == null ? new byte[0] : content;
        } catch (RestClientException ex) {
            throw failure("téléchargement de fichier", ex);
        }
    }

    @Override
    public void terminateSession(String sessionId) {
        // Nettoyage best-effort : ne doit jamais faire échouer le run appelant.
        try {
            restClient.delete()
                    .uri("/v1/sessions/" + sessionId)
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    .header("anthropic-beta", MANAGED_AGENTS_BETA)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            log.debug("Terminaison best-effort de la session en échec (ignorée).");
        }
    }

    @Override
    public SessionUsage getSessionUsage(String sessionId) {
        try {
            JsonNode response = restClient.get()
                    .uri("/v1/sessions/" + sessionId)
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    .header("anthropic-beta", MANAGED_AGENTS_BETA)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new AgentProviderException("Réponse vide du fournisseur d'agents (usage de session).");
            }
            JsonNode usage = response.path("usage");
            JsonNode cacheCreation = usage.path("cache_creation");
            // Tokens d'entrée = entrée directe + lecture de cache + créations de cache (5m + 1h).
            long inputTokens = usage.path("input_tokens").asLong(0)
                    + usage.path("cache_read_input_tokens").asLong(0)
                    + cacheCreation.path("ephemeral_5m_input_tokens").asLong(0)
                    + cacheCreation.path("ephemeral_1h_input_tokens").asLong(0);
            long outputTokens = usage.path("output_tokens").asLong(0);
            // Temps facturé du bac à sable : active_seconds arrondi à la seconde.
            long activeSeconds = Math.round(response.path("stats").path("active_seconds").asDouble(0));
            return new SessionUsage(inputTokens, outputTokens, activeSeconds, listCost(response));
        } catch (RestClientException ex) {
            throw failure("usage de session", ex);
        }
    }

    /**
     * Coût cumulé de la session en <b>unités mineures</b> (F-36 / SF-36-02), ou {@code null} si le
     * fournisseur ne le rapporte pas — l'appelant retombe alors sur le décompte des tokens.
     *
     * <p>Le montant est porté sous la même forme que le plafond posé à la création
     * ({@code {amount, currency}}, {@code amount} en unités mineures). Une forme inattendue est
     * traitée comme « non rapporté » : mieux vaut décompter les tokens que décompter faux.</p>
     */
    private static Long listCost(JsonNode response) {
        JsonNode node = response.path("list_cost");
        if (node.isMissingNode() || node.isNull()) {
            node = response.path("usage").path("list_cost");
        }
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode amount = node.isObject() ? node.path("amount") : node;
        if (amount.isNumber()) {
            return amount.asLong();
        }
        if (amount.isTextual()) {
            try {
                return Long.parseLong(amount.asText().trim());
            } catch (NumberFormatException malformed) {
                log.debug("Coût de session illisible : décompte des tokens (repli).");
                return null;
            }
        }
        return null;
    }

    /** Lit une page d'events (polling) de la session. Extraite pour la testabilité. */
    private JsonNode readEventsPage(String sessionId, String pageCursor) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/v1/sessions/" + sessionId + "/events").queryParam("limit", 1000);
                        // `page` est un curseur opaque (jamais un entier) : absent sur la 1re page,
                        // puis alimenté par le `next_page` de la réponse précédente.
                        if (pageCursor != null && !pageCursor.isBlank()) {
                            uriBuilder.queryParam("page", pageCursor);
                        }
                        return uriBuilder.build();
                    })
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    .header("anthropic-beta", MANAGED_AGENTS_BETA)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw failure("lecture des events", ex);
        }
    }

    /** Attente configurable entre deux tours de polling ({@code 0} en test → aucun sleep réel). */
    private void sleepBetweenPolls() {
        Duration delay = agentProperties.pollDelay();
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AgentProviderException("Attente de complétion interrompue.", ex);
        }
    }

    /**
     * Exécute un POST Managed Agents avec les en-têtes d'authentification et beta. Toute erreur
     * {@link RestClientException} (4xx/5xx, réseau) est convertie en {@link AgentProviderException}
     * avec un message neutre (jamais de clé ni de réponse brute).
     */
    /**
     * Convertit un échec d'appel en exception, en loggant de quoi diagnostiquer (F-30 SF-30-08).
     *
     * <p>Sont loggés l'opération, le <b>statut HTTP</b> et le <b>type d'erreur</b> renvoyé — jamais
     * la clé d'API, ni le corps brut, ni aucune donnée utilisateur. Un log réduit au seul « appel en
     * échec » a déjà coûté un diagnostic manuel pour une cause triviale.</p>
     *
     * <p>Un solde de crédits épuisé est reconnu et distingué : réessayer ne peut pas aboutir tant que
     * le compte n'est pas rechargé, et l'utilisateur doit le savoir.</p>
     */
    private static AgentProviderException failure(String operation, RestClientException ex) {
        if (!(ex instanceof RestClientResponseException response)) {
            // Pas de réponse HTTP (réseau, timeout) : rien de plus à dire que l'opération.
            log.warn("Appel au fournisseur d'agents en échec ({}) : pas de réponse HTTP", operation);
            return new AgentProviderException("Échec de l'appel au fournisseur d'agents.", ex);
        }
        int status = response.getStatusCode().value();
        String errorType = "inconnu";
        String message = "";
        try {
            JsonNode error = MAPPER.readTree(response.getResponseBodyAsString()).path("error");
            errorType = error.path("type").asText("inconnu");
            message = error.path("message").asText("");
        } catch (RuntimeException | java.io.IOException parseFailure) {
            // Corps non exploitable : le statut seul oriente déjà le diagnostic.
        }
        // Le message du fournisseur est la seule chose qui distingue deux 400 `invalid_request_error`
        // (champ inconnu, session close, budget épuisé…). Le lire puis le jeter, c'est se condamner à
        // un diagnostic manuel à chaque incident — ce qui est déjà arrivé deux fois. Il est borné :
        // un corps d'erreur peut citer la requête, on n'en veut que la tête.
        log.warn("Appel au fournisseur d'agents en échec ({}) : HTTP {}, type {}, message : {}",
                operation, status, errorType, abbreviate(message));
        if (isCreditExhausted(status, errorType, message)) {
            return new AgentCreditExhaustedException("Crédit du fournisseur d'agents épuisé.", ex);
        }
        return new AgentProviderException("Échec de l'appel au fournisseur d'agents.", ex);
    }

    /** Tête du message d'erreur du fournisseur : assez pour diagnostiquer, trop court pour déverser. */
    private static String abbreviate(String message) {
        if (message == null || message.isBlank()) {
            return "(aucun)";
        }
        String flat = message.replace('\n', ' ').strip();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }

    /**
     * Reconnaît un solde épuisé. L'API n'expose pas de code dédié : la détection s'appuie sur le
     * libellé, et c'est assumé comme une <b>heuristique</b> — si le message est reformulé, on retombe
     * sur l'erreur générique plutôt que d'échouer.
     */
    private static boolean isCreditExhausted(int status, String errorType, String message) {
        if (status != 400 || !"invalid_request_error".equals(errorType)) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("credit balance") || lower.contains("purchase credits");
    }

    private JsonNode post(String uri, Map<String, Object> body, String operation) {
        try {
            return restClient.post()
                    .uri(uri)
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    .header("anthropic-beta", MANAGED_AGENTS_BETA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw failure(operation, ex);
        }
    }

    /** En-têtes de la part fichier du multipart (contenu binaire opaque). */
    private static HttpHeaders octetStreamHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return headers;
    }

    /** Events d'une page : nœud {@code data} (tableau) ou tableau racine ; sinon vide. */
    private static Iterable<JsonNode> events(JsonNode page) {
        if (page == null) {
            return List.of();
        }
        JsonNode data = page.get("data");
        if (data != null && data.isArray()) {
            return data;
        }
        return page.isArray() ? page : List.<JsonNode>of();
    }

    /** Éléments d'une réponse liste : nœud {@code data} (tableau) ou tableau racine ; sinon vide. */
    private static Iterable<JsonNode> dataArray(JsonNode response) {
        return events(response);
    }

    /**
     * Sortie d'un event {@code agent.tool_result} (F-30 SF-30-01).
     *
     * <p>La forme exacte de ces events n'est pas documentée : on cherche successivement les
     * emplacements plausibles ({@code content} textuel ou en blocs, {@code output}, {@code result},
     * puis {@code stdout}/{@code stderr}). Une forme inattendue renvoie une chaîne vide — un défaut
     * d'affichage ne doit jamais faire échouer le run.</p>
     */
    private static String extractToolOutput(JsonNode event) {
        if (event == null) {
            return "";
        }
        String fromContent = extractText(event);
        if (!fromContent.isBlank()) {
            return fromContent;
        }
        for (String field : List.of("output", "result")) {
            JsonNode node = event.get(field);
            if (node != null && !node.isNull()) {
                if (node.isTextual()) {
                    return node.asText();
                }
                String nested = extractText(node);
                return nested.isBlank() ? node.toString() : nested;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String field : List.of("stdout", "stderr")) {
            JsonNode node = event.get(field);
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(node.asText());
            }
        }
        return sb.toString();
    }

    /** Vrai si l'event de résultat signale un échec ({@code is_error} ou code de retour non nul). */
    private static boolean isToolError(JsonNode event) {
        if (event == null) {
            return false;
        }
        JsonNode isError = event.get("is_error");
        if (isError != null && isError.isBoolean() && isError.asBoolean()) {
            return true;
        }
        JsonNode code = event.get("return_code");
        return code != null && code.isNumber() && code.asInt() != 0;
    }

    /**
     * Tronque une sortie trop volumineuse (F-30 SF-30-01). Un {@code npm install} produit des dizaines
     * de milliers de lignes : les laisser traverser le flux SSE saturerait le navigateur avant même
     * qu'elles soient affichées. La troncature est donc faite ici, pas seulement au rendu.
     */
    private static String truncate(String output, int max) {
        if (output == null) {
            return "";
        }
        if (max <= 0 || output.length() <= max) {
            return output;
        }
        int omitted = output.length() - max;
        return output.substring(0, max) + "\n… (" + omitted + " caractères omis)";
    }

    /**
     * Texte d'un event {@code agent.message} : concatène les fragments {@code text} du {@code content}
     * (tableau de blocs) ; tolère un {@code content} textuel simple et un repli via {@code message}.
     */
    private static String extractText(JsonNode event) {
        JsonNode content = event.get("content");
        if (content == null && event.get("message") != null) {
            content = event.get("message").get("content");
        }
        if (content == null) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode block : content) {
                JsonNode textNode = block.get("text");
                if (textNode != null && !textNode.isNull()) {
                    sb.append(textNode.asText());
                }
            }
        }
        return sb.toString();
    }

    /**
     * Fil d'exécution dont vient un event d'outil (F-35 / SF-35-02) : {@code thread_id}, avec repli sur
     * {@code thread}. Un run séquentiel n'en porte aucun et renvoie {@code null} — c'est le cas de
     * tous les runs d'avant F-35, dont l'affichage ne change donc pas.
     *
     * <p>Chaîne <b>opaque</b> : elle sert uniquement à distinguer les fils entre eux, jamais à
     * déduire un ordre ou une hiérarchie, et n'est jamais journalisée.</p>
     */
    private static String threadId(JsonNode event) {
        String id = text(event, "thread_id");
        if (id == null || id.isBlank()) {
            // Repli : le fil peut être porté par un objet imbriqué plutôt que par un identifiant nu.
            JsonNode thread = event == null ? null : event.get("thread");
            id = thread != null && thread.isObject() ? text(thread, "id") : text(event, "thread");
        }
        return id == null || id.isBlank() ? null : id;
    }

    /**
     * Nom de l'outil d'un event {@code agent.tool_use} : champ {@code name} (ou {@code tool_name} en
     * repli), sinon {@code "tool"} par défaut. Jamais {@code null}.
     */
    private static String toolName(JsonNode event) {
        String name = text(event, "name");
        if (name == null || name.isBlank()) {
            name = text(event, "tool_name");
        }
        return name == null || name.isBlank() ? "tool" : name;
    }

    /**
     * Courte description de l'action d'un event {@code agent.tool_use} : la commande {@code input.command}
     * (ex. bash) si présente, sinon la représentation compacte de {@code input}, sinon {@code ""}.
     * Aucun secret ne transite ici (seule l'action de l'agent est relayée).
     */
    private static String toolDetail(JsonNode event) {
        JsonNode input = event.get("input");
        if (input == null || input.isNull()) {
            return "";
        }
        JsonNode command = input.get("command");
        if (command != null && !command.isNull()) {
            return command.isTextual() ? command.asText() : command.toString();
        }
        return input.isTextual() ? input.asText() : input.toString();
    }

    /** Lit un champ texte non nul de la réponse, ou {@code null} si absent. */
    private static String text(JsonNode response, String field) {
        if (response == null) {
            return null;
        }
        JsonNode node = response.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
