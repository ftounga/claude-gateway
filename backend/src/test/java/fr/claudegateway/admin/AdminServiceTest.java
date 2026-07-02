package fr.claudegateway.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.admin.dto.AdminUserView;
import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.quota.UsageCounter;
import fr.claudegateway.quota.UsageCounterRepository;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests unitaires de l'autorisation admin (rôle ADMIN / super-admin par e-mail) et de l'agrégation
 * utilisateurs + abonnement + consommation (F-20).
 */
@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private UsageCounterRepository usageCounterRepository;
    @Mock
    private CurrentUser currentUser;

    private AdminService serviceWithSuperAdmin(String email) {
        return new AdminService(userRepository, subscriptionRepository, usageCounterRepository,
                currentUser, email);
    }

    private void principal(String email, UserRole role) {
        when(currentUser.principal())
                .thenReturn(Optional.of(new AuthenticatedUser(UUID.randomUUID(), email, role)));
    }

    @Test
    void adminListsUsersAggregatingUsage() {
        AdminService service = serviceWithSuperAdmin("boss@example.com");
        principal("admin@example.com", UserRole.ADMIN);

        UUID uid = UUID.randomUUID();
        User user = User.builder().email("u@example.com").role(UserRole.USER).build();
        user.setId(uid);
        user.setCreatedAt(OffsetDateTime.now());
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(subscriptionRepository.findByUserId(uid)).thenReturn(Optional.empty());
        when(usageCounterRepository.findByUserId(uid)).thenReturn(List.of(
                UsageCounter.builder().inputTokens(10).outputTokens(5).build(),
                UsageCounter.builder().inputTokens(3).outputTokens(2).build()));

        List<AdminUserView> views = service.listUsers();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).email()).isEqualTo("u@example.com");
        assertThat(views.get(0).totalTokens()).isEqualTo(20L);
    }

    @Test
    void superAdminByEmailIsAuthorizedEvenWithUserRole() {
        AdminService service = serviceWithSuperAdmin("boss@example.com");
        // Rôle stocké encore USER mais e-mail == super-admin (casse insensible) → autorisé.
        principal("BOSS@example.com", UserRole.USER);
        when(userRepository.findAll()).thenReturn(List.of());

        assertThat(service.listUsers()).isEmpty();
    }

    @Test
    void plainUserIsForbidden() {
        AdminService service = serviceWithSuperAdmin("boss@example.com");
        principal("someone@example.com", UserRole.USER);

        assertThatThrownBy(service::listUsers).isInstanceOf(AdminForbiddenException.class);
        verify(userRepository, never()).findAll();
    }
}
