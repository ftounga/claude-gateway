package fr.claudegateway.terminals;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * Les <b>bornes</b> du registre des terminaux vivants (F-70 / SF-70-01).
 *
 * <p>Ce qui se vérifie ici ne dépend d'aucune base : que la configuration ne puisse pas défaire la
 * décision du PO. Le plafond de quatre est un <b>garde-fou de dépense</b> — quatre flux vivants,
 * ce sont quatre tours facturés en parallèle. Une propriété qui le relèverait ne serait pas un
 * réglage, ce serait le retrait du garde-fou ; elle est donc ramenée à quatre.</p>
 *
 * <p>Le délai de grâce est borné dans les deux sens pour la même raison : trop court, un battement
 * en retard perdrait sa place et le signe de vie clignoterait ; trop long, un onglet fermé
 * brutalement condamnerait une place pendant des heures.</p>
 */
@ExtendWith(MockitoExtension.class)
class LiveTerminalServiceTest {

    @Mock private LiveTerminalRepository repository;
    @Mock private WorkspaceService workspaceService;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private RunnerHostRepository hostRepository;

    private LiveTerminalService service(int limit, Duration ttl) {
        return new LiveTerminalService(repository, workspaceService, workspaceRepository,
                hostRepository, limit, ttl);
    }

    @Test
    void theCeilingCannotBeRaisedByConfiguration() {
        assertThat(service(9, Duration.ofMinutes(2)).limit()).isEqualTo(4);
        assertThat(service(Integer.MAX_VALUE, Duration.ofMinutes(2)).limit()).isEqualTo(4);
    }

    @Test
    void theCeilingCanBeLoweredButNeverBelowOne() {
        assertThat(service(2, Duration.ofMinutes(2)).limit()).isEqualTo(2);
        assertThat(service(0, Duration.ofMinutes(2)).limit()).isEqualTo(1);
        assertThat(service(-3, Duration.ofMinutes(2)).limit()).isEqualTo(1);
    }

    @Test
    void theGracePeriodIsClampedOnBothSides() {
        // Trop court : un battement de 30 s perdrait sa place avant même d'arriver.
        assertThat(service(4, Duration.ofSeconds(1)).ttl()).isEqualTo(LiveTerminalService.MIN_TTL);
        // Trop long : un onglet fermé brutalement condamnerait une place pendant des heures.
        assertThat(service(4, Duration.ofHours(3)).ttl()).isEqualTo(LiveTerminalService.MAX_TTL);
        // Absent : on retombe sur la borne basse, jamais sur « pas d'expiration ».
        assertThat(service(4, null).ttl()).isEqualTo(LiveTerminalService.MIN_TTL);
        // Dans les bornes : la valeur configurée est respectée telle quelle.
        assertThat(service(4, Duration.ofSeconds(90)).ttl()).isEqualTo(Duration.ofSeconds(90));
    }
}
