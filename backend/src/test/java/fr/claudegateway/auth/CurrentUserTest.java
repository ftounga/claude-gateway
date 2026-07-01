package fr.claudegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import fr.claudegateway.user.UserRole;

/**
 * Tests unitaires de {@link CurrentUser} : extraction du {@code user_id} depuis le contexte
 * de sécurité (patron d'isolation) et comportement en l'absence d'authentification.
 */
class CurrentUserTest {

    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void extractsUserIdFromSecurityContext() {
        UUID userId = UUID.randomUUID();
        authenticateAs(new AuthenticatedUser(userId, "bob@example.com", UserRole.USER));

        assertThat(currentUser.requireId()).isEqualTo(userId);
        assertThat(currentUser.id()).contains(userId);
        assertThat(currentUser.principal()).map(AuthenticatedUser::email).contains("bob@example.com");
    }

    @Test
    void returnsEmptyWhenAnonymous() {
        assertThat(currentUser.id()).isEmpty();
        assertThat(currentUser.principal()).isEmpty();
        assertThatThrownBy(currentUser::requireId).isInstanceOf(IllegalStateException.class);
    }

    private void authenticateAs(AuthenticatedUser principal) {
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
