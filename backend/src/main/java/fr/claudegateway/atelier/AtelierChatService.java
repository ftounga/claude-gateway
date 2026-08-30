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
import fr.claudegateway.runner.audit.RunnerAuditOutcome;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.runner.relay.RelayInterruptTarget;
import fr.claudegateway.runner.relay.RunnerRelayBroadcaster;

/**
 * Cœur de l'Atelier (F-28 / SF-28-02) : orchestre une boucle <b>tool-use</b> où Claude lit et édite
 * les fichiers d'un workspace via des outils exécutés par le backend (aucune exécution de commande —
 * opérations fichiers uniquement, Phase 1). Gateway-First : le backend orchestre, Claude raisonne ;
 * Provider Independence via {@link AiAgentProvider}. Isolation multi-tenant : tout accès aux fichiers
 * et à la conversation passe par {@code user_id}.
 */
@Service
public class AtelierChatService implements RelayInterruptTarget {

    /** Garde-fou anti-boucle : nombre maximal d'allers-retours par message. */
    private static final int MAX_ITERATIONS = 12;
    /**
     * Budget de temps d'un tour (F-38 / SF-38-07). Sans lui, 12 itérations × 120 s de {@code bash}
     * dépassent largement la durée de vie du flux SSE : l'émetteur se clôt, l'écran se fige, et la
     * boucle continue d'exécuter des commandes sur la machine de l'utilisateur. Le budget garantit
     * l'inverse : la boucle rend la main <b>avant</b> que le flux expire.
     */
    static final long TURN_BUDGET_MS = 600_000L;
    /** Longueur de la commande relayée à l'écran comme étape de progression (contrat §3). */
    private static final int STEP_COMMAND_CHARS = 200;
    /** Agrégat de sortie de commande conservé et rendu au modèle (contrat §5), en octets. */
    private static final int MAX_BASH_OUTPUT_BYTES = 131_072;
    static final String INTERRUPTED_REPLY = "J'ai arrêté le travail en cours à ta demande.";
    static final String BUDGET_REACHED_REPLY =
            "Le temps imparti à ce message est écoulé ; relance-moi pour continuer.";
    /** Garde-fou : longueur max de la consigne système (CLAUDE.md + skills). */
    private static final int SYSTEM_MAX_CHARS = 40_000;
    private static final List<String> SKILL_PREFIXES = List.of(".claude/skills/", "skills/");
    /** Cible d'audit d'une commande (F-38 / SF-38-08) : la ligne du journal, pas un contenu. */
    private static final int AUDIT_TARGET_CHARS = 1_000;

    private final WorkspaceService workspaceService;
    private final AtelierMessageRepository messageRepository;
    private final AiAgentProvider agentProvider;
    private final ByokKeyService byokKeyService;
    private final QuotaService quotaService;
    private final ModelCatalog modelCatalog;
    private final fr.claudegateway.atelier.git.GitWorkspaceService gitWorkspaceService;
    private final RunnerToolGateway runnerToolGateway;
    private final fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    private final RunnerConfirmationGate confirmationGate;
    private final RunnerAuditService runnerAuditService;
    /**
     * Diffusion des gestes qui doivent atteindre le pod où tourne la boucle (F-38 / SF-38-13).
     * Inerte tant que le relais n'est pas configuré : le chemin mono-pod reste le chemin par défaut.
     */
    private final RunnerRelayBroadcaster relayBroadcaster;

