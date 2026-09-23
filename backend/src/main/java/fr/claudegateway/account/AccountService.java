package fr.claudegateway.account;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.account.dto.AccountExport;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.seat.HostSeatMonthRepository;
import fr.claudegateway.byok.UserApiKeyRepository;
import fr.claudegateway.chat.Conversation;
import fr.claudegateway.chat.ConversationRepository;
import fr.claudegateway.chat.Message;
import fr.claudegateway.chat.MessageRepository;
import fr.claudegateway.git.GitTokenService;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.chat.MessageLibraryDocumentRepository;
import fr.claudegateway.ocr.Document;
import fr.claudegateway.ocr.DocumentRepository;
import fr.claudegateway.quota.UsageCounterRepository;
import fr.claudegateway.quota.UsageTurnRepository;
import fr.claudegateway.radar.RadarPurgeService;
import fr.claudegateway.rag.ChunkRepository;
import fr.claudegateway.runner.RunnerPairingCodeRepository;
import fr.claudegateway.runner.RunnerTokenRepository;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.template.TemplateRepository;
import fr.claudegateway.upload.UploadedFileRepository;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserService;

/**
 * Opérations RGPD sur le compte courant : export de l'intégralité des données (art. 20) et
 * suppression définitive du compte et de toutes ses données rattachées (art. 17).
 *
 * <p>Racine d'isolation : chaque opération porte <b>exclusivement</b> sur le {@code user_id}
 * fourni par l'appelant (issu du {@code SecurityContext}, jamais d'un paramètre client). Aucun
 * accès n'est jamais élargi aux données d'un autre utilisateur.</p>
 */
@Service
public class AccountService {

    private final UserService userService;
    private final RadarPurgeService radarPurgeService;
    private final fr.claudegateway.runner.host.HostSpaceService hostSpaceService;
    /** F-136 / SF-136-01 : la copie de travail des cartes des clients de ce compte. */
    private final fr.claudegateway.governance.map.HostMapFileRepository hostMapFileRepository;
    /** F-148 / SF-148-06 : le cache des sources de la consigne (CLAUDE.md, STATE/PLAN, skills). */
    private final fr.claudegateway.atelier.promptsource.PromptSourceFileRepository promptSourceFileRepository;
    /** F-148 / SF-148-07 : l'index de repo persistant (chemins de fichiers des projets). */
    private final fr.claudegateway.atelier.repoindex.RepoIndexPathRepository repoIndexPathRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UsageCounterRepository usageCounterRepository;
    private final UsageTurnRepository usageTurnRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final UserApiKeyRepository userApiKeyRepository;
    private final GitTokenService gitTokenService;
    private final TemplateRepository templateRepository;
    private final RunnerTokenRepository runnerTokenRepository;
    private final RunnerPairingCodeRepository runnerPairingCodeRepository;
    private final RunnerAuditRepository runnerAuditRepository;
    private final RunnerHostRepository runnerHostRepository;
    private final HostSeatMonthRepository hostSeatMonthRepository;
    private final fr.claudegateway.terminals.LiveTerminalRepository liveTerminalRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceService workspaceService;
    private final AtelierMessageRepository atelierMessageRepository;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final MessageLibraryDocumentRepository messageLibraryDocumentRepository;

