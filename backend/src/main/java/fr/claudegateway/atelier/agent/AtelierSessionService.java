package fr.claudegateway.atelier.agent;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.ProjectInstructions;
import fr.claudegateway.atelier.ProjectInstructionsService;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.agent.ManagedAgentProvider.SessionUsage;
import fr.claudegateway.git.GitTokenMissingException;
import fr.claudegateway.git.GitTokenService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.UsageSnapshot;

/**
 * Orchestration d'un run d'exécution d'atelier sur une session Managed Agents (F-28 / Phase 2,
 * ADR-013, <b>révisé par ADR-014</b>). Réalise le <b>pont fichiers S3⇄session</b> :
 *
 * <ol>
 *   <li>isolation {@code user_id} d'abord ({@link WorkspaceService#requireOwned}) ;</li>
 *   <li><b>session persistante par workspace</b> (F-30 SF-30-04) : ouverte au premier message avec
 *       les fichiers montés, puis <b>réutilisée</b> — la sandbox et son système de fichiers survivent
 *       d'un message à l'autre ({@code npm install} une fois, les tests réutilisent l'installation) ;</li>
 *   <li>message utilisateur, attente de complétion, récupération des sorties ;</li>
 *   <li>réécriture <b>incrémentale</b> des sorties dans le workspace (garde-fous Phase 1) ;</li>
 *   <li>décompte du <b>delta</b> d'usage depuis le relevé précédent (jamais le cumul).</li>
 * </ol>
 *
 * <p>La session n'est plus terminée en {@code finally} : elle est terminée explicitement par
 * {@link #resetSession} (une session {@code idle} n'est pas facturée — ADR-014).</p>
 *
 * <p>Provider Independence : ne dépend que de {@link ManagedAgentProvider} (jamais d'Anthropic).
 * <b>Aucun endpoint exposé</b> (SF-28-10) ; service interne activé par flag
 * ({@code app.atelier.agent.enabled}). Flag off ⇒ refus <b>avant tout appel réseau</b>.</p>
 */
@Service
public class AtelierSessionService {

    private static final Logger log = LoggerFactory.getLogger(AtelierSessionService.class);

    /** Préfixe de montage des fichiers du workspace dans le bac à sable. */
    private static final String WORKSPACE_MOUNT = "/workspace/";

    /** Point de montage du dépôt cloné (F-31 / SF-31-02) : la racine du projet, sans barre finale. */
    private static final String GIT_MOUNT_PATH = "/workspace";

    /** Préfixe possible des sorties générées par la session (retiré à la réécriture). */
    private static final String OUTPUTS_PREFIX = "/mnt/session/outputs/";

    /**
     * Raison d'arrêt d'un tour stoppé par le <b>plafond de dépense</b> de la session (F-36 SF-36-01).
     * Reconnue sur la racine : le libellé exact n'est pas garanti par le fournisseur, et c'est ce
     * signal — jamais le montant affiché, arrondi au cent — qui dit que la session est en pause.
     */
    private static final String BUDGET_STOP_REASON = "budget";

    /** Diviseur de conversion « tokens → millions de tokens » des tarifs de référence. */
    private static final BigDecimal TOKENS_PER_MILLION = new BigDecimal("1000000");

    /**
     * Numérateur de conversion « unités mineures → tokens » : {@code 1 000 000 / 100}. Le coût est
     * rapporté en cents, le tarif de référence en dollars par million de tokens.
     */
    private static final BigDecimal TOKENS_PER_MINOR_UNIT_NUMERATOR = new BigDecimal("10000");

    private final ManagedAgentProvider provider;
    private final WorkspaceService workspaceService;
    private final AtelierAgentBootstrapService bootstrapService;
    private final AtelierAgentProperties properties;
    private final QuotaService quotaService;
    private final WorkspaceRepository workspaceRepository;
    private final AtelierMessageRepository messageRepository;
    private final GitTokenService gitTokenService;
    private final ProjectInstructionsService instructionsService;
    private final McpVaultService mcpVaultService;
    private final AtelierCostProperties costProperties;

    /** Sérialisation de la transcription persistée (F-30 SF-30-09) : donnée d'affichage. */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Sorties déjà rapatriées, par session (F-30 SF-30-04). Une session persistante expose à chaque
     * tour <b>toutes</b> ses sorties, y compris celles des tours précédents : sans ce registre, chaque
     * tour réécrirait tout le workspace et signalerait comme « modifiés » des fichiers intacts.
     *
     * <p>Volontairement en mémoire : un redémarrage d'instance fait au pire réécrire une fois des
     * contenus identiques (idempotent). Le persister n'apporterait que de la complexité.</p>
     */
    private final Map<String, Set<String>> syncedOutputs = new ConcurrentHashMap<>();

    /**
     * Sessions pour lesquelles une interruption a été demandée (F-32 SF-32-01). Marque
     * d'<b>affichage</b>, jamais un mécanisme d'arrêt : l'arrêt est fait par le fournisseur, qui
     * ramène la session à une frontière sûre. Elle sert seulement à dire au tour en vol, quand il
     * sort de son attente, qu'il a été interrompu plutôt que mené à son terme.
     *
     * <p>Posée sur le thread de requête, consommée sur le pool SSE : d'où l'ensemble concurrent. Elle
     * est <b>remise à zéro à l'ouverture de chaque tour</b>, pour qu'une interruption arrivée hors run
     * ne vienne pas marquer le tour suivant.</p>
     */
    private final Set<String> interruptedSessions = ConcurrentHashMap.newKeySet();

