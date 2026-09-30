package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

    // ------------------------------------------- F-164 / SF-164-03 : déclenchement manuel (dont unitaire)

    @Test
    void theManualQuestionTriggerAndUnitaryModeArePresentOnASandboxProject() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        // La doctrine « demander » est là.
        assertThat(system).contains("Poser des questions avec l'outil « demander »");
        // Signal de déclenchement manuel (SF-164-01, gardé).
        assertThat(system).contains("pose-moi les questions que tu veux");
        assertThat(system).contains("tu DOIS utiliser « demander »");
        // Mode unitaire explicite (SF-164-03).
        assertThat(system).contains("UNE PAR UNE");
        assertThat(system).contains("UNE seule question par appel");
        // Non-régression : règle par défaut obligatoire + anti-spam.
        assertThat(system).contains("JAMAIS par de la prose");
        assertThat(system).contains("Ne demande QUE si tu es vraiment bloqué");
    }

    @Test
    void theManualQuestionTriggerAndUnitaryModeArePresentOnARunnerProject() {
        String system = systemPromptOfRunnerProjectDeclaring(null);

        // La doctrine « demander » vaut sur les DEUX cibles.
        assertThat(system).contains("Poser des questions avec l'outil « demander »");
        assertThat(system).contains("pose-moi les questions que tu veux");
        assertThat(system).contains("UNE PAR UNE");
        assertThat(system).contains("UNE seule question par appel");
        assertThat(system).contains("JAMAIS par de la prose");
        // Coexistence + non-régression du rôle RUNNER.
        assertThat(system).contains("bash (ls, find, grep -n)");
    }

    // ------------------------------------------- F-164 / SF-164-04 : règle impérative du format structuré

    @Test
    void theStructuredFormatRuleForProposableQuestionsIsPresentOnASandboxProject() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        // Règle impérative : liste / réponses proposables → format structuré, jamais la prose.
        assertThat(system).contains("Toute question à réponses PROPOSABLES");
        assertThat(system).contains("JAMAIS par de la prose");
        // Durcissement SF-164-04 : une liste en prose est un défaut, pas un style.
        assertThat(system).contains("C'est une RÈGLE, pas un style");
        assertThat(system).contains("une liste de questions rendue en prose est un DÉFAUT");
        // Exception préservée : la prose reste pour les questions vraiment ouvertes.
        assertThat(system).contains("réservée aux questions vraiment ouvertes");
        // Non-régression SF-164-03 (même doctrine) : signal manuel + unitaire + anti-spam.
        assertThat(system).contains("pose-moi les questions que tu veux");
        assertThat(system).contains("UNE seule question par appel");
        assertThat(system).contains("Ne demande QUE si tu es vraiment bloqué");
    }

    @Test
    void theStructuredFormatRuleForProposableQuestionsIsPresentOnARunnerProject() {
        String system = systemPromptOfRunnerProjectDeclaring(null);

        assertThat(system).contains("Toute question à réponses PROPOSABLES");
        assertThat(system).contains("JAMAIS par de la prose");
        assertThat(system).contains("une liste de questions rendue en prose est un DÉFAUT");
        assertThat(system).contains("réservée aux questions vraiment ouvertes");
        // Coexistence + non-régression du rôle RUNNER.
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

    // ------------------------------------------- F-166 / SF-166-01 : savoir durable (REPO-MAP.md/STATE.md)

    /** Vérifie le contenu clé de la doctrine « savoir durable » sur un préfixe donné. */
    private static void assertDurableKnowledgeDoctrine(String system) {
        // Amorce distinctive + les deux artefacts nommés.
        assertThat(system).contains("Entretiens un savoir durable du dépôt");
        assertThat(system).contains("`REPO-MAP.md`");
        assertThat(system).contains("`STATE.md`");
        // Lire d'abord au lieu de re-scanner.
        assertThat(system).contains("LIS-LES D'ABORD");
        // Proposer aux moments clés, jamais à chaque tour.
        assertThat(system).contains("PROPOSE d'en créer un aux MOMENTS CLÉS");
        assertThat(system).contains("jamais en douce ni à chaque tour");
        // Construction bornée + ligne à revérifier.
        assertThat(system).contains("git ls-files");
        assertThat(system).contains("PAS une relecture complète du dépôt");
        assertThat(system).contains("à revérifier avant de");
        // Garde-fou : pointeur, jamais substitut à la lecture réelle.
        assertThat(system).contains("POINTEUR À REVÉRIFIER, JAMAIS");
        assertThat(system).contains("substitut à la lecture du fichier RÉEL");
        // Rafraîchir quand le dépôt bouge + additive.
        assertThat(system).contains("RAFRAÎCHIS la carte quand le dépôt bouge");
        assertThat(system).contains("S'AJOUTE à ta démarche");
    }

    @Test
    void theDurableKnowledgeDoctrineIsPresentOnTheHostTerminal() {
        String system = systemPromptOfHostTerminal();

        assertDurableKnowledgeDoctrine(system);
        // Non-régression : coexiste avec les doctrines universelles et l'aiguillage host-only.
        assertThat(system).contains("Dis où tu ranges un fait durable");
        assertThat(system).contains("Tenue de la carte, en silence");
        assertThat(system).contains("À la racine du poste, aiguille avant de ranger");
    }

    @Test
    void theDurableKnowledgeDoctrineIsPresentOnARunnerProject() {
        // Terminal de sujet / projet RUNNER : le savoir durable a un sens (dépôt réel) → présent.
        String system = systemPromptOfRunnerProjectDeclaring(null);

        assertDurableKnowledgeDoctrine(system);
        // Le sujet n'a PAS l'aiguillage host-only : le scope host+sujet ne colle pas au host-only.
        assertThat(system).doesNotContain("À la racine du poste, aiguille avant de ranger");
        // Non-régression : doctrines universelles + rôle RUNNER.
        assertThat(system).contains("Dis où tu ranges un fait durable");
        assertThat(system).contains("bash (ls, find, grep -n)");
    }

    @Test
    void theDurableKnowledgeDoctrineIsAbsentOnASandboxProject() {
        // Projet hébergé (SANDBOX, hors poste) : hors scope → doctrine absente, préfixe plus court.
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).doesNotContain("Entretiens un savoir durable du dépôt");
        assertThat(system).doesNotContain("`REPO-MAP.md`");
        // Les doctrines universelles restent (non-régression) : seul le bloc host+sujet manque.
        assertThat(system).contains("Dis où tu ranges un fait durable");
    }

    @Test
    void theDurableKnowledgeDoctrineIsByteStableBetweenTwoBuilds() {
        // Le littéral est constant et injecté à un point fixe : le bloc de doctrine doit être identique
        // à l'octet entre deux tours, sans quoi le préfixe change et le cache (F-134) tombe. Comparé sur
        // le seul bloc de doctrine (même prudence que theEnvironmentBlockIsByteStableBetweenTwoBuilds).
        String first = systemPromptOfRunnerProjectDeclaring(null);
        String second = systemPromptOfRunnerProjectDeclaring(null);

        String marker = "Entretiens un savoir durable du dépôt";
        String firstDoctrine = first.substring(first.indexOf(marker),
                first.indexOf("\n\n", first.indexOf(marker)));
        String secondDoctrine = second.substring(second.indexOf(marker),
                second.indexOf("\n\n", second.indexOf(marker)));
        assertThat(firstDoctrine).isEqualTo(secondDoctrine);
    }

    // ------------------------------ F-166 / SF-166-02 : déclencheur léger (re-scan sans artefact)

    /** Vérifie le contenu clé du déclencheur léger « savoir durable » sur un préfixe donné. */
    private static void assertDurableKnowledgeTrigger(String system) {
        // Amorce distinctive.
        assertThat(system).contains("Repère le re-scan à vide");
        // Le signal auto-observable : plusieurs fichiers du même dépôt, sans REPO-MAP/STATE.
        assertThat(system).contains("OUVERT PLUSIEURS FICHIERS du MÊME dépôt");
        assertThat(system).contains("AUCUN `REPO-MAP.md` / `STATE.md` n'existe");
        assertThat(system).contains("ce re-scan EST le signal");
        // Proposer (borné) avant de re-explorer.
        assertThat(system).contains("PROPOSE d'en créer un");
        assertThat(system).contains("AVANT de continuer à re-explorer");
        // Anti-spam : une seule fois.
        assertThat(system).contains("UNE SEULE FOIS (anti-spam)");
        // Garde-fou : ne bloque ni ne remplace jamais la lecture réelle.
        assertThat(system).contains("NE BLOQUE JAMAIS, NE REMPLACE JAMAIS");
        assertThat(system).contains("ta lecture réelle");
    }

    @Test
    void theDurableKnowledgeTriggerIsPresentOnTheHostTerminal() {
        String system = systemPromptOfHostTerminal();

        assertDurableKnowledgeTrigger(system);
        // Non-régression : s'ajoute à la doctrine SF-166-01, ne la remplace pas.
        assertDurableKnowledgeDoctrine(system);
        // Non-régression : coexiste avec les doctrines universelles et l'aiguillage host-only.
        assertThat(system).contains("Dis où tu ranges un fait durable");
        assertThat(system).contains("À la racine du poste, aiguille avant de ranger");
    }

    @Test
    void theDurableKnowledgeTriggerIsPresentOnARunnerProject() {
        // Terminal de sujet / projet RUNNER : même scope que SF-166-01 → présent.
        String system = systemPromptOfRunnerProjectDeclaring(null);

        assertDurableKnowledgeTrigger(system);
        // Non-régression : la doctrine SF-166-01 reste présente et inchangée à côté du déclencheur.
        assertDurableKnowledgeDoctrine(system);
        // Le sujet n'a PAS l'aiguillage host-only.
        assertThat(system).doesNotContain("À la racine du poste, aiguille avant de ranger");
    }

    @Test
    void theDurableKnowledgeTriggerIsAbsentOnASandboxProject() {
        // Projet hébergé (SANDBOX, hors poste) : hors scope → déclencheur absent, préfixe plus court.
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).doesNotContain("Repère le re-scan à vide");
        assertThat(system).doesNotContain("ce re-scan EST le signal");
        // La doctrine SF-166-01 est elle aussi absente (même scope) ; les universelles restent.
        assertThat(system).doesNotContain("Entretiens un savoir durable du dépôt");
        assertThat(system).contains("Dis où tu ranges un fait durable");
    }

    @Test
    void theDurableKnowledgeTriggerIsByteStableBetweenTwoBuilds() {
        // Littéral constant injecté à un point fixe : le bloc doit être identique à l'octet entre deux
        // tours, sinon le préfixe change et le cache (F-134) tombe (même prudence que le test SF-166-01).
        String first = systemPromptOfRunnerProjectDeclaring(null);
        String second = systemPromptOfRunnerProjectDeclaring(null);

        String marker = "Repère le re-scan à vide";
        String firstBlock = first.substring(first.indexOf(marker),
                first.indexOf("\n\n", first.indexOf(marker)));
        String secondBlock = second.substring(second.indexOf(marker),
                second.indexOf("\n\n", second.indexOf(marker)));
        assertThat(firstBlock).isEqualTo(secondBlock);
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

    // ------------------------------------------- F-141 / SF-141-05 : aiguillage proactif à choix structuré (× F-164)

    @Test
    void theProactiveStructuredRoutingDoctrineIsPresentOnTheHostTerminal() {
        String system = systemPromptOfHostTerminal();

        // Le choix de rangement passe par « demander », pas par la prose.
        assertThat(system).contains("Ne pose PAS ce choix de rangement en PROSE");
        assertThat(system).contains("utilise l'outil « demander »");
        // Options concrètes classées + raison courte + recommended.
        assertThat(system).contains("chacun avec sa RAISON courte");
        assertThat(system).contains("`cloudops-run` — même périmètre run/infra CAGIP");
        assertThat(system).contains("Nouveau sujet : <nom déduit>");
        assertThat(system).contains("comme recommended");
        // Mapping du choix : rattacher / create_subject / transverse ; décider-par-défaut délégué.
        assertThat(system).contains("crée-le avec create_subject");
        assertThat(system).contains("prend l'option recommandée et la flague");
        // Non-régression SF-141-02 (découverte + classement) : les quatre classes tiennent.
        assertThat(system).contains("À la racine du poste, aiguille avant de ranger");
        assertThat(system).contains("Découvre les sujets existants");
        assertThat(system).contains("sujet EXISTANT");
        assertThat(system).contains("TRANSVERSE");
        assertThat(system).contains("NOUVEAU sujet");
        assertThat(system).contains("répartition : A→data-platform");
        // Non-régression SF-141-01 + F-125.
        assertThat(system).contains("Dis où tu ranges un fait durable");
        assertThat(system).contains("Tenue de la carte, en silence");
    }

    @Test
    void theProactiveStructuredRoutingDoctrineIsAbsentOnAnOrdinaryProject() {
        // Terminal de projet RUNNER : dans un sujet, aucun aiguillage à choix structuré.
        String runner = systemPromptOfRunnerProjectDeclaring(null);
        assertThat(runner).doesNotContain("Ne pose PAS ce choix de rangement en PROSE");
        assertThat(runner).doesNotContain("À la racine du poste, aiguille avant de ranger");
        // Les doctrines universelles (dont la règle « demander ») restent (non-régression).
        assertThat(runner).contains("Dis où tu ranges un fait durable");
        assertThat(runner).contains("Poser des questions avec l'outil « demander »");
    }

    @Test
    void theProactiveStructuredRoutingDoctrineIsAbsentOnASandboxProject() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).doesNotContain("Ne pose PAS ce choix de rangement en PROSE");
    }

    // ------------------------------------------- F-141 / SF-141-06 : passation + garde-fou anti-poursuite

    @Test
    void theHandoffAndGuardDoctrineArePresentOnTheHostTerminal() {
        String system = systemPromptOfHostTerminal();

        // Passation visible après création/rattachement.
        assertThat(system).contains("Après avoir créé ou rattaché un sujet, passe la main");
        assertThat(system).contains("Le poste ROUTE, il n'EXÉCUTE pas le travail d'un sujet");
        assertThat(system).contains("rouvre le terminal dans le sujet");
        assertThat(system).contains("PHRASE DE DÉMARRAGE");
        // Présentation via « demander » + repli en clair.
        assertThat(system).contains("Ouvrir le sujet");
        assertThat(system).contains("émets la passation EN CLAIR");
        // Garde-fou anti-poursuite : refus doux + redirection.
        assertThat(system).contains("GARDE-FOU anti-poursuite");
        assertThat(system).contains("ce travail vit dans");
        assertThat(system).contains("NE L'EXÉCUTE PAS ici : redirige");
        // Distinction poursuite vs usages légitimes.
        assertThat(system).contains("Restent LÉGITIMES au poste");
        assertThat(system).contains("Ne bloque QUE la poursuite substantielle");
        // Pas de verrou : override explicite avec caveat d'une ligne.
        assertThat(system).contains("Ce n'est PAS un verrou");
        assertThat(system).contains("caveat d'UNE ligne");
        // Non-régression : aiguillage (SF-141-02/05), annonce (SF-141-01), carte silencieuse F-125.
        assertThat(system).contains("À la racine du poste, aiguille avant de ranger");
        assertThat(system).contains("Ne pose PAS ce choix de rangement en PROSE");
        assertThat(system).contains("Dis où tu ranges un fait durable");
        assertThat(system).contains("Tenue de la carte, en silence");
    }

    @Test
    void theHandoffAndGuardDoctrineAreAbsentOnAnOrdinaryProject() {
        // Terminal de projet RUNNER : dans un sujet, travailler le sujet EST légitime — pas de garde-fou.
        String runner = systemPromptOfRunnerProjectDeclaring(null);
        assertThat(runner).doesNotContain("Après avoir créé ou rattaché un sujet, passe la main");
        assertThat(runner).doesNotContain("GARDE-FOU anti-poursuite");
        // Doctrines universelles préservées (non-régression).
        assertThat(runner).contains("Dis où tu ranges un fait durable");
    }

    @Test
    void theHandoffAndGuardDoctrineAreAbsentOnASandboxProject() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).doesNotContain("Après avoir créé ou rattaché un sujet, passe la main");
        assertThat(system).doesNotContain("GARDE-FOU anti-poursuite");
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
    // ---------------------------------------------------------------- F-142 / SF-142-10

    @Test
    @DisplayName("SF-142-10 : l'outil de la gateway PRIME sur une recette périmée du poste, et c'est dit APRÈS les skills")
    void thetoolPrimesOverAnOutdatedLocalRecipe() {
        // Un poste qui porte encore l'ancienne recette : c'est exactement le cas de production du
        // 2026-09-24 — le correctif déployé, l'outil ouvert, et pourtant jamais appelé.
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of(".claude/skills/pptx.md"));
        when(workspaceService.readFile(userId, workspaceId, ".claude/skills/pptx.md")).thenReturn(SKILL_BODY);
        service.setDiagramTool(openDiagramCatalog(), null);

        String system = systemPrompt();

        assertThat(system).contains(AtelierChatService.TOOL_PRIMACY);
        assertThat(system).contains("N'installe rien").contains("render_diagram").contains("python-pptx");
        // L'ordre fait la décision : ce qu'on lit en dernier pèse le plus.
        assertThat(system.indexOf(AtelierChatService.TOOL_PRIMACY))
                .as("la règle doit venir APRÈS le catalogue des skills")
                .isGreaterThan(system.indexOf("--- Skills du projet"));
    }

    @Test
    @DisplayName("SF-142-10 : sans outil de production ouvert, la règle n'est pas injectée — elle parlerait dans le vide")
    void withoutToolsTheRuleIsAbsent() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of(".claude/skills/pptx.md"));
        when(workspaceService.readFile(userId, workspaceId, ".claude/skills/pptx.md")).thenReturn(SKILL_BODY);

        String system = systemPrompt();

        assertThat(system).doesNotContain(AtelierChatService.TOOL_PRIMACY);
    }

    // ------------------------------- F-121 / SF-121-12 : sections stables, CLAUDE.md encadré, choix d'outil

    @Test
    @DisplayName("SF-121-12 : les bannières de section apparaissent dans un ordre stable, le rôle d'abord")
    void sectionsAppearInStableOrderAfterTheRole() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        // L'amorce de rôle reste les tout premiers octets (SF-148-02) : aucune bannière ne la précède.
        assertThat(system).startsWith("Tu es un assistant de développement");
        assertThat(system.indexOf("--- Environnement ---"))
                .isLessThan(system.indexOf(AtelierChatService.SECTION_METHOD));
        assertThat(system.indexOf(AtelierChatService.SECTION_METHOD))
                .isLessThan(system.indexOf(AtelierChatService.SECTION_STYLE));
        assertThat(system.indexOf(AtelierChatService.SECTION_STYLE))
                .isLessThan(system.indexOf(AtelierChatService.SECTION_TOOLS));
        // Chaque bannière ouvre bien SA section : elle précède immédiatement son premier bloc.
        assertThat(system).contains(AtelierChatService.SECTION_METHOD + "Discipline de travail");
        assertThat(system).contains(AtelierChatService.SECTION_STYLE + "Style de réponse (terminal)");
    }

    @Test
    @DisplayName("SF-121-12 : le CLAUDE.md injecté verbatim est ENCADRÉ — ouverture ET fermeture")
    void projectConventionsAreClosedByAnEndMarker() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenReturn("REFUS si le dev démarre sans mini-spec.");

        String system = systemPrompt();

        assertThat(system).contains(AtelierChatService.PROJECT_CONVENTIONS_HEADER)
                .contains(AtelierChatService.PROJECT_CONVENTIONS_FOOTER);
        // Le contenu verbatim est bien ENTRE les deux bornes : c'est ce qui le rend distinguable
        // d'une consigne de la passerelle, au lieu de couler dans la section suivante.
        assertThat(system.indexOf(AtelierChatService.PROJECT_CONVENTIONS_HEADER))
                .isLessThan(system.indexOf("REFUS si le dev démarre sans mini-spec."));
        assertThat(system.indexOf("REFUS si le dev démarre sans mini-spec."))
                .isLessThan(system.indexOf(AtelierChatService.PROJECT_CONVENTIONS_FOOTER));
    }

    @Test
    @DisplayName("SF-121-12 : sans CLAUDE.md lisible, NI la borne d'ouverture NI celle de fermeture")
    void noConventionMarkersWhenNoProjectConventions() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).doesNotContain(AtelierChatService.PROJECT_CONVENTIONS_HEADER);
        assertThat(system).doesNotContain(AtelierChatService.PROJECT_CONVENTIONS_FOOTER);
    }

    @Test
    @DisplayName("SF-121-12 : en SANDBOX, la section nomme les outils déclarés là-bas — jamais bash ni task")
    void toolChoiceSectionNamesSandboxTools() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String choice = toolChoiceSectionOf(systemPrompt());

        assertThat(choice).contains("`grep`").contains("`glob`").contains("`read_file`")
                .contains("`edit_file`").contains("`multi_edit`").contains("`write_file`");
        // Déclarés en SANDBOX seulement (SF-39-05).
        assertThat(choice).contains("`list_files`").contains("`search_files`");
        // Annoncer un outil absent ne produit que des appels perdus.
        assertThat(choice).doesNotContain("`bash`").doesNotContain("`task`");
        // L'exploration, elle, est déclarée sur les DEUX cibles quand la délégation est ouverte.
        assertThat(choice).contains("`explore`");
    }

    @Test
    @DisplayName("SF-121-12 : sur le poste, la section nomme bash et task, et préfère grep à bash pour chercher")
    void toolChoiceSectionNamesRunnerTools() {
        String choice = toolChoiceSectionOf(systemPromptOfRunnerProjectDeclaring("posix"));

        assertThat(choice).contains("`bash`").contains("`task`").contains("`explore`");
        assertThat(choice)
                .as("la guidance doit trancher entre grep et bash pour chercher")
                .contains("préfère `grep`/`glob` à `bash`");
        // Retirés au profit de bash sur cette cible (SF-39-05) : ne pas les annoncer.
        assertThat(choice).doesNotContain("`list_files`").doesNotContain("`search_files`");
    }

    @Test
    @DisplayName("SF-121-12 : délégation fermée (max-delegations: 0) ⇒ ni explore ni task annoncés")
    void toolChoiceSectionOmitsExploreAndTaskWithoutDelegation() {
        serviceWithMaxDelegations(0);

        String choice = toolChoiceSectionOf(systemPromptOfRunnerProjectDeclaring("posix"));

        assertThat(choice).doesNotContain("`explore`").doesNotContain("`task`");
        // Le reste de la section tient debout : les outils toujours déclarés restent nommés.
        assertThat(choice).contains("`grep`").contains("`bash`");
    }

    @Test
    @DisplayName("SF-121-12 : la section est byte-stable entre deux tours — le cache de prompt (F-134) tient")
    void toolChoiceSectionIsByteStableBetweenTwoBuilds() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        assertThat(toolChoiceSectionOf(systemPrompt()))
                .isEqualTo(toolChoiceSectionOf(systemPrompt()));
    }

    /** La section « Choix des outils » extraite du prompt, de sa bannière à la ligne vide qui la clôt. */
    private static String toolChoiceSectionOf(String system) {
        int start = system.indexOf(AtelierChatService.SECTION_TOOLS);
        assertThat(start).as("la section « Choix des outils » doit exister").isNotNegative();
        int end = system.indexOf("\n\n", start);
        return end < 0 ? system.substring(start) : system.substring(start, end);
    }

    /** Rebâtit le service sous test avec un plafond de délégations donné (F-39 / SF-39-14). */
    private void serviceWithMaxDelegations(int maxDelegations) {
        service = new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null,
                        maxDelegations, true));
    }

    // ---------------------------------------------------------------- F-129 / SF-129-07

    @Test
    @DisplayName("SF-129-07 : quand les outils Office sont ouverts, leur guide est injecté, et la règle « l'outil prime » aussi")
    void theofficeGuideIsInjectedWhenTheToolsAreOpen() {
        service.setOfficeTool(openOfficeCatalog(), null);

        String system = systemPrompt();

        assertThat(system).contains(fr.claudegateway.office.OfficeToolCatalog.GUIDE);
        assertThat(system).contains(AtelierChatService.TOOL_PRIMACY)
                .contains("build_document").contains("build_spreadsheet");
    }

    @Test
    @DisplayName("SF-129-07 : sans service configuré, rien n'est promis — ni outils, ni guide")
    void withoutTheServiceNoOfficeGuide() {
        String system = systemPrompt();

        assertThat(system).doesNotContain(fr.claudegateway.office.OfficeToolCatalog.GUIDE);
    }

    /** Un catalogue Office RÉELLEMENT ouvert : le constructeur est configuré. */
    private fr.claudegateway.office.OfficeToolCatalog openOfficeCatalog() {
        fr.claudegateway.diagrams.DiagramProperties properties =
                new fr.claudegateway.diagrams.DiagramProperties();
        properties.setBaseUrl("http://diagram-renderer");
        return new fr.claudegateway.office.OfficeToolCatalog(
                new fr.claudegateway.office.HttpOfficeBuilder(properties,
                        new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    /** Un catalogue de diagrammes RÉELLEMENT ouvert : le moteur est configuré. */
    private fr.claudegateway.diagrams.DiagramToolCatalog openDiagramCatalog() {
        fr.claudegateway.diagrams.DiagramProperties properties =
                new fr.claudegateway.diagrams.DiagramProperties();
        properties.setBaseUrl("http://diagram-renderer");
        return new fr.claudegateway.diagrams.DiagramToolCatalog(
                new fr.claudegateway.diagrams.HttpDiagramRenderer(properties,
                        new com.fasterxml.jackson.databind.ObjectMapper()));
    }
}