    public AccountService(
            UserService userService,
            SubscriptionRepository subscriptionRepository,
            UsageCounterRepository usageCounterRepository,
            UsageTurnRepository usageTurnRepository,
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            UploadedFileRepository uploadedFileRepository,
            UserApiKeyRepository userApiKeyRepository,
            GitTokenService gitTokenService,
            TemplateRepository templateRepository,
            RunnerTokenRepository runnerTokenRepository,
            RunnerPairingCodeRepository runnerPairingCodeRepository,
            RunnerAuditRepository runnerAuditRepository,
            RunnerHostRepository runnerHostRepository,
            HostSeatMonthRepository hostSeatMonthRepository,
            fr.claudegateway.terminals.LiveTerminalRepository liveTerminalRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceService workspaceService,
            AtelierMessageRepository atelierMessageRepository,
            DocumentRepository documentRepository,
            ChunkRepository chunkRepository,
            MessageLibraryDocumentRepository messageLibraryDocumentRepository,
            RadarPurgeService radarPurgeService,
            fr.claudegateway.runner.host.HostSpaceService hostSpaceService,
            fr.claudegateway.governance.map.HostMapFileRepository hostMapFileRepository,
            fr.claudegateway.atelier.promptsource.PromptSourceFileRepository promptSourceFileRepository,
            fr.claudegateway.atelier.repoindex.RepoIndexPathRepository repoIndexPathRepository) {
        this.radarPurgeService = radarPurgeService;
        this.hostSpaceService = hostSpaceService;
        this.hostMapFileRepository = hostMapFileRepository;
        this.promptSourceFileRepository = promptSourceFileRepository;
        this.repoIndexPathRepository = repoIndexPathRepository;
        this.userService = userService;
        this.subscriptionRepository = subscriptionRepository;
        this.usageCounterRepository = usageCounterRepository;
        this.usageTurnRepository = usageTurnRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.userApiKeyRepository = userApiKeyRepository;
        this.gitTokenService = gitTokenService;
        this.templateRepository = templateRepository;
        this.runnerTokenRepository = runnerTokenRepository;
        this.runnerPairingCodeRepository = runnerPairingCodeRepository;
        this.runnerAuditRepository = runnerAuditRepository;
        this.runnerHostRepository = runnerHostRepository;
        this.hostSeatMonthRepository = hostSeatMonthRepository;
        this.liveTerminalRepository = liveTerminalRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceService = workspaceService;
        this.atelierMessageRepository = atelierMessageRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.messageLibraryDocumentRepository = messageLibraryDocumentRepository;
    }

    /**
     * Les pages (F-109 / SF-109-04). Injectées par mutateur pour ne toucher à aucune forme de constructeur :
     * {@code null} (tests unitaires historiques) = aucun objet de page à effacer.
     */
    private fr.claudegateway.pages.PageService pageService;

    /** Branche la purge des pages à la suppression du compte (F-109 / SF-109-04). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPageService(fr.claudegateway.pages.PageService pageService) {
        this.pageService = pageService;
    }

    /**
     * Les pièces jointes des courriels en attente (F-110 / SF-110-03), même doctrine que les pages : {@code null}
     * (tests unitaires historiques) = rien à effacer.
     */
    private fr.claudegateway.mail.ClientMailAttachmentStore clientMailAttachmentStore;

    /** Branche l'effacement des pièces jointes en attente à la suppression du compte (F-110 / SF-110-03). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientMailAttachmentStore(fr.claudegateway.mail.ClientMailAttachmentStore store) {
        this.clientMailAttachmentStore = store;
    }

    /**
     * Les règles de permission d'outil (F-121 / SF-121-02), même doctrine que les pages : injectées par
     * mutateur pour ne toucher à aucune forme de constructeur ; {@code null} (tests unitaires
     * historiques) = rien à effacer.
     */
    private fr.claudegateway.atelier.permission.AtelierPermissionRuleRepository atelierPermissionRuleRepository;

    /** Branche la purge des règles de permission à la suppression du compte (F-121 / SF-121-02). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAtelierPermissionRuleRepository(
            fr.claudegateway.atelier.permission.AtelierPermissionRuleRepository repository) {
        this.atelierPermissionRuleRepository = repository;
    }

    /**
     * Agrège l'ensemble des données de l'utilisateur pour l'export RGPD. Lecture seule, filtrée
     * sur {@code userId} pour chaque source.
     */
    @Transactional(readOnly = true)
    public AccountExport export(UUID userId) {
        User user = userService.findByIdOrThrow(userId);

        AccountExport.Account account = new AccountExport.Account(
                user.getId(), user.getEmail(), user.isEmailVerified(),
                user.getProvider(), user.getRole(), user.getCreatedAt());

        AccountExport.SubscriptionExport subscription = subscriptionRepository.findByUserId(userId)
                .map(AccountService::toSubscriptionExport)
                .orElse(null);

        List<AccountExport.UsageExport> usage = usageCounterRepository.findByUserId(userId).stream()
                .map(counter -> new AccountExport.UsageExport(
                        counter.getPeriodStart(), counter.getInputTokens(), counter.getOutputTokens()))
                .toList();

        List<AccountExport.ConversationExport> conversations =
                conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                        .map(this::toConversationExport)
                        .toList();

        List<AccountExport.UploadedFileExport> files = uploadedFileRepository.findByUserId(userId).stream()
                .map(file -> new AccountExport.UploadedFileExport(
                        file.getFilename(), file.getMediaType(), file.getSizeBytes(), file.getCreatedAt()))
                .toList();

        List<AccountExport.TemplateExport> templates =
                templateRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                        .map(t -> new AccountExport.TemplateExport(
                                t.getName(), t.getCategory(), t.getContent(),
                                t.getCreatedAt(), t.getUpdatedAt()))
                        .toList();

        return new AccountExport(
                OffsetDateTime.now(), account, subscription, usage, conversations, files, templates);
    }

