package fr.claudegateway.atelier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.atelier.dto.AtelierChatResponse.AtelierAction;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * Cœur de l'Atelier (F-28 / SF-28-02) : orchestre une boucle <b>tool-use</b> où Claude lit et édite
 * les fichiers d'un workspace via des outils exécutés par le backend (aucune exécution de commande —
 * opérations fichiers uniquement, Phase 1). Gateway-First : le backend orchestre, Claude raisonne ;
 * Provider Independence via {@link AiAgentProvider}. Isolation multi-tenant : tout accès aux fichiers
 * et à la conversation passe par {@code user_id}.
 */
@Service
public class AtelierChatService {

    /** Garde-fou anti-boucle : nombre maximal d'allers-retours par message. */
    private static final int MAX_ITERATIONS = 12;
    /** Garde-fou : longueur max de la consigne système (CLAUDE.md + skills). */
    private static final int SYSTEM_MAX_CHARS = 40_000;
    private static final List<String> SKILL_PREFIXES = List.of(".claude/skills/", "skills/");

    private final WorkspaceService workspaceService;
    private final AtelierMessageRepository messageRepository;
    private final AiAgentProvider agentProvider;
    private final ByokKeyService byokKeyService;
    private final QuotaService quotaService;
    private final ModelCatalog modelCatalog;

    public AtelierChatService(WorkspaceService workspaceService, AtelierMessageRepository messageRepository,
            AiAgentProvider agentProvider, ByokKeyService byokKeyService, QuotaService quotaService,
            ModelCatalog modelCatalog) {
        this.workspaceService = workspaceService;
        this.messageRepository = messageRepository;
        this.agentProvider = agentProvider;
        this.byokKeyService = byokKeyService;
        this.quotaService = quotaService;
        this.modelCatalog = modelCatalog;
    }

    /**
     * Traite un message d'atelier : boucle tool-use jusqu'à la réponse finale, persiste l'échange,
     * comptabilise l'usage. Le workspace est vérifié possédé par l'utilisateur (404 sinon) et le quota
     * contrôlé avant tout appel fournisseur.
     *
     * <p>Volontairement <b>non transactionnel</b> : chaque écriture de fichier ({@code writeFile}) a sa
     * propre transaction. Un outil qui échoue (chemin invalide…) est renvoyé au modèle comme erreur sans
     * empoisonner la conversation (pas de rollback-only propagé). Les persistances (messages, usage) sont
     * atomiques par appel de repository.</p>
     */
    public AtelierChatResult chat(UUID userId, UUID workspaceId, String rawMessage) {
        return runLoop(userId, workspaceId, rawMessage, AtelierProgressListener.NOOP);
    }

    /**
     * Variante <b>streaming</b> (SF-28-05) : boucle tool-use identique à {@link #chat}, mais notifie
     * chaque étape (action fichier, commentaire de tour) via le {@code listener} pour un relais SSE au
     * fil de l'eau. Le résultat final ({@link AtelierChatResult}) et la persistance sont identiques —
     * seul le retour d'information intermédiaire diffère (zéro régression sur le mode synchrone).
     */
    public AtelierChatResult chatStreaming(UUID userId, UUID workspaceId, String rawMessage,
            AtelierProgressListener listener) {
        return runLoop(userId, workspaceId, rawMessage, listener);
    }

    private AtelierChatResult runLoop(UUID userId, UUID workspaceId, String rawMessage,
            AtelierProgressListener listener) {
        workspaceService.requireOwned(userId, workspaceId); // 404 si non possédé (isolation) — TOUJOURS en premier
        // Mode BYOK (clé personnelle active) vs Hosted (clé plateforme) : en BYOK, les tokens sont sur
        // le compte Anthropic de l'utilisateur => aucun contrôle ni comptage du quota plateforme (F-28 /
        // SF-28-06). En Hosted, comportement historique : contrôle avant + comptabilisation après.
        String apiKey = byokKeyService.resolveActiveApiKey(userId).orElse(null);
        boolean hosted = apiKey == null;
        if (hosted) {
            quotaService.assertWithinQuota(userId);
        }
        String userText = rawMessage.trim();

        // Historique de l'atelier (texte) + nouveau message utilisateur.
        List<AgentMessage> messages = new ArrayList<>();
        for (AtelierMessage past : messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId)) {
            String role = "ASSISTANT".equalsIgnoreCase(past.getRole()) ? "assistant" : "user";
            messages.add(new AgentMessage(role, List.of(new AgentContentBlock.Text(past.getContent()))));
        }
        messages.add(AgentMessage.userText(userText));

        messageRepository.save(AtelierMessage.builder()
                .workspaceId(workspaceId).userId(userId).role("USER").content(userText).build());

