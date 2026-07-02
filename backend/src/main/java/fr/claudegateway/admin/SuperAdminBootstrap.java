package fr.claudegateway.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Promotion du super-admin (F-20) : au démarrage, si un utilisateur porte l'e-mail configuré
 * ({@code app.admin.super-admin-email}) et n'est pas encore {@code ADMIN}, il est promu — le JWT émis
 * ensuite porte {@code ROLE_ADMIN}, ce qui active le lien Admin côté UI. Idempotent ; sans objet si
 * l'e-mail n'est pas configuré ou l'utilisateur n'existe pas encore.
 */
@Component
public class SuperAdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrap.class);

    private final UserRepository userRepository;
    private final String superAdminEmail;

    public SuperAdminBootstrap(UserRepository userRepository,
            @Value("${app.admin.super-admin-email:}") String superAdminEmail) {
        this.userRepository = userRepository;
        this.superAdminEmail = superAdminEmail == null ? "" : superAdminEmail.trim();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void promoteSuperAdmin() {
        if (!StringUtils.hasText(superAdminEmail)) {
            return;
        }
        userRepository.findByEmail(superAdminEmail.toLowerCase()).ifPresent(user -> {
            if (user.getRole() != UserRole.ADMIN) {
                user.setRole(UserRole.ADMIN);
                userRepository.save(user);
                // L'e-mail n'est pas un secret ; on trace la promotion pour l'audit.
                log.info("Super-admin promu au rôle ADMIN : {}", superAdminEmail);
            }
        });
    }
}
