package fr.claudegateway.atelier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentContextPolicy;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentReasoning;
import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
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

    /**
     * Journal du service (F-39 / SF-39-17). Deux lignes par tour — ouverture, fermeture — au niveau
     * {@code info}, et <b>aucun contenu</b> : ni commande, ni sortie, ni chemin. C'est la règle de
     * l'audit runner (SF-38-08), et elle vaut ici : le journal dit ce qui s'est passé, pas ce qui a
     * été lu. Une ligne par itération noierait le journal sous des dizaines d'entrées par message.
     */
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AtelierChatService.class);

    /**
     * Garde-fou anti-boucle : nombre maximal d'allers-retours par message, lu dans la configuration
     * (F-28 / SF-28-19, défaut 30).
     *
     * <p>La valeur d'origine — 12 — coupait <b>31 % des demandes</b> de l'usage réel mesuré, dont la
     * médiane est de 6 outils mais la moyenne de 13,8. Ce n'est pas la borne de dépense : le budget
     * de temps du tour et le quota le sont, et ils s'appliquent d'abord. Celle-ci protège d'une
     * boucle qui tournerait en rond.</p>
     */
    private final int maxIterations;
    /**
     * Plafond de consommation d'un <b>message</b> (F-39 / SF-39-15), en tokens traités. Voir
     * {@link AtelierTurnBudget} pour le raisonnement : le nombre d'itérations borne les
     * allers-retours, le budget de temps borne la durée, et jusqu'ici <b>rien</b> ne bornait ce
     * qu'un seul message pouvait consommer.
     */
    private final long maxTurnTokens;
    /** Plafond d'explorations déléguées par message (F-39 / SF-39-14). */
    private final int maxDelegations;
    /** Coupe-circuit de la cible {@code SANDBOX} de la boucle maison (F-39 / SF-39-16). */
    private final boolean storageExecution;
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
    /**
     * Réponse rendue quand le modèle a été coupé au plafond de sortie (SF-28-18). Elle dit les trois
     * choses que l'utilisateur doit savoir : ce qui s'est passé, que <b>rien n'a été exécuté</b>, et
     * quoi faire ensuite. Sans elle, il ne voyait qu'une phrase d'intention suivie de rien.
     */
    static final String TRUNCATED_REPLY =
            "Ma réponse a dépassé la taille maximale autorisée et a été coupée : rien n'a été exécuté. "
                    + "Demande-moi une modification plus courte, ou de travailler fichier par fichier.";
    /**
     * Réponse de repli quand le tour ne produit aucun texte (SF-28-18). Elle n'est pas cosmétique :
     * l'API refuse un bloc de texte vide, donc un message vide persisté ici rendrait <b>tous</b> les
     * tours suivants de ce projet impossibles — vérifié, {@code 400 "text content blocks must be
     * non-empty"}. L'historique ne doit jamais pouvoir contenir un tel message.
     */
    static final String EMPTY_REPLY_FALLBACK = "Je n'ai pas produit de réponse pour ce message.";
    static final String BUDGET_REACHED_REPLY =
            "Le temps imparti à ce message est écoulé ; relance-moi pour continuer.";
    /**
     * Réponse rendue quand le tour s'arrête sur le <b>plafond de consommation</b> du message
     * (F-39 / SF-39-15). Volontairement distincte de {@link #BUDGET_REACHED_REPLY} : les confondre
     * ferait proposer « racheter des tokens » à quelqu'un que la montre a arrêté (décision D-L8-5).
     */
    static final String SPEND_CAP_REPLY =
            "Ce message a atteint son plafond de consommation ; le travail déjà fait est conservé, "
                    + "relance-moi pour continuer.";
    /** Garde-fou : longueur max de la consigne système (CLAUDE.md + skills). */
    private static final int SYSTEM_MAX_CHARS = 40_000;
    private static final List<String> SKILL_PREFIXES = List.of(".claude/skills/", "skills/");
    /**
     * Nombre de skills annoncés dans la consigne (F-39 / SF-39-02, décision D3). Une borne explicite
     * vaut mieux qu'une coupe au caractère près : le point d'arrêt devient prévisible, donc le
     * préfixe cacheable.
     */
    private static final int MAX_SKILLS_ANNOUNCED = 50;
    /** Longueur d'une description de skill dans le catalogue (F-39 / SF-39-02). */
    private static final int SKILL_DESCRIPTION_CHARS = 200;
    /**
     * Tours rejoués <b>avec</b> leur trajectoire d'outils (F-39 / SF-39-03, décision D3). Au-delà,
     * les tours plus anciens sont rejoués en texte seul : la valeur d'une trajectoire décroît vite
     * avec l'ancienneté, son coût en tokens non.
     */
    private static final int REPLAYED_TRACE_TURNS = 5;
    /**
     * Outils confiés à une sous-boucle d'exploration (F-39 / SF-39-14) : la lecture, et rien
     * d'autre. Ni écriture, ni {@code bash}, ni plan, ni délégation récursive.
     */
    private static final java.util.Set<String> READ_ONLY_TOOLS =
            java.util.Set.of("read_file", "list_files", "search_files");

    /** Cible d'audit d'une commande (F-38 / SF-38-08) : la ligne du journal, pas un contenu. */
    private static final int AUDIT_TARGET_CHARS = 1_000;
    /**
     * Contexte d'entrée à partir duquel les résultats d'outils périmés sont écartés
     * (F-39 / SF-39-12). Large devant un tour ordinaire, très en deçà de la fenêtre du modèle : ce
     * qui déborde n'est pas la conversation, c'est l'accumulation des sorties d'outils <b>dans un
     * même tour</b> — une seule sortie de commande pèse jusqu'à {@value #MAX_BASH_OUTPUT_BYTES}
     * octets, et un tour en compte jusqu'à trente.
     */
    private static final int CONTEXT_TRIGGER_INPUT_TOKENS = 200_000;
    /** Résultats d'outils toujours conservés : ce sur quoi l'agent travaille à l'instant. */
    private static final int CONTEXT_KEEP_TOOL_RESULTS = 3;
    /**
     * Plancher d'écartement (F-39 / SF-39-12, décision D-L6-9). Une édition modifie le préfixe et
     * invalide donc le cache de prompt à partir du point édité : sans plancher, on paierait une
     * réécriture complète du cache pour quelques centaines de tokens gagnés.
     */
    private static final int CONTEXT_CLEAR_AT_LEAST_INPUT_TOKENS = 20_000;

    private final WorkspaceService workspaceService;
    private final AtelierMessageRepository messageRepository;
    private final AiAgentProvider agentProvider;
    private final ByokKeyService byokKeyService;
    private final QuotaService quotaService;
    /**
     * Modèle de la boucle maison (F-39 / SF-39-10) : un réglage à elle, et non plus le modèle par
     * défaut du catalogue de <b>chat</b> (F-02) — deux features distinctes ne partagent pas un
     * réglage par hasard.
     */
    private final String model;
    /** Raisonnement demandé à chaque itération d'un tour (F-39 / SF-39-10). */
    private final AgentReasoning reasoning;
    /**
     * Politique de contexte appliquée à chaque itération d'un tour (F-39 / SF-39-12). Elle dit une
     * intention — écarter les résultats d'outils périmés — que le fournisseur traduit ; le mécanisme
     * lui-même n'existe que dans {@code AnthropicAgentProvider}.
     */
    private final AgentContextPolicy contextPolicy;
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

    /**
     * Tours pour lesquels l'utilisateur a autorisé <b>toutes</b> les commandes (F-38 / SF-38-20).
     *
     * <p>La portée est le <b>tour</b>, jamais le projet : la marque est effacée à l'ouverture de
     * chaque message, comme celle d'interruption. C'est ce qui distingue un raccourci d'un
     * renoncement — on autorise ce qu'on a commencé à voir, pas tout ce qui viendra un jour.</p>
     */
    private final java.util.Set<String> blanketAllowedTurns =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Précisions déposées <b>pendant</b> un tour, en attente d'être lues (F-39 / SF-39-19).
     *
     * <p>Ce n'est pas une interruption : on n'arrête rien, on ajoute au contexte. La boucle les
     * consomme au début de l'itération suivante, à la même frontière sûre où elle regarde déjà
     * l'interruption et le budget.</p>
     *
     * <p>Clefé {@code userId:workspaceId} et vidé à l'ouverture de chaque tour — même parade que
     * pour le plan (SF-39-13) et la marque d'interruption : ce service est un singleton partagé par
     * tous les utilisateurs.</p>
     */
    private final java.util.Map<String, java.util.List<String>> pendingSteers =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Précisions en attente au plus : au-delà, c'est un nouveau tour qu'il faut, pas des rustines. */
    static final int MAX_PENDING_STEERS = 5;

    /** Longueur d'une précision : c'est une précision, pas une nouvelle consigne. */
    static final int MAX_STEER_CHARS = 4_000;

    public AtelierChatService(WorkspaceService workspaceService, AtelierMessageRepository messageRepository,
            AiAgentProvider agentProvider, ByokKeyService byokKeyService, QuotaService quotaService,
            fr.claudegateway.atelier.git.GitWorkspaceService gitWorkspaceService,
            RunnerToolGateway runnerToolGateway,
            fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher,
            RunnerConfirmationGate confirmationGate,
            RunnerAuditService runnerAuditService,
            RunnerRelayBroadcaster relayBroadcaster,
            AtelierProperties atelierProperties) {
        this.workspaceService = workspaceService;
        this.messageRepository = messageRepository;
        this.agentProvider = agentProvider;
        this.byokKeyService = byokKeyService;
        this.quotaService = quotaService;
        this.gitWorkspaceService = gitWorkspaceService;
        this.runnerToolGateway = runnerToolGateway;
        this.runnerCallDispatcher = runnerCallDispatcher;
        this.confirmationGate = confirmationGate;
        this.runnerAuditService = runnerAuditService;
        this.relayBroadcaster = relayBroadcaster;
        this.maxIterations = atelierProperties.maxIterations();
        this.maxTurnTokens = atelierProperties.maxTurnTokens();
        this.maxDelegations = atelierProperties.maxDelegations();
        this.storageExecution = atelierProperties.storageExecution();
        this.model = atelierProperties.model();
        this.reasoning = new AgentReasoning(true, atelierProperties.effort());
        this.contextPolicy = Boolean.TRUE.equals(atelierProperties.contextPruning())
                ? new AgentContextPolicy(true, CONTEXT_TRIGGER_INPUT_TOKENS,
                        CONTEXT_KEEP_TOOL_RESULTS, CONTEXT_CLEAR_AT_LEAST_INPUT_TOKENS)
                : AgentContextPolicy.none();
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
        // Cible SANDBOX sur la boucle maison (F-39 / SF-39-16) : chemin fermé par défaut. Depuis le
        // lot 4, l'écran ne l'emprunte plus — un projet sans runner passe par les Managed Agents.
        // Refus AVANT tout appel fournisseur et avant le contrôle de quota : aucun token consommé,
        // aucun message persisté. Posé APRÈS `requireOwned` (D3) : un projet d'autrui rend 404, et
        // jamais un refus qui révélerait son existence.
        if (!workspace.isRunnerTarget() && !storageExecution) {
            throw new StorageExecutionClosedException(
                    "Ce projet n'a pas de machine connectée : son terminal passe par le bac à sable "
                            + "hébergé.");
        }
        if (!workspace.isRunnerTarget()) {
            gitWorkspaceService.requireArchiveChatMode(workspace);
        }
        // Mode BYOK (clé personnelle active) vs Hosted (clé plateforme) : en BYOK, les tokens sont sur
        // le compte Anthropic de l'utilisateur => aucun contrôle ni comptage du quota plateforme (F-28 /
        // SF-28-06). En Hosted, comportement historique : contrôle avant + comptabilisation après.
        String apiKey = byokKeyService.resolveActiveApiKey(userId).orElse(null);
        boolean hosted = apiKey == null;
        // Plafond de consommation de CE message (F-39 / SF-39-15). En Hosted il est borné par le
        // quota restant de l'utilisateur du contexte de sécurité — un message ne consomme jamais
        // plus que ce qui a été payé, règle de F-36 transposée à la boucle maison. En BYOK, les
        // tokens sont sur le compte du client et aucun quota n'est tenu (SF-28-06) : seul le
        // réglage s'applique.
        AtelierTurnBudget turnBudget;
        if (hosted) {
            quotaService.assertWithinQuota(userId);
            turnBudget = AtelierTurnBudget.hosted(maxTurnTokens,
                    quotaService.currentUsage(userId).remainingTokens());
        } else {
            turnBudget = AtelierTurnBudget.byok(maxTurnTokens);
        }
        // Une interruption demandée alors qu'aucun tour ne tournait ne doit pas tuer celui-ci
        // (même précaution que F-32 SF-32-01).
        interruptedTurns.remove(turnKey(userId, workspaceId));
        // La marque « tout autoriser » ne survit jamais au message qui l'a reçue (SF-38-20).
        blanketAllowedTurns.remove(turnKey(userId, workspaceId));
        // Les précisions non lues meurent avec le tour (SF-39-19, D3) : les rejouer ferait resurgir
        // une remarque devenue sans objet, dans un contexte qui a changé.
        pendingSteers.remove(turnKey(userId, workspaceId));
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + TURN_BUDGET_MS;
        String userText = rawMessage.trim();

        // Historique de l'atelier (texte) + nouveau message utilisateur.
        // L'historique rejoué démarre à la frontière du fil (SF-39-04) : après un « nouveau
        // départ », les tours d'avant restent lisibles à l'écran mais ne repartent plus chez le
        // fournisseur.
        List<AgentMessage> messages = new ArrayList<>(replayableHistory(
                workspace.getChatThreadStartedAt() == null
                        ? messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId)
                        : messageRepository
                                .findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                                        workspaceId, userId, workspace.getChatThreadStartedAt())));
        messages.add(AgentMessage.userText(userText));

        messageRepository.save(AtelierMessage.builder()
                .workspaceId(workspaceId).userId(userId).role("USER").content(userText).build());

        String system = buildSystemPrompt(userId, workspace);
        List<AgentTool> tools = buildTools(workspace);

        // Plan du tour (F-39 / SF-39-13) : local, donc jamais partagé entre utilisateurs.
        java.util.concurrent.atomic.AtomicReference<AtelierPlan> planOfTurn =
                new java.util.concurrent.atomic.AtomicReference<>(AtelierPlan.EMPTY);
        List<AtelierAction> actions = new ArrayList<>();
        /** Trajectoire du tour (SF-39-03), rejouée au message suivant. */
        List<AtelierToolTrace.Step> trace = new ArrayList<>();
        /** Transcription rendue à l'écran au rechargement (SF-39-17), bornée à la persistance. */
        List<AtelierTurnReport.Block> transcript = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        /** Plus grosse itération observée dans ce tour : majorant de la suivante (D-L8-2). */
        long largestIterationTokens = 0L;
        boolean interrupted = false;
        boolean spendCapReached = false;
        /** Explorations déjà déléguées dans ce message (F-39 / SF-39-14). */
        int delegations = 0;
        String finalText = "";

        log.info("Tour d'atelier ouvert (workspace={}, cible={}, plafond={} étapes)",
                workspaceId, workspace.executionTargetOrDefault(), maxIterations);
        int iterationsUsed = 0;

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            iterationsUsed = iteration + 1;
            if (interruptedTurns.remove(turnKey(userId, workspaceId))) {
                finalText = INTERRUPTED_REPLY;
                interrupted = true;
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                // Frontière sûre : on s'arrête ici plutôt que de laisser tourner des commandes
                // derrière un flux SSE déjà expiré.
                finalText = BUDGET_REACHED_REPLY;
                break;
            }
            // Précisions déposées pendant le tour (F-39 / SF-39-19) : lues ICI, à la frontière sûre,
            // et jamais au milieu d'un appel — modifier la conversation pendant qu'elle part au
            // fournisseur serait la façon la plus sûre de la corrompre (D2).
            for (String steer : consumeSteers(userId, workspaceId)) {
                messages.add(AgentMessage.userText(steer));
            }
            // Plafond de consommation du message (F-39 / SF-39-15). On renonce AVANT l'appel qui
            // ferait franchir le plafond, l'itération à venir étant majorée par la plus grosse déjà
            // observée : rien ici ne peut refuser un appel une fois parti, et le constater après
            // coup autoriserait une itération entière au-delà du plafond (D-L8-2). La première
            // itération n'est jamais refusée (D-L8-3).
            if (turnBudget.exceededBy((long) inputTokens + outputTokens, largestIterationTokens)) {
                finalText = SPEND_CAP_REPLY;
                spendCapReached = true;
                break;
            }
            AgentTurn turn = agentProvider.nextTurn(
                    new AgentTurnRequest(model, system, messages, tools, apiKey, reasoning, contextPolicy));
            inputTokens += turn.inputTokens();
            outputTokens += turn.outputTokens();
            largestIterationTokens =
                    Math.max(largestIterationTokens, (long) turn.inputTokens() + turn.outputTokens());
            // Consommation relayée au fil de l'eau : c'est ce qui fait apparaître les tokens dans la
            // ligne vivante du terminal (acquis §4 n°5), jusqu'ici muette sur la boucle maison.
            listener.onProgress((long) inputTokens + outputTokens);

            // Réponse coupée au plafond de sortie (SF-28-18) : ses blocs sont incomplets. On
            // n'exécute AUCUN de ses outils — rien ne distingue de façon fiable un `tool_use` complet
            // d'un `tool_use` coupé au bon endroit, et écrire un fichier au contenu tronqué
            // remplacerait un échec silencieux par un dégât silencieux (décision D3).
            if (turn.truncated()) {
                finalText = TRUNCATED_REPLY;
                break;
            }
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
            // Le raisonnement d'abord, tel quel (SF-39-10, décision D-L5-3) : le fournisseur exige de
            // retrouver ses blocs signés, inchangés et dans l'ordre, sur le dernier tour d'assistant
            // quand on lui renvoie les tool_result. Ils vivent le temps du tour et ne sont jamais
            // persistés : d'un message à l'autre, le raisonnement des tours passés n'est plus rejoué.
            assistantBlocks.addAll(turn.reasoning());
            if (turn.text() != null && !turn.text().isBlank()) {
                assistantBlocks.add(new AgentContentBlock.Text(turn.text()));
            }
            List<AgentContentBlock> toolResults = new ArrayList<>();
            List<AtelierToolTrace.Call> tracedCalls = new ArrayList<>();
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
                ToolOutcome outcome;
                if ("explore".equals(call.name())) {
                    // Délégation (F-39 / SF-39-14) : bornée en nombre, et sa consommation revient
                    // dans les compteurs du TOUR — déléguer ne doit jamais permettre de passer sous
                    // le plafond par message (D4).
                    if (delegations >= maxDelegations) {
                        outcome = ToolOutcome.error("Limite de délégations atteinte pour ce message ("
                                + maxDelegations + ") : poursuis toi-même.");
                    } else {
                        delegations++;
                        ExplorationOutcome explored =
                                explore(userId, workspace, call, model, apiKey, deadline);
                        inputTokens += explored.inputTokens();
                        outputTokens += explored.outputTokens();
                        listener.onProgress((long) inputTokens + outputTokens);
                        outcome = explored.outcome();
                    }
                } else {
                    outcome = executeTool(userId, workspace, callId, call, listener, deadline, planOfTurn);
                }
                if (outcome.action() != null) {
                    actions.add(outcome.action());
                }
                toolResults.add(new AgentContentBlock.ToolResult(callId, outcome.content(), outcome.isError()));
                // Mémoire du tour (SF-39-03) : l'appel ET son résultat, appariés — le fournisseur
                // refuse un tool_use orphelin au rejeu.
                tracedCalls.add(new AtelierToolTrace.Call(callId, call.name(), call.input(),
                        AtelierToolTrace.boundResult(outcome.content()), outcome.isError()));
                // Transcription du tour (SF-39-17) : ce que l'écran relit après un rechargement.
                // Elle ne l'était pas, et une coupure de connexion effaçait tout ce qui s'était
                // passé — l'acquis §4 n°7 de F-30 ne valait pas pour le moteur qui exécute.
                transcript.add(new AtelierTurnReport.Block(call.name(), auditTarget(call), callId,
                        null, outcome.content() == null ? "" : outcome.content(),
                        outcome.content() != null, outcome.isError(), false));
            }
            messages.add(AgentMessage.assistant(assistantBlocks));
            messages.add(AgentMessage.toolResults(toolResults));
            trace.add(new AtelierToolTrace.Step(turn.text(), List.copyOf(tracedCalls)));

            if (interruptedTurns.remove(turnKey(userId, workspaceId))) {
                // Interruption arrivée pendant les outils de ce tour : on s'arrête sans rappeler le
                // fournisseur — l'appel runner en vol a déjà reçu son tool_cancel.
                finalText = INTERRUPTED_REPLY;
                interrupted = true;
                break;
            }
            if (iteration == maxIterations - 1) {
                finalText = (turn.text() == null || turn.text().isBlank())
                        ? "J'ai atteint la limite d'étapes pour ce message ; relance-moi pour continuer."
                        : turn.text();
            }
        }

        if (hosted) {
            quotaService.recordUsage(userId, inputTokens, outputTokens);
        }

        // Jamais de message vide dans l'historique (SF-28-18) : il serait relu au tour suivant et
        // refusé par le fournisseur, condamnant le projet.
        log.info("Tour d'atelier terminé : {} étape(s), {} s, {} tokens — arrêt : {}",
                iterationsUsed, Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L),
                inputTokens + outputTokens, stopCause(interrupted, spendCapReached, finalText));
        String reply = nonEmptyReply(finalText);
        long activeSeconds = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
        // Relevé du tour rangé dans la colonne d'affichage existante (F-39 / SF-39-15, D-L8-6) :
        // sans lui, le coût du tour et le motif de son arrêt disparaîtraient au rechargement — et
        // c'est précisément après un rechargement qu'on se demande pourquoi un tour s'est arrêté.
        AtelierTurnReport report = new AtelierTurnReport(inputTokens, outputTokens, activeSeconds,
                interrupted, spendCapReached, planOfTurn.get(), List.copyOf(transcript));
        AtelierMessage assistant = messageRepository.save(AtelierMessage.builder()
                .workspaceId(workspaceId).userId(userId).role("ASSISTANT")
                .content(reply)
                .toolTrace(new AtelierToolTrace(List.copyOf(trace)).toJson())
                .terminalJson(report.toJson())
                .build());

        return new AtelierChatResult(reply, actions, assistant.getId(), inputTokens, outputTokens,
                activeSeconds, spendCapReached);
    }

    /**
     * Historique <b>rejouable</b> auprès du fournisseur (SF-28-18) : les messages de l'atelier, moins
     * ceux qu'il refuserait.
     *
     * <p>Deux filtres, et deux seulement :</p>
     * <ul>
     *   <li>les messages au contenu <b>blanc</b> sont écartés — l'API rejette un bloc de texte vide
     *       ({@code 400 "text content blocks must be non-empty"}), et un seul suffit à rendre muet
     *       tout le projet. Ceux déjà écrits en base avant SF-28-18 sont ainsi neutralisés sans
     *       toucher à la base, ni à ce que l'écran montre de l'historique (décision D4) ;</li>
     *   <li>les messages <b>assistant en tête</b> sont écartés — une conversation doit commencer par
     *       un message utilisateur. Le cas ne se produit qu'après le filtre précédent, ou après une
     *       suppression manuelle.</li>
     * </ul>
     *
     * <p>Deux messages {@code user} consécutifs, eux, sont acceptés par le fournisseur (vérifié) :
     * retirer un assistant au milieu ne casse donc pas l'échange.</p>
     */
    private static List<AgentMessage> replayableHistory(List<AtelierMessage> past) {
        int traceFrom = firstTracedIndex(past);
        List<AgentMessage> messages = new ArrayList<>(past.size());
        for (int index = 0; index < past.size(); index++) {
            AtelierMessage message = past.get(index);
            String content = message.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            boolean assistant = "ASSISTANT".equalsIgnoreCase(message.getRole());
            if (assistant && messages.isEmpty()) {
                continue;
            }
            if (assistant && index >= traceFrom) {
                // Trajectoire du tour (SF-39-03) rejouée AVANT sa réponse finale : l'agent retrouve
                // ce qu'il a fait, au lieu de le refaire. Une trajectoire illisible rend une liste
                // vide et le message retombe sur le texte seul, comme avant.
                messages.addAll(AtelierToolTrace.fromJson(message.getToolTrace()).replay());
            }
            messages.add(new AgentMessage(assistant ? "assistant" : "user",
                    List.of(new AgentContentBlock.Text(content))));
        }
        return messages;
    }

    /**
     * Index à partir duquel un message assistant est rejoué <b>avec</b> sa trajectoire d'outils
     * (SF-39-03) : les {@value #REPLAYED_TRACE_TURNS} derniers tours, pas davantage.
     */
    private static int firstTracedIndex(List<AtelierMessage> past) {
        int seen = 0;
        for (int index = past.size() - 1; index >= 0; index--) {
            if ("ASSISTANT".equalsIgnoreCase(past.get(index).getRole())) {
                seen++;
                if (seen == REPLAYED_TRACE_TURNS) {
                    return index;
                }
            }
        }
        return 0;
    }

    /**
     * Cause d'arrêt du tour, en un mot, pour le journal (SF-39-17). Déduite des drapeaux et des
     * réponses de repli — jamais du texte du modèle, qui n'a rien à faire dans un journal serveur.
     */
    private static String stopCause(boolean interrupted, boolean spendCapReached, String finalText) {
        if (interrupted) {
            return "interruption";
        }
        if (spendCapReached) {
            return "plafond de consommation";
        }
        if (BUDGET_REACHED_REPLY.equals(finalText)) {
            return "budget de temps";
        }
        if (TRUNCATED_REPLY.equals(finalText)) {
            return "réponse coupée au plafond de sortie";
        }
        return "réponse rendue";
    }

    /** Réponse à persister : celle du tour, ou un texte explicite si le tour n'a rien produit. */
    private static String nonEmptyReply(String finalText) {
        return finalText == null || finalText.isBlank() ? EMPTY_REPLY_FALLBACK : finalText;
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
    /**
     * Pose la marque « tout autoriser » <b>sur ce pod</b> (F-38 / SF-38-20). Appelée par le clic
     * local et par le relais interne, pour que la boucle la trouve où qu'elle tourne.
     */
    public void allowAllLocally(UUID userId, UUID workspaceId) {
        blanketAllowedTurns.add(turnKey(userId, workspaceId));
    }

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
        confirmToolUse(userId, workspaceId, toolUseId, allow, reason, false);
    }

    /**
     * Variante qui accepte une décision <b>groupée</b> (F-38 / SF-38-20) : « tout autoriser pour ce
     * message ».
     *
     * <p>La marque est posée <b>avant</b> de résoudre la demande en attente, pour que la commande
     * suivante la trouve déjà là. Elle est aussi <b>diffusée</b> aux pods pairs, par le même chemin
     * que la décision elle-même : la boucle tourne peut-être ailleurs que là où ce clic atterrit
     * (SF-38-13).</p>
     */
    public void confirmToolUse(UUID userId, UUID workspaceId, String toolUseId, boolean allow,
            String reason, boolean allowAll) {
        workspaceService.requireOwned(userId, workspaceId);
        if (allowAll && allow) {
            blanketAllowedTurns.add(turnKey(userId, workspaceId));
            relayBroadcaster.broadcastConfirm(userId, workspaceId, callIdOf(toolUseId), true, reason);
            // La marque est l'essentiel : « tout autoriser » reste valable même si la demande qui
            // l'a déclenchée vient d'expirer, ou si la boucle n'en attendait plus aucune.
            try {
                confirmationGate.resolve(userId, workspaceId, callIdOf(toolUseId), true, reason);
            } catch (fr.claudegateway.runner.exec.NoPendingConfirmationException ignored) {
                // Rien n'attendait : la marque vaut pour les commandes à venir.
            }
            return;
        }
        String callId = toolUseId == null ? "" : toolUseId.trim();
        try {
            confirmationGate.resolve(userId, workspaceId, callId, allow, reason);
        } catch (fr.claudegateway.runner.exec.NoPendingConfirmationException ex) {
            if (!relayBroadcaster.broadcastConfirm(userId, workspaceId, callId, allow, reason)) {
                throw ex;
            }
        }
    }

    /**
     * Applique un appel {@code set_plan} (F-39 / SF-39-13) : le plan est normalisé, relayé à
     * l'écran, et rendu au modèle sous forme de compte rendu.
     *
     * <p>Aucun chemin d'échec : un plan mal formé est corrigé, jamais refusé (décision D2). Le
     * résultat dit au modèle ce qui a été retenu, ce qui lui permet de se corriger lui-même.</p>
     */
    private ToolOutcome applyPlan(AgentToolCall call, AtelierProgressListener listener,
            java.util.concurrent.atomic.AtomicReference<AtelierPlan> planOfTurn) {
        JsonNode stepsNode = call.input() == null ? null : call.input().get("steps");
        AtelierPlan plan = AtelierPlan.from(stepsNode);
        int submitted = stepsNode != null && stepsNode.isArray() ? stepsNode.size() : 0;
        // Le plan vit dans le TOUR, jamais dans le service : celui-ci est un singleton partagé par
        // tous les utilisateurs, et un champ d'instance ferait fuiter le plan de l'un chez l'autre.
        planOfTurn.set(plan);
        listener.onPlan(plan);
        return ToolOutcome.info(plan.acknowledgement(submitted));
    }

    /**
     * Exécute une délégation d'exploration (F-39 / SF-39-14) : une sous-boucle en lecture seule dont
     * <b>seule la réponse</b> revient ici. Ce qu'elle a lu reste chez elle — c'est tout l'intérêt.
     *
     * <p>Sa consommation est rendue à l'appelant pour être <b>ajoutée à celle du tour</b> (D4) :
     * elle n'a ni quota propre, ni plafond propre. Une panne dans la sous-boucle devient un résultat
     * d'outil en erreur, jamais un échec du tour principal : c'est une aide, et quand elle échoue,
     * l'agent doit pouvoir faire le travail lui-même.</p>
     */
    private ExplorationOutcome explore(UUID userId, Workspace workspace, AgentToolCall call,
            String model, String apiKey, long deadline) {
        String question = call.input() == null ? null : call.input().path("question").asText(null);
        if (question == null || question.isBlank()) {
            return new ExplorationOutcome(ToolOutcome.error("Question requise pour explorer."), 0, 0);
        }
        String scope = call.input().path("path").asText(null);
        // Outils de la sous-boucle : lecture seule, et cela vaut aussi en cible RUNNER (D2).
        List<AgentTool> readTools = buildTools(workspace).stream()
                .filter(tool -> READ_ONLY_TOOLS.contains(tool.name()))
                .toList();
        try {
            AtelierExploration.Result result = AtelierExploration.run(agentProvider, model, apiKey,
                    question.trim(), scope, readTools,
                    subCall -> {
                        ToolOutcome outcome = READ_ONLY_TOOLS.contains(subCall.name())
                                ? executeTool(userId, workspace, UUID.randomUUID().toString(), subCall,
                                        AtelierProgressListener.NOOP, deadline,
                                        new java.util.concurrent.atomic.AtomicReference<>(AtelierPlan.EMPTY))
                                : ToolOutcome.error("Outil indisponible en exploration : " + subCall.name());
                        return new AtelierExploration.ExecutedTool(outcome.content(), outcome.isError());
                    },
                    () -> interruptedTurns.contains(turnKey(userId, workspace.getId()))
                            || System.currentTimeMillis() >= deadline);
            return new ExplorationOutcome(ToolOutcome.info(result.answer()),
                    result.inputTokens(), result.outputTokens());
        } catch (RuntimeException ex) {
            return new ExplorationOutcome(
                    ToolOutcome.error("L'exploration a échoué ; poursuis toi-même."), 0, 0);
        }
    }

    /** Issue d'une délégation : le résultat rendu au modèle, et ce qu'elle a consommé. */
    private record ExplorationOutcome(ToolOutcome outcome, int inputTokens, int outputTokens) {
    }

    /**
     * Dépose une précision pour le tour en cours (F-39 / SF-39-19). Elle sera lue au début de
     * l'itération suivante — l'agent la voit donc à l'étape d'après, sans que rien s'arrête.
     *
     * <p>Isolation d'abord : un projet qu'on ne possède pas rend 404, jamais un refus qui
     * révélerait son existence.</p>
     *
     * @throws WorkspaceNotFoundException si le workspace n'est pas possédé
     * @throws InvalidFilePathException si le message est vide ou trop long
     * @throws TooManySteersException si trop de précisions attendent déjà
     */
    public void steer(UUID userId, UUID workspaceId, String rawMessage) {
        workspaceService.requireOwned(userId, workspaceId);
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.isEmpty()) {
            throw new InvalidFilePathException("Message requis.");
        }
        if (message.length() > MAX_STEER_CHARS) {
            throw new InvalidFilePathException(
                    "Message trop long (" + MAX_STEER_CHARS + " caractères au maximum).");
        }
        java.util.List<String> queue = pendingSteers.computeIfAbsent(turnKey(userId, workspaceId),
                key -> java.util.Collections.synchronizedList(new ArrayList<>()));
        synchronized (queue) {
            if (queue.size() >= MAX_PENDING_STEERS) {
                throw new TooManySteersException(
                        "Trop de précisions en attente pour ce message ; laissez-le avancer.");
            }
            queue.add(message);
        }
        // La boucle tourne peut-être sur un autre pod que celui qui reçoit ce dépôt (SF-38-13).
        relayBroadcaster.broadcastSteer(userId, workspaceId, message);
    }

    /** Dépose une précision <b>sur ce pod</b> — appelé par le relais interne. */
    public void steerLocally(UUID userId, UUID workspaceId, String message) {
        java.util.List<String> queue = pendingSteers.computeIfAbsent(turnKey(userId, workspaceId),
                key -> java.util.Collections.synchronizedList(new ArrayList<>()));
        synchronized (queue) {
            if (queue.size() < MAX_PENDING_STEERS) {
                queue.add(message);
            }
        }
    }

    /** Précisions en attente, retirées du registre : elles ne sont ajoutées qu'une fois. */
    private java.util.List<String> consumeSteers(UUID userId, UUID workspaceId) {
        java.util.List<String> queue = pendingSteers.remove(turnKey(userId, workspaceId));
        return queue == null ? java.util.List.of() : java.util.List.copyOf(queue);
    }

    /** Identifiant d'appel normalisé, tel que la porte l'attend. */
    private static String callIdOf(String toolUseId) {
        return toolUseId == null ? "" : toolUseId.trim();
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
            // SF-39-06 (D4) : une édition ciblée est une écriture pour l'écran — c'est ce qui
            // déclenche le rafraîchissement du fichier ouvert. Le journal, lui, garde le nom réel.
            case "edit_file" -> new AtelierProgressListener.AtelierStepEvent("write", arg(input, "path"));
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
            AtelierProgressListener listener, long deadline,
            java.util.concurrent.atomic.AtomicReference<AtelierPlan> planOfTurn) {
        // Le plan ne s'exécute nulle part : il ne touche ni la machine, ni le stockage. Il est donc
        // traité AVANT le routage par cible (F-39 / SF-39-13).
        if ("set_plan".equals(call.name())) {
            return applyPlan(call, listener, planOfTurn);
        }
        if (workspace.isRunnerTarget()) {
            return executeToolOnRunner(userId, workspace, callId, call, listener, deadline);
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
    private ToolOutcome executeToolOnRunner(UUID userId, Workspace workspace, String callId,
            AgentToolCall call, AtelierProgressListener listener, long deadline) {
        UUID workspaceId = workspace.getId();
        String tool = call.name();
        String target = auditTarget(call);
        // Deux façons de ne plus être interrompu, l'une bornée au message, l'autre au projet
        // (F-38 / SF-38-20). Dans les deux cas, l'audit continue de tout tracer.
        if (requiresConfirmation(tool, workspace)
                && !blanketAllowedTurns.contains(turnKey(userId, workspace.getId()))) {
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
     * <b>amendé par SF-38-20</b>).
     *
     * <p>SF-38-08 avait rendu la porte non désactivable en cible {@code RUNNER}. Le banc d'essai a
     * montré le prix de cette rigidité : une procédure de treize étapes demande des dizaines de
     * clics, et une garde qu'on subit finit par être contournée plutôt que respectée. Le réglage
     * {@code agent_ask_before_bash} du projet est donc <b>consulté</b> — c'est une décision de
     * l'utilisateur sur sa propre machine, prise en connaissance de cause.</p>
     *
     * <p>Ce qui ne change pas : le <b>journal d'audit</b> trace chaque commande, autorisée ou non,
     * et le <b>coupe-circuit</b> reste immédiat. Ce qui disparaît est le clic, pas la trace.</p>
     *
     * <p>Les écritures de fichier n'y sont toujours pas soumises : c'est l'usage central du mode
     * (l'agent édite le projet), et un clic par écriture pousserait à chercher un contournement.</p>
     */
    private static boolean requiresConfirmation(String tool, Workspace workspace) {
        return "bash".equals(tool) && workspace.isAgentAskBeforeBash();
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
            case "edit_file" -> editFileOnRunner(workspaceId, callId, input);
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
            case "read_file" -> readOutcome(result, input);
            case "edit_file" -> result.ok()
                    ? new ToolOutcome(result.content(), false, new AtelierAction("write", arg(input, "path")))
                    : ToolOutcome.error(result.errorMessage());
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
            case "read_file", "write_file", "edit_file" -> arg(input, "path");
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

    /**
     * Lecture d'un fichier de la machine, rendue en lignes numérotées et paginées (SF-39-06). Le
     * marqueur de troncature du runner est conservé : une lecture partielle doit rester visible,
     * sans quoi l'agent croirait avoir lu tout le fichier.
     */
    private ToolOutcome readOutcome(RunnerCallResult result, JsonNode input) {
        if (!result.ok()) {
            return ToolOutcome.error(result.errorMessage());
        }
        String page;
        try {
            page = AtelierFileText.numbered(result.content(), intArg(input, "offset"), intArg(input, "limit"));
        } catch (RuntimeException ex) {
            return ToolOutcome.error(ex.getMessage());
        }
        String content = result.truncated() ? page + "\n… (contenu tronqué)" : page;
        return new ToolOutcome(content, false, new AtelierAction("read", arg(input, "path")));
    }

    /**
     * Édition ciblée sur la machine de l'utilisateur (SF-39-06) : lire, remplacer, réécrire — avec
     * les primitives que le runner expose déjà, donc <b>sans</b> évolution du protocole (D1).
     *
     * <p>Une lecture <b>tronquée</b> arrête l'opération (D2) : appliquer un remplacement sur un
     * fragment puis le réécrire détruirait la fin du fichier, en silence. C'est le seul cas où
     * l'outil refuse ce que le modèle croit possible, et il le dit.</p>
     */
    private RunnerCallResult editFileOnRunner(UUID workspaceId, String callId, JsonNode input) {
        String path = requiredArg(input, "path");
        // Identifiant propre pour la lecture interne : deux trames ne partagent jamais une clef de
        // corrélation (contrat de messages §1). L'appel visible reste l'écriture.
        RunnerCallResult read = runnerToolGateway.readFile(workspaceId, UUID.randomUUID().toString(), path);
        if (!read.ok()) {
            return read;
        }
        if (read.truncated()) {
            return RunnerCallResult.backendError(RunnerErrorCodes.INVALID_INPUT,
                    "Fichier trop volumineux pour une édition ciblée : la lecture a été tronquée.");
        }
        AtelierFileText.Edit edit;
        try {
            edit = AtelierFileText.replace(read.content(), requiredArg(input, "old_string"),
                    input.path("new_string").asText(""), input.path("replace_all").asBoolean(false));
        } catch (RuntimeException ex) {
            return RunnerCallResult.backendError(RunnerErrorCodes.INVALID_INPUT, ex.getMessage());
        }
        RunnerCallResult written = runnerToolGateway.writeFile(workspaceId, callId, path, edit.content());
        if (!written.ok()) {
            return written;
        }
        return new RunnerCallResult(true, editedMessage(path, edit.replacements()), false, null,
                written.durationMs(), written.bytes(), null, null, "", false);
    }

    /** Message rendu au modèle après une édition ciblée : ce qui a changé, et combien de fois. */
    private static String editedMessage(String path, int replacements) {
        return "Fichier modifié : " + path + " (" + replacements + " remplacement"
                + (replacements > 1 ? "s)" : ")");
    }

    /** Extrait un argument entier d'un input d'outil, ou {@code null} s'il est absent/illisible. */
    private static Integer intArg(JsonNode input, String name) {
        if (input == null || !input.path(name).isInt()) {
            return null;
        }
        return input.path(name).asInt();
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
                    yield new ToolOutcome(AtelierFileText.numbered(content, intArg(input, "offset"),
                            intArg(input, "limit")), false, new AtelierAction("read", path));
                }
                case "edit_file" -> {
                    String path = requiredArg(input, "path");
                    AtelierFileText.Edit edit = AtelierFileText.replace(
                            workspaceService.readFile(userId, workspaceId, path),
                            requiredArg(input, "old_string"), input.path("new_string").asText(""),
                            input.path("replace_all").asBoolean(false));
                    workspaceService.writeFile(userId, workspaceId, path, edit.content());
                    yield new ToolOutcome(editedMessage(path, edit.replacements()), false,
                            new AtelierAction("write", path));
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
        List<AgentTool> tools = new ArrayList<>(fileTools(stringProp, workspace.isRunnerTarget()));
        if (workspace.isRunnerTarget()) {
            tools.add(new AgentTool("bash",
                    "Exécute une commande shell sur la machine connectée (runner), depuis la racine "
                            + "du projet. Renvoie la sortie (stdout et stderr) et le code de sortie.",
                    Map.of("type", "object",
                            "properties", Map.of("command", stringProp, "cwd", stringProp),
                            "required", List.of("command"))));
        }
        // L'exploration est déclarée sur les deux cibles, et seulement si elle est autorisée
        // (F-39 / SF-39-14). Elle absorbe le volume de lecture qui, sinon, remplit le contexte du
        // travail principal.
        if (maxDelegations > 0) {
            tools.add(new AgentTool("explore",
                    "Délègue une exploration en LECTURE SEULE à un agent qui ne voit pas cette "
                            + "conversation : il lit, cherche, et te rend une réponse courte. Utile "
                            + "quand répondre demande de parcourir beaucoup de fichiers dont tu n'as "
                            + "pas besoin ensuite. Il ne peut ni écrire, ni exécuter de commande.",
                    Map.of("type", "object",
                            "properties", Map.of("question", stringProp, "path", stringProp),
                            "required", List.of("question"))));
        }
        // Le plan est déclaré sur les DEUX cibles : c'est un outil d'organisation, pas d'exécution
        // (F-39 / SF-39-13). Rien de ce qu'il fait ne dépend de l'endroit où le code tourne.
        tools.add(new AgentTool("set_plan",
                "Pose ou met à jour ton plan de travail pour ce message. Envoie la liste COMPLÈTE des "
                        + "étapes à chaque appel : elle remplace la précédente. Marque une seule étape "
                        + "active à la fois, et mets-la à jour dès qu'une étape est terminée. "
                        + "Utile dès que le travail dépasse deux ou trois étapes ; inutile sinon.",
                Map.of("type", "object",
                        "properties", Map.of("steps", Map.of(
                                "type", "array",
                                "items", Map.of("type", "object",
                                        "properties", Map.of(
                                                "title", Map.of("type", "string"),
                                                "status", Map.of("type", "string",
                                                        "enum", List.of("pending", "active", "done"))),
                                        "required", List.of("title")))),
                        "required", List.of("steps"))));
        return List.copyOf(tools);
    }

    /**
     * Outils fichiers déclarés au modèle (F-39 / SF-39-05, décision D4 du cadrage).
     *
     * <p>Quand {@code bash} est disponible — cible {@code RUNNER} — {@code list_files} et
     * {@code search_files} ne sont <b>pas</b> déclarés : {@code ls}, {@code find} et {@code grep -n}
     * font strictement mieux (filtres, profondeur, expressions régulières, numéros de ligne), et
     * l'usage réel mesuré est déjà à 95 % de {@code bash}. Deux définitions de moins, ce sont deux
     * définitions qu'on ne paie plus à chaque itération dans le préfixe caché.</p>
     *
     * <p>En cible {@code SANDBOX}, elles restent : il n'y a pas de {@code bash} là-bas, et les
     * retirer priverait le modèle de tout moyen d'explorer sans rien lui donner en échange
     * (décision D1 de la subfeature). Leur sort suit celui de la cible elle-même, en SF-39-16.</p>
     */
    private List<AgentTool> fileTools(Map<String, Object> stringProp, boolean bashAvailable) {
        List<AgentTool> tools = new ArrayList<>();
        if (!bashAvailable) {
            tools.add(new AgentTool("list_files", "Liste tous les fichiers du projet (chemins relatifs).",
                    Map.of("type", "object", "properties", Map.of())));
        }
        Map<String, Object> intProp = Map.of("type", "integer");
        tools.add(new AgentTool("read_file",
                "Lit un fichier du projet en lignes numérotées. Pagine avec offset (première ligne, "
                        + "1 par défaut) et limit (2000 au plus).",
                Map.of("type", "object",
                        "properties", Map.of("path", stringProp, "offset", intProp, "limit", intProp),
                        "required", List.of("path"))));
        tools.add(new AgentTool("write_file", "Écrit (ou remplace) le contenu texte d'un fichier du projet.",
                Map.of("type", "object",
                        "properties", Map.of("path", stringProp, "content", stringProp),
                        "required", List.of("path", "content"))));
        tools.add(new AgentTool("edit_file",
                "Remplace un passage exact dans un fichier du projet. old_string doit être unique, "
                        + "sinon passe replace_all à true. À préférer à write_file pour modifier un fichier.",
                Map.of("type", "object",
                        "properties", Map.of("path", stringProp, "old_string", stringProp,
                                "new_string", stringProp, "replace_all", Map.of("type", "boolean")),
                        "required", List.of("path", "old_string", "new_string"))));
        if (!bashAvailable) {
            tools.add(new AgentTool("search_files", "Recherche une chaîne dans les fichiers du projet.",
                    Map.of("type", "object", "properties", Map.of("query", stringProp),
                            "required", List.of("query"))));
        }
        return List.copyOf(tools);
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
        // L'énoncé du rôle suit l'outillage réellement déclaré (SF-39-05) : annoncer des outils qui
        // n'existent pas dans ce projet ne produirait que des appels perdus.
        if (workspace.isRunnerTarget()) {
            system.append("Tu es un assistant de développement qui travaille sur le projet de l'utilisateur, ")
                    .append("sur sa machine. Explore avec bash (ls, find, grep -n) — c'est le bon outil pour ")
                    .append("lister, chercher et vérifier. Utilise read_file pour lire un fichier que tu vas ")
                    .append("utiliser, et write_file pour l'écrire. Ne fais aucune supposition sur un fichier ")
                    .append("sans l'avoir lu. Après une modification, résume clairement ce que tu as changé.\n\n");
        } else {
            system.append("Tu es un assistant de développement qui travaille sur le projet de l'utilisateur, ")
                    .append("dans un espace de travail hébergé. Utilise les outils fournis (list_files, read_file, ")
                    .append("write_file, search_files) pour lire et modifier les fichiers du projet. ")
                    .append("Ne fais aucune supposition sur un fichier sans l'avoir lu. Après une modification, ")
                    .append("résume clairement ce que tu as changé.\n\n");
        }

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
        List<String> skillPaths = tree.stream().filter(AtelierChatService::isSkillPath).toList();
        StringBuilder catalog = new StringBuilder();
        for (String path : skillPaths.stream().limit(MAX_SKILLS_ANNOUNCED).toList()) {
            java.util.Optional<String> skill = readOptional(userId, workspace, path);
            if (skill.isEmpty()) {
                continue; // Skill illisible : ignoré, jamais bloquant pour les autres.
            }
            reads++;
            chars += skill.get().length();
            String description = describeSkill(skill.get());
            catalog.append("- ").append(path);
            if (!description.isEmpty()) {
                catalog.append(" : ").append(description);
            }
            catalog.append('\n');
        }
        if (catalog.length() > 0) {
            system.append("--- Skills du projet (lis le fichier pour le mode d'emploi complet) ---\n")
                    .append(catalog);
            if (skillPaths.size() > MAX_SKILLS_ANNOUNCED) {
                system.append("… et ").append(skillPaths.size() - MAX_SKILLS_ANNOUNCED)
                        .append(" autre(s) skill(s) non listé(s).\n");
            }
            system.append("Ouvre un skill avec read_file au moment où il sert ; ne suppose pas son contenu.\n\n");
        }
        if (workspace.isRunnerTarget()) {
            runnerAuditService.recordBootstrap(userId, workspace.getId(),
                    UUID.randomUUID().toString(), reads, chars);
        }
        String result = system.toString();
        return result.length() > SYSTEM_MAX_CHARS ? result.substring(0, SYSTEM_MAX_CHARS) : result;
    }

    /** Un fichier du projet est-il un skill ? Mêmes préfixes qu'avant SF-39-02. */
    private static boolean isSkillPath(String path) {
        return SKILL_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /**
     * Description d'un skill pour le catalogue (F-39 / SF-39-02) : la clef {@code description} de
     * l'entête YAML si le fichier en a un, sinon sa première ligne utile — titres Markdown et
     * délimiteurs d'entête exclus, car un titre répète le nom du fichier sans rien apprendre.
     *
     * <p>Le résultat est toujours <b>une ligne</b> et borné : le catalogue est le préfixe qu'on
     * cherche à garder court et stable, une description de dix lignes le ruinerait.</p>
     *
     * @return la description, ou une chaîne vide s'il n'y en a pas d'exploitable
     */
    static String describeSkill(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String[] lines = body.split("\n", -1);
        if (lines[0].strip().equals("---")) {
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.strip().equals("---")) {
                    break; // Fin de l'entête : pas de clef description.
                }
                if (line.regionMatches(true, 0, "description:", 0, "description:".length())) {
                    return flatten(unquote(line.substring("description:".length())));
                }
            }
        }
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.equals("---") || stripped.startsWith("#")) {
                continue;
            }
            return flatten(stripped);
        }
        return "";
    }

    /** Retire les guillemets d'une valeur YAML simple ({@code description: "…"}). */
    private static String unquote(String value) {
        String text = value.strip();
        boolean quoted = text.length() >= 2
                && (text.startsWith("\"") && text.endsWith("\"")
                        || text.startsWith("'") && text.endsWith("'"));
        return quoted ? text.substring(1, text.length() - 1) : text;
    }

    /** Une ligne, espaces normalisés, bornée — avec un « … » quand la coupe a eu lieu. */
    private static String flatten(String text) {
        String line = text.replaceAll("\\s+", " ").strip();
        return line.length() <= SKILL_DESCRIPTION_CHARS
                ? line
                : line.substring(0, SKILL_DESCRIPTION_CHARS) + "…";
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

    /**
     * Résultat d'un tour d'atelier.
     *
     * @param reply         réponse finale rendue à l'utilisateur
     * @param actions       fichiers lus/écrits pendant le tour
     * @param messageId     identifiant du message assistant persisté
     * @param inputTokens   tokens d'entrée du tour, cache compris (F-39 / SF-39-15)
     * @param outputTokens  tokens de sortie du tour
     * @param activeSeconds durée d'horloge du tour, en secondes
     * @param budgetReached le tour s'est arrêté sur le <b>plafond de consommation</b> du message —
     *                      jamais sur le budget de temps, qui dit déjà sa cause dans {@code reply}
     */
    public record AtelierChatResult(String reply, List<AtelierAction> actions, UUID messageId,
            long inputTokens, long outputTokens, long activeSeconds, boolean budgetReached) {

        /** Forme historique, conservée pour les appelants (et les tests) qui l'attendent. */
        public AtelierChatResult(String reply, List<AtelierAction> actions, UUID messageId) {
            this(reply, actions, messageId, 0L, 0L, 0L, false);
        }
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