        String system = buildSystemPrompt(userId, workspaceId);
        List<AgentTool> tools = buildTools();
        String model = modelCatalog.defaultModel();

        List<AtelierAction> actions = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        String finalText = "";

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            AgentTurn turn = agentProvider.nextTurn(
                    new AgentTurnRequest(model, system, messages, tools, apiKey));
            inputTokens += turn.inputTokens();
            outputTokens += turn.outputTokens();

            if (turn.finished() || turn.toolCalls().isEmpty()) {
                finalText = turn.text();
                break;
            }

            // Commentaire du tour (le cas échéant) relayé avant l'exécution de ses outils.
            if (turn.text() != null && !turn.text().isBlank()) {
                listener.onText(turn.text());
            }

            // Rejoue le message assistant (texte + tool_use) puis exécute chaque outil.
            List<AgentContentBlock> assistantBlocks = new ArrayList<>();
            if (turn.text() != null && !turn.text().isBlank()) {
                assistantBlocks.add(new AgentContentBlock.Text(turn.text()));
            }
            List<AgentContentBlock> toolResults = new ArrayList<>();
            for (AgentToolCall call : turn.toolCalls()) {
                assistantBlocks.add(new AgentContentBlock.ToolUse(call.id(), call.name(), call.input()));
                // Intention d'étape relayée avant exécution (émise même si l'outil échoue ensuite).
                AtelierProgressListener.AtelierStepEvent step = stepFor(call);
                if (step != null) {
                    listener.onAction(step);
                }
                ToolOutcome outcome = executeTool(userId, workspaceId, call);
                if (outcome.action() != null) {
                    actions.add(outcome.action());
                }
                toolResults.add(new AgentContentBlock.ToolResult(call.id(), outcome.content(), outcome.isError()));
            }
            messages.add(AgentMessage.assistant(assistantBlocks));
            messages.add(AgentMessage.toolResults(toolResults));

