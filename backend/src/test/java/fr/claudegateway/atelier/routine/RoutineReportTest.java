package fr.claudegateway.atelier.routine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import fr.claudegateway.push.PushEvent;

/** F-183 / SF-183-04 — la ligne de compte rendu, et les notifications neutres d'une routine. */
class RoutineReportTest {

    @Test
    void countsTheAttentesWithTheRightPlural() {
        assertThat(RoutineReport.summary("Routine du matin", RoutineRunStatus.DONE, 3))
                .isEqualTo("Routine du matin — 3 attentes mises à jour");
        assertThat(RoutineReport.summary("Routine du matin", RoutineRunStatus.DONE, 1))
                .isEqualTo("Routine du matin — 1 attente mise à jour");
        assertThat(RoutineReport.summary("Routine du matin", RoutineRunStatus.DONE, 0))
                .isEqualTo("Routine du matin — terminée, aucune attente modifiée");
        assertThat(RoutineReport.summary("Routine du matin", RoutineRunStatus.DONE, null))
                .isEqualTo("Routine du matin — terminée");
    }

    @Test
    void saysHowItEnded() {
        assertThat(RoutineReport.summary("R", RoutineRunStatus.INCOMPLETE, 2))
                .isEqualTo("R — 2 attentes mises à jour · INCOMPLET (budget atteint)");
        assertThat(RoutineReport.summary("R", RoutineRunStatus.FAILED, 0)).endsWith("· échec");
        assertThat(RoutineReport.summary("R", RoutineRunStatus.MISSED_OFFLINE, null))
                .isEqualTo("R — manquée — poste hors ligne");
        assertThat(RoutineReport.summary("R", RoutineRunStatus.SKIPPED_BUDGET, null))
                .isEqualTo("R — non lancée — budget du mois atteint");
        assertThat(RoutineReport.summary(null, RoutineRunStatus.DONE, 1)).startsWith("Routine — ");
    }

    @Test
    void isBounded() {
        assertThat(RoutineReport.summary("x".repeat(400), RoutineRunStatus.DONE, 1)).hasSize(RoutineReport.MAX);
    }

    @Test
    void notificationsAreNeutralDeliveredAndNotCritical() {
        for (PushEvent event : new PushEvent[] {PushEvent.ROUTINE_DONE, PushEvent.ROUTINE_STOPPED,
                PushEvent.ROUTINE_MISSED}) {
            assertThat(event.critical()).isFalse();
            assertThat(event.alwaysDelivered()).isTrue();
            assertThat(event.title() + event.body()).doesNotContainPattern("\\d");
        }
        assertThat(RoutineNotifier.eventFor(RoutineRunStatus.DONE)).isEqualTo(PushEvent.ROUTINE_DONE);
        assertThat(RoutineNotifier.eventFor(RoutineRunStatus.INCOMPLETE)).isEqualTo(PushEvent.ROUTINE_STOPPED);
        assertThat(RoutineNotifier.eventFor(RoutineRunStatus.SKIPPED_BUDGET)).isEqualTo(PushEvent.ROUTINE_STOPPED);
        assertThat(RoutineNotifier.eventFor(RoutineRunStatus.MISSED_OFFLINE)).isEqualTo(PushEvent.ROUTINE_MISSED);
        assertThat(RoutineNotifier.eventFor(RoutineRunStatus.RUNNING)).isNull();
    }
}