    /**
     * Tours pour lesquels une interruption a été demandée (F-38 / SF-38-07, même geste que F-32).
     * Clef {@code userId:workspaceId} : l'isolation est déjà garantie par {@code requireOwned}, la
     * clef composite évite en plus qu'une marque déborde d'un utilisateur à l'autre. Remise à zéro à
     * l'ouverture de chaque tour, pour qu'une interruption arrivée hors run ne tue pas le suivant.
     */
    private final java.util.Set<String> interruptedTurns = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public AtelierChatService(WorkspaceService workspaceService, AtelierMessageRepository messageRepository,
            AiAgentProvider agentProvider, ByokKeyService byokKeyService, QuotaService quotaService,
            ModelCatalog modelCatalog,
            fr.claudegateway.atelier.git.GitWorkspaceService gitWorkspaceService,
            RunnerToolGateway runnerToolGateway,
            fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher,
            RunnerConfirmationGate confirmationGate,
            RunnerAuditService runnerAuditService,
            RunnerRelayBroadcaster relayBroadcaster) {
        this.workspaceService = workspaceService;
        this.messageRepository = messageRepository;
        this.agentProvider = agentProvider;
        this.byokKeyService = byokKeyService;
        this.quotaService = quotaService;
        this.modelCatalog = modelCatalog;
        this.gitWorkspaceService = gitWorkspaceService;
        this.runnerToolGateway = runnerToolGateway;
        this.runnerCallDispatcher = runnerCallDispatcher;
        this.confirmationGate = confirmationGate;
        this.runnerAuditService = runnerAuditService;
        this.relayBroadcaster = relayBroadcaster;
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
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId); // 404 si non possédé (isolation) — TOUJOURS en premier
        // Mode « Assistant » sur un projet Git (F-31 / SF-31-03) : cette boucle lit et édite le
        // stockage objet, vide sur ce type de projet. Répondre quand même reviendrait à commenter un
        // projet inexistant ; le mode Terminal, lui, a le dépôt réellement cloné.
        //
        // Le garde-fou ne vaut QUE pour la cible SANDBOX (F-38 / SF-38-05) : en cible RUNNER, les
        // outils s'exécutent sur la machine de l'utilisateur, où le dépôt est réellement cloné. Un
        // projet Git + RUNNER est donc légitime — le refuser serait un faux positif.
        if (!workspace.isRunnerTarget()) {
            gitWorkspaceService.requireArchiveChatMode(workspace);
        }
        // Mode BYOK (clé personnelle active) vs Hosted (clé plateforme) : en BYOK, les tokens sont sur
        // le compte Anthropic de l'utilisateur => aucun contrôle ni comptage du quota plateforme (F-28 /
        // SF-28-06). En Hosted, comportement historique : contrôle avant + comptabilisation après.
        String apiKey = byokKeyService.resolveActiveApiKey(userId).orElse(null);
        boolean hosted = apiKey == null;
        if (hosted) {
            quotaService.assertWithinQuota(userId);
        }
        // Une interruption demandée alors qu'aucun tour ne tournait ne doit pas tuer celui-ci
        // (même précaution que F-32 SF-32-01).
        interruptedTurns.remove(turnKey(userId, workspaceId));
        long deadline = System.currentTimeMillis() + TURN_BUDGET_MS;
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

        String system = buildSystemPrompt(userId, workspace);
        List<AgentTool> tools = buildTools(workspace);
        String model = modelCatalog.defaultModel();

