package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * Consigne système de la boucle maison (F-39 / SF-39-02) : les skills y sont <b>annoncés</b>
 * (chemin + description), jamais déversés. Le corps d'un skill se lit à la demande avec
 * {@code read_file} ; c'est ce qui rend le préfixe court et stable, donc réellement cacheable
 * (SF-39-01).
 *
 * <p>La consigne n'étant pas exposée, elle est observée là où elle compte : dans la requête reçue
 * par le fournisseur.</p>
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceSystemPromptTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private fr.claudegateway.runner.exec.RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private fr.claudegateway.runner.exec.RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;
    @Mock private fr.claudegateway.governance.GovernanceHostFiles governanceHostFiles;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    /** Poste du projet (F-48 / SF-48-01) : c'est lui qui porte l'interpréteur élu. */
    private final UUID hostId = UUID.randomUUID();

    private static final String SKILL_BODY = """
            ---
            name: deploy
            description: Déploie le projet sur l'environnement cible.
            ---

            # Déploiement

            Étape 1 : lancer le pipeline. SECRET_INTERNE_DU_CORPS
            """;

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        // Quota lu pour dériver le plafond de consommation du message (F-39 / SF-39-15).
        org.mockito.Mockito.lenient().when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
    }

    /** Issue d'appel runner réussie, forme minimale du contrat §2.4. */
    private static fr.claudegateway.runner.channel.RunnerCallResult runnerOk(String content) {
        return new fr.claudegateway.runner.channel.RunnerCallResult(
                true, content, false, null, 5L, null, null, null, "", false);
    }

    /** Consigne système effectivement envoyée au fournisseur pour un tour trivial. */
    private String systemPrompt() {
        agentProvider.enqueueFinal("fini");
        service.chat(userId, workspaceId, "bonjour");
        return agentProvider.lastRequest.system();
    }

    @Test
    void skillIsAnnouncedByPathAndDescriptionWithoutItsBody() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of(".claude/skills/deploy.md"));
        when(workspaceService.readFile(userId, workspaceId, ".claude/skills/deploy.md")).thenReturn(SKILL_BODY);
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).contains("- .claude/skills/deploy.md : Déploie le projet sur l'environnement cible.");
        assertThat(system).doesNotContain("SECRET_INTERNE_DU_CORPS");
        assertThat(system).contains("read_file");
    }

    // ------------------------------------------- F-119 / SF-119-02 : discipline d'investigation

    @Test
    void theInvestigationDisciplineIsPresentOnASandboxProject() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).contains("Vérifie avant d'affirmer");
        assertThat(system).contains("Ne généralise jamais à partir d'un seul exemple");
        assertThat(system).contains("Relis la source avant d'affirmer");
        assertThat(system).contains("Corrige tôt");
        assertThat(system).contains("non concluant");
        // Non-régression : le rôle et l'outillage hébergés restent annoncés.
        assertThat(system).contains("list_files, read_file, write_file, search_files");
    }

    @Test
    void theInvestigationDisciplineIsPresentOnARunnerProject() {
        String system = systemPromptOfRunnerProjectDeclaring(null);

        assertThat(system).contains("Vérifie avant d'affirmer");
        assertThat(system).contains("Ne généralise jamais à partir d'un seul exemple");
        assertThat(system).contains("non concluant");
        // Non-régression : le rôle RUNNER (exploration par bash) reste annoncé.
        assertThat(system).contains("bash (ls, find, grep -n)");
    }

    // ------------------------------------------- F-120 / SF-120-01 : doctrine « réponds d'abord »

    @Test
    void theRestraintDoctrineIsPresentOnASandboxProject() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).contains("Répondre d'abord, agir sur demande");
        assertThat(system).contains("Une question n'est pas un ordre");
        assertThat(system).contains("Veux-tu que je le fasse");
        // La doctrine dit que lire pour répondre reste permis, la mutation non demandée est proscrite.
        assertThat(system).contains("MUTATION non demandée");
        // Non-régression : la discipline d'investigation SF-119-02 cohabite toujours.
        assertThat(system).contains("Vérifie avant d'affirmer");
    }

    @Test
    void theRestraintDoctrineIsPresentOnARunnerProject() {
        String system = systemPromptOfRunnerProjectDeclaring(null);

        assertThat(system).contains("Répondre d'abord, agir sur demande");
        assertThat(system).contains("Une question n'est pas un ordre");
        assertThat(system).contains("Veux-tu que je le fasse");
        // Non-régression : le rôle RUNNER et la discipline d'investigation restent annoncés.
        assertThat(system).contains("bash (ls, find, grep -n)");
        assertThat(system).contains("Ne généralise jamais à partir d'un seul exemple");
    }

    // ------------------------------------------- F-121 / SF-121-03 : style de réponse

    @Test
    void theResponseStyleBlockIsPresentOnASandboxProject() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).contains("Style de réponse (terminal)");
        assertThat(system).contains("pas de préambule");
        assertThat(system).contains("`chemin:ligne`");
        assertThat(system).contains("Pas d'émoji");
        // Coexistence : la discipline SF-119-02 et la doctrine SF-120-01 ne sont pas écrasées.
        assertThat(system).contains("Vérifie avant d'affirmer");
        assertThat(system).contains("Répondre d'abord, agir sur demande");
    }

    @Test
    void theResponseStyleBlockIsPresentOnARunnerProject() {
        String system = systemPromptOfRunnerProjectDeclaring(null);

        assertThat(system).contains("Style de réponse (terminal)");
        assertThat(system).contains("Cite tes sources par `chemin:ligne`");
        assertThat(system).contains("Pas d'émoji");
        // Coexistence + non-régression du rôle RUNNER.
        assertThat(system).contains("Répondre d'abord, agir sur demande");
        assertThat(system).contains("bash (ls, find, grep -n)");
    }

    // ------------------------------------------- F-121 / SF-121-21 : bloc « environnement »

    @Test
    void theEnvironmentBlockIsPresentOnASandboxProject() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).contains("--- Environnement ---");
        assertThat(system).contains("Date du jour : " + java.time.LocalDate.now());
        assertThat(system).contains("Plateforme : espace de travail hébergé");
        // Projet archive : pas un dépôt git, pas de statut git déversé.
        assertThat(system).contains("Dépôt git : non");
        // Coexistence : les doctrines en tête ne sont pas écrasées, le rôle reste la 1re phrase.
        assertThat(system).contains("Vérifie avant d'affirmer");
        assertThat(system).startsWith("Tu es un assistant de développement");
    }

    @Test
    void theEnvironmentBlockIsPresentOnARunnerProject() {
        String system = systemPromptOfRunnerProjectDeclaring("posix");

        assertThat(system).contains("--- Environnement ---");
        assertThat(system).contains("Date du jour : " + java.time.LocalDate.now());
        assertThat(system).contains("Plateforme : poste de l'utilisateur");
        assertThat(system).contains("Shell : posix");
        // Non-régression : le rôle RUNNER et la discipline restent annoncés.
        assertThat(system).contains("bash (ls, find, grep -n)");
        assertThat(system).contains("Vérifie avant d'affirmer");
    }

    @Test
    void theEnvironmentBlockIsByteStableBetweenTwoBuilds() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        // Deux tours successifs, environnement inchangé : le bloc doit être identique à l'octet, sans
        // quoi le préfixe change à chaque tour et le cache (F-134) tombe.
        String first = systemPrompt();
        String second = systemPrompt();
        String firstEnv = first.substring(first.indexOf("--- Environnement ---"),
                first.indexOf("\n\n", first.indexOf("--- Environnement ---")));
        String secondEnv = second.substring(second.indexOf("--- Environnement ---"),
                second.indexOf("\n\n", second.indexOf("--- Environnement ---")));
        assertThat(firstEnv).isEqualTo(secondEnv);
    }

    // ------------------------------------------- F-125 / SF-125-01 : silence de la tenue de carte

    @Test
    void theCardSilenceDoctrineIsPresentOnASandboxProject() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).contains("Tenue de la carte, en silence");
        assertThat(system).contains("Réponds D'ABORD à la question");
        assertThat(system).contains("travail de COULISSE");
        // Les termes de plomberie sont nommés comme interdits dans la réponse.
        assertThat(system).contains("termes de plomberie");
        // Coexistence : les trois consignes précédentes ne sont pas écrasées.
        assertThat(system).contains("Vérifie avant d'affirmer");
        assertThat(system).contains("Répondre d'abord, agir sur demande");
        assertThat(system).contains("Style de réponse (terminal)");
    }

    @Test
    void theCardSilenceDoctrineIsPresentOnARunnerProject() {
        String system = systemPromptOfRunnerProjectDeclaring(null);

        assertThat(system).contains("Tenue de la carte, en silence");
        assertThat(system).contains("travail de COULISSE");
        assertThat(system).contains("termes de plomberie");
        // Coexistence + non-régression du rôle RUNNER.
        assertThat(system).contains("Style de réponse (terminal)");
        assertThat(system).contains("bash (ls, find, grep -n)");
    }

    // ------------------------------------------- F-126 / SF-126-01 : balisage de la réponse essentielle

    @Test
    void theEssentialAnswerDoctrineIsPresentOnASandboxProject() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).contains("Mets en avant l'essentiel");
        assertThat(system).contains("<<essentiel>>");
        assertThat(system).contains("<</essentiel>>");
        assertThat(system).contains("la réponse directe et courte");
        // Coexistence : les quatre consignes précédentes ne sont pas écrasées.
        assertThat(system).contains("Vérifie avant d'affirmer");
        assertThat(system).contains("Répondre d'abord, agir sur demande");
        assertThat(system).contains("Style de réponse (terminal)");
        assertThat(system).contains("Tenue de la carte, en silence");
    }

    @Test
    void theEssentialAnswerDoctrineIsPresentOnARunnerProject() {
        String system = systemPromptOfRunnerProjectDeclaring(null);

        assertThat(system).contains("Mets en avant l'essentiel");
        assertThat(system).contains("<<essentiel>>");
        assertThat(system).contains("<</essentiel>>");
        // Coexistence + non-régression du rôle RUNNER.
        assertThat(system).contains("Tenue de la carte, en silence");
        assertThat(system).contains("bash (ls, find, grep -n)");
    }

    @Test
    void theEssentialMarkerDoesNotCollideWithTheFinDeTourMarker() {
        // Le marqueur essentiel n'est PAS un commentaire HTML : il ne peut pas être pris pour le
        // marqueur `fin-de-tour` (F-125), qui ne vise que `<!-- fin-de-tour: … -->`.
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).doesNotContain("<!-- fin-de-tour");
        assertThat(AtelierChatService.stripTurnMetadata(
                "<<essentiel>>\nNon.\n<</essentiel>>\nLe détail suit."))
                .isEqualTo("<<essentiel>>\nNon.\n<</essentiel>>\nLe détail suit.");
    }

    // ------------------------------------------- F-125 / SF-125-05 : conseil → tranche, jamais un statut de rangement

    @Test
    void theAdviceDecisionDoctrineIsPresentOnASandboxProject() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        // Conseil/décision → prendre position et trancher.
        assertThat(system).contains("Sur une question de conseil ou de décision, tranche");
        assertThat(system).contains("PRENDS POSITION");
        assertThat(system).contains("ta meilleure recommandation par défaut");
        // Baliser l'essentiel même sur un tour court.
        assertThat(system).contains("Balise l'essentiel MÊME sur un tour court");
        // Interdiction explicite des formules de statut de rangement.
        assertThat(system).contains("statut de rangement de la carte");
        assertThat(system).contains("rien à ranger");
        assertThat(system).contains("ce tour n'était qu'un");
        // Coexistence : les cinq consignes précédentes ne sont pas écrasées.
        assertThat(system).contains("Vérifie avant d'affirmer");
        assertThat(system).contains("Répondre d'abord, agir sur demande");
        assertThat(system).contains("Style de réponse (terminal)");
        assertThat(system).contains("Tenue de la carte, en silence");
        assertThat(system).contains("Mets en avant l'essentiel");
        assertThat(system).contains("<<essentiel>>");
    }

    @Test
    void theAdviceDecisionDoctrineIsPresentOnARunnerProject() {
        String system = systemPromptOfRunnerProjectDeclaring(null);

        assertThat(system).contains("Sur une question de conseil ou de décision, tranche");
        assertThat(system).contains("Balise l'essentiel MÊME sur un tour court");
        assertThat(system).contains("statut de rangement de la carte");
        assertThat(system).contains("ce tour n'était qu'un");
        // Coexistence + non-régression du rôle RUNNER.
        assertThat(system).contains("Tenue de la carte, en silence");
        assertThat(system).contains("Mets en avant l'essentiel");
        assertThat(system).contains("bash (ls, find, grep -n)");
    }

    // ------------------------------------------- F-141 / SF-141-01 : annonce de destination + demande si ambigu

    @Test
    void theDestinationAnnounceDoctrineIsPresentOnASandboxProject() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        // Nommer la destination d'un fait durable.
        assertThat(system).contains("Dis où tu ranges un fait durable");
        assertThat(system).contains("rangé dans `data-platform/PLAN-ACTION.md`");
        // La destination est une information, pas de la plomberie.
        assertThat(system).contains("n'est PAS de la plomberie");
        // Demander si ambigu au lieu de deviner.
        assertThat(system).contains("Si la destination est AMBIGUË");
        assertThat(system).contains("NE DEVINE PAS : demande");
        // Non-régression F-125 : la carte silencieuse et le reste des doctrines coexistent.
        assertThat(system).contains("Tenue de la carte, en silence");
        assertThat(system).contains("Vérifie avant d'affirmer");
        assertThat(system).contains("Mets en avant l'essentiel");
        assertThat(system).contains("Sur une question de conseil ou de décision, tranche");
    }

    @Test
    void theDestinationAnnounceDoctrineIsPresentOnARunnerProject() {
        String system = systemPromptOfRunnerProjectDeclaring(null);

        assertThat(system).contains("Dis où tu ranges un fait durable");
        assertThat(system).contains("rangé dans `data-platform/PLAN-ACTION.md`");
        assertThat(system).contains("NE DEVINE PAS : demande");
        // Non-régression F-125 + rôle RUNNER.
        assertThat(system).contains("Tenue de la carte, en silence");
        assertThat(system).contains("bash (ls, find, grep -n)");
    }

    @Test
    void theDestinationExceptionDoesNotReopenTheFinDeTourPlumbing() {
        // La destination devient visible, mais le strip du marqueur `fin-de-tour` (F-125) ne change
        // pas : le commentaire HTML reste retiré, l'annonce de destination (texte simple) reste.
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).doesNotContain("<!-- fin-de-tour");
        assertThat(AtelierChatService.stripTurnMetadata(
                "rangé dans `data-platform/PLAN-ACTION.md`\n<!-- fin-de-tour: promu=1 -->"))
                .isEqualTo("rangé dans `data-platform/PLAN-ACTION.md`");
    }

    // ------------------------------------------- F-141 / SF-141-02 : aiguillage à la racine

    /** Consigne du TERMINAL DU POSTE (racine) : projet RUNNER avec {@code hostTerminal = true}. */
    private String systemPromptOfHostTerminal() {
        Workspace host = new Workspace();
        host.setId(workspaceId);
        host.setUserId(userId);
        host.setSource(WorkspaceSource.ARCHIVE);
        host.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        host.setHostId(hostId);
        host.setHostTerminal(true);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(host);
        when(runnerToolGateway.listFiles(any(), any())).thenReturn(runnerOk(""));
        when(runnerToolGateway.readFile(any(), any(), any())).thenReturn(runnerOk("conventions"));
        return systemPrompt();
    }

    @Test
    void theSubjectRoutingDoctrineIsPresentOnTheHostTerminal() {
        String system = systemPromptOfHostTerminal();

        assertThat(system).contains("À la racine du poste, aiguille avant de ranger");
        // Découverte des sujets existants avant de proposer.
        assertThat(system).contains("Découvre les sujets existants");
        // Les quatre classes de destination + le mix explicite.
        assertThat(system).contains("sujet EXISTANT");
        assertThat(system).contains("TRANSVERSE");
        assertThat(system).contains("NOUVEAU sujet");
        assertThat(system).contains("MIX");
        assertThat(system).contains("répartition : A→data-platform");
        // Attendre validation, demander si vraiment ambigu.
        assertThat(system).contains("ATTENDS la validation");
        assertThat(system).contains("DEMANDE plutôt que de trancher tout seul");
        // Non-régression : SF-141-01 et F-125 tiennent aussi à la racine.
        assertThat(system).contains("Dis où tu ranges un fait durable");
        assertThat(system).contains("Tenue de la carte, en silence");
    }

    @Test
    void theSubjectRoutingDoctrineIsAbsentOnAnOrdinaryProject() {
        // Terminal de projet RUNNER : dans un sujet, aucun routage — la consigne ne s'injecte pas.
        String runner = systemPromptOfRunnerProjectDeclaring(null);
        assertThat(runner).doesNotContain("À la racine du poste, aiguille avant de ranger");
        // Les doctrines universelles, elles, restent (non-régression).
        assertThat(runner).contains("Dis où tu ranges un fait durable");
    }

    @Test
    void theSubjectRoutingDoctrineIsAbsentOnAHostedProject() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).doesNotContain("À la racine du poste, aiguille avant de ranger");
    }

    // ------------------------------------------- F-141 / SF-141-03 : créer un sujet + gouvernance héritée

    /** Prépare un TERMINAL DU POSTE (racine) sans envoyer de tour : pour scénariser des appels d'outil. */
    private void configureHostTerminal() {
        Workspace host = new Workspace();
        host.setId(workspaceId);
        host.setUserId(userId);
        host.setSource(WorkspaceSource.ARCHIVE);
        host.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        host.setHostId(hostId);
        host.setHostTerminal(true);
        host.setName("Terminal du poste");
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(host);
        lenient().when(runnerToolGateway.listFiles(any(), any())).thenReturn(runnerOk(""));
        lenient().when(runnerToolGateway.readFile(any(), any(), any())).thenReturn(runnerOk("conventions"));
    }

    private static java.util.List<String> toolNames(java.util.List<fr.claudegateway.agent.AgentTool> tools) {
        return tools.stream().map(fr.claudegateway.agent.AgentTool::name).toList();
    }

    @Test
    void createSubjectToolIsOfferedOnlyOnTheHostTerminal() {
        // Présent à la racine.
        configureHostTerminal();
        agentProvider.enqueueFinal("fini");
        service.chat(userId, workspaceId, "bonjour");
        assertThat(toolNames(agentProvider.lastRequest.tools())).contains("create_subject");
    }

    @Test
    void createSubjectToolIsAbsentOnAnOrdinaryProject() {
        systemPromptOfRunnerProjectDeclaring(null);
        assertThat(toolNames(agentProvider.lastRequest.tools())).doesNotContain("create_subject");
    }

    @Test
    void createSubjectToolIsAbsentOnAHostedProject() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));
        agentProvider.enqueueFinal("fini");
        service.chat(userId, workspaceId, "bonjour");
        assertThat(toolNames(agentProvider.lastRequest.tools())).doesNotContain("create_subject");
    }

    @Test
    void createSubjectDelegatesToOpenOnHostWithUserAndHost() {
        configureHostTerminal();
        Workspace created = new Workspace();
        created.setId(UUID.randomUUID());
        created.setUserId(userId);
        created.setName("data-platform");
        created.setHostId(hostId);
        created.setProjectPath("data-platform");
        when(workspaceService.openOnHost(userId, hostId, "data-platform", "Terminal du poste"))
                .thenReturn(created);
        agentProvider.enqueueToolCall("create_subject", "name", "data-platform");
        agentProvider.enqueueFinal("déposé");

        service.chat(userId, workspaceId, "range ce journal dans un nouveau sujet data-platform");

        // Le chemin de création EXISTANT est appelé, avec l'isolation user_id + host_id.
        org.mockito.Mockito.verify(workspaceService).openOnHost(userId, hostId, "data-platform",
                "Terminal du poste");
    }

    @Test
    void createSubjectOnAnExistingFolderDoesNotOverwrite() {
        configureHostTerminal();
        when(workspaceService.openOnHost(userId, hostId, "data-platform", "Terminal du poste"))
                .thenThrow(new fr.claudegateway.runner.host.HostProjectExistsException("data-platform",
                        "data-platform"));
        agentProvider.enqueueToolCall("create_subject", "name", "data-platform");
        agentProvider.enqueueFinal("compris");

        service.chat(userId, workspaceId, "crée data-platform");

        // Une seule tentative : le doublon n'est jamais réécrit ni recréé.
        org.mockito.Mockito.verify(workspaceService, org.mockito.Mockito.times(1))
                .openOnHost(userId, hostId, "data-platform", "Terminal du poste");
        // Le modèle a reçu le refus dans le résultat de l'outil.
        assertThat(agentProvider.toolNamesSeen).contains("create_subject");
    }

    @Test
    void createSubjectRejectsABlankNameWithoutTouchingTheWorkspace() {
        configureHostTerminal();
        agentProvider.enqueueToolCall("create_subject", "name", "   ");
        agentProvider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "crée un sujet");

        org.mockito.Mockito.verify(workspaceService, org.mockito.Mockito.never())
                .openOnHost(any(), any(), any(), any());
    }

    // ------------------------------------------- F-141 / SF-141-04 : reclasser une entrée

    private static fr.claudegateway.governance.GovernanceHostFiles.HostFileRead present(String content) {
        return new fr.claudegateway.governance.GovernanceHostFiles.HostFileRead(
                fr.claudegateway.governance.GovernanceHostFiles.Presence.PRESENT, content, false);
    }

    @Test
    void reclassEntryToolIsOfferedOnlyOnTheHostTerminalWhenWired() {
        configureHostTerminal();
        service.setGovernanceHostFiles(governanceHostFiles);
        agentProvider.enqueueFinal("fini");
        service.chat(userId, workspaceId, "bonjour");
        assertThat(toolNames(agentProvider.lastRequest.tools())).contains("reclass_entry");
    }

    @Test
    void reclassEntryToolIsAbsentWhenNotWired() {
        configureHostTerminal();
        // Pas de setGovernanceHostFiles : l'outil n'existe pas.
        agentProvider.enqueueFinal("fini");
        service.chat(userId, workspaceId, "bonjour");
        assertThat(toolNames(agentProvider.lastRequest.tools())).doesNotContain("reclass_entry");
    }

    @Test
    void reclassEntryToolIsAbsentOnAnOrdinaryProject() {
        // Même câblé, l'outil ne s'offre pas hors du terminal du poste.
        Workspace runner = new Workspace();
        runner.setId(workspaceId);
        runner.setUserId(userId);
        runner.setSource(WorkspaceSource.ARCHIVE);
        runner.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        runner.setHostId(hostId);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runner);
        lenient().when(runnerToolGateway.listFiles(any(), any())).thenReturn(runnerOk(""));
        lenient().when(runnerToolGateway.readFile(any(), any(), any())).thenReturn(runnerOk("x"));
        service.setGovernanceHostFiles(governanceHostFiles);
        agentProvider.enqueueFinal("fini");
        service.chat(userId, workspaceId, "bonjour");
        assertThat(toolNames(agentProvider.lastRequest.tools())).doesNotContain("reclass_entry");
    }

    @Test
    void reclassEntryMovesTheLineWithTraceFromSourceToTarget() {
        configureHostTerminal();
        service.setGovernanceHostFiles(governanceHostFiles);
        String fromContent = "- fait A\n- fait B mal rangé\n- fait C\n";
        when(governanceHostFiles.read(eqUser(), any(), org.mockito.ArgumentMatchers.eq("lzi/PLAN-ACTION.md")))
                .thenReturn(present(fromContent));
        when(governanceHostFiles.read(eqUser(), any(),
                org.mockito.ArgumentMatchers.eq("data-platform/PLAN-ACTION.md")))
                .thenReturn(present("- déjà là\n"));
        when(governanceHostFiles.write(eqUser(), any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        agentProvider.enqueueToolCall("reclass_entry",
                "from", "lzi/PLAN-ACTION.md",
                "to", "data-platform/PLAN-ACTION.md",
                "entry", "- fait B mal rangé");
        agentProvider.enqueueFinal("reclassé");

        service.chat(userId, workspaceId, "reclasse ce fait vers data-platform");

        org.mockito.ArgumentCaptor<String> path = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(governanceHostFiles, org.mockito.Mockito.times(2))
                .write(eqUser(), any(), path.capture(), body.capture());
        int toIdx = path.getAllValues().indexOf("data-platform/PLAN-ACTION.md");
        int fromIdx = path.getAllValues().indexOf("lzi/PLAN-ACTION.md");
        // La destination reçoit le fait AVEC sa trace de provenance.
        assertThat(body.getAllValues().get(toIdx)).contains("- fait B mal rangé (reclassé depuis "
                + "lzi/PLAN-ACTION.md le ");
        assertThat(body.getAllValues().get(toIdx)).contains("- déjà là");
        // La source ne contient plus le fait déplacé, mais garde les autres.
        assertThat(body.getAllValues().get(fromIdx)).doesNotContain("fait B mal rangé");
        assertThat(body.getAllValues().get(fromIdx)).contains("- fait A");
        assertThat(body.getAllValues().get(fromIdx)).contains("- fait C");
    }

    @Test
    void reclassEntryDoesNotLoseTheFactWhenTargetWriteFails() {
        configureHostTerminal();
        service.setGovernanceHostFiles(governanceHostFiles);
        when(governanceHostFiles.read(eqUser(), any(), org.mockito.ArgumentMatchers.eq("lzi/x.md")))
                .thenReturn(present("- fait B\n"));
        when(governanceHostFiles.read(eqUser(), any(), org.mockito.ArgumentMatchers.eq("data/x.md")))
                .thenReturn(present("- autre\n"));
        // La destination refuse l'écriture : on n'écrit JAMAIS la source ensuite (fait non perdu).
        when(governanceHostFiles.write(eqUser(), any(), org.mockito.ArgumentMatchers.eq("data/x.md"),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

        agentProvider.enqueueToolCall("reclass_entry", "from", "lzi/x.md", "to", "data/x.md",
                "entry", "- fait B");
        agentProvider.enqueueFinal("compris");

        service.chat(userId, workspaceId, "reclasse");

        // La source n'est jamais réécrite : le fait reste là où il était.
        org.mockito.Mockito.verify(governanceHostFiles, org.mockito.Mockito.never())
                .write(eqUser(), any(), org.mockito.ArgumentMatchers.eq("lzi/x.md"),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void reclassEntryRejectsWhenTheLineIsNotInTheSource() {
        configureHostTerminal();
        service.setGovernanceHostFiles(governanceHostFiles);
        when(governanceHostFiles.read(eqUser(), any(), org.mockito.ArgumentMatchers.eq("lzi/x.md")))
                .thenReturn(present("- rien de tel\n"));

        agentProvider.enqueueToolCall("reclass_entry", "from", "lzi/x.md", "to", "data/x.md",
                "entry", "- fait absent");
        agentProvider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "reclasse");

        // Aucune écriture : rien n'a bougé.
        org.mockito.Mockito.verify(governanceHostFiles, org.mockito.Mockito.never())
                .write(any(), any(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void reclassEntryRejectsIdenticalSourceAndTarget() {
        configureHostTerminal();
        service.setGovernanceHostFiles(governanceHostFiles);
        agentProvider.enqueueToolCall("reclass_entry", "from", "a.md", "to", "a.md", "entry", "- x");
        agentProvider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "reclasse");

        // Ni lecture ni écriture : refus immédiat.
        org.mockito.Mockito.verify(governanceHostFiles, org.mockito.Mockito.never())
                .read(any(), any(), org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(governanceHostFiles, org.mockito.Mockito.never())
                .write(any(), any(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    private UUID eqUser() {
        return org.mockito.ArgumentMatchers.eq(userId);
    }

    @Test
    void theSetPlanDescriptionOnlyPlansWhenAskedOrActing() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));
        agentProvider.enqueueFinal("fini");

        service.chat(userId, workspaceId, "bonjour");

        String setPlanDesc = agentProvider.lastRequest.tools().stream()
                .filter(t -> "set_plan".equals(t.name())).findFirst().orElseThrow().description();
        assertThat(setPlanDesc).contains("que si l'utilisateur te demande");
        assertThat(setPlanDesc).contains("pas parce que le mot");
    }

    @Test
    void theExploreToolTeachesGroupingIndependentExplorations() {
        // F-39 / SF-39-22, DURCI par F-148 / SF-148-04 : le moteur (SF-39-21) exécute en parallèle les
        // `explore` d'un même tour ; la doctrine, dans la description de l'outil, apprend à l'agent à
        // les GROUPER SYSTÉMATIQUEMENT quand elles sont indépendantes et à NE PAS les grouper quand
        // l'une dépend de l'autre.
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));
        agentProvider.enqueueFinal("fini");

        service.chat(userId, workspaceId, "bonjour");

        String exploreDesc = agentProvider.lastRequest.tools().stream()
                .filter(t -> "explore".equals(t.name())).findFirst().orElseThrow().description();
        // Grouper SYSTÉMATIQUEMENT les indépendantes dans le même tour (parallélisme).
        assertThat(exploreDesc).contains("INDÉPENDANTES");
        assertThat(exploreDesc).contains("SYSTÉMATIQUEMENT");
        assertThat(exploreDesc).contains("MÊME tour");
        assertThat(exploreDesc).contains("en parallèle");
        // Consigne impérative de ne pas les étaler sur des tours séparés quand rien ne les relie.
        assertThat(exploreDesc).contains("Ne les étale JAMAIS");
        // Garde de dépendance : l'exception, puis enchaîner au tour suivant.
        assertThat(exploreDesc).contains("SEULE exception");
        assertThat(exploreDesc).contains("tour suivant");
        // La garantie de base reste dite : lecture seule, ni écriture ni commande.
        assertThat(exploreDesc).contains("LECTURE SEULE");
        assertThat(exploreDesc).contains("ni écrire, ni exécuter de commande");
    }

    @Test
    void theExploreToolTeachesDelegatingRepoAudit() {
        // F-149 / SF-149-02 : la description de l'outil `explore` apprend à DÉLÉGUER l'audit lourd de
        // dépôt (read_file/grep/glob) plutôt que de lire fichier par fichier en bash dans la boucle
        // principale — le volume de lecture reste hors du contexte principal.
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));
        agentProvider.enqueueFinal("fini");

        service.chat(userId, workspaceId, "bonjour");

        String exploreDesc = agentProvider.lastRequest.tools().stream()
                .filter(t -> "explore".equals(t.name())).findFirst().orElseThrow().description();
        // La doctrine de délégation d'audit de dépôt.
        assertThat(exploreDesc).contains("AUDITER");
        assertThat(exploreDesc).contains("dépôt");
        assertThat(exploreDesc).contains("read_file/grep/glob");
        assertThat(exploreDesc).contains("fichier par fichier");
        assertThat(exploreDesc).contains("bash");
        // Non-régression : la doctrine de groupement (SF-39-22 / SF-148-04) coexiste toujours.
        assertThat(exploreDesc).contains("INDÉPENDANTES");
        assertThat(exploreDesc).contains("SYSTÉMATIQUEMENT");
        // La garantie de base reste dite.
        assertThat(exploreDesc).contains("LECTURE SEULE");
        assertThat(exploreDesc).contains("ni écrire, ni exécuter de commande");
    }

    @Test
    void theGovernancePreambleFramesTheInjectedClaudeMd() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenReturn("Avant d'écrire la moindre ligne, produis la mini-spec. REFUS sinon.");

        String system = systemPrompt();

        assertThat(system).contains("Elles ne transforment pas une question en ordre");
        // Le préambule précède le contenu injecté du CLAUDE.md.
        assertThat(system.indexOf("Elles ne transforment pas une question en ordre"))
                .isLessThan(system.indexOf("Conventions du projet (CLAUDE.md)"));
    }

    @Test
    void theEditAndWriteToolContractsCarryTheDiscipline() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));
        agentProvider.enqueueFinal("fini");

        service.chat(userId, workspaceId, "bonjour");

        String editDesc = agentProvider.lastRequest.tools().stream()
                .filter(t -> "edit_file".equals(t.name())).findFirst().orElseThrow().description();
        assertThat(editDesc).contains("EXACTEMENT");
        assertThat(editDesc).contains("lis le fichier avant");
        assertThat(editDesc).contains("relis le fichier avant de réessayer");

        String writeDesc = agentProvider.lastRequest.tools().stream()
                .filter(t -> "write_file".equals(t.name())).findFirst().orElseThrow().description();
        assertThat(writeDesc).contains("ÉCRASANT");
        assertThat(writeDesc).contains("préfère edit_file");
    }

    @Test
    void onAMachineBackedProjectTheRoleSendsExplorationToBash() {
        // SF-39-05 : annoncer list_files/search_files là où ils ne sont plus déclarés ne produirait
        // que des appels perdus. La consigne suit l'outillage réel.
        Workspace runner = new Workspace();
        runner.setId(workspaceId);
        runner.setUserId(userId);
        runner.setSource(WorkspaceSource.ARCHIVE);
        runner.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runner);
        when(runnerToolGateway.listFiles(any(), any())).thenReturn(runnerOk(""));
        when(runnerToolGateway.readFile(any(), any(), any())).thenReturn(runnerOk("conventions"));

        String system = systemPrompt();

        assertThat(system).contains("bash (ls, find, grep -n)");
        assertThat(system).doesNotContain("search_files");
    }

    @Test
    void onAWindowsMachineWithoutBashTheRoleSpeaksPowerShell() {
        // F-38 / SF-38-27 : le runner a élu PowerShell faute de bash. Continuer à dicter
        // `ls`/`find`/`grep -n` ferait échouer chaque exploration — et sur cette cible, bash est
        // le seul outil d'exploration déclaré.
        String system = systemPromptOfRunnerProjectDeclaring("powershell");

        assertThat(system).contains("Get-ChildItem");
        assertThat(system).contains("Select-String");
        assertThat(system).doesNotContain("bash (ls, find, grep -n)");
    }

    @Test
    void onAMachineWithOnlyCmdTheRoleSaysThereIsNoGrep() {
        String system = systemPromptOfRunnerProjectDeclaring("cmd");

        assertThat(system).contains("cmd.exe");
        assertThat(system).contains("dir /s /b");
        assertThat(system).contains("findstr");
        assertThat(system).doesNotContain("bash (ls, find, grep -n)");
    }

    @Test
    void anUndeclaredInterpreterKeepsThePosixWording() {
        // Runner antérieur à SF-38-27, ou projet dont aucun runner ne s'est encore connecté :
        // la consigne ne change pas, et elle reste juste sur toute machine Unix.
        String system = systemPromptOfRunnerProjectDeclaring(null);

        assertThat(system).contains("bash (ls, find, grep -n)");
    }

    @Test
    void anUnknownDeclaredInterpreterFallsBackToPosixInsteadOfLeakingIntoThePrompt() {
        // La valeur vient d'un client : hors liste blanche, elle ne doit ni changer la consigne ni
        // y apparaître (décision D6).
        String system = systemPromptOfRunnerProjectDeclaring("<script>fish</script>");

        assertThat(system).contains("bash (ls, find, grep -n)");
        assertThat(system).doesNotContain("fish");
    }

    @Test
    void aHostedProjectIgnoresTheDeclaredInterpreter() {
        // Cible SANDBOX : il n'y a pas de machine, donc pas d'interpréteur. La colonne, même
        // renseignée par un ancien appairage, ne doit rien changer ici.
        Workspace hosted = new Workspace();
        hosted.setId(workspaceId);
        hosted.setUserId(userId);
        hosted.setSource(WorkspaceSource.ARCHIVE);
        // Un interpréteur déclaré vit désormais sur le POSTE (F-48 / SF-48-01) ; ce projet
        // hébergé n'en a aucun, et la consigne garde son texte POSIX.
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(hosted);
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).contains("list_files, read_file, write_file, search_files");
        assertThat(system).doesNotContain("cmd.exe");
    }

    /** Consigne d'un projet en cible {@code RUNNER} dont le runner a déclaré {@code shell}. */
    private String systemPromptOfRunnerProjectDeclaring(String declaredShell) {
        Workspace runner = new Workspace();
        runner.setId(workspaceId);
        runner.setUserId(userId);
        runner.setSource(WorkspaceSource.ARCHIVE);
        runner.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        runner.setHostId(hostId);
        when(runnerHostService.declaredShell(hostId)).thenReturn(declaredShell);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runner);
        when(runnerToolGateway.listFiles(any(), any())).thenReturn(runnerOk(""));
        when(runnerToolGateway.readFile(any(), any(), any())).thenReturn(runnerOk("conventions"));
        return systemPrompt();
    }

    @Test
    void projectConventionsAreStillInlinedInFull() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenReturn("Règle du projet : toujours tester.");

        String system = systemPrompt();

        assertThat(system).contains("Conventions du projet (CLAUDE.md)");
        assertThat(system).contains("Règle du projet : toujours tester.");
    }

    @Test
    void noSkillMeansNoCatalogSection() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of("src/main.js"));
        when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md")).thenReturn("conventions");

        String system = systemPrompt();

        assertThat(system).doesNotContain("Skills du projet");
    }

    @Test
    void unreadableSkillIsSkippedAndOthersSurvive() {
        when(workspaceService.tree(userId, workspaceId))
                .thenReturn(List.of("skills/broken.md", "skills/ok.md"));
        when(workspaceService.readFile(userId, workspaceId, "skills/broken.md"))
                .thenThrow(new InvalidFilePathException("illisible"));
        when(workspaceService.readFile(userId, workspaceId, "skills/ok.md")).thenReturn("Fait la revue.");
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).doesNotContain("skills/broken.md");
        assertThat(system).contains("- skills/ok.md : Fait la revue.");
    }

    @Test
    void catalogIsBoundedAndAnnouncesTheRemainder() {
        // F-148 / SF-148-03 : le plafond du catalogue annoncé est abaissé à 15 (moins de lectures
        // d'amorçage avant le 1er token), la coupe se dit toujours, l'ordre reste déterministe.
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 55; i++) {
            many.add("skills/s" + i + ".md");
        }
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.copyOf(many));
        lenient().when(workspaceService.readFile(any(), any(), any())).thenAnswer(invocation -> {
            String path = invocation.getArgument(2);
            if ("CLAUDE.md".equals(path)) {
                throw new InvalidFilePathException("absent");
            }
            return "Description de " + path;
        });

        String system = systemPrompt();

        assertThat(system).contains("- skills/s0.md : Description de skills/s0.md");
        // Le 16ᵉ skill (index 15) et suivants ne sont plus annoncés.
        assertThat(system).doesNotContain("skills/s15.md");
        assertThat(system).contains("et 40 autre(s) skill(s) non listé(s).");
    }
}
