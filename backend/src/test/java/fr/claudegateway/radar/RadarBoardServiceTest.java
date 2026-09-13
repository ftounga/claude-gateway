package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.radar.dto.RadarBoardViews.BoardCommitment;
import fr.claudegateway.radar.dto.RadarViews.CommitmentView;

/** F-102 / SF-102-02 — un engagement dans sa colonne : dû, question, retard, preuve la plus récente. */
class RadarBoardServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);

    private static CommitmentView view(RadarCommitmentDirection direction, LocalDate dueDate, RadarCertainty certainty,
            boolean sovereign, LocalDate followUpDueOn, List<UUID> evidenceIds, String description) {
        return new CommitmentView(UUID.randomUUID(), UUID.randomUUID(), "MFA", direction, description, null, null,
                null, dueDate, false, RadarCommitmentStatus.OPEN, certainty, sovereign, false, evidenceIds, null,
                followUpDueOn, followUpDueOn != null && !followUpDueOn.isAfter(TODAY),
                OffsetDateTime.of(2026, 9, 1, 8, 0, 0, 0, ZoneOffset.UTC), null);
    }

    private static RadarEvidence proof(RadarEvidenceSource source, OffsetDateTime at, String link) {
        return RadarEvidence.builder().id(UUID.randomUUID()).source(source).sourceRef("ref-" + at).occurredAt(at)
                .quote("q").deepLink(link).build();
    }

    @Test
    @DisplayName("la preuve la plus récente donne la source, l'instant et le lien ; retard et question")
    void itemCarriesLatestProofAndUrgency() {
        RadarEvidence old = proof(RadarEvidenceSource.TEAMS_MESSAGE, OffsetDateTime.of(2026, 9, 10, 9, 0, 0, 0, ZoneOffset.UTC),
                "https://teams/old");
        RadarEvidence recent = proof(RadarEvidenceSource.TEAMS_MEETING, OffsetDateTime.of(2026, 9, 14, 14, 32, 0, 0, ZoneOffset.UTC),
                "https://teams/recent");
        CommitmentView late = view(RadarCommitmentDirection.ME_TO_OTHER, TODAY.minusDays(3), RadarCertainty.CERTAIN,
                false, null, List.of(old.getId(), recent.getId()), "Présenter Sophie");

        BoardCommitment item = RadarBoardService.item(late, Map.of(old.getId(), old, recent.getId(), recent), TODAY);
        assertThat(item.source()).isEqualTo(RadarEvidenceSource.TEAMS_MEETING);
        assertThat(item.sourceAt()).isEqualTo(recent.getOccurredAt());
        assertThat(item.deepLink()).isEqualTo("https://teams/recent");
        assertThat(item.due()).isTrue();
        assertThat(item.overdueDays()).isEqualTo(3);
        assertThat(item.question()).isFalse();

        BoardCommitment question = RadarBoardService.item(view(RadarCommitmentDirection.ME_TO_OTHER, null,
                RadarCertainty.PROBABLE, false, null, List.of(), "Note DSI"), Map.of(), TODAY);
        assertThat(question.question()).isTrue();
        assertThat(question.due()).isFalse();
        assertThat(question.source()).isNull();

        // Tranché par l'utilisateur (« c'est moi ») : ce n'est plus une question.
        assertThat(RadarBoardService.item(view(RadarCommitmentDirection.ME_TO_OTHER, null, RadarCertainty.PROBABLE,
                true, null, List.of(), "x"), Map.of(), TODAY).question()).isFalse();

        // Un attendu est dû dès que sa relance l'est.
        assertThat(RadarBoardService.item(view(RadarCommitmentDirection.OTHER_TO_ME, null, RadarCertainty.CERTAIN,
                false, TODAY, List.of(), "Devis"), Map.of(), TODAY).due()).isTrue();
        // À ma charge, une échéance future n'est pas due.
        assertThat(RadarBoardService.item(view(RadarCommitmentDirection.ME_TO_OTHER, TODAY.plusDays(1),
                RadarCertainty.CERTAIN, false, null, List.of(), "Plus tard"), Map.of(), TODAY).due()).isFalse();
    }

    @Test
    @DisplayName("rangement : dus d'abord, puis questions, puis par échéance, sans échéance à la fin")
    void urgencyOrder() {
        BoardCommitment later = RadarBoardService.item(view(RadarCommitmentDirection.ME_TO_OTHER, TODAY.plusDays(9),
                RadarCertainty.CERTAIN, false, null, List.of(), "plus tard"), Map.of(), TODAY);
        BoardCommitment none = RadarBoardService.item(view(RadarCommitmentDirection.ME_TO_OTHER, null,
                RadarCertainty.CERTAIN, false, null, List.of(), "sans échéance"), Map.of(), TODAY);
        BoardCommitment soon = RadarBoardService.item(view(RadarCommitmentDirection.ME_TO_OTHER, TODAY.plusDays(2),
                RadarCertainty.CERTAIN, false, null, List.of(), "bientôt"), Map.of(), TODAY);
        BoardCommitment question = RadarBoardService.item(view(RadarCommitmentDirection.ME_TO_OTHER, null,
                RadarCertainty.PROBABLE, false, null, List.of(), "question"), Map.of(), TODAY);
        BoardCommitment late = RadarBoardService.item(view(RadarCommitmentDirection.ME_TO_OTHER, TODAY.minusDays(1),
                RadarCertainty.CERTAIN, false, null, List.of(), "en retard"), Map.of(), TODAY);

        assertThat(java.util.stream.Stream.of(later, none, soon, question, late).sorted(RadarBoardService.urgency())
                .map(b -> b.commitment().description()))
                .containsExactly("en retard", "question", "bientôt", "plus tard", "sans échéance");
    }
}
