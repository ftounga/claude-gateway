package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * Tests de la consommation par client (F-61 / SF-61-02) : regroupement par poste, projets dessous,
 * parts, coût, seau « hors client », et refus de fenêtre.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UsageByClientServiceTest {

    @Mock
    private UsageTurnRepository usageTurnRepository;
    @Mock
    private RunnerHostRepository hostRepository;
    @Mock
    private WorkspaceRepository workspaceRepository;

    private UsageByClientService service;

    private final UUID alice = UUID.randomUUID();
    private final UUID hostA = UUID.randomUUID();
    private final UUID hostB = UUID.randomUUID();
    private final UUID projectA1 = UUID.randomUUID();
    private final UUID projectA2 = UUID.randomUUID();
    private final UUID projectB1 = UUID.randomUUID();

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T08:00:00Z"), ZoneOffset.UTC);
    private final UsageReportProperties properties =
            new UsageReportProperties("EUR", 12, new BigDecimal("3.00"), new BigDecimal("15.00"));

    @BeforeEach
    void setUp() {
        service = new UsageByClientService(usageTurnRepository, hostRepository, workspaceRepository,
                new UsageCostEstimator(properties), clock);
        when(hostRepository.findByUserIdOrderByCreatedAtDesc(alice)).thenReturn(List.of(
                host(hostA, "poste-groupe-x"), host(hostB, "poste-mairie")));
        when(workspaceRepository.findByUserIdOrderByCreatedAtDesc(alice)).thenReturn(List.of(
                workspace(projectA1, "refonte-paie"),
                workspace(projectA2, "reprise-donnees"),
                workspace(projectB1, "portail-citoyen")));
    }

    private RunnerHost host(UUID id, String name) {
        return RunnerHost.builder().id(id).userId(alice).name(name)
                .createdAt(OffsetDateTime.now(clock)).updatedAt(OffsetDateTime.now(clock)).build();
    }

    private Workspace workspace(UUID id, String name) {
        return Workspace.builder().id(id).userId(alice).name(name).build();
    }

    private UsageTurnAggregate row(UUID hostId, UUID workspaceId, long input, long output) {
        return new UsageTurnAggregate() {
            @Override public UUID getHostId() {
                return hostId;
            }

            @Override public UUID getWorkspaceId() {
                return workspaceId;
            }

            @Override public long getInputTokens() {
                return input;
            }

            @Override public long getOutputTokens() {
                return output;
            }
        };
    }

    private void stubRows(UsageTurnAggregate... rows) {
        when(usageTurnRepository.aggregateByHostAndWorkspace(eq(alice), any(), any()))
                .thenReturn(List.of(rows));
    }

    @Test
    void emptyWhenNothingConsumed() {
        stubRows();

        UsageByClient usage = service.byClient(alice, null, null);

        assertThat(usage.clients()).isEmpty();
        assertThat(usage.totalTokens()).isZero();
        assertThat(usage.estimatedCost()).isEqualByComparingTo("0");
        assertThat(usage.currency()).isEqualTo("EUR");
    }

    @Test
    void twoProjectsOfTheSameHostAreOneClient() {
        stubRows(row(hostA, projectA1, 600_000L, 100_000L),
                row(hostA, projectA2, 300_000L, 80_000L));

        UsageByClient usage = service.byClient(alice, null, null);

        assertThat(usage.clients()).hasSize(1);
        UsageByClient.ClientUsage client = usage.clients().get(0);
        assertThat(client.hostName()).isEqualTo("poste-groupe-x");
        assertThat(client.inputTokens()).isEqualTo(900_000L);
        assertThat(client.outputTokens()).isEqualTo(180_000L);
        assertThat(client.projects()).hasSize(2);
        // Projets triés par consommation décroissante.
        assertThat(client.projects().get(0).name()).isEqualTo("refonte-paie");
    }

    @Test
    void twoHostsAreTwoClientsSortedByConsumption() {
        stubRows(row(hostB, projectB1, 100_000L, 10_000L),
                row(hostA, projectA1, 900_000L, 180_000L));

        UsageByClient usage = service.byClient(alice, null, null);

        assertThat(usage.clients()).hasSize(2);
        assertThat(usage.clients().get(0).hostName()).isEqualTo("poste-groupe-x");
        assertThat(usage.clients().get(1).hostName()).isEqualTo("poste-mairie");
    }

    @Test
    void turnsWithoutHostFallInTheOutsideBucketRenderedLast() {
        // Le seau « hors client » passe en dernier même quand il pèse le plus : ce n'est pas un
        // client, et le placer en tête ferait lire comme un client ce qui est son contraire.
        stubRows(row(null, null, 5_000_000L, 1_000_000L),
                row(hostA, projectA1, 10_000L, 1_000L));

        UsageByClient usage = service.byClient(alice, null, null);

        assertThat(usage.clients()).hasSize(2);
        assertThat(usage.clients().get(0).hostId()).isEqualTo(hostA);
        UsageByClient.ClientUsage outside = usage.clients().get(1);
        assertThat(outside.hostId()).isNull();
        assertThat(outside.hostName()).isNull();
        assertThat(outside.projects()).singleElement()
                .satisfies(project -> assertThat(project.workspaceId()).isNull());
    }

    @Test
    void clientsSumUpToTheTotal() {
        stubRows(row(hostA, projectA1, 600_000L, 100_000L),
                row(hostB, projectB1, 300_000L, 80_000L),
                row(null, null, 50_000L, 20_000L));

        UsageByClient usage = service.byClient(alice, null, null);

        long summed = usage.clients().stream().mapToLong(UsageByClient.ClientUsage::totalTokens).sum();
        assertThat(summed).isEqualTo(usage.totalTokens());
        assertThat(usage.inputTokens()).isEqualTo(950_000L);
        assertThat(usage.outputTokens()).isEqualTo(200_000L);
    }

    @Test
    void sharesSumToOne() {
        stubRows(row(hostA, projectA1, 750_000L, 0L),
                row(hostB, projectB1, 250_000L, 0L));

        UsageByClient usage = service.byClient(alice, null, null);

        assertThat(usage.clients().get(0).share()).isEqualByComparingTo("0.7500");
        assertThat(usage.clients().get(1).share()).isEqualByComparingTo("0.2500");
    }

    @Test
    void costAppliesBothRatesSeparately() {
        // 1 M d'entrée (3 €) + 200 k de sortie (3 €) = 6 €. Un tarif moyen se tromperait.
        stubRows(row(hostA, projectA1, 1_000_000L, 200_000L));

        UsageByClient usage = service.byClient(alice, null, null);

        assertThat(usage.clients().get(0).estimatedCost()).isEqualByComparingTo("6.0000");
    }

    @Test
    void deletedProjectKeepsItsSpendingWithoutName() {
        // La dépense a eu lieu : elle reste comptée, et l'écran dira « projet supprimé ».
        UUID gone = UUID.randomUUID();
        stubRows(row(hostA, gone, 10_000L, 1_000L));

        UsageByClient usage = service.byClient(alice, null, null);

        UsageByClient.ProjectUsage project = usage.clients().get(0).projects().get(0);
        assertThat(project.workspaceId()).isEqualTo(gone);
        assertThat(project.name()).isNull();
        assertThat(project.totalTokens()).isEqualTo(11_000L);
    }

    @Test
    void invertedWindowIsRefused() {
        assertThatThrownBy(() -> service.byClient(alice,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 4, 1)))
                .isInstanceOf(InvalidUsageWindowException.class);
    }

    @Test
    void windowLongerThanTheCapIsRefused() {
        assertThatThrownBy(() -> service.byClient(alice,
                LocalDate.of(2023, 1, 1), LocalDate.of(2026, 9, 1)))
                .isInstanceOf(InvalidUsageWindowException.class);
    }

    @Test
    void windowBoundsAreReportedBack() {
        stubRows();

        UsageByClient usage = service.byClient(alice,
                LocalDate.of(2026, 4, 17), LocalDate.of(2026, 9, 3));

        assertThat(usage.from()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(usage.to()).isEqualTo(LocalDate.of(2026, 9, 1));
    }
}
