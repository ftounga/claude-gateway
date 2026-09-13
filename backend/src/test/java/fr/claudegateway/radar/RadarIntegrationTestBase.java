package fr.claudegateway.radar;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Socle des tests d'intégration du Radar (F-99) : base H2 migrée par Liquibase, tables du Radar
 * vidées avant chaque test, et deux comptes — Alice (deux postes) et Bob (un poste).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class RadarIntegrationTestBase {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected JwtService jwtService;
    @Autowired protected UserRepository userRepository;
    @Autowired protected RunnerHostRepository hostRepository;
    @Autowired protected RadarRegistry registry;
    @Autowired protected RadarSubjectRepository subjects;
    @Autowired protected RadarSubjectAliasRepository aliases;
    @Autowired protected RadarSubjectFactRepository facts;
    @Autowired protected RadarPersonRepository people;
    @Autowired protected RadarSubjectRoleRepository roles;
    @Autowired protected RadarCommitmentRepository commitments;
    @Autowired protected RadarEvidenceRepository evidence;
    @Autowired protected RadarEvidenceLinkRepository links;
    @Autowired protected RadarSyncRepository syncs;
    @Autowired protected RadarCorrectionRepository corrections;
    @Autowired protected RadarCorrectionService correctionService;
    @Autowired protected RadarPurgeRepository purges;
    @Autowired protected fr.claudegateway.radar.analysis.RadarAnalysisBatchRepository analysisBatches;
    @Autowired protected fr.claudegateway.radar.analysis.RadarAnalysisLeaseRepository analysisLeases;

    protected User alice;
    protected User bob;
    protected String aliceToken;
    protected String bobToken;
    /** Poste EDENRED d'Alice. */
    protected RadarScope aliceA;
    /** Poste CAGIP d'Alice. */
    protected RadarScope aliceB;
    /** Poste de Bob. */
    protected RadarScope bobScope;

    private int refSequence;

    @BeforeEach
    void resetRadar() {
        cleanRadarTables();
        hostRepository.deleteAll();
        userRepository.deleteAll();
        alice = seedUser("alice-radar@example.com", UserRole.ADMIN);
        bob = seedUser("bob-radar@example.com", UserRole.ADMIN);
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
        aliceA = new RadarScope(alice.getId(), seedHost(alice.getId(), "EDENRED"));
        aliceB = new RadarScope(alice.getId(), seedHost(alice.getId(), "CAGIP"));
        bobScope = new RadarScope(bob.getId(), seedHost(bob.getId(), "Poste de Bob"));
    }

    /** Vide les tables du Radar ; les sous-classes qui ajoutent des tables la surchargent. */
    protected void cleanRadarTables() {
        corrections.deleteAll();
        analysisBatches.deleteAll();
        analysisLeases.deleteAll();
        purges.deleteAll();
        links.deleteAll();
        evidence.deleteAll();
        commitments.deleteAll();
        roles.deleteAll();
        people.deleteAll();
        facts.deleteAll();
        aliases.deleteAll();
        subjects.deleteAll();
        syncs.deleteAll();
    }

    protected User seedUser(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    protected UUID seedHost(UUID userId, String name) {
        return hostRepository.save(RunnerHost.builder().userId(userId).name(name).build()).getId();
    }

    /** Une preuve Teams neuve, à l'instant donné. */
    protected RadarEvidence proof(RadarScope scope, String quote, OffsetDateTime at) {
        return registry.recordEvidence(scope, new RadarRegistry.EvidenceInput(
                RadarEvidenceSource.TEAMS_MESSAGE, "msg-" + (++refSequence), at, quote,
                "https://teams.microsoft.com/l/message/" + refSequence, null));
    }

    protected RadarEvidence proof(RadarScope scope, String quote) {
        return proof(scope, quote, OffsetDateTime.now().minusHours(1));
    }

    protected static List<UUID> ids(RadarEvidence... proofs) {
        return java.util.Arrays.stream(proofs).map(RadarEvidence::getId).toList();
    }

    protected String url(RadarScope scope, String path) {
        return "/api/radar/hosts/" + scope.hostId() + path;
    }
}
