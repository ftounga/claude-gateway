package fr.claudegateway.admin;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.admin.dto.AdminUserView;
import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.quota.UsageCounter;
import fr.claudegateway.quota.UsageCounterRepository;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Service d'administration (F-20) : autorisation fine (rôle {@code ADMIN} ou super-admin par e-mail)
 * et agrégation transverse (utilisateurs + abonnements + consommation). Réservé à l'admin — n'affecte
 * pas l'isolation {@code user_id} des endpoints utilisateur.
 */
@Service
public class AdminService {

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UsageCounterRepository usageCounterRepository;
    private final CurrentUser currentUser;
    private final String superAdminEmail;

    public AdminService(UserRepository userRepository, SubscriptionRepository subscriptionRepository,
            UsageCounterRepository usageCounterRepository, CurrentUser currentUser,
            @Value("${app.admin.super-admin-email:}") String superAdminEmail) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.usageCounterRepository = usageCounterRepository;
        this.currentUser = currentUser;
        this.superAdminEmail = superAdminEmail == null ? "" : superAdminEmail.trim();
    }

    /**
     * Liste agrégée de tous les utilisateurs (abonnement + consommation totale de tokens).
     *
     * @throws AdminForbiddenException si l'appelant n'est ni ADMIN ni le super-admin configuré (403)
     */
    @Transactional(readOnly = true)
    public List<AdminUserView> listUsers() {
        assertAdmin();
        List<User> users = userRepository.findAll();
        List<AdminUserView> views = new ArrayList<>(users.size());
        for (User user : users) {
            Subscription subscription = subscriptionRepository.findByUserId(user.getId()).orElse(null);
            long totalTokens = usageCounterRepository.findByUserId(user.getId()).stream()
                    .mapToLong(UsageCounter::totalTokens)
                    .sum();
            views.add(new AdminUserView(
                    user.getId(),
                    user.getEmail(),
                    user.getRole().name(),
                    user.getCreatedAt(),
                    subscription != null && subscription.getPlanCode() != null
                            ? subscription.getPlanCode().name() : null,
                    subscription != null && subscription.getStatus() != null
                            ? subscription.getStatus().name() : null,
                    subscription != null ? subscription.getCurrentPeriodEnd() : null,
                    totalTokens));
        }
        return views;
    }

    /**
     * Autorise l'appelant : rôle {@code ADMIN} <b>ou</b> e-mail égal au super-admin configuré
     * (garantit l'accès même avant la promotion du rôle stocké).
     */
    private void assertAdmin() {
        AuthenticatedUser principal = currentUser.principal().orElseThrow(AdminForbiddenException::new);
        boolean admin = principal.role() == UserRole.ADMIN
                || (!superAdminEmail.isEmpty() && superAdminEmail.equalsIgnoreCase(principal.email()));
        if (!admin) {
            throw new AdminForbiddenException();
        }
    }
}
