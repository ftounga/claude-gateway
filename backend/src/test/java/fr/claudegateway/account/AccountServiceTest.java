package fr.claudegateway.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.account.dto.AccountExport;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.chat.Conversation;
import fr.claudegateway.chat.ConversationRepository;
import fr.claudegateway.chat.Message;
import fr.claudegateway.chat.MessageRepository;
import fr.claudegateway.chat.MessageRole;
import fr.claudegateway.quota.UsageCounter;
import fr.claudegateway.quota.UsageCounterRepository;
import fr.claudegateway.upload.UploadedFile;
import fr.claudegateway.upload.UploadedFileRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRole;
import fr.claudegateway.user.UserService;

/**
 * Tests unitaires de {@link AccountService} : agrégation d'export (isolation {@code user_id},
 * abonnement absent) et suppression complète et ordonnée des données rattachées.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private UserService userService;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private UsageCounterRepository usageCounterRepository;
    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private UploadedFileRepository uploadedFileRepository;

    private AccountService service() {
        return new AccountService(userService, subscriptionRepository, usageCounterRepository,
                conversationRepository, messageRepository, uploadedFileRepository);
    }

    private User user(UUID id) {
        return User.builder().id(id).email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build();
    }

    @Test
    void exportAggregatesAllUserDataForTheGivenUserId() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(userService.findByIdOrThrow(userId)).thenReturn(user(userId));
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.of(
                Subscription.builder().userId(userId).status(SubscriptionStatus.TRIALING)
                        .planCode(PlanCode.SOLO).build()));
        when(usageCounterRepository.findByUserId(userId)).thenReturn(List.of(
                UsageCounter.builder().userId(userId).periodStart(LocalDate.of(2026, 7, 1))
                        .inputTokens(1200L).outputTokens(800L).build()));
        when(conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(
                Conversation.builder().id(conversationId).userId(userId).title("Analyse").model("m").build()));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(List.of(
                Message.builder().conversationId(conversationId).userId(userId)
                        .role(MessageRole.USER).content("Bonjour").build()));
        when(uploadedFileRepository.findByUserId(userId)).thenReturn(List.of(
                UploadedFile.builder().userId(userId).providerFileId("file_x").filename("c.pdf")
                        .mediaType("application/pdf").sizeBytes(2048L).build()));

        AccountExport export = service().export(userId);

        assertThat(export.account().email()).isEqualTo("alice@example.com");
        assertThat(export.subscription().status()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(export.subscription().planCode()).isEqualTo(PlanCode.SOLO);
        assertThat(export.usage()).singleElement()
                .satisfies(u -> assertThat(u.inputTokens()).isEqualTo(1200L));
        assertThat(export.conversations()).singleElement()
                .satisfies(c -> {
                    assertThat(c.title()).isEqualTo("Analyse");
                    assertThat(c.messages()).singleElement()
                            .satisfies(m -> assertThat(m.content()).isEqualTo("Bonjour"));
                });
        assertThat(export.uploadedFiles()).singleElement()
                .satisfies(f -> assertThat(f.filename()).isEqualTo("c.pdf"));
    }

    @Test
    void exportReturnsNullSubscriptionWhenAbsent() {
        UUID userId = UUID.randomUUID();
        when(userService.findByIdOrThrow(userId)).thenReturn(user(userId));
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(usageCounterRepository.findByUserId(userId)).thenReturn(List.of());
        when(conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());
        when(uploadedFileRepository.findByUserId(userId)).thenReturn(List.of());

        AccountExport export = service().export(userId);

        assertThat(export.subscription()).isNull();
        assertThat(export.conversations()).isEmpty();
    }

    @Test
    void deleteAccountRemovesAllRelatedDataThenTheUser() {
        UUID userId = UUID.randomUUID();
        when(userService.findByIdOrThrow(userId)).thenReturn(user(userId));

        service().deleteAccount(userId);

        InOrder order = inOrder(messageRepository, conversationRepository, uploadedFileRepository,
                usageCounterRepository, subscriptionRepository, userService);
        order.verify(messageRepository).deleteByUserId(userId);
        order.verify(conversationRepository).deleteByUserId(userId);
        order.verify(uploadedFileRepository).deleteByUserId(userId);
        order.verify(usageCounterRepository).deleteByUserId(userId);
        order.verify(subscriptionRepository).deleteByUserId(userId);
        order.verify(userService).deleteById(userId);
        verify(userService).findByIdOrThrow(any());
    }
}
