package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>L'administrateur a tout</b> (F-107 / SF-107-06) : la source unique du rôle lue par les services
 * de droits. Fail-closed : seul {@code users.role = ADMIN} ouvre.
 */
@ExtendWith(MockitoExtension.class)
class AdministratorEntitlementTest {

    @Mock private UserRepository userRepository;

    private AdministratorEntitlement administrators;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        administrators = new AdministratorEntitlement(userRepository);
    }

    private void storedWithRole(UserRole role) {
        User user = new User();
        user.setRole(role);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("ADMIN en base : administrateur")
    void adminIsAdministrator() {
        storedWithRole(UserRole.ADMIN);

        assertThat(administrators.isAdministrator(userId)).isTrue();
    }

    @Test
    @DisplayName("USER en base : pas administrateur")
    void userIsNotAdministrator() {
        storedWithRole(UserRole.USER);

        assertThat(administrators.isAdministrator(userId)).isFalse();
    }

    @Test
    @DisplayName("utilisateur inconnu : pas administrateur (fail-closed)")
    void unknownUserIsNotAdministrator() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThat(administrators.isAdministrator(userId)).isFalse();
    }

    @Test
    @DisplayName("identifiant nul : pas administrateur, et la base n'est pas lue")
    void nullIdIsNotAdministrator() {
        assertThat(administrators.isAdministrator(null)).isFalse();
        verifyNoInteractions(userRepository);
    }
}