    public AtelierSessionService(ManagedAgentProvider provider, WorkspaceService workspaceService,
            AtelierAgentBootstrapService bootstrapService, AtelierAgentProperties properties,
            QuotaService quotaService, WorkspaceRepository workspaceRepository,
            AtelierMessageRepository messageRepository, GitTokenService gitTokenService,
            ProjectInstructionsService instructionsService, McpVaultService mcpVaultService,
            AtelierCostProperties costProperties) {
        this.provider = provider;
        this.workspaceService = workspaceService;
        this.bootstrapService = bootstrapService;
        this.properties = properties;
        this.quotaService = quotaService;
        this.workspaceRepository = workspaceRepository;
        this.messageRepository = messageRepository;
        this.gitTokenService = gitTokenService;
        this.instructionsService = instructionsService;
        this.mcpVaultService = mcpVaultService;
        this.costProperties = costProperties;
    }

    /**
     * Exécute une tâche d'atelier sur une session Managed Agents éphémère, avec pont fichiers.
     *
     * @param userId      utilisateur propriétaire (isolation)
     * @param workspaceId workspace cible
     * @param message     message/instruction à envoyer à l'agent
     * @return la réponse finale de l'agent + la liste des fichiers réécrits
     * @throws AtelierAgentDisabledException si la Phase 2 est désactivée (aucun appel réseau)
     * @throws fr.claudegateway.atelier.WorkspaceNotFoundException si le workspace n'est pas possédé
     */
    public AtelierSessionResult runTask(UUID userId, UUID workspaceId, String message) {
        // Run non-streamé = run streamé avec un écouteur inerte (aucune régression).
        return runTaskStreaming(userId, workspaceId, message, AtelierAgentListener.NOOP);
    }

    /**
     * Variante <b>streaming</b> de {@link #runTask} : exécute le même run (pont fichiers compris) mais
     * relaie en direct chaque étape (texte de l'agent, usage d'outil, transition d'état) au
     * {@code listener} pendant l'attente de complétion. Les garde-fous restent identiques : isolation
     * {@code user_id} d'abord, flag off avant tout appel réseau, terminaison systématique ({@code finally}).
     *
     * @param userId      utilisateur propriétaire (isolation)
     * @param workspaceId workspace cible
     * @param message     message/instruction à envoyer à l'agent
     * @param listener    écouteur des étapes du run (jamais {@code null} ; {@link AtelierAgentListener#NOOP}
     *                    pour ne rien relayer)
     * @return la réponse finale de l'agent + la liste des fichiers réécrits
     * @throws AtelierAgentDisabledException si la Phase 2 est désactivée (aucun appel réseau)
     * @throws fr.claudegateway.atelier.WorkspaceNotFoundException si le workspace n'est pas possédé
     */
    public AtelierSessionResult runTaskStreaming(UUID userId, UUID workspaceId, String message,
            AtelierAgentListener listener) {
        return run(userId, workspaceId, message, listener, true);
    }

    /**
     * Exécute un tour <b>dans la session déjà ouverte</b>, sans jamais en ouvrir une nouvelle
     * (F-31 / SF-31-04).
     *
     * <p>Réservé aux opérations qui agissent sur un travail déjà fait — la publication sur une
     * branche. Une session neuve repartirait d'un clone vierge : la branche poussée serait identique
     * à la base, et l'utilisateur croirait avoir publié son travail.</p>
     *
     * @param userId      utilisateur propriétaire (isolation)
     * @param workspaceId workspace cible
     * @param message     instruction à exécuter
     * @return la réponse de l'agent + les fichiers réécrits
     * @throws NoActiveSessionException si aucune session n'est en cours, ou si celle-ci n'est plus
     *                                  jouable (elle est alors oubliée)
     */
    public AtelierSessionResult runInExistingSession(UUID userId, UUID workspaceId, String message) {
        return run(userId, workspaceId, message, AtelierAgentListener.NOOP, false);
    }

    private AtelierSessionResult run(UUID userId, UUID workspaceId, String message,
            AtelierAgentListener listener, boolean mayOpenSession) {
        AtelierAgentListener sink = listener == null ? AtelierAgentListener.NOOP : listener;

        // 1. Isolation EN PREMIER : workspace d'un autre user / inexistant ⇒ 404, aucun appel provider.
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);

        // 1 bis. Opération réservée à une session déjà ouverte (F-31 / SF-31-04) : sans elle, il n'y a
        // rien à publier, et le dire tout de suite évite d'engager quoi que ce soit.
        if (!mayOpenSession && (workspace.getAgentSessionId() == null
                || workspace.getAgentSessionId().isBlank())) {
            throw new NoActiveSessionException(
                    "Aucun travail en cours dans ce projet : demandez d'abord une modification à Claude.");
        }

        // 2. Flag off ⇒ refus avant tout appel réseau / coût runtime.
        if (!properties.enabled()) {
            throw new AtelierAgentDisabledException("Atelier Phase 2 désactivé.");
        }

        // 2 bis. Pré-vol quota/plafond (SF-28-12) AVANT toute création OU réutilisation de session :
        // un refus ici n'engage AUCUN coût. Le sandbox est décompté comme les tokens.
        quotaService.assertWithinQuota(userId);
        quotaService.assertWithinSandboxLimit(userId);

        // 3. Config Managed Agents (environment/agent provisionnés une fois).
        AtelierAgentConfig config = bootstrapService.ensureBootstrapped()
                .orElseThrow(() -> new IllegalStateException(
                        "Configuration Managed Agents indisponible (bootstrap requis)."));

