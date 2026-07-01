package fr.claudegateway.account;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.account.dto.AccountExport;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.byok.UserApiKeyRepository;
import fr.claudegateway.chat.Conversation;
import fr.claudegateway.chat.ConversationRepository;
import fr.claudegateway.chat.Message;
import fr.claudegateway.chat.MessageRepository;
import fr.claudegateway.quota.UsageCounterRepository;
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
    private final SubscriptionRepository subscriptionRepository;
    private final UsageCounterRepository usageCounterRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final UserApiKeyRepository userApiKeyRepository;
    private final TemplateRepository templateRepository;

    public AccountService(
            UserService userService,
            SubscriptionRepository subscriptionRepository,
            UsageCounterRepository usageCounterRepository,
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            UploadedFileRepository uploadedFileRepository,
            UserApiKeyRepository userApiKeyRepository,
            TemplateRepository templateRepository) {
        this.userService = userService;
        this.subscriptionRepository = subscriptionRepository;
        this.usageCounterRepository = usageCounterRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.userApiKeyRepository = userApiKeyRepository;
        this.templateRepository = templateRepository;
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
        subscriptionRepository.deleteByUserId(userId);
        userApiKeyRepository.deleteByUserId(userId);
        templateRepository.deleteByUserId(userId);

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