    /**
     * Supprime définitivement le compte et toutes les données rattachées au {@code userId}, dans
     * une transaction unique (tout ou rien). Ordre choisi pour respecter les contraintes
     * d'intégrité : d'abord les données filles filtrées par {@code user_id}, puis le compte
     * (dont la suppression fait tomber en cascade les jetons de vérification/réinitialisation).
     */
    @Transactional
    public void deleteAccount(UUID userId) {
        // Existence garantie par le filtre JWT en amont ; on lève proprement sinon (course).
        User user = userService.findByIdOrThrow(userId);

        messageRepository.deleteByUserId(userId);
        conversationRepository.deleteByUserId(userId);
        uploadedFileRepository.deleteByUserId(userId);
        usageCounterRepository.deleteByUserId(userId);
        // Journal de consommation par tour (F-61 / SF-61-01) : il décrit l'activité d'un
        // compte — quel projet, quel poste, quand — et ne lui survit pas.
        usageTurnRepository.deleteByUserId(userId);
        subscriptionRepository.deleteByUserId(userId);
        userApiKeyRepository.deleteByUserId(userId);
        // Secrets de l'utilisateur : la clé Claude ET le jeton GitHub (F-31) disparaissent avec le
        // compte. Le retrait passe par GitTokenService et non par le repository, car depuis SF-31-05
        // une COPIE du jeton peut vivre dans un vault chez le fournisseur d'agents : effacer la seule
        // ligne en base la laisserait active là-bas, et sans son identifiant plus rien ne permettrait
        // de la retrouver. `deleteToken` publie la révocation, consommée après commit, qui détruit le
        // vault.
        gitTokenService.deleteToken(userId);
        templateRepository.deleteByUserId(userId);
        // Domaine runner (F-38 / SF-38-14) : jetons, codes d'appairage et journal d'audit. Sans cette
        // purge, un jeton continuerait d'authentifier un runner jusqu'à son expiration (30 j) au nom
        // d'un compte effacé, et le journal — qui porte des chemins lus et des commandes exécutées —
        // survivrait au compte qu'il décrit. Aucune clé étrangère vers `users` n'existe dans ce
        // schéma : rien ne tombe en cascade, la purge doit être explicite.
        runnerTokenRepository.deleteByUserId(userId);
        runnerPairingCodeRepository.deleteByUserId(userId);
        runnerAuditRepository.deleteByUserId(userId);
        // Les POSTES en dernier (F-48 / SF-48-01) : ils portent la racine, le système et les droits
        // déclarés par la machine — des données personnelles, qui ne survivent pas au compte.
        // Mois-postes (F-65 / SF-65-01) AVANT les postes : ce sont des pièces de facturation, et
        // elles ne survivent pas au compte qu'elles décrivent.
        hostSeatMonthRepository.deleteByUserId(userId);
        // Le Radar (F-99 / SF-99-05) avant les postes : des extraits de communications internes du client
        // et des données de tiers, qui ne survivent ni au poste ni au compte.
        radarPurgeService.purgeUser(userId);
        // Les espaces des postes (F-106 / SF-106-01) : sans clé étrangère, ils partent explicitement.
        hostSpaceService.purgeUser(userId);
        // La copie de travail des cartes (F-136 / SF-136-01) : c'est le savoir accumulé sur
        // l'infrastructure des clients de ce compte. Il ne lui survit pas.
        hostMapFileRepository.deleteByUserId(userId);
        // Le cache des sources de la consigne (F-148 / SF-148-06) : copie des CLAUDE.md, STATE/PLAN et
        // skills des projets de ce compte. Il ne lui survit pas.
        promptSourceFileRepository.deleteByUserId(userId);
        // L'index de repo persistant (F-148 / SF-148-07) : chemins des fichiers des projets de ce
        // compte. Il ne lui survit pas.
        repoIndexPathRepository.deleteByUserId(userId);
        runnerHostRepository.deleteByUserId(userId);
        // Places de terminal vivant (F-70 / SF-70-01) : elles nomment les projets ouverts par le
        // compte. Sans purge, elles survivraient à sa suppression jusqu'à leur expiration.
        liveTerminalRepository.deleteByUserId(userId);
        // Domaine documentaire (F-05/F-06) et Atelier (F-28), ajoutés par SF-11-03. Ces données
        // survivaient au compte : documents OCR (texte extrait et réponse brute du fournisseur
        // compris), embeddings, historique des sessions d'agent, et les fichiers de chaque
        // workspace dans le stockage objet.
        // L'ordre suit les dépendances de lecture : les liens d'abord (ils ne portent pas d'user_id
        // et se retrouvent par leurs documents), puis les chunks, puis les documents.
        List<UUID> documentIds = documentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(Document::getId)
                .toList();
        if (!documentIds.isEmpty()) {
            messageLibraryDocumentRepository.deleteByDocumentIdIn(documentIds);
        }
        chunkRepository.deleteByUserId(userId);
        documentRepository.deleteByUserId(userId);
        atelierMessageRepository.deleteByUserId(userId);
        // F-121 / SF-121-02 : les règles de permission d'outil s'en vont avec le compte (elles portent
        // des préfixes de commande de l'utilisateur). Best-effort si la politique n'est pas branchée.
        if (atelierPermissionRuleRepository != null) {
            atelierPermissionRuleRepository.deleteByUserId(userId);
        }
        // On passe par WorkspaceService.delete plutôt que de supprimer les lignes directement : lui
        // seul connaît le préfixe de stockage (il porte le préfixe applicatif configuré, pas
        // seulement userId/workspaceId), et il efface fichiers, messages et ligne d'un seul geste.
        // Recalculer ce préfixe ici, c'est prendre le risque d'effacer à côté — donc de laisser les
        // fichiers en place en croyant les avoir supprimés.
        for (Workspace workspace : workspaceRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            workspaceService.delete(userId, workspace.getId());
        }
        // Les pages (F-109 / SF-109-04) : leurs objets d'abord — des documents qui portent souvent des données
        // de client ; leurs lignes tombent en cascade avec le compte.
        if (pageService != null) {
            pageService.purgeUser(userId);
        }
        // Les pièces jointes des courriels encore en file (F-110 / SF-110-03) : des documents du client ; les
        // lignes `client_emails` tombent en cascade avec le compte.
        if (clientMailAttachmentStore != null) {
            clientMailAttachmentStore.deleteAccount(userId);
        }

        userService.deleteById(user.getId());
    }

    private AccountExport.ConversationExport toConversationExport(Conversation conversation) {
        List<AccountExport.MessageExport> messages =
                messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId()).stream()
                        .map(AccountService::toMessageExport)
                        .toList();
        return new AccountExport.ConversationExport(
                conversation.getId(), conversation.getTitle(), conversation.getModel(),
                conversation.getCreatedAt(), messages);
    }

    private static AccountExport.MessageExport toMessageExport(Message message) {
        return new AccountExport.MessageExport(
                message.getRole(), message.getContent(), message.getModel(), message.getCreatedAt());
    }

    private static AccountExport.SubscriptionExport toSubscriptionExport(Subscription subscription) {
        return new AccountExport.SubscriptionExport(
                subscription.getStatus(), subscription.getPlanCode(),
                subscription.getTrialEndsAt(), subscription.getCurrentPeriodEnd());
    }
}