            if (iteration == MAX_ITERATIONS - 1) {
                finalText = (turn.text() == null || turn.text().isBlank())
                        ? "J'ai atteint la limite d'étapes pour ce message ; relance-moi pour continuer."
                        : turn.text();
            }
        }

        if (hosted) {
            quotaService.recordUsage(userId, inputTokens, outputTokens);
        }

        AtelierMessage assistant = messageRepository.save(AtelierMessage.builder()
                .workspaceId(workspaceId).userId(userId).role("ASSISTANT")
                .content(finalText == null ? "" : finalText).build());

        return new AtelierChatResult(finalText, actions, assistant.getId());
    }

    /** Historique des messages de l'atelier (isolation {@code user_id}). */
    public List<AtelierMessage> history(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId);
        return messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId);
    }

    // ----------------------------------------------------------------- outils

    /**
     * Traduit un appel d'outil en étape de progression pour l'UI (SF-28-05), ou {@code null} si l'outil
     * n'a pas d'étape visible. Le chemin/terme est extrait des arguments ({@code path} / {@code query}).
     */
    private AtelierProgressListener.AtelierStepEvent stepFor(AgentToolCall call) {
        JsonNode input = call.input();
        return switch (call.name()) {
            case "read_file" -> new AtelierProgressListener.AtelierStepEvent("read", arg(input, "path"));
            case "write_file" -> new AtelierProgressListener.AtelierStepEvent("write", arg(input, "path"));
            case "list_files" -> new AtelierProgressListener.AtelierStepEvent("list", null);
            case "search_files" -> new AtelierProgressListener.AtelierStepEvent("search", arg(input, "query"));
            default -> null;
        };
    }

    /** Extrait un argument texte d'un input d'outil (ou {@code null} si absent). */
    private String arg(JsonNode input, String name) {
        return input == null ? null : input.path(name).asText(null);
    }

    private ToolOutcome executeTool(UUID userId, UUID workspaceId, AgentToolCall call) {
        try {
            JsonNode input = call.input();
            return switch (call.name()) {
                case "list_files" -> ToolOutcome.info(String.join("\n", workspaceService.tree(userId, workspaceId)));
                case "read_file" -> {
                    String path = requiredArg(input, "path");
                    String content = workspaceService.readFile(userId, workspaceId, path);
                    yield new ToolOutcome(content, false, new AtelierAction("read", path));
                }
                case "write_file" -> {
                    String path = requiredArg(input, "path");
                    String content = input.path("content").asText("");
                    workspaceService.writeFile(userId, workspaceId, path, content);
                    yield new ToolOutcome("Fichier écrit : " + path, false, new AtelierAction("write", path));
                }
                case "search_files" -> ToolOutcome.info(search(userId, workspaceId, requiredArg(input, "query")));
                default -> ToolOutcome.error("Outil inconnu : " + call.name());
            };
        } catch (RuntimeException ex) {
            // Erreur métier (fichier introuvable, chemin invalide, trop volumineux…) : renvoyée à
            // l'assistant comme résultat d'erreur (il peut se corriger), jamais un détail sensible.
            return ToolOutcome.error(ex.getMessage() != null ? ex.getMessage() : "Opération refusée.");
        }
    }

    private String requiredArg(JsonNode input, String name) {
        String value = input == null ? null : input.path(name).asText(null);
        if (value == null || value.isBlank()) {
            throw new InvalidFilePathException("Paramètre requis manquant : " + name);
        }
        return value;
    }

    /** Recherche naïve (sous-chaîne) sur les fichiers texte du workspace ; résultat borné. */
    private String search(UUID userId, UUID workspaceId, String query) {
        StringBuilder result = new StringBuilder();
        String needle = query.toLowerCase();
        for (String path : workspaceService.tree(userId, workspaceId)) {
            String content;
            try {
                content = workspaceService.readFile(userId, workspaceId, path);
            } catch (RuntimeException ignored) {
                continue;
            }
            int line = 0;
            for (String text : content.split("\n", -1)) {
                line++;
                if (text.toLowerCase().contains(needle)) {
                    result.append(path).append(':').append(line).append(": ").append(text.strip()).append('\n');
                    if (result.length() > 8_000) {
                        return result.append("… (résultats tronqués)").toString();
                    }
                }
            }
        }
        return result.length() == 0 ? "Aucun résultat." : result.toString();
    }

    private List<AgentTool> buildTools() {
        Map<String, Object> stringProp = Map.of("type", "string");
        return List.of(
                new AgentTool("list_files", "Liste tous les fichiers du projet (chemins relatifs).",
                        Map.of("type", "object", "properties", Map.of())),
                new AgentTool("read_file", "Lit le contenu texte d'un fichier du projet.",
                        Map.of("type", "object", "properties", Map.of("path", stringProp),
                                "required", List.of("path"))),
                new AgentTool("write_file", "Écrit (ou remplace) le contenu texte d'un fichier du projet.",
                        Map.of("type", "object",
                                "properties", Map.of("path", stringProp, "content", stringProp),
                                "required", List.of("path", "content"))),
                new AgentTool("search_files", "Recherche une chaîne dans les fichiers du projet.",
                        Map.of("type", "object", "properties", Map.of("query", stringProp),
                                "required", List.of("query"))));
    }

    /** Consigne système : conventions du projet (CLAUDE.md) + skills + rôle de l'agent. */
    private String buildSystemPrompt(UUID userId, UUID workspaceId) {
        StringBuilder system = new StringBuilder();
        system.append("Tu es un assistant de développement qui travaille sur le projet de l'utilisateur, ")
                .append("dans un espace de travail hébergé. Utilise les outils fournis (list_files, read_file, ")
                .append("write_file, search_files) pour lire et modifier les fichiers du projet. ")
                .append("Ne fais aucune supposition sur un fichier sans l'avoir lu. Après une modification, ")
                .append("résume clairement ce que tu as changé.\n\n");

        readOptional(userId, workspaceId, "CLAUDE.md").ifPresent(content ->
                system.append("--- Conventions du projet (CLAUDE.md) ---\n").append(content).append("\n\n"));

        for (String path : safeTree(userId, workspaceId)) {
            if (SKILL_PREFIXES.stream().anyMatch(path::startsWith)) {
                readOptional(userId, workspaceId, path).ifPresent(content ->
                        system.append("--- Skill : ").append(path).append(" ---\n").append(content).append("\n\n"));
            }
            if (system.length() > SYSTEM_MAX_CHARS) {
                break;
            }
        }
        String result = system.toString();
        return result.length() > SYSTEM_MAX_CHARS ? result.substring(0, SYSTEM_MAX_CHARS) : result;
    }

    private List<String> safeTree(UUID userId, UUID workspaceId) {
        try {
            return workspaceService.tree(userId, workspaceId);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private java.util.Optional<String> readOptional(UUID userId, UUID workspaceId, String path) {
        try {
            return java.util.Optional.of(workspaceService.readFile(userId, workspaceId, path));
        } catch (RuntimeException ex) {
            return java.util.Optional.empty();
        }
    }

    /** Résultat d'un tour d'atelier. */
    public record AtelierChatResult(String reply, List<AtelierAction> actions, UUID messageId) {
    }

    /** Issue interne d'un outil : contenu renvoyé au modèle, indicateur d'erreur, action pour l'UI. */
    private record ToolOutcome(String content, boolean isError, AtelierAction action) {
        static ToolOutcome info(String content) {
            return new ToolOutcome(content, false, null);
        }

        static ToolOutcome error(String message) {
            return new ToolOutcome(message, true, null);
        }
    }
}
