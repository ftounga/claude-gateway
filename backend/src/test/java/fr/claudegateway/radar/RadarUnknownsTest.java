package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.radar.dto.RadarSubjectPageViews.UnknownKind;
import fr.claudegateway.radar.dto.RadarSubjectPageViews.UnknownView;
import fr.claudegateway.radar.dto.RadarViews.CommitmentView;
import fr.claudegateway.radar.dto.RadarViews.EvidenceView;
import fr.claudegateway.radar.dto.RadarViews.PersonRef;
import fr.claudegateway.radar.dto.RadarViews.RoleView;
import fr.claudegateway.radar.dto.RadarViews.SubjectDetail;

/** F-103 / SF-103-02 — ce que le Radar ne sait pas, et à qui le demander, en fonction pure. */
class RadarUnknownsTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);

    private final Map<UUID, RadarPerson> directory = new HashMap<>();
    private final RadarPerson sophie = person("Sophie Laurent", "Cheffe de projet IAM");
    private final RadarPerson paul = person("Paul Martin", "Manager sécurité");
    private final RadarPerson karim = person("Karim Benali", null);

    private RadarPerson person(String name, String job) {
        RadarPerson p = RadarPerson.builder().id(UUID.randomUUID()).displayName(name).jobTitle(job)
                .sourceKey(name).build();
        directory.put(p.getId(), p);
        return p;
    }

    private static RoleView role(RadarPerson p, RadarRole role) {
        return new RoleView(UUID.randomUUID(), p.getId(), p.getDisplayName(), p.getJobTitle(), role, List.of());
    }

    private static EvidenceView proof(RadarPerson author, OffsetDateTime at) {
        return new EvidenceView(UUID.randomUUID(), RadarEvidenceSource.TEAMS_MESSAGE, "m", at, "q", null,
                author == null ? null : author.getId());
    }

    private static CommitmentView commitment(RadarCommitmentDirection direction, String description,
            RadarPerson from, RadarPerson to, RadarCertainty certainty, LocalDate due, boolean deduced,
            RadarCommitmentStatus status, boolean disowned, List<UUID> evidenceIds) {
        return new CommitmentView(UUID.randomUUID(), UUID.randomUUID(), "MFA", direction, description,
                from == null ? null : new PersonRef(from.getId(), from.getDisplayName()),
                to == null ? null : new PersonRef(to.getId(), to.getDisplayName()), null, due, deduced, status,
                certainty, false, disowned, evidenceIds, null, null, false, null, null);
    }

    /** Un sujet ouvert ; les listes sont modifiables pour que chaque test ajoute ce qu'il regarde. */
    private static final class Subject {
        RadarSubjectState state = RadarSubjectState.ADVANCING;
        String nextStep;
        LocalDate dueDate;
        OffsetDateTime lastActivityAt;
        OffsetDateTime dormantSince;
        UUID mergedIntoId;
        final List<RoleView> people = new ArrayList<>();
        final List<CommitmentView> commitments = new ArrayList<>();
        final List<EvidenceView> chronology = new ArrayList<>();

        SubjectDetail detail() {
            return new SubjectDetail(UUID.randomUUID(), "MFA", state, nextStep, dueDate, lastActivityAt, null,
                    mergedIntoId, null, null, List.of(), null, dormantSince, null, List.of(), false, false, false,
                    false, List.of(), List.of(), List.of(), List.of(), List.of(), people, commitments, chronology);
        }
    }

    private List<UnknownView> unknowns(Subject subject, RadarSyncStatus lastSync) {
        return RadarUnknowns.of(subject.detail(), directory, lastSync, PARIS, TODAY);
    }

    private static List<UnknownKind> kinds(List<UnknownView> unknowns) {
        return unknowns.stream().map(UnknownView::kind).toList();
    }

    @Test
    @DisplayName("sans prochaine étape, échéance ni décideur : trois manques, adressés au pilote")
    void subjectGapsGoToTheDriver() {
        Subject s = new Subject();
        s.people.add(role(sophie, RadarRole.DRIVES));
        s.people.add(role(karim, RadarRole.EXPERT));

        List<UnknownView> result = unknowns(s, RadarSyncStatus.SUCCEEDED);

        assertThat(kinds(result)).containsExactly(UnknownKind.NEXT_STEP, UnknownKind.DUE_DATE, UnknownKind.DECIDER);
        assertThat(result).allSatisfy(u -> {
            assertThat(u.ask().personId()).isEqualTo(sophie.getId());
            assertThat(u.ask().displayName()).isEqualTo("Sophie Laurent");
            assertThat(u.ask().jobTitle()).isEqualTo("Cheffe de projet IAM");
            assertThat(u.ask().role()).isEqualTo(RadarRole.DRIVES);
            assertThat(u.ask().reason()).isEqualTo("pilote le sujet");
        });
        assertThat(result.get(0).question()).isEqualTo("La prochaine étape n'est pas connue.");
    }

    @Test
    @DisplayName("sans pilote : le décideur pour l'étape et l'échéance")
    void deciderWhenNoDriver() {
        Subject s = new Subject();
        s.people.add(role(paul, RadarRole.DECIDES));

        List<UnknownView> result = unknowns(s, null);

        assertThat(kinds(result)).containsExactly(UnknownKind.NEXT_STEP, UnknownKind.DUE_DATE);
        assertThat(result.get(0).ask().displayName()).isEqualTo("Paul Martin");
        assertThat(result.get(0).ask().reason()).isEqualTo("décide sur ce sujet");
    }

    @Test
    @DisplayName("sans rôle : l'auteur de la preuve la plus récente, avec sa date")
    void latestAuthorWhenNoRole() {
        Subject s = new Subject();
        s.nextStep = "Lancer le pilote";
        s.dueDate = LocalDate.of(2026, 10, 1);
        s.chronology.add(proof(paul, OffsetDateTime.of(2026, 9, 4, 9, 0, 0, 0, ZoneOffset.UTC)));
        s.chronology.add(proof(karim, OffsetDateTime.of(2026, 9, 12, 14, 32, 0, 0, ZoneOffset.UTC)));
        s.chronology.add(proof(null, OffsetDateTime.of(2026, 9, 13, 8, 0, 0, 0, ZoneOffset.UTC)));

        List<UnknownView> result = unknowns(s, RadarSyncStatus.SUCCEEDED);

        assertThat(kinds(result)).containsExactly(UnknownKind.DECIDER);
        assertThat(result.get(0).ask().displayName()).isEqualTo("Karim Benali");
        assertThat(result.get(0).ask().role()).isNull();
        assertThat(result.get(0).ask().reason()).isEqualTo("a écrit en dernier sur le sujet, le 12 septembre");
    }

    @Test
    @DisplayName("personne de connu : le manque est dit sans destinataire")
    void nobodyKnown() {
        Subject s = new Subject();
        s.chronology.add(proof(null, OffsetDateTime.now()));

        assertThat(unknowns(s, null)).allSatisfy(u -> assertThat(u.ask()).isNull());
    }

    @Test
    @DisplayName("synchro partielle ou échouée : la couverture en tête, sans destinataire")
    void coverageFirst() {
        Subject s = new Subject();
        s.nextStep = "x";
        s.dueDate = TODAY;
        s.people.add(role(paul, RadarRole.DECIDES));

        assertThat(kinds(unknowns(s, RadarSyncStatus.PARTIAL))).containsExactly(UnknownKind.COVERAGE);
        assertThat(kinds(unknowns(s, RadarSyncStatus.FAILED))).containsExactly(UnknownKind.COVERAGE);
        assertThat(unknowns(s, RadarSyncStatus.FAILED).get(0).ask()).isNull();
        assertThat(unknowns(s, RadarSyncStatus.RUNNING)).isEmpty();
        assertThat(unknowns(s, RadarSyncStatus.SUCCEEDED)).isEmpty();
    }

    @Test
    @DisplayName("un sujet clos ou fusionné n'a aucun manque")
    void closedOrMergedHasNone() {
        Subject closed = new Subject();
        closed.state = RadarSubjectState.CLOSED;
        assertThat(unknowns(closed, RadarSyncStatus.PARTIAL)).isEmpty();

        Subject merged = new Subject();
        merged.mergedIntoId = UUID.randomUUID();
        assertThat(unknowns(merged, RadarSyncStatus.PARTIAL)).isEmpty();
    }

    @Test
    @DisplayName("en sommeil : le silence, avec le jour de la dernière activité")
    void dormantSubjectAsksWhatIsGoingOn() {
        Subject s = new Subject();
        s.state = RadarSubjectState.DORMANT;
        s.nextStep = "x";
        s.dueDate = TODAY;
        s.lastActivityAt = OffsetDateTime.of(2026, 8, 20, 10, 0, 0, 0, ZoneOffset.UTC);
        s.people.add(role(paul, RadarRole.DECIDES));

        List<UnknownView> result = unknowns(s, null);

        assertThat(kinds(result)).containsExactly(UnknownKind.SILENCE);
        assertThat(result.get(0).question()).isEqualTo("Rien n'a bougé depuis le 20 août : où en est le sujet ?");
        assertThat(result.get(0).ask().displayName()).isEqualTo("Paul Martin");
    }

    @Test
    @DisplayName("un engagement probable est adressé à sa partie ; une échéance déduite est signalée")
    void commitments() {
        Subject s = new Subject();
        s.nextStep = "x";
        s.dueDate = TODAY;
        s.people.add(role(paul, RadarRole.DECIDES));
        s.people.add(role(sophie, RadarRole.DRIVES));
        EvidenceView karimSaid = proof(karim, OffsetDateTime.of(2026, 9, 10, 9, 0, 0, 0, ZoneOffset.UTC));
        s.chronology.add(karimSaid);
        CommitmentView fromPaul = commitment(RadarCommitmentDirection.OTHER_TO_ME, "Valider le périmètre", paul, null,
                RadarCertainty.PROBABLE, null, false, RadarCommitmentStatus.OPEN, false, List.of());
        CommitmentView noParty = commitment(RadarCommitmentDirection.ME_TO_OTHER, "Rédiger la note DSI", null, null,
                RadarCertainty.PROBABLE, null, false, RadarCommitmentStatus.OPEN, false, List.of(karimSaid.id()));
        CommitmentView deduced = commitment(RadarCommitmentDirection.ME_TO_OTHER, "Envoyer le plan", null, sophie,
                RadarCertainty.CERTAIN, LocalDate.of(2026, 10, 2), true, RadarCommitmentStatus.POSTPONED, false, List.of());
        s.commitments.add(fromPaul);
        s.commitments.add(noParty);
        s.commitments.add(deduced);
        // Ignorés : tenu, désavoué, certain à échéance écrite.
        s.commitments.add(commitment(RadarCommitmentDirection.OTHER_TO_ME, "Tenu", paul, null, RadarCertainty.PROBABLE,
                null, false, RadarCommitmentStatus.KEPT, false, List.of()));
        s.commitments.add(commitment(RadarCommitmentDirection.OTHER_TO_ME, "Pas moi", paul, null, RadarCertainty.PROBABLE,
                null, false, RadarCommitmentStatus.OPEN, true, List.of()));
        s.commitments.add(commitment(RadarCommitmentDirection.OTHER_TO_ME, "Écrit", paul, null, RadarCertainty.CERTAIN,
                TODAY, false, RadarCommitmentStatus.OPEN, false, List.of()));

        List<UnknownView> result = unknowns(s, null);

        assertThat(kinds(result)).containsExactly(UnknownKind.OWNER, UnknownKind.OWNER, UnknownKind.DEDUCED_DUE);
        assertThat(result.get(0).question()).isEqualTo("« Valider le périmètre » : évoqué, sans porteur certain.");
        assertThat(result.get(0).ask().displayName()).isEqualTo("Paul Martin");
        assertThat(result.get(0).ask().reason()).isEqualTo("est attendu sur cet engagement");
        assertThat(result.get(0).commitmentId()).isEqualTo(fromPaul.id());
        assertThat(result.get(1).ask().displayName()).isEqualTo("Karim Benali");
        assertThat(result.get(1).ask().reason()).isEqualTo("a écrit la preuve de cet engagement, le 10 septembre");
        assertThat(result.get(1).evidenceIds()).containsExactly(karimSaid.id());
        assertThat(result.get(2).question()).isEqualTo("« Envoyer le plan » : l'échéance du 2 octobre est déduite, pas écrite.");
        assertThat(result.get(2).ask().reason()).isEqualTo("attend cet engagement");
    }

    @Test
    @DisplayName("au plus huit manques")
    void capped() {
        Subject s = new Subject();
        s.people.add(role(sophie, RadarRole.DRIVES));
        for (int i = 0; i < 12; i++) {
            s.commitments.add(commitment(RadarCommitmentDirection.ME_TO_OTHER, "Tâche " + i, null, null,
                    RadarCertainty.PROBABLE, null, false, RadarCommitmentStatus.OPEN, false, List.of()));
        }

        List<UnknownView> result = unknowns(s, RadarSyncStatus.PARTIAL);

        assertThat(result).hasSize(RadarUnknowns.MAX_UNKNOWNS);
        assertThat(result.get(0).kind()).isEqualTo(UnknownKind.COVERAGE);
        // Sans partie ni preuve, l'engagement retombe sur le pilote.
        assertThat(result.get(7).ask().reason()).isEqualTo("pilote le sujet");
    }
}