        // 4. Pont vers le provider : chaque event relayé est transmis au listener applicatif, et
        // accumulé pour la transcription persistée (F-30 SF-30-09) — reconstruite depuis les events
        // du fournisseur, jamais depuis ce que déclare le client.
        TerminalTranscript transcript = new TerminalTranscript();
        ManagedEventListener bridge = new ManagedEventListener() {
            @Override
            public void onAgentText(String text) {
                sink.onAgentText(text);
            }

            @Override
            public void onAction(String tool, String detail) {
                sink.onAction(tool, detail);
            }

            // Les deux arités sont surchargées : la variante sans fil reste le chemin nominal d'un
            // run séquentiel, et n'a pas à traverser une délégation pour être enregistrée.
            @Override
            public void onAction(String tool, String toolUseId, String detail) {
                onAction(tool, toolUseId, detail, null);
            }

            @Override
            public void onAction(String tool, String toolUseId, String detail, String threadId) {
                transcript.addCommand(tool, toolUseId, detail, threadId);
                sink.onAction(tool, toolUseId, detail, threadId);
            }

            @Override
            public void onActionResult(String tool, String toolUseId, String output, boolean error) {
                onActionResult(tool, toolUseId, output, error, null);
            }

            @Override
            public void onActionResult(String tool, String toolUseId, String output, boolean error,
                    String threadId) {
                transcript.addOutput(tool, toolUseId, output, error, threadId);
                sink.onActionResult(tool, toolUseId, output, error, threadId);
            }

            @Override
            public void onStatus(String state) {
                sink.onStatus(state);
            }

            @Override
            public void onConfirmationRequest(String tool, String confirmationId, String detail) {
                sink.onConfirmationRequest(tool, confirmationId, detail);
            }

            @Override
            public void onConfirmationResolved(String confirmationId, String decision) {
                sink.onConfirmationResolved(confirmationId, decision);
            }
        };

