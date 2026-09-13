package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.radar.dto.RadarBoardViews.BriefKind;
import fr.claudegateway.radar.dto.RadarBoardViews.BriefView;
import fr.claudegateway.radar.dto.RadarBoardViews.CoverageLine;
import fr.claudegateway.radar.dto.RadarViews.SyncView;
import fr.claudegateway.radar.sync.RadarCoverageSummary;

/** F-102 / SF-102-01 — le résumé du matin, composé depuis le registre. */
class RadarBriefServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 15, 7, 30, 0, 0, ZoneOffset.UTC);
    private static final LocalDate TODAY = NOW.toLocalDate();

    private final RadarScope scope = new RadarScope(UUID.randomUUID(), UUID.randomUUID());
    private final RadarSubjectRepository subjectRepo = mock(RadarSubjectRepository.class);
    private final RadarCommitmentRepository commitmentRepo = mock(RadarCommitmentRepository.class);
    private final RadarPersonRepository personRepo = mock(RadarPersonRepository.class);
    private final RadarReadService readService = mock(RadarReadService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final List<RadarSubject> subjects = new ArrayList<>();
    private final List<RadarCommitment> commitments = new ArrayList<>();
    private final List<RadarPerson> people = new ArrayList<>();
    private final List<SyncView> syncs = new ArrayList<>();
    private RadarBriefService service;
    private RadarSubject mfa;

    @BeforeEach
    void setUp() {
        when(subjectRepo.findByUserIdAndHostId(eq(scope.userId()), eq(scope.hostId()))).thenReturn(subjects);
        when(commitmentRepo.findByUserIdAndHostId(eq(scope.userId()), eq(scope.hostId()))).thenReturn(commitments);
        when(personRepo.findByUserIdAndHostIdOrderByDisplayNameAsc(eq(scope.userId()), eq(scope.hostId())))
                .thenReturn(people);
        when(readService.syncs(any())).thenReturn(syncs);
        service = new RadarBriefService(subjectRepo, commitmentRepo, personRepo, readService,
                Clock.fixed(Instant.from(NOW), ZoneOffset.UTC));
        mfa = subject("MFA prestataires", RadarSubjectState.ADVANCING, NOW.minusDays(10), NOW.minusDays(3));
    }

    private RadarSubject subject(String name, RadarSubjectState state, OffsetDateTime createdAt,
            OffsetDateTime lastActivityAt) {
        RadarSubject s = RadarSubject.builder().id(UUID.randomUUID()).userId(scope.userId()).hostId(scope.hostId())
                .name(name).state(state).createdAt(createdAt).lastActivityAt(lastActivityAt).build();
        subjects.add(s);
        return s;
    }

    private RadarCommitment commitment(RadarCommitmentDirection direction, String description, LocalDate dueDate,
            RadarCertainty certainty) {
        RadarCommitment c = RadarCommitment.builder().id(UUID.randomUUID()).userId(scope.userId())
                .hostId(scope.hostId()).subjectId(mfa.getId()).direction(direction).description(description)
                .dueDate(dueDate).status(RadarCommitmentStatus.OPEN).certainty(certainty)
                .createdAt(NOW.minusDays(5)).build();
        commitments.add(c);
        return c;
    }

    private RadarPerson person(String name) {
        RadarPerson p = RadarPerson.builder().id(UUID.randomUUID()).userId(scope.userId()).hostId(scope.hostId())
                .sourceKey(name).displayName(name).build();
        people.add(p);
        return p;
    }

    private SyncView sync(RadarSyncStatus status, OffsetDateTime finishedAt, String headline, String coverageJson)
            throws Exception {
        JsonNode coverage = coverageJson == null ? null : mapper.readTree(coverageJson);
        SyncView view = new SyncView(UUID.randomUUID(), status, finishedAt == null ? NOW.minusMinutes(5)
                : finishedAt.minusMinutes(30), finishedAt, coverage, 0L, null, RadarSyncTrigger.SCHEDULED, null, null,
                null, new RadarCoverageSummary(headline, null, List.of()));
        syncs.add(view);
        return view;
    }

    @Test
    @DisplayName("au plus trois phrases, dans l'ordre de priorité, chacune désigne son objet")
    void priorityAndCap() throws Exception {
        sync(RadarSyncStatus.SUCCEEDED, NOW.minusHours(9), "Synchro complète : 3 conversations lues.", "{}");
        RadarSubject ldap = subject("Migration LDAP", RadarSubjectState.CLOSED, NOW.minusDays(40), NOW.minusHours(3));
        ldap.setWokeAt(NOW.minusHours(3));
        RadarCommitment late = commitment(RadarCommitmentDirection.ME_TO_OTHER, "Présenter Sophie à Karim",
                TODAY.minusDays(3), RadarCertainty.CERTAIN);
        RadarPerson julie = person("Julie Robert");
        RadarCommitment waiting = commitment(RadarCommitmentDirection.OTHER_TO_ME, "Retour de l'éditeur SSO", null,
                RadarCertainty.CERTAIN);
        waiting.setFromPersonId(julie.getId());
        waiting.setFollowUpDueOn(TODAY.minusDays(1));
        subject("Accès réseau", RadarSubjectState.NEW, NOW.minusHours(2), NOW.minusHours(2));

        BriefView brief = service.brief(scope);

        assertThat(brief.sentences()).extracting(s -> s.kind())
                .containsExactly(BriefKind.WAKE, BriefKind.OVERDUE, BriefKind.FOLLOW_UP);
        assertThat(brief.sentences().get(0).text()).isEqualTo("Le sujet « Migration LDAP » se réveille : rouvrir ou laisser clos ?");
        assertThat(brief.sentences().get(0).subjectId()).isEqualTo(ldap.getId());
        assertThat(brief.sentences().get(1).text())
                .isEqualTo("Vous deviez « Présenter Sophie à Karim » pour le 12 septembre : en retard de 3 jours.");
        assertThat(brief.sentences().get(1).commitmentId()).isEqualTo(late.getId());
        assertThat(brief.sentences().get(2).text()).isEqualTo("Relance due : Julie Robert — « Retour de l'éditeur SSO ».");
        assertThat(brief.coverageComplete()).isTrue();
        assertThat(brief.coverageWarning()).isNull();
    }

    @Test
    @DisplayName("relances groupées, nouveaux sujets groupés, question, sujet qui avance, sommeil")
    void groupedAndLowerPriorities() throws Exception {
        sync(RadarSyncStatus.SUCCEEDED, NOW.minusHours(9), "Synchro complète.", "{}");
        RadarCommitment a = commitment(RadarCommitmentDirection.OTHER_TO_ME, "Devis", null, RadarCertainty.CERTAIN);
        a.setFromPersonId(person("Thomas Dubois").getId());
        a.setFollowUpDueOn(TODAY);
        RadarCommitment b = commitment(RadarCommitmentDirection.OTHER_TO_ME, "Planning", null, RadarCertainty.CERTAIN);
        b.setFollowUpDueOn(TODAY.minusDays(2));
        subject("A", RadarSubjectState.NEW, NOW.minusHours(1), NOW.minusHours(1));
        subject("B", RadarSubjectState.NEW, NOW.minusHours(2), NOW.minusHours(2));
        RadarCommitment note = commitment(RadarCommitmentDirection.ME_TO_OTHER, "Rédiger la note DSI", null,
                RadarCertainty.PROBABLE);
        note.setCreatedAt(NOW.minusHours(4));

        BriefView brief = service.brief(scope);
        assertThat(brief.sentences()).extracting(s -> s.text()).containsExactly(
                "2 relances dues : « Planning », Thomas Dubois.",
                "2 nouveaux sujets : « A », « B ».",
                "À confirmer : « Rédiger la note DSI » vous revient-il ?");

        subjects.removeIf(s -> s.getState() == RadarSubjectState.NEW);
        commitments.clear();
        mfa.setLastActivityAt(NOW.minusHours(5));
        mfa.setNextStep("note DSI");
        RadarSubject sso = subject("Bascule SSO", RadarSubjectState.DORMANT, NOW.minusDays(60), NOW.minusDays(21));
        sso.setDormantSince(NOW.minusHours(6));
        assertThat(service.brief(scope).sentences()).extracting(s -> s.text()).containsExactly(
                "« MFA prestataires » avance ; prochaine étape : note DSI.",
                "« Bascule SSO » n'a plus bougé depuis 21 jours : qu'en est-il ?");
    }

    @Test
    @DisplayName("désavoué, tenu ou sujet fusionné : ni phrase ni compteur ; toHandle sans double compte")
    void countsExcludeAndDeduplicate() throws Exception {
        sync(RadarSyncStatus.SUCCEEDED, NOW.minusHours(9), "Synchro complète.", "{}");
        RadarCommitment disowned = commitment(RadarCommitmentDirection.ME_TO_OTHER, "Pas moi", TODAY.minusDays(2),
                RadarCertainty.CERTAIN);
        disowned.setDisowned(true);
        commitment(RadarCommitmentDirection.ME_TO_OTHER, "Tenu", TODAY.minusDays(2), RadarCertainty.CERTAIN)
                .setStatus(RadarCommitmentStatus.KEPT);
        // Dû ET probable : compté une fois.
        commitment(RadarCommitmentDirection.ME_TO_OTHER, "Dû et probable", TODAY, RadarCertainty.PROBABLE);
        commitment(RadarCommitmentDirection.ME_TO_OTHER, "Plus tard", TODAY.plusDays(5), RadarCertainty.CERTAIN);
        commitment(RadarCommitmentDirection.INTRODUCTION, "Présenter A à B", null, RadarCertainty.CERTAIN);
        RadarCommitment waiting = commitment(RadarCommitmentDirection.OTHER_TO_ME, "Attendu", null, RadarCertainty.CERTAIN);
        waiting.setFollowUpDueOn(TODAY);
        subject("Bloqué", RadarSubjectState.BLOCKED, NOW.minusDays(9), NOW.minusDays(4));
        subject("Clos ?", RadarSubjectState.CLOSE_PROPOSED, NOW.minusDays(9), NOW.minusDays(4));
        subject("Clos", RadarSubjectState.CLOSED, NOW.minusDays(9), NOW.minusDays(4));
        RadarSubject merged = subject("Fusionné", RadarSubjectState.BLOCKED, NOW.minusDays(9), NOW.minusHours(1));
        merged.setMergedIntoId(mfa.getId());

        BriefView brief = service.brief(scope);
        assertThat(brief.counts().toDoByMe()).isEqualTo(2);
        assertThat(brief.counts().introductions()).isEqualTo(1);
        assertThat(brief.counts().followUpsDue()).isEqualTo(1);
        assertThat(brief.counts().subjectsFollowed()).isEqualTo(3);
        assertThat(brief.counts().blockedSubjects()).isEqualTo(1);
        // « Dû et probable » + « Attendu » + « Clos ? ».
        assertThat(brief.counts().toHandle()).isEqualTo(3);
        assertThat(brief.sentences()).extracting(s -> s.text()).noneMatch(t -> t.contains("Pas moi") || t.contains("Tenu"));
    }

    @Test
    @DisplayName("il dit ce qu'il n'a pas lu : partielle en tête, aucune synchro, synchro ancienne, première en cours")
    void coverageWarnings() throws Exception {
        BriefView none = service.brief(scope);
        assertThat(none.sentences()).isEmpty();
        assertThat(none.coverageComplete()).isFalse();
        assertThat(none.coverageWarning()).startsWith("Aucune synchro encore");

        SyncView running = sync(RadarSyncStatus.RUNNING, null, "Synchro en cours.", null);
        BriefView first = service.brief(scope);
        assertThat(first.running()).isEqualTo(running);
        assertThat(first.lastSync()).isNull();
        assertThat(first.coverageWarning()).startsWith("Première synchro en cours");

        sync(RadarSyncStatus.PARTIAL, NOW.minusHours(9), "Synchro partielle : 1 fil non entièrement lu.", "{}");
        BriefView partial = service.brief(scope);
        assertThat(partial.running()).isEqualTo(running);
        assertThat(partial.lastSync().status()).isEqualTo(RadarSyncStatus.PARTIAL);
        assertThat(partial.coverageComplete()).isFalse();
        assertThat(partial.coverageWarning()).isEqualTo("Synchro partielle : 1 fil non entièrement lu.");
        assertThat(partial.sentences()).extracting(s -> s.text()).containsExactly("Rien de nouveau dans ce qui a été lu.");

        syncs.clear();
        sync(RadarSyncStatus.SUCCEEDED, NOW.minusDays(3), "Synchro complète.", "{}");
        BriefView stale = service.brief(scope);
        assertThat(stale.coverageComplete()).isFalse();
        assertThat(stale.coverageWarning()).isEqualTo("Dernière synchro le 12 septembre : ce qui a bougé depuis n'a pas été lu.");
        assertThat(stale.sentences()).isEmpty();

        syncs.clear();
        sync(RadarSyncStatus.SUCCEEDED, NOW.minusHours(9), "Synchro faite : 2 conversations lues ; 1 canal actif non lu.", "{}");
        assertThat(service.brief(scope).coverageWarning()).isEqualTo("Synchro faite : 2 conversations lues ; 1 canal actif non lu.");

        syncs.clear();
        sync(RadarSyncStatus.SUCCEEDED, NOW.minusHours(9), "Synchro complète : 2 conversations lues.", "{}");
        BriefView calm = service.brief(scope);
        assertThat(calm.coverageWarning()).isNull();
        assertThat(calm.sentences()).extracting(s -> s.kind()).containsExactly(BriefKind.CALM);
    }

    @Test
    @DisplayName("lignes de couverture par source, et le manque qui touche chacune")
    void coverageLines() throws Exception {
        List<CoverageLine> lines = RadarBriefService.coverageLines(mapper.readTree("""
                {"conversations":{"active":25,"read":23,"partial":0,"failed":0},"messages":214,
                 "discovery":{"complete":true},"channels":{"unreadActive":0},
                 "meetings":{"seen":4,"transcribed":3,"noTranscript":1},
                 "depot":{"found":1,"transcribed":1,"failed":0,"unavailable":0}}
                """));
        assertThat(lines).containsExactly(
                new CoverageLine("TEAMS", true, "Teams · 23 fils lus sur 25 actifs, 214 messages"),
                new CoverageLine("MEETINGS", false, "3 réunions transcrites sur 4 vues"),
                new CoverageLine("RECORDINGS", true, "1 enregistrement déposé transcrit"));

        assertThat(RadarBriefService.coverageLines(mapper.readTree(
                "{\"conversations\":{\"read\":1,\"partial\":1},\"meetings\":{\"navigation\":\"REFUSED\"},\"depot\":{\"found\":0}}")))
                .containsExactly(new CoverageLine("TEAMS", false, "Teams · 1 fil lu"),
                        new CoverageLine("MEETINGS", false, "Calendrier inaccessible"));
        assertThat(RadarBriefService.coverageLines(null)).isEmpty();
    }

    @Test
    @DisplayName("dates et citations : 1er du mois, citation tronquée à 80 caractères")
    void wording() {
        assertThat(RadarBriefService.day(LocalDate.of(2026, 10, 1))).isEqualTo("1er octobre");
        assertThat(RadarBriefService.clip("x".repeat(120))).hasSize(RadarBriefService.QUOTE_MAX).endsWith("…");
    }
}
