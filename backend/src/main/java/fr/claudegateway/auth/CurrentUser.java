package fr.claudegateway.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Résout l'utilisateur courant depuis le {@code SecurityContext}.
 *
 * <p>Point d'entrée <b>unique</b> de l'isolation multi-tenant : tout service qui accède à des
 * données propres à un utilisateur récupère le {@code user_id} via {@link #requireId()} puis
 * filtre ses requêtes dessus. Ce composant est le patron que toutes les features V1 réutilisent.</p>
 */
@Component
public class CurrentUser {

    /**
     * Identifiant de l'utilisateur authentifié.
     *
     * @throws IllegalStateException si aucun utilisateur n'est authentifié (usage hors contexte protégé)
     */
    public UUID requireId() {
        return principal()
                .map(AuthenticatedUser::id)
                .orElseThrow(() -> new IllegalStateException("Aucun utilisateur authentifié dans le contexte de sécurité"));
    }

    /** Identifiant de l'utilisateur authentifié, ou vide si la requête est anonyme. */
    public Optional<UUID> id() {
        return principal().map(AuthenticatedUser::id);
    }

    /** Identité complète authentifiée, ou vide si la requête est anonyme. */
    public Optional<AuthenticatedUser> principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof AuthenticatedUser authenticatedUser) {
            return Optional.of(authenticatedUser);
        }
        return Optional.empty();
    }
}