        // 5. Session PERSISTANTE (F-30 SF-30-04) : réutilisée si le workspace en porte une, sinon
        // ouverte avec les fichiers montés. Réutiliser sans remonter les fichiers est délibéré : la
        // sandbox porte l'état laissé par le tour précédent, le remontage l'écraserait.
        String sessionId = workspace.getAgentSessionId();
        SessionRun run;
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                run = runInSession(sessionId, message, bridge);
            } catch (RuntimeException ex) {
                if (!mayOpenSession) {
                    // Publication sur une session morte : on l'oublie et on le dit. Rejouer dans une
                    // session neuve publierait un clone vierge sous couvert de succès.
                    forgetSession(workspace);
                    throw new NoActiveSessionException(
                            "La session de travail s'est terminée : relancez une commande avant de publier.");
                }
                // Session expirée, terminée ou inconnue : on en ouvre une neuve et on rejoue le
                // message UNE fois. Boucler au-delà masquerait une panne réelle du fournisseur.
                log.debug("Session d'atelier injouable, ouverture d'une nouvelle session.");
                sessionId = openSession(userId, workspaceId, config, workspace);
                run = runInSession(sessionId, message, bridge);
            }
        } else {
            // Le cas « aucune session » sans droit d'en ouvrir a déjà été refusé plus haut.
            sessionId = openSession(userId, workspaceId, config, workspace);
            run = runInSession(sessionId, message, bridge);
        }

        // 5 bis. Le tour s'est-il arrêté sur une demande d'interruption (F-32 SF-32-01) ? La marque
        // est consommée ici : elle ne vaut que pour le tour qui vient de sortir de son attente.
        boolean interrupted = consumeInterrupted(sessionId, run.stopReason());

        // 5 ter. Le tour s'est-il arrêté sur le PLAFOND DE DÉPENSE de la session (F-36 SF-36-01) ?
        // Le tour est conservé comme tout autre : il a eu lieu, il est facturé — mais l'écran doit
        // pouvoir le dire, et proposer le rachat plutôt qu'un « réessayez » qui échouerait pareil.
        boolean budgetReached = isBudgetStopReason(run.stopReason());

        // 6. Resync INCRÉMENTAL : une session persistante réexpose ses sorties à chaque tour ; ne
        // réécrire que les nouvelles évite de repasser sur tout le workspace et de signaler comme
        // modifiés des fichiers intacts.
        List<String> changed = new ArrayList<>();
        Set<String> alreadySynced = syncedOutputs.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        Map<String, String> byBasename = basenameIndex(workspaceService.tree(userId, workspaceId));
        Set<String> knownPaths = new HashSet<>(workspaceService.tree(userId, workspaceId));
        for (OutputFile output : provider.listOutputs(sessionId)) {
            if (!alreadySynced.add(output.fileId())) {
                continue;
            }
            byte[] bytes = provider.downloadFile(output.fileId());
            String relPath = resolveOutputPath(normalizePath(output.filename()), knownPaths, byBasename);
            workspaceService.writeFile(userId, workspaceId, relPath, new String(bytes, UTF_8));
            changed.add(relPath);
        }

        // 7. Décompte du DELTA de consommation (F-30 SF-30-04) : `getSessionUsage` renvoie un cumul
        // depuis l'ouverture de la session — recréditer ce cumul à chaque tour ferait payer plusieurs
        // fois la même consommation. Best-effort : la réponse et le resync sont déjà livrés.
        TurnUsage usage = recordSessionUsage(userId, workspaceId, sessionId);

        // 8. Historique (F-30 SF-30-09) : le run a abouti, on conserve la demande, la réponse et la
        // transcription. Un run en échec ne passe jamais ici — cohérent avec l'écran, qui retire le
        // tour optimiste en annonçant que rien n'a été enregistré.
        persistTurn(userId, workspaceId, message, run.reply(), transcript, usage, interrupted, budgetReached);

        log.debug("Run atelier terminé : {} fichier(s) modifié(s).", changed.size());
        return new AtelierSessionResult(run.reply(), changed, usage.inputTokens(), usage.outputTokens(),
                usage.activeSeconds(), interrupted, budgetReached);
    }

    /**
     * Conserve le tour dans l'historique (F-30 SF-30-09) : la demande, la réponse, et la transcription
     * des commandes pour le tour assistant. Le mode Terminal ne persistait rien — recharger la page
     * vidait l'écran alors que la sandbox, elle, gardait son état.
     *
     * <p><b>Best-effort</b> : la réponse et le resync sont déjà livrés à l'écran ; un échec d'écriture
     * ne doit pas les faire échouer après coup.</p>
     */
    private void persistTurn(UUID userId, UUID workspaceId, String message, String reply,
            TerminalTranscript transcript, TurnUsage usage, boolean interrupted, boolean budgetReached) {
        try {
            messageRepository.save(AtelierMessage.builder()
                    .workspaceId(workspaceId)
                    .userId(userId)
                    .role("USER")
                    .content(message == null ? "" : message)
                    .build());
            messageRepository.save(AtelierMessage.builder()
                    .workspaceId(workspaceId)
                    .userId(userId)
                    .role("ASSISTANT")
                    .content(reply == null ? "" : reply)
                    .terminalJson(serializeTranscript(transcript, usage, interrupted, budgetReached))
                    .build());
        } catch (RuntimeException ex) {
            log.debug("Historisation du tour d'exécution ignorée (best-effort) : run déjà livré.");
        }
    }

    /**
     * Sérialise la transcription et le coût du tour. Bornée par {@code maxTranscriptChars} : un tour
     * qui installe un projet entier ne doit pas faire gonfler l'historique sans limite, et le nombre
     * de blocs omis est mentionné explicitement. Renvoie {@code null} si rien n'a été exécuté.
     *
     * <p>Un tour <b>interrompu</b> est sérialisé même sans aucune commande (F-32 SF-32-01) : sans
     * document, la mention « interrompu » serait perdue au rechargement, et l'utilisateur relirait un
     * tour d'apparence normale là où il avait coupé.</p>
     */
    private String serializeTranscript(TerminalTranscript transcript, TurnUsage usage,
            boolean interrupted, boolean budgetReached) {
        if (transcript.isEmpty() && !interrupted && !budgetReached) {
            return null;
        }
        TerminalTranscript.Bounded bounded = transcript.bounded(properties.maxTranscriptChars());
        Map<String, Object> document = new java.util.LinkedHashMap<>();
        document.put("blocks", bounded.blocks());
        document.put("omittedBlocks", bounded.omitted());
        document.put("inputTokens", usage.inputTokens());
        document.put("outputTokens", usage.outputTokens());
        document.put("activeSeconds", usage.activeSeconds());
        document.put("interrupted", interrupted);
        document.put("budgetReached", budgetReached);
        try {
            return MAPPER.writeValueAsString(document);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            log.debug("Transcription non sérialisable : tour historisé sans transcription.");
            return null;
        }
    }

    /**
     * Ouvre une session pour ce workspace et persiste son identifiant (F-30 SF-30-04) : téléverse les
     * fichiers du workspace (bornés par {@code maxSessionFiles}) et les monte dans la sandbox. Remet
     * à zéro les compteurs d'usage, une session neuve repartant d'un cumul nul.
     */
    private String openSession(UUID userId, UUID workspaceId, AtelierAgentConfig config, Workspace workspace) {
        String system = sessionSystemPrompt(userId, workspace);
        // Politique d'outils du projet (F-33 / SF-33-01), lue sur le workspace DÉJÀ possédé : elle est
        // fixée pour toute la vie de la session, une bascule ultérieure ne change pas celle-ci.
        SessionPermissions permissions = SessionPermissions.of(workspace.isAgentAskBeforeBash());
        // Délégation (F-35 / SF-35-01) : fixée elle aussi à l'ouverture. Les sous-agents sont des
        // threads de cette session — ils partagent son conteneur et son budget.
        DelegationPolicy delegation = DelegationPolicy.of(
                properties.subagentsEnabled(), properties.maxSubagents());
        if (workspace.isGit()) {
            return openGitSession(userId, config, workspace, system, permissions, delegation);
        }
        List<String> paths = workspaceService.tree(userId, workspaceId);
        int max = properties.maxSessionFiles();
        if (paths.size() > max) {
            paths = paths.subList(0, max);
        }
        List<FileMount> mounts = new ArrayList<>();
        for (String path : paths) {
            String content = workspaceService.readFile(userId, workspaceId, path);
            // La Files API refuse les caractères interdits dans le nom (dont « / ») : on téléverse sous
            // un nom aplati, tandis que l'arborescence réelle est portée par le mount_path.
            String fileId = provider.uploadFile(uploadFilename(path), content.getBytes(UTF_8));
            mounts.add(new FileMount(fileId, WORKSPACE_MOUNT + path));
        }
        ManagedSession session = provider.createSession(
                config.getAgentId(), config.getEnvironmentId(), mounts, null, system, permissions,
                null, sessionBudget(userId, delegation), delegation);
        markSessionOpened(workspace, session.id());
        return session.id();
    }

    /**
     * Prompt système de la session (F-34 / SF-34-01) : le prompt plateforme, augmenté des instructions
     * portées par le projet quand il en porte. Renvoie {@code null} quand le projet n'en porte aucune —
     * la session est alors créée exactement comme avant F-34, sans surcharge.
     *
     * <p>Les instructions sont lues <b>à l'ouverture</b> et figées pour toute la vie de la session :
     * le fournisseur ne permet pas de changer le prompt d'une session ouverte. Une modification du
     * fichier prend donc effet à la session suivante (décision D5 du cadrage F-34).</p>
     */
    private String sessionSystemPrompt(UUID userId, Workspace workspace) {
        Optional<ProjectInstructions> instructions = instructionsService.resolve(userId, workspace);
        if (instructions.isEmpty()) {
            return null;
        }
        ProjectInstructions resolved = instructions.get();
        log.debug("Instructions de projet injectées depuis {} ({} caractères{}).",
                resolved.path(), resolved.content().length(), resolved.truncated() ? ", tronquées" : "");
        return AgentSystemPrompt.withProjectInstructions(resolved.content());
    }

    /**
     * Ouvre une session sur un workspace <b>Git</b> (F-31 / SF-31-02) : le dépôt est cloné par le
     * fournisseur au lieu d'être téléversé fichier par fichier. Aucun {@link FileMount} n'est produit,
     * ce qui supprime de fait le plafond {@code maxSessionFiles} (300) sur ces projets.
     *
     * <p>Le jeton est déchiffré à la volée pour cet appel, appartient au <b>propriétaire du
     * workspace</b> (isolation), et n'est ni journalisé ni conservé. Il n'entre jamais dans le
     * conteneur : le proxy git du fournisseur l'injecte en sortie de sandbox (ADR-015).</p>
     *
     * <p>La session déclare en outre le <b>serveur MCP GitHub</b> quand un vault est disponible
     * (F-31 / SF-31-05), ce qui rend l'outil {@code create_pull_request} accessible à l'agent. Là
     * encore, le secret reste chez le fournisseur : le proxy MCP l'injecte hors du conteneur.</p>
     *
     * @throws GitTokenMissingException si l'utilisateur n'a plus de jeton enregistré (retiré ou jamais
     *                                  posé) — le workspace survit, c'est le montage qui échoue
     */
    private String openGitSession(UUID userId, AtelierAgentConfig config, Workspace workspace,
            String systemOverride, SessionPermissions permissions, DelegationPolicy delegation) {
        String token = gitTokenService.resolveToken(userId)
                .orElseThrow(() -> new GitTokenMissingException(
                        "Aucun jeton GitHub enregistré : ajoutez-en un dans vos réglages pour ouvrir ce projet."));
        RepositoryMount repository = new RepositoryMount(
                workspace.getGitRepoUrl(), token, GIT_MOUNT_PATH, workspace.getGitBranch());
        // Serveur MCP GitHub, s'il est disponible (F-31 / SF-31-05) : c'est lui qui permettra de
        // créer la pull request. Le vault s'attache ICI et nulle part ailleurs — le fournisseur
        // refuse d'en ajouter un à une session déjà ouverte.
        McpAccess mcpAccess = mcpVaultService.resolveAccess(userId).orElse(null);
        ManagedSession session = provider.createSession(
                config.getAgentId(), config.getEnvironmentId(), List.of(), repository, systemOverride,
                permissions, mcpAccess, sessionBudget(userId, delegation), delegation);
        markSessionOpened(workspace, session.id());
        return session.id();
    }

    /**
     * Plafond de dépense de la session à ouvrir (F-36 / SF-36-01) : le <b>minimum</b> entre le quota
     * restant de l'utilisateur converti en dollars et le plafond par run configuré, jamais en dessous
     * du plancher.
     *
     * <p>C'est ce qui transforme le quota en garantie structurelle : jusqu'ici il était vérifié
     * <b>après</b> le run, et un seul run pouvait donc dépasser le quota mensuel entier. Ici, la
     * plateforme met le thread en pause avant l'appel qui ferait franchir le plafond.</p>
     *
     * <p>Le quota lu est celui de l'utilisateur du <b>contexte de sécurité</b> — jamais un identifiant
     * venu du client (isolation).</p>
     *
     * <p>Le plancher évite une session au budget nul quand il ne reste presque plus de quota : elle
     * serait refusée par le fournisseur, ou mise en pause avant le premier mot. Le dépassement
     * possible est alors borné à ce plancher (défaut 0,10 $), contre « illimité » avant F-36.</p>
     */
    private SessionBudget sessionBudget(UUID userId, DelegationPolicy delegation) {
        UsageSnapshot usage = quotaService.currentUsage(userId);
        BigDecimal remainingCost = BigDecimal.valueOf(usage.remainingTokens())
                .multiply(costProperties.costPerMillionTokens())
                .divide(TOKENS_PER_MILLION, 6, RoundingMode.DOWN);
        // Une session qui délègue mène plusieurs travaux de front : son plafond par run est majoré
        // (F-35 / SF-35-01, propriété laissée dormante par SF-36-01). Il reste borné par le quota
        // restant — déléguer ne donne jamais accès à plus que ce que l'utilisateur a payé.
        BigDecimal maxRunCost = delegation != null && delegation.enabled()
                ? costProperties.maxRunCostDelegated()
                : costProperties.maxRunCost();
        BigDecimal amount = remainingCost.min(maxRunCost);
        if (amount.compareTo(costProperties.minRunCost()) < 0) {
            amount = costProperties.minRunCost();
        }
        return SessionBudget.ofUsd(amount);
    }

    /**
     * Enregistre l'ouverture d'une session sur le workspace et remet à zéro les compteurs d'usage :
     * une session neuve repart d'un cumul nul, et seul le delta est décompté (F-30 SF-30-04).
     */
    private void markSessionOpened(Workspace workspace, String sessionId) {
        workspace.setAgentSessionId(sessionId);
        workspace.setAgentSessionStartedAt(OffsetDateTime.now());
        workspace.setAgentInputTokens(0L);
        workspace.setAgentOutputTokens(0L);
        workspace.setAgentActiveSeconds(0L);
        // Le coût facturé repart lui aussi de zéro (F-36 / SF-36-02) : une session neuve n'a rien
        // dépensé, et garder l'ancien cumul empêcherait de décompter ses premiers tours.
        workspace.setAgentListCost(0L);
        workspaceRepository.save(workspace);
    }

    /** Oublie la session courante du workspace, sans rien terminer chez le fournisseur. */
    private void forgetSession(Workspace workspace) {
        workspace.setAgentSessionId(null);
        workspace.setAgentSessionStartedAt(null);
        workspaceRepository.save(workspace);
    }

    /** Envoie le message dans la session donnée et attend la complétion du tour. */
    private SessionRun runInSession(String sessionId, String message, ManagedEventListener bridge) {
        // Une interruption demandée alors qu'aucun run n'était en vol ne doit pas marquer CE tour :
        // la marque ne vaut que pour le travail lancé après elle.
        interruptedSessions.remove(sessionId);
        provider.sendUserMessage(sessionId, message);
        return provider.awaitCompletion(sessionId, properties.sessionTimeout(), properties.maxPolls(), bridge);
    }

    /**
     * Demande l'interruption du travail en cours dans la session du workspace (F-32 SF-32-01).
     *
     * <p>Relaie {@code user.interrupt} au fournisseur : la session s'arrête à une <b>frontière
     * sûre</b>, ce n'est pas un {@code kill}. Le run en vol sort alors de son attente par le chemin
     * nominal, et son tour est conservé et décompté — il a réellement consommé du bac à sable.</p>
     *
     * <p>Aucun pré-vol de quota ici, délibérément : c'est l'action qui <b>réduit</b> la consommation,
     * la refuser faute de quota enfermerait l'utilisateur dans le run qu'il cherche à arrêter.</p>
     *
     * @param userId      utilisateur propriétaire (isolation)
     * @param workspaceId workspace dont la session doit être interrompue
     * @throws fr.claudegateway.atelier.WorkspaceNotFoundException si le workspace n'est pas possédé
     * @throws NoActiveSessionException si aucune session n'est en cours (rien à interrompre)
     * @throws AgentProviderException   si le fournisseur refuse l'interruption
     */
    public void interruptSession(UUID userId, UUID workspaceId) {
        // Isolation EN PREMIER : workspace d'un autre user / inexistant ⇒ 404, aucun appel provider.
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        String sessionId = workspace.getAgentSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new NoActiveSessionException("Aucune exécution en cours à interrompre.");
        }
        // Marque posée AVANT le relais : le `session.status_idle` peut arriver au run pendant que
        // cet appel se termine. Retirée si le relais échoue, pour ne pas afficher comme interrompu
        // un tour qui ne l'a pas été.
        interruptedSessions.add(sessionId);
        try {
            provider.interruptSession(sessionId);
        } catch (RuntimeException ex) {
            interruptedSessions.remove(sessionId);
            throw ex;
        }
    }

    /**
     * Dit si le tour qui vient de s'achever a été interrompu, et consomme la marque (F-32 SF-32-01).
     *
     * <p>Deux signaux, jamais l'un à la place de l'autre : la marque locale (posée par l'appel
     * d'interruption reçu par <b>cette</b> instance) et la raison d'arrêt rapportée par le
     * fournisseur (seule disponible quand l'interruption a été traitée par une autre réplique).</p>
     */
    private boolean consumeInterrupted(String sessionId, String stopReason) {
        boolean marked = interruptedSessions.remove(sessionId);
        return marked || isInterruptStopReason(stopReason);
    }

    /** Raison d'arrêt d'interruption : le libellé exact n'est pas garanti, on reconnaît la racine. */
    private static boolean isInterruptStopReason(String stopReason) {
        return stopReason != null && stopReason.toLowerCase(java.util.Locale.ROOT).contains("interrupt");
    }

    /**
     * Vrai si le tour s'est arrêté parce que le <b>plafond de dépense</b> de la session est atteint
     * (F-36 SF-36-01). On se fie à la raison d'arrêt, jamais au montant affiché : celui-ci est arrondi
     * au cent et ne dirait pas de façon fiable que la session est en pause.
     */
    private static boolean isBudgetStopReason(String stopReason) {
        return stopReason != null
                && stopReason.toLowerCase(java.util.Locale.ROOT).contains(BUDGET_STOP_REASON);
    }

    /**
     * Répond à une demande d'autorisation posée par l'agent (F-33 / SF-33-02) : autorise la commande,
     * ou la refuse — avec un motif que l'agent recevra, pour qu'il propose autre chose plutôt que de
     * rester bloqué.
     *
     * <p>Le rendez-vous ne passe par <b>aucun état partagé</b> : le run attend sur le pool SSE, cette
     * réponse arrive sur un thread de requête (et possiblement sur une autre réplique). Elle est
     * postée à la session chez le fournisseur, et la boucle d'attente la voit revenir dans le flux
     * d'events.</p>
     *
     * <p>Aucun pré-vol de quota ici, délibérément : répondre ne consomme rien, et refuser la réponse
     * laisserait le run bloqué jusqu'au refus automatique — en facturant l'attente.</p>
     *
     * @param userId         utilisateur propriétaire (isolation)
     * @param workspaceId    workspace dont la session attend une réponse
     * @param confirmationId identifiant de la demande, tel que relayé dans le flux
     * @param allow          vrai pour autoriser, faux pour refuser
     * @param reason         motif du refus relayé à l'agent (ignoré sur une autorisation)
     * @throws fr.claudegateway.atelier.WorkspaceNotFoundException si le workspace n'est pas possédé
     * @throws NoActiveSessionException si aucune session n'est en cours (rien à autoriser)
     * @throws AgentProviderException   si la demande est inconnue, déjà tranchée, ou le fournisseur en panne
     */
    public void confirmToolUse(UUID userId, UUID workspaceId, String confirmationId, boolean allow,
            String reason) {
        // Isolation EN PREMIER : workspace d'un autre user / inexistant ⇒ 404, aucun appel provider.
        // L'identifiant de session n'est jamais accepté du client : il est lu sur le workspace possédé,
        // ce qui rend l'identifiant de demande inopérant en dehors de la session de CE workspace.
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        String sessionId = workspace.getAgentSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new NoActiveSessionException("Aucune exécution en cours à autoriser.");
        }
        provider.confirmToolUse(sessionId, confirmationId, allow, reason);
    }

    /**
     * Active ou désactive la <b>demande d'autorisation avant exécution</b> pour ce projet
     * (F-33 / SF-33-01).
     *
     * <p>La politique d'outils est fixée à l'<b>ouverture</b> de la session : une bascule ne change
     * donc pas une sandbox déjà ouverte. C'est dit explicitement par
     * {@link AgentConfirmationState#appliesToCurrentSession()} plutôt que passé sous silence —
     * annoncer une protection qui n'est pas en vigueur serait pire que ne rien annoncer.</p>
     *
     * @param userId      utilisateur propriétaire (isolation)
     * @param workspaceId workspace à régler
     * @param enabled     vrai pour demander l'autorisation avant chaque commande
     * @return l'état retenu et son application à la session en cours
     * @throws fr.claudegateway.atelier.WorkspaceNotFoundException si le workspace n'est pas possédé
     */
    public AgentConfirmationState setAskBeforeBash(UUID userId, UUID workspaceId, boolean enabled) {
        // Isolation EN PREMIER : workspace d'un autre user / inexistant ⇒ 404, aucune écriture.
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        workspace.setAgentAskBeforeBash(enabled);
        workspaceRepository.save(workspace);
        String sessionId = workspace.getAgentSessionId();
        boolean sessionOpen = sessionId != null && !sessionId.isBlank();
        return new AgentConfirmationState(enabled, !sessionOpen);
    }

    /**
     * État de l'option « demander avant d'exécuter » après une bascule (F-33 / SF-33-01).
     *
     * @param enabled                  l'option telle qu'elle est désormais enregistrée
     * @param appliesToCurrentSession  faux si une session est déjà ouverte : elle garde la politique
     *                                 posée à son ouverture, et seule une réinitialisation de la
     *                                 sandbox (F-30 SF-30-06) appliquera la nouvelle
     */
    public record AgentConfirmationState(boolean enabled, boolean appliesToCurrentSession) {
    }

    /**
     * Termine la session du workspace et efface son identifiant (F-30 SF-30-04) : le message suivant
     * repartira d'une sandbox neuve. Contrepartie de l'abandon du {@code finally} — une sandbox
     * longue-vie détenant l'état d'un projet doit avoir une fin de vie explicite (ADR-014).
     *
     * <p>La terminaison est <b>best-effort</b> : si le fournisseur refuse (session déjà morte,
     * indisponible), l'identifiant est effacé quand même, sinon le workspace resterait collé à une
     * session injouable.</p>
     *
     * @throws fr.claudegateway.atelier.WorkspaceNotFoundException si le workspace n'est pas possédé
     */
    public void resetSession(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        String sessionId = workspace.getAgentSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            provider.terminateSession(sessionId);
        } catch (RuntimeException ex) {
            log.debug("Terminaison de session ignorée (best-effort) : identifiant effacé malgré tout.");
        }
        syncedOutputs.remove(sessionId);
        interruptedSessions.remove(sessionId);
        workspace.setAgentSessionId(null);
        workspace.setAgentSessionStartedAt(null);
        workspaceRepository.save(workspace);
    }

    /**
     * Table basename → chemin d'origine : la Files API renvoie les sorties sous leur seul nom de base
     * (l'arborescence est perdue). On remappe une sortie vers son chemin de projet quand ce nom de
     * base est <b>unique</b> dans le workspace, pour réécrire au bon endroit (et non à la racine).
     */
    private static Map<String, String> basenameIndex(List<String> paths) {
        Map<String, String> byBasename = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (String p : paths) {
            String b = basename(p);
            if (byBasename.putIfAbsent(b, p) != null) {
                ambiguous.add(b);
            }
        }
        ambiguous.forEach(byBasename::remove);
        return byBasename;
    }

    /**
     * Décompte best-effort de la consommation, en <b>delta</b> depuis le relevé précédent (F-30
     * SF-30-04). {@code getSessionUsage} renvoie le cumul depuis l'ouverture de la session : sur une
     * session persistante, recréditer ce cumul à chaque tour ferait payer plusieurs fois la même
     * consommation, de plus en plus cher à mesure que la session vit.
     *
     * <p>Le delta est borné à zéro : un relevé inférieur au précédent (session remplacée, compteur
     * remis à zéro côté fournisseur) ne doit jamais créditer de valeur négative.</p>
     *
     * <p>Toute erreur est <b>avalée</b> (log debug) : le run a déjà produit sa réponse et
     * resynchronisé ses fichiers, un comptage manqué ne doit rien interrompre.</p>
     */
    private TurnUsage recordSessionUsage(UUID userId, UUID workspaceId, String sessionId) {
        try {
            SessionUsage usage = provider.getSessionUsage(sessionId);
            Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
            long inputDelta = Math.max(0L, usage.inputTokens() - workspace.getAgentInputTokens());
            long outputDelta = Math.max(0L, usage.outputTokens() - workspace.getAgentOutputTokens());
            long secondsDelta = Math.max(0L, usage.activeSeconds() - workspace.getAgentActiveSeconds());
            Long cost = usage.listCostMinorUnits();
            long costDelta = cost == null ? 0L : Math.max(0L, cost - workspace.getAgentListCost());
            workspace.setAgentInputTokens(usage.inputTokens());
            workspace.setAgentOutputTokens(usage.outputTokens());
            workspace.setAgentActiveSeconds(usage.activeSeconds());
            if (cost != null) {
                workspace.setAgentListCost(cost);
            }
            workspaceRepository.save(workspace);
            // Décompte au COÛT RÉEL quand le fournisseur le rapporte (F-36 / SF-36-02), sinon repli
            // sur les tokens bruts — exactement le comportement d'avant F-36.
            TurnUsage billed = cost == null
                    ? new TurnUsage(inputDelta, outputDelta, secondsDelta)
                    : billedFromCost(costDelta, inputDelta, outputDelta, secondsDelta);
            // recordUsage prend des int : on borne les deltas à Integer.MAX_VALUE.
            quotaService.recordUsage(userId, (int) Math.min(billed.inputTokens(), Integer.MAX_VALUE),
                    (int) Math.min(billed.outputTokens(), Integer.MAX_VALUE));
            quotaService.recordSandboxSeconds(userId, secondsDelta);
            return billed;
        } catch (RuntimeException ex) {
            log.debug("Décompte de l'usage de session ignoré (best-effort) : run déjà livré.");
            return TurnUsage.UNKNOWN;
        }
    }

    /**
     * Convertit le coût réellement facturé en <b>équivalent tokens</b> décompté du quota
     * (F-36 / SF-36-02). Le quota reste libellé en tokens ; c'est la conversion qui fait entrer dans
     * le décompte ce que les tokens ignorent — le modèle réellement servi, les recherches web, le
     * temps de bac à sable.
     *
     * <p>Formule : {@code cents ÷ 100 × markup ÷ coût de référence par million × 1 000 000}. Le
     * markup est le levier de marge, ajustable par configuration ; à {@code 1.0} (défaut) le décompte
     * reproduit l'économie d'avant F-36.</p>
     *
     * <p>L'équivalent est réparti entre entrée et sortie <b>au prorata des tokens rapportés</b> : le
     * compteur n'a que ces deux colonnes, et inventer une autre ventilation fausserait le rapport
     * d'usage. Un coût sans aucun token rapporté (recherche web ou temps de bac à sable seuls) est
     * décompté entièrement en entrée — ne rien décompter serait faux.</p>
     */
    private TurnUsage billedFromCost(long costDeltaMinorUnits, long inputDelta, long outputDelta,
            long secondsDelta) {
        if (costDeltaMinorUnits <= 0) {
            return new TurnUsage(0L, 0L, secondsDelta);
        }
        long total = BigDecimal.valueOf(costDeltaMinorUnits)
                .multiply(costProperties.markup())
                .multiply(TOKENS_PER_MINOR_UNIT_NUMERATOR)
                .divide(costProperties.costPerMillionTokens(), 0, RoundingMode.HALF_UP)
                .longValue();
        long rawTokens = inputDelta + outputDelta;
        if (rawTokens <= 0) {
            return new TurnUsage(total, 0L, secondsDelta);
        }
        long input = BigDecimal.valueOf(total)
                .multiply(BigDecimal.valueOf(inputDelta))
                .divide(BigDecimal.valueOf(rawTokens), 0, RoundingMode.HALF_UP)
                .longValue();
        return new TurnUsage(input, total - input, secondsDelta);
    }

    /**
     * Consommation d'un tour (F-30 SF-30-05) : les deltas <b>effectivement décomptés</b> du quota.
     * {@link #UNKNOWN} signale un relevé manqué — l'écran n'affiche alors rien, plutôt qu'un
     * « 0 token » qui serait faux après une exécution réelle.
     */
    private record TurnUsage(long inputTokens, long outputTokens, long activeSeconds) {
        private static final TurnUsage UNKNOWN = new TurnUsage(0L, 0L, 0L);
    }

    /**
     * Ramène un nom de fichier de sortie à un chemin relatif au workspace, en retirant un éventuel
     * préfixe de montage ({@code /workspace/}) ou de sorties ({@code /mnt/session/outputs/}).
     */
    private static String normalizePath(String filename) {
        if (filename == null) {
            return "";
        }
        String path = filename;
        if (path.startsWith(OUTPUTS_PREFIX)) {
            path = path.substring(OUTPUTS_PREFIX.length());
        } else if (path.startsWith(WORKSPACE_MOUNT)) {
            path = path.substring(WORKSPACE_MOUNT.length());
        }
        return path;
    }

    /**
     * Nom de fichier « plat » accepté par la Files API : tout caractère hors {@code [A-Za-z0-9._-]}
     * (dont le séparateur de chemin {@code /}) est remplacé par {@code _}. L'arborescence réelle du
     * projet reste portée par le {@code mount_path} de la ressource, pas par ce nom.
     */
    static String uploadFilename(String path) {
        String flat = path.replaceAll("[^A-Za-z0-9._-]", "_");
        return flat.isBlank() ? "file" : flat;
    }

    /** Nom de base d'un chemin (segment après le dernier {@code /}). */
    static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * Résout le chemin de réécriture d'une sortie : chemin exact s'il existe déjà dans le workspace ;
     * sinon, chemin d'origine si le nom de base est unique dans le projet (la Files API aplatit les
     * sorties à leur seul nom de base) ; sinon le chemin tel quel (fichier nouveau).
     */
    static String resolveOutputPath(String relPath, Set<String> knownPaths, Map<String, String> byBasename) {
        if (knownPaths.contains(relPath)) {
            return relPath;
        }
        String mapped = byBasename.get(basename(relPath));
        return mapped != null ? mapped : relPath;
    }
}
