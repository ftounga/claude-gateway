package fr.claudegateway.teams.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.radar.RadarCommitment;
import fr.claudegateway.radar.RadarCommitmentDirection;
import fr.claudegateway.radar.RadarCommitmentRepository;
import fr.claudegateway.radar.RadarCommitmentStatus;
import fr.claudegateway.radar.RadarCorrection;
import fr.claudegateway.radar.RadarCorrectionAction;
import fr.claudegateway.radar.RadarCorrectionRepository;
import fr.claudegateway.radar.RadarCorrectionService;
import fr.claudegateway.radar.RadarEvidence;
import fr.claudegateway.radar.RadarEvidenceLinkRepository;
import fr.claudegateway.radar.RadarEvidenceRepository;
import fr.claudegateway.radar.RadarEvidenceSource;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarSubject;
import fr.claudegateway.radar.RadarSubjectRepository;
import fr.claudegateway.radar.RadarSubjectState;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.teams.meeting.dto.MeetingActionsToRadar;
import fr.claudegateway.teams.meeting.dto.MeetingActionsToRadar.PushedAction;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Le pont <b>actions d'une réunion → engagements Radar</b> (F-128 / SF-128-06), registre réel (H2).
 * On vérifie que les actions deviennent des engagements « À faire par moi » rattachés au bon sujet, avec
 * la réunion pour preuve, annulables ; que rien n'est écrit sans sujet ni sans matière ; que le doublon
 * est neutralisé ; et l'isolation par périmètre.
 */
@SpringBootTest
@ActiveProfiles("test")
class MeetingActionsToRadarServiceTest {

    @Autowired private MeetingActionsToRadarService service;
    @Autowired private MeetingRepository meetings;
    @Autowired private RadarSubjectRepository subjects;
    @Autowired private RadarCommitmentRepository commitments;
    @Autowired private RadarEvidenceRepository evidence;
    @Autowired private RadarEvidenceLinkRepository links;
    @Autowired private RadarCorrectionRepository corrections;
    @Autowired private RadarCorrectionService correctionService;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;

    private RadarScope scope;
    private RadarScope otherScope;

