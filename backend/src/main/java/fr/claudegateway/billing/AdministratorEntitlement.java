package fr.claudegateway.billing;

import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>Le compte administrateur a tout</b> (F-107 / SF-107-06, cadrage §3 bis — décision du PO du
 * 2026-09-13).
 *
 * <p>Un utilisateur de rôle {@link UserRole#ADMIN} a <b>tous les droits de fonctionnalité</b>, quel
 * que soit son plan : Forge, volet Teams, Radar — et tout droit futur. Cette source unique est lue
 * <b>par le service de droits lui-même</b> ({@link SpaceEntitlementService}, qui a absorbé en SF-107-02
 * les droits Atelier et Teams), jamais par un écran :
 * un droit ajouté demain l'hérite en appelant ce composant.</p>
 *
 * <p><b>Le rôle est lu en base</b> ({@code users.role}), pas dans le principal : les chemins qui
 * posent la question hors requête — la synchro de nuit du Radar, les relances du runner — n'ont
 * pas de principal, et deux sources du rôle finiraient par dire deux choses.</p>
 *
 * <p><b>Ce que le rôle n'ouvre pas</b> : le quota de jetons (paquet {@code quota}, jamais lu ici) et
 * le supplément par poste. Fail-closed : un utilisateur inconnu, ou un identifiant nul, n'est pas
 * administrateur.</p>
 */
@Component
public class AdministratorEntitlement {

    private final UserRepository userRepository;

    public AdministratorEntitlement(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Vrai si l'utilisateur est administrateur, et donc titulaire de tout droit de fonctionnalité.
     *
     * @param userId utilisateur du contexte de sécurité ou du tour (jamais un paramètre client)
     * @return {@code true} ssi {@code users.role = ADMIN}
     */
    public boolean isAdministrator(UUID userId) {
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId)
                .map(user -> user.getRole() == UserRole.ADMIN)
                .orElse(false);
    }
}