        List<AtelierAction> actions = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        String finalText = "";

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            if (interruptedTurns.remove(turnKey(userId, workspaceId))) {
                finalText = INTERRUPTED_REPLY;
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                // Frontière sûre : on s'arrête ici plutôt que de laisser tourner des commandes
                // derrière un flux SSE déjà expiré.
                finalText = BUDGET_REACHED_REPLY;
                break;
            }
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
                // Identifiant de corrélation unique de l'appel (contrat de messages runner §1) : celui
                // du fournisseur, ou un UUID généré s'il manque — et le MÊME partout (bloc tool_use,
                // bloc tool_result, trame runner), sans quoi la réponse ne se rattacherait à rien.
                String callId = correlationId(call);
                assistantBlocks.add(new AgentContentBlock.ToolUse(callId, call.name(), call.input()));
                // Intention d'étape relayée avant exécution (émise même si l'outil échoue ensuite).
                AtelierProgressListener.AtelierStepEvent step = stepFor(call);
                if (step != null) {
                    listener.onAction(step);
                }
                ToolOutcome outcome = executeTool(userId, workspace, callId, call, listener, deadline);
                if (outcome.action() != null) {
                    actions.add(outcome.action());
                }
                toolResults.add(new AgentContentBlock.ToolResult(callId, outcome.content(), outcome.isError()));
            }
            messages.add(AgentMessage.assistant(assistantBlocks));
            messages.add(AgentMessage.toolResults(toolResults));

            if (interruptedTurns.remove(turnKey(userId, workspaceId))) {
                // Interruption arrivée pendant les outils de ce tour : on s'arrête sans rappeler le
                // fournisseur — l'appel runner en vol a déjà reçu son tool_cancel.
                finalText = INTERRUPTED_REPLY;
                break;
            }
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

    /**
     * Demande l'interruption du tour en cours sur ce workspace (F-38 / SF-38-07, même geste que
     * F-32 SF-32-01). Deux effets, dans cet ordre :
     * <ol>
     *   <li>un {@code tool_cancel(user_interrupt)} part vers le runner, qui <b>tue la commande</b>
     *       en cours et émet quand même sa trame terminale (contrat §2.5) ;</li>
     *   <li>le tour est marqué : la boucle s'arrête à la <b>frontière sûre</b> suivante plutôt que
     *       de relancer le fournisseur.</li>
     * </ol>
     *
     * <p>Idempotent et silencieux si rien ne tourne : la marque est de toute façon effacée à
     * l'ouverture du prochain tour. Isolation : {@code requireOwned} d'abord, toujours (404 sinon).</p>
     *
     * <p><b>Multi-pod (F-38 / SF-38-13)</b> : ces trois gestes vivent en mémoire, sur le pod qui
     * exécute la boucle et tient le flux SSE — pas forcément celui qui reçoit ce clic. On agit donc
     * d'abord ici, puis on <b>diffuse</b> le même geste aux pods pairs. La diffusion est best-effort :
     * un pair injoignable est journalisé et ne change pas la réponse rendue au navigateur.</p>
     */
    public void interruptChat(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId);
        interruptLocally(userId, workspaceId, "user_interrupt");
        relayBroadcaster.broadcastInterrupt(userId, workspaceId, "user_interrupt");
    }

    /**
     * Les trois gestes d'une interruption, appliqués <b>sur ce pod</b> — appelés par
     * {@link #interruptChat} et par le relais interne, dans le même ordre.
     */
    @Override
    public RelayInterruptOutcome interruptLocally(UUID userId, UUID workspaceId, String reason) {
        interruptedTurns.add(turnKey(userId, workspaceId));
        // Une demande d'autorisation encore en attente bloquerait la boucle jusqu'à son échéance,
        // alors que l'utilisateur vient précisément de demander l'arrêt (F-38 / SF-38-08).
        int released = confirmationGate.cancelWorkspace(workspaceId);
        int cancelled = runnerCallDispatcher.cancelWorkspace(workspaceId, reason);
        return new RelayInterruptOutcome(released, cancelled);
    }

    /**
     * Tranche une demande d'autorisation posée par la boucle en cible {@code RUNNER}
     * (F-38 / SF-38-08, décision D7) : autorise la commande, ou la refuse avec un motif que le
     * modèle recevra.
     *
     * <p>Le tour, lui, attend sur son flux SSE : cette réponse arrive sur une <b>autre requête</b>.
     * Isolation appliquée en premier ({@code requireOwned} : 404 sur un projet d'autrui), et la
     * porte revérifie que la demande appartient bien à ce couple utilisateur/workspace — un
     * identifiant de corrélation deviné n'autorise rien.</p>
     *
     * <p><b>Multi-pod (F-38 / SF-38-13)</b> : la porte qui attend vit sur le pod qui exécute la
     * boucle, alors que ce clic peut atterrir sur n'importe lequel. Si personne n'attend ici, la
     * décision est <b>diffusée</b> aux pairs ; un seul d'entre eux détient la demande et la tranche.
     * Si vraiment personne ne résout, l'erreur d'origine est relancée (409) — et la porte qui
     * attendrait sans être atteinte expirera en refus : le silence ne vaut jamais autorisation.</p>
     *
     * @throws WorkspaceNotFoundException si le workspace n'est pas possédé
     * @throws fr.claudegateway.runner.exec.NoPendingConfirmationException si rien n'attend cette réponse
     */
    public void confirmToolUse(UUID userId, UUID workspaceId, String toolUseId, boolean allow,
            String reason) {
        workspaceService.requireOwned(userId, workspaceId);
        String callId = toolUseId == null ? "" : toolUseId.trim();
        try {
            confirmationGate.resolve(userId, workspaceId, callId, allow, reason);
        } catch (fr.claudegateway.runner.exec.NoPendingConfirmationException ex) {
            if (!relayBroadcaster.broadcastConfirm(userId, workspaceId, callId, allow, reason)) {
                throw ex;
            }
        }
    }

    /** Clef de marque d'interruption : l'utilisateur ET le workspace, jamais l'un sans l'autre. */
    private static String turnKey(UUID userId, UUID workspaceId) {
        return userId + ":" + workspaceId;
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
            // F-38 / SF-38-07 : la commande elle-même est l'information utile à l'écran, tronquée
            // pour qu'un one-liner de 3 000 caractères ne noie pas la liste des étapes (contrat §3).
            case "bash" -> new AtelierProgressListener.AtelierStepEvent("bash",
                    shorten(arg(input, "command"), STEP_COMMAND_CHARS));
            default -> null;
        };
    }

    /** Tronque un texte à {@code max} caractères, sans marqueur : c'est une étiquette, pas un contenu. */
    private static String shorten(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Extrait un argument texte d'un input d'outil (ou {@code null} si absent). */
    private String arg(JsonNode input, String name) {
        return input == null ? null : input.path(name).asText(null);
    }

    /** Identifiant de corrélation d'un appel d'outil : celui du fournisseur, ou un UUID de secours. */
    private String correlationId(AgentToolCall call) {
        return call.id() == null || call.id().isBlank() ? UUID.randomUUID().toString() : call.id();
    }

    private ToolOutcome executeTool(UUID userId, Workspace workspace, String callId, AgentToolCall call,
            AtelierProgressListener listener, long deadline) {
        if (workspace.isRunnerTarget()) {
            return executeToolOnRunner(userId, workspace.getId(), callId, call, listener, deadline);
        }
        return executeToolOnStorage(userId, workspace.getId(), call);
    }

    /**
     * Exécute un outil sur la <b>machine de l'utilisateur</b> (F-38 / SF-38-05). La gateway relaie :
     * elle traduit l'appel en trame {@code tool_call}, attend le {@code tool_result} et le retraduit
     * en résultat d'outil pour le modèle, dans les formats exacts du mode sandbox (contrat §3) — le
     * prompt ne doit pas dériver selon la cible d'exécution.
     *
     * <p>Deux garde-fous s'ajoutent en SF-38-08, dans cet ordre : la <b>validation d'action</b>
     * (D7) — la trame n'est jamais émise avant décision — puis la <b>trace</b> (D11) : une ligne de
     * journal par appel, qu'il ait abouti, échoué ou été refusé.</p>
     */
    private ToolOutcome executeToolOnRunner(UUID userId, UUID workspaceId, String callId,
            AgentToolCall call, AtelierProgressListener listener, long deadline) {
        String tool = call.name();
        String target = auditTarget(call);
        if (requiresConfirmation(tool)) {
            RunnerConfirmationGate.Outcome decision =
                    askPermission(userId, workspaceId, callId, tool, target, listener);
            if (!decision.decision().allows()) {
                // Refus AVANT émission (contrat §6) : rien n'est parti sur la machine, et le modèle
                // reçoit le motif pour proposer autre chose plutôt que de rester bloqué.
                runnerAuditService.recordDenied(userId, workspaceId, callId, tool, target,
                        decision.decision() == RunnerConfirmationGate.Decision.TIMEOUT
                                ? RunnerAuditOutcome.TIMEOUT
                                : RunnerAuditOutcome.DENIED);
                return ToolOutcome.error(deniedMessage(decision));
            }
        }
        RunnerCallResult result;
        try {
            result = callRunner(workspaceId, callId, call, listener, deadline);
        } catch (RuntimeException ex) {
            // Argument manquant ou malformé : rien n'est parti, mais la tentative est tracée — le
            // journal doit dire ce que le modèle a essayé, pas seulement ce qui a abouti.
            result = RunnerCallResult.backendError(RunnerErrorCodes.INVALID_INPUT,
                    ex.getMessage() != null ? ex.getMessage() : "Opération refusée.");
        }
        if (result == null) {
            return ToolOutcome.error("Outil inconnu : " + tool);
        }
        runnerAuditService.recordCall(userId, workspaceId, callId, tool, target, result);
        return runnerOutcome(call, result);
    }

    /**
     * Vrai si l'action doit être autorisée par l'utilisateur avant d'être émise (F-38 / SF-38-08,
     * décision D7). En cible {@code RUNNER}, l'exécution de commandes l'est <b>toujours</b> : le
     * réglage {@code agent_ask_before_bash} n'est pas consulté ici, il n'est pas désactivable.
     *
     * <p>Les écritures de fichier n'y sont volontairement pas soumises : c'est l'usage central du
     * mode (l'agent édite le projet), et un clic par écriture pousserait à chercher un contournement
     * — ce qui affaiblirait la garde. Elles restent tracées, confinées à la racine et révocables
     * d'un geste (coupe-circuit). Étendre la porte ne coûte qu'une ligne ici.</p>
     */
    private static boolean requiresConfirmation(String tool) {
        return "bash".equals(tool);
    }

    /** Pose la demande d'autorisation à l'écran, attend la décision, puis relaie sa résolution. */
    private RunnerConfirmationGate.Outcome askPermission(UUID userId, UUID workspaceId, String callId,
            String tool, String detail, AtelierProgressListener listener) {
        RunnerConfirmationGate.Outcome outcome = confirmationGate.await(userId, workspaceId, callId,
                () -> listener.onConfirmRequest(
                        new AtelierProgressListener.AtelierConfirmRequest(callId, tool, detail)));
        listener.onConfirmResolved(new AtelierProgressListener.AtelierConfirmResolved(
                callId, outcome.decision().label()));
        return outcome;
    }

    /** Message rendu au modèle quand l'action n'a pas été autorisée (jamais un détail technique). */
    private static String deniedMessage(RunnerConfirmationGate.Outcome outcome) {
        if (outcome.decision() == RunnerConfirmationGate.Decision.TIMEOUT) {
            return "Commande refusée : aucune autorisation n'a été donnée dans le délai imparti.";
        }
        return outcome.reason() == null || outcome.reason().isBlank()
                ? "Commande refusée par l'utilisateur."
                : "Commande refusée par l'utilisateur. Motif : " + outcome.reason();
    }

    /** Émet l'appel vers le runner ; {@code null} si l'outil demandé n'existe pas. */
    private RunnerCallResult callRunner(UUID workspaceId, String callId, AgentToolCall call,
            AtelierProgressListener listener, long deadline) {
        JsonNode input = call.input();
        return switch (call.name()) {
            case "list_files" -> runnerToolGateway.listFiles(workspaceId, callId);
            case "read_file" -> runnerToolGateway.readFile(workspaceId, callId,
                    requiredArg(input, "path"));
            case "write_file" -> runnerToolGateway.writeFile(workspaceId, callId,
                    requiredArg(input, "path"), input.path("content").asText(""));
            case "search_files" -> runnerToolGateway.searchFiles(workspaceId, callId,
                    requiredArg(input, "query"));
            // Le délai est ramené au budget de tour restant : une commande ne doit jamais pouvoir
            // survivre au tour qui l'a lancée.
            case "bash" -> runnerToolGateway.bash(workspaceId, callId, requiredArg(input, "command"),
                    input.path("cwd").asText(null), deadline - System.currentTimeMillis(),
                    listener::onOutput);
            default -> null;
        };
    }

    /** Traduit l'issue d'un appel runner en résultat d'outil, aux formats du mode sandbox. */
    private ToolOutcome runnerOutcome(AgentToolCall call, RunnerCallResult result) {
        JsonNode input = call.input();
        return switch (call.name()) {
            case "read_file" -> textOutcome(result, new AtelierAction("read", arg(input, "path")));
            // Le `content` renvoyé par le runner est ignoré : seul compte l'aboutissement, et le
            // modèle attend la formulation historique.
            case "write_file" -> result.ok()
                    ? new ToolOutcome("Fichier écrit : " + arg(input, "path"), false,
                            new AtelierAction("write", arg(input, "path")))
                    : ToolOutcome.error(result.errorMessage());
            case "bash" -> bashOutcome(arg(input, "command"), result);
            default -> textOutcome(result, null);
        };
    }

    /**
     * Cible journalisée d'un appel (F-38 / SF-38-08) : un chemin, un terme recherché ou une commande
     * tronquée — jamais un contenu de fichier ni une sortie de commande.
     */
    private String auditTarget(AgentToolCall call) {
        JsonNode input = call.input();
        return switch (call.name()) {
            case "read_file", "write_file" -> arg(input, "path");
            case "search_files" -> arg(input, "query");
            case "bash" -> shorten(arg(input, "command"), AUDIT_TARGET_CHARS);
            default -> null;
        };
    }

    /**
     * Assemble le résultat d'une commande pour le modèle (F-38 / SF-38-07, contrat §3) :
     * {@code "$ <commande>\n<sortie entrelacée>\n[code de sortie: N]"}.
     *
     * <p>La sortie vient des trames {@code tool_stream} — dans leur ordre d'émission, donc avec
     * l'entrelacement réel de {@code stdout} et {@code stderr} — et non du {@code content} du
     * {@code tool_result}, qui est vide pour {@code bash}. Elle est bornée en <b>octets</b> : la
     * tête est conservée, c'est là que se trouve la commande qui a échoué.</p>
     *
     * <p>Un code de sortie non nul reste un <b>succès d'appel</b> : la commande a tourné, son échec
     * est une information que le modèle doit lire, pas une panne de la gateway.</p>
     */
    private ToolOutcome bashOutcome(String command, RunnerCallResult result) {
        if (!result.ok()) {
            return ToolOutcome.error(result.errorMessage());
        }
        boolean truncated = result.streamTruncated() || result.truncated();
        String output = result.streamed() == null ? "" : result.streamed();
        byte[] bytes = output.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_BASH_OUTPUT_BYTES) {
            output = new String(bytes, 0, MAX_BASH_OUTPUT_BYTES, java.nio.charset.StandardCharsets.UTF_8);
            truncated = true;
        }
        StringBuilder text = new StringBuilder("$ ").append(command).append('\n').append(output);
        if (truncated) {
            text.append("\n… (sortie tronquée)");
        }
        if (text.charAt(text.length() - 1) != '\n') {
            text.append('\n');
        }
        text.append("[code de sortie: ")
                .append(result.exitCode() == null ? "inconnu" : result.exitCode())
                .append(']');
        return new ToolOutcome(text.toString(), false, null);
    }

    /** Traduit une issue d'appel runner en résultat d'outil dont le contenu est rendu verbatim. */
    private ToolOutcome textOutcome(RunnerCallResult result, AtelierAction action) {
        if (!result.ok()) {
            return ToolOutcome.error(result.errorMessage());
        }
        String content = result.truncated()
                ? result.content() + "\n… (contenu tronqué)"
                : result.content();
        return new ToolOutcome(content, false, action);
    }

    private ToolOutcome executeToolOnStorage(UUID userId, UUID workspaceId, AgentToolCall call) {
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

    /**
     * Outils exposés au modèle. Les quatre outils fichiers sont inconditionnels ; {@code bash}
     * n'apparaît qu'en cible <b>{@code RUNNER}</b> (F-38 / SF-38-07).
     *
     * <p>La condition n'est pas cosmétique : en cible {@code SANDBOX}, il n'existe aucun endroit où
     * exécuter une commande — le backend est une gateway, il n'exécute rien lui-même. Exposer
     * l'outil reviendrait à promettre au modèle une capacité qui n'aboutirait qu'à des erreurs.</p>
     */
    List<AgentTool> buildTools(Workspace workspace) {
        Map<String, Object> stringProp = Map.of("type", "string");
        List<AgentTool> tools = new ArrayList<>(fileTools(stringProp));
        if (workspace.isRunnerTarget()) {
            tools.add(new AgentTool("bash",
                    "Exécute une commande shell sur la machine connectée (runner), depuis la racine "
                            + "du projet. Renvoie la sortie (stdout et stderr) et le code de sortie.",
                    Map.of("type", "object",
                            "properties", Map.of("command", stringProp, "cwd", stringProp),
                            "required", List.of("command"))));
        }
        return List.copyOf(tools);
    }

    private List<AgentTool> fileTools(Map<String, Object> stringProp) {
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

    /**
     * Consigne système : conventions du projet (CLAUDE.md) + skills + rôle de l'agent.
     *
     * <p>Les lectures passent par la <b>cible d'exécution</b> du workspace (F-38 / SF-38-05). En cible
     * {@code RUNNER}, le stockage objet est vide : lire là-bas enverrait une consigne sans les
     * conventions du projet, <b>en silence</b> (les lectures optionnelles avalent l'erreur). C'est
     * exactement la panne qu'on ne verrait pas.</p>
     */
    private String buildSystemPrompt(UUID userId, Workspace workspace) {
        StringBuilder system = new StringBuilder();
        system.append("Tu es un assistant de développement qui travaille sur le projet de l'utilisateur, ")
                .append("dans un espace de travail hébergé. Utilise les outils fournis (list_files, read_file, ")
                .append("write_file, search_files) pour lire et modifier les fichiers du projet. ")
                .append("Ne fais aucune supposition sur un fichier sans l'avoir lu. Après une modification, ")
                .append("résume clairement ce que tu as changé.\n\n");

        // Compteurs d'amorçage : ces lectures sont journalisées en UNE ligne (F-38 / SF-38-08).
        // Les tracer une par une noierait le journal sous des dizaines d'entrées que l'utilisateur
        // n'a pas demandées, et masquerait ce qu'il cherche : ce que le modèle a décidé de lire.
        int reads = 0;
        long chars = 0L;

        java.util.Optional<String> instructions = readOptional(userId, workspace, "CLAUDE.md");
        if (instructions.isPresent()) {
            reads++;
            chars += instructions.get().length();
            system.append("--- Conventions du projet (CLAUDE.md) ---\n")
                    .append(instructions.get()).append("\n\n");
        }

        List<String> tree = safeTree(userId, workspace);
        if (!tree.isEmpty()) {
            reads++; // Le listage est lui aussi une action menée sur la machine.
        }
        for (String path : tree) {
            if (SKILL_PREFIXES.stream().anyMatch(path::startsWith)) {
                java.util.Optional<String> skill = readOptional(userId, workspace, path);
                if (skill.isPresent()) {
                    reads++;
                    chars += skill.get().length();
                    system.append("--- Skill : ").append(path).append(" ---\n")
                            .append(skill.get()).append("\n\n");
                }
            }
            if (system.length() > SYSTEM_MAX_CHARS) {
                break;
            }
        }
        if (workspace.isRunnerTarget()) {
            runnerAuditService.recordBootstrap(userId, workspace.getId(),
                    UUID.randomUUID().toString(), reads, chars);
        }
        String result = system.toString();
        return result.length() > SYSTEM_MAX_CHARS ? result.substring(0, SYSTEM_MAX_CHARS) : result;
    }

    /** Arborescence pour la consigne système, prise là où les fichiers vivent réellement. */
    private List<String> safeTree(UUID userId, Workspace workspace) {
        try {
            if (workspace.isRunnerTarget()) {
                RunnerCallResult result = runnerToolGateway.listFiles(
                        workspace.getId(), UUID.randomUUID().toString());
                return result.ok() ? List.of(result.content().split("\n")) : List.of();
            }
            return workspaceService.tree(userId, workspace.getId());
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /** Lecture optionnelle pour la consigne système, prise là où les fichiers vivent réellement. */
    private java.util.Optional<String> readOptional(UUID userId, Workspace workspace, String path) {
        try {
            if (workspace.isRunnerTarget()) {
                RunnerCallResult result = runnerToolGateway.readFile(
                        workspace.getId(), UUID.randomUUID().toString(), path);
                return result.ok() ? java.util.Optional.of(result.content()) : java.util.Optional.empty();
            }
            return java.util.Optional.of(workspaceService.readFile(userId, workspace.getId(), path));
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