    @BeforeEach
    void setUp() {
        meetings.deleteAll();
        corrections.deleteAll();
        links.deleteAll();
        evidence.deleteAll();
        commitments.deleteAll();
        subjects.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        User alice = userRepository.save(User.builder().email("alice-a2r@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        User bob = userRepository.save(User.builder().email("bob-a2r@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        UUID aliceHost = hostRepository.save(RunnerHost.builder().userId(alice.getId()).name("EDENRED").build()).getId();
        UUID bobHost = hostRepository.save(RunnerHost.builder().userId(bob.getId()).name("Poste Bob").build()).getId();
        scope = new RadarScope(alice.getId(), aliceHost);
        otherScope = new RadarScope(bob.getId(), bobHost);
    }

    @Test
    @DisplayName("Nominal : les actions deviennent des engagements ME_TO_OTHER sur le sujet de la réunion, preuve = réunion")
    void pushesActionsAsEngagements() {
        RadarSubject subject = seedSubject(scope, "Migration EDENRED");
        UUID meetingId = seedMeeting(scope, subject.getId(), "Comité migration");

        MeetingActionsToRadar result = service.push(scope, meetingId, null,
                List.of("Envoyer le compte rendu", "Relancer l'infra sur les certificats"));

        assertThat(result.needsSubject()).isFalse();
        assertThat(result.added()).isEqualTo(2);
        assertThat(result.subjectId()).isEqualTo(subject.getId());
        assertThat(result.actions()).extracting(PushedAction::status)
                .containsExactly(PushedAction.ADDED, PushedAction.ADDED);

        List<RadarCommitment> saved = commitments.findByUserIdAndHostIdAndSubjectId(
                scope.userId(), scope.hostId(), subject.getId());
        assertThat(saved).hasSize(2);
        assertThat(saved).allSatisfy(c -> {
            assertThat(c.getDirection()).isEqualTo(RadarCommitmentDirection.ME_TO_OTHER);
            assertThat(c.getStatus()).isEqualTo(RadarCommitmentStatus.OPEN);
            assertThat(c.isSovereign()).isTrue();
        });
        assertThat(saved).extracting(RadarCommitment::getDescription)
                .containsExactlyInAnyOrder("Envoyer le compte rendu", "Relancer l'infra sur les certificats");

        // Preuve unique de type réunion, rattachée à la réunion par sourceRef.
        List<RadarEvidence> proofs = evidence.findByUserIdAndHostId(scope.userId(), scope.hostId());
        assertThat(proofs).hasSize(1);
        assertThat(proofs.get(0).getSource()).isEqualTo(RadarEvidenceSource.TEAMS_MEETING);
        assertThat(proofs.get(0).getSourceRef()).isEqualTo("teams-meeting:" + meetingId);
        assertThat(result.evidenceId()).isEqualTo(proofs.get(0).getId());
    }

    @Test
    @DisplayName("Le sujet de la requête prime sur celui de la réunion")
    void requestSubjectOverridesMeetingSubject() {
        RadarSubject meetingSubject = seedSubject(scope, "Sujet de la réunion");
        RadarSubject chosen = seedSubject(scope, "Sujet choisi par le consultant");
        UUID meetingId = seedMeeting(scope, meetingSubject.getId(), "Point hebdo");

        MeetingActionsToRadar result = service.push(scope, meetingId, chosen.getId(),
                List.of("Préparer le budget"));

        assertThat(result.subjectId()).isEqualTo(chosen.getId());
        assertThat(commitments.findByUserIdAndHostIdAndSubjectId(scope.userId(), scope.hostId(), chosen.getId()))
                .hasSize(1);
        assertThat(commitments.findByUserIdAndHostIdAndSubjectId(scope.userId(), scope.hostId(), meetingSubject.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("Aucun sujet (ni requête ni réunion) : needsSubject, rien n'est écrit")
    void needsSubjectWhenNoneAvailable() {
        UUID meetingId = seedMeeting(scope, null, "Réunion sans sujet");

        MeetingActionsToRadar result = service.push(scope, meetingId, null, List.of("Faire un truc"));

        assertThat(result.needsSubject()).isTrue();
        assertThat(result.added()).isZero();
        assertThat(commitments.findByUserIdAndHostId(scope.userId(), scope.hostId())).isEmpty();
        assertThat(evidence.findByUserIdAndHostId(scope.userId(), scope.hostId())).isEmpty();
    }

    @Test
    @DisplayName("Aucune action réelle : rien n'est écrit (pas même une preuve)")
    void nothingWhenNoActions() {
        RadarSubject subject = seedSubject(scope, "Sujet");
        UUID meetingId = seedMeeting(scope, subject.getId(), "Réunion");

        MeetingActionsToRadar result = service.push(scope, meetingId, null, List.of("   ", ""));

        assertThat(result.needsSubject()).isFalse();
        assertThat(result.added()).isZero();
        assertThat(commitments.findByUserIdAndHostId(scope.userId(), scope.hostId())).isEmpty();
        assertThat(evidence.findByUserIdAndHostId(scope.userId(), scope.hostId())).isEmpty();
    }

    @Test
    @DisplayName("Idempotence : re-pousser les mêmes actions ne crée pas de doublon")
    void isIdempotent() {
        RadarSubject subject = seedSubject(scope, "Sujet");
        UUID meetingId = seedMeeting(scope, subject.getId(), "Réunion");
        List<String> actions = List.of("Envoyer le CR", "Relancer l'infra");

        MeetingActionsToRadar first = service.push(scope, meetingId, null, actions);
        MeetingActionsToRadar second = service.push(scope, meetingId, null, actions);

        assertThat(first.added()).isEqualTo(2);
        assertThat(second.added()).isZero();
        assertThat(second.actions()).extracting(PushedAction::status)
                .containsExactly(PushedAction.SKIPPED, PushedAction.SKIPPED);
        assertThat(commitments.findByUserIdAndHostIdAndSubjectId(scope.userId(), scope.hostId(), subject.getId()))
                .hasSize(2);
        assertThat(evidence.findByUserIdAndHostId(scope.userId(), scope.hostId())).hasSize(1);
    }

    @Test
    @DisplayName("Annulable : la correction ADD_COMMITMENT écrite peut être défaite, l'engagement disparaît")
    void engagementsAreUndoable() {
        RadarSubject subject = seedSubject(scope, "Sujet");
        UUID meetingId = seedMeeting(scope, subject.getId(), "Réunion");
        service.push(scope, meetingId, null, List.of("Une action"));

        List<RadarCorrection> journal = corrections.findByUserIdAndHostIdAndEvidenceIdOrderByCreatedAtDesc(
                scope.userId(), scope.hostId(), evidence.findByUserIdAndHostId(scope.userId(), scope.hostId()).get(0).getId());
        assertThat(journal).hasSize(1);
        assertThat(journal.get(0).getAction()).isEqualTo(RadarCorrectionAction.ADD_COMMITMENT);

        correctionService.undo(scope, journal.get(0).getId());

        assertThat(commitments.findByUserIdAndHostIdAndSubjectId(scope.userId(), scope.hostId(), subject.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("Isolation : un sujet d'un autre poste est introuvable (404)")
    void crossHostSubjectIsNotFound() {
        RadarSubject bobSubject = seedSubject(otherScope, "Sujet de Bob");
        UUID meetingId = seedMeeting(scope, null, "Réunion d'Alice");

        assertThatThrownBy(() -> service.push(scope, meetingId, bobSubject.getId(), List.of("Action")))
                .isInstanceOf(MeetingNotFoundException.class);
        assertThat(commitments.findByUserIdAndHostId(scope.userId(), scope.hostId())).isEmpty();
    }

    // ------------------------------------------------------------------ montage

    private RadarSubject seedSubject(RadarScope where, String name) {
        return subjects.save(RadarSubject.builder().userId(where.userId()).hostId(where.hostId())
                .name(name).state(RadarSubjectState.NEW).build());
    }

    private UUID seedMeeting(RadarScope where, UUID subjectId, String title) {
        return meetings.save(Meeting.builder().userId(where.userId()).hostId(where.hostId())
                .subjectId(subjectId).state(MeetingState.STOPPED).meetingUrl("https://teams.microsoft.com/x")
                .consentAcknowledged(true).retentionDays(30).title(title).startedAt(OffsetDateTime.now())
                .transcript("[00:00] Bonjour").transcriptStatus(TranscriptStatus.TRANSCRIBED).build()).getId();
    }
}
