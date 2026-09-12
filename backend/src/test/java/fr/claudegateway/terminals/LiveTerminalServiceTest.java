package fr.claudegateway.terminals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.terminals.dto.TerminalPreview;

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

    private final UUID alice = UUID.randomUUID();

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

    // ------------------------------------------------------------ aperçu (F-76 / SF-76-01)

    private LiveTerminal fiche(UUID workspaceId, TerminalActivity activity, String lines,
            OffsetDateTime at) {
        return LiveTerminal.builder()
                .id(UUID.randomUUID())
                .userId(alice)
                .workspaceId(workspaceId)
                .sessionId("onglet-" + UUID.randomUUID())
                .openedAt(at)
                .lastSeenAt(at)
                .activity(activity)
                .previewLines(lines)
                .activityAt(at)
                .build();
    }

    @Test
    void twoTabsOnTheSameProjectKeepTheFreshestPreview() {
        // La carte d'un poste parle du PROJET, pas de l'onglet : il faut trancher, et le plus
        // récent est la seule règle qui ne mente jamais sur « où en est-on ».
        UUID projet = UUID.randomUUID();
        OffsetDateTime vieux = OffsetDateTime.now().minusSeconds(20);
        OffsetDateTime recent = OffsetDateTime.now();
        when(repository.findByUserIdAndLastSeenAtAfterOrderByOpenedAtAsc(eq(alice), any()))
                .thenReturn(List.of(
                        fiche(projet, TerminalActivity.RUNNING, "ancien", vieux),
                        fiche(projet, TerminalActivity.IDLE, "frais", recent)));

        Map<UUID, TerminalPreview> previews =
                service(4, Duration.ofSeconds(90)).previewsByWorkspace(alice);

        assertThat(previews.get(projet).lines()).containsExactly("frais");
    }

    @Test
    void whatAwaitsApprovalWinsOverWhatIsMerelyRunning() {
        // C'est l'exigence non négociable : au même instant, l'attente passe devant. Un terminal
        // qui attend une autorisation est resté douze heures invisible le 2026-09-08 (F-47).
        UUID projet = UUID.randomUUID();
        OffsetDateTime meme = OffsetDateTime.now();
        when(repository.findByUserIdAndLastSeenAtAfterOrderByOpenedAtAsc(eq(alice), any()))
                .thenReturn(List.of(
                        fiche(projet, TerminalActivity.AWAITING_APPROVAL, "autorisation ?", meme),
                        fiche(projet, TerminalActivity.RUNNING, "npm test", meme)));

        Map<UUID, TerminalPreview> previews =
                service(4, Duration.ofSeconds(90)).previewsByWorkspace(alice);

        assertThat(previews.get(projet).activity()).isEqualTo(TerminalActivity.AWAITING_APPROVAL);
    }

    @Test
    void aTerminalWithNothingToSayHasNoPreviewAtAll() {
        // Une tuile qui afficherait « IDLE » et rien d'autre n'apprendrait rien de plus que la
        // pastille de vie déjà présente depuis F-70.
        UUID projet = UUID.randomUUID();
        when(repository.findByUserIdAndLastSeenAtAfterOrderByOpenedAtAsc(eq(alice), any()))
                .thenReturn(List.of(fiche(projet, null, null, OffsetDateTime.now())));

        assertThat(service(4, Duration.ofSeconds(90)).previewsByWorkspace(alice)).isEmpty();
    }
}
