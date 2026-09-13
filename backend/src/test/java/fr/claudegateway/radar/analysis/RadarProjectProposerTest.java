package fr.claudegateway.radar.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarSubjectProjectService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;

/** F-106 / SF-106-06 — la lecture des noms de projet dans un échange. */
class RadarProjectProposerTest {

    private final RadarScope scope = new RadarScope(UUID.randomUUID(), UUID.randomUUID());
    private final UUID subjectId = UUID.randomUUID();
    private RadarSubjectProjectService links;
    private RadarProjectProposer proposer;

    @BeforeEach
    void setUp() {
        links = mock(RadarSubjectProjectService.class);
        RunnerHostRepository hosts = mock(RunnerHostRepository.class);
        when(hosts.findByIdAndUserId(scope.hostId(), scope.userId()))
                .thenReturn(Optional.of(RunnerHost.builder().name("Édenred").build()));
        when(links.propose(any(), any(), any())).thenReturn(true);
        proposer = new RadarProjectProposer(links, hosts);
    }

    private static Workspace project(String name, String path) {
        return Workspace.builder().id(UUID.randomUUID()).name(name).projectPath(path).build();
    }

    @Test
    void folderNameAndProjectNameAreRecognizedIgnoringCaseAndAccents() {
        Workspace billing = project("facturation", "clients/Billing-API");
        Workspace reseau = project("Réseau", "net");
        when(links.projectsOf(scope)).thenReturn(List.of(billing, reseau));

        int created = proposer.propose(scope, subjectId, List.of("MEP de BILLING-api jeudi", "le reseau tombe"));

        assertThat(created).isEqualTo(2);
        verify(links).propose(scope, subjectId, billing.getId());
        verify(links).propose(scope, subjectId, reseau.getId());
    }

    @Test
    void partialWordsShortNamesAndTheHostNameProposeNothing() {
        Workspace api = project("api", "api");
        Workspace root = project("EDENRED", "");
        Workspace web = project("web", "we");
        when(links.projectsOf(scope)).thenReturn(List.of(api, root, web));

        int created = proposer.propose(scope, subjectId, List.of("La rapidité chez edenred, le webinaire, we"));

        assertThat(created).isZero();
        verify(links, never()).propose(any(), any(), any());
    }

    @Test
    void anExistingPairIsNotCounted() {
        Workspace billing = project("billing", "billing");
        when(links.projectsOf(scope)).thenReturn(List.of(billing));
        when(links.propose(scope, subjectId, billing.getId())).thenReturn(false);

        assertThat(proposer.propose(scope, subjectId, List.of("billing en retard"))).isZero();
    }

    @Test
    void mentionsIsAWholeWordMatch() {
        assertThat(RadarProjectProposer.mentions("deploy billing-api now", "billing-api")).isTrue();
        assertThat(RadarProjectProposer.mentions("billing-apis", "billing-api")).isFalse();
        assertThat(RadarProjectProposer.mentions("(billing)", "billing")).isTrue();
        assertThat(RadarProjectProposer.mentions("rebilling", "billing")).isFalse();
    }
}
