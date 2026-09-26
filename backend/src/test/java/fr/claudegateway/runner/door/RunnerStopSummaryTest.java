package fr.claudegateway.runner.door;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.AtelierToolTrace;

/**
 * Le message rendu quand le poste tombe en plein tour (F-161 / SF-161-02).
 *
 * <p>Ce que ces tests tiennent : le texte <b>ne vient pas du modèle</b> et dit quand même
 * l'essentiel — ce qui a abouti avant la coupure. Sur la session mesurée, ces messages avaient de
 * la valeur ; on les rend ici pour zéro jeton.</p>
 */
class RunnerStopSummaryTest {

    private static AtelierToolTrace.Call ok(String name) {
        return new AtelierToolTrace.Call("c-" + name, name, null, "fait", false);
    }

    private static AtelierToolTrace.Call ko(String name) {
        return new AtelierToolTrace.Call("c-" + name, name, null, "runner_unavailable", true);
    }

    private static AtelierToolTrace trace(AtelierToolTrace.Call... calls) {
        return new AtelierToolTrace(List.of(new AtelierToolTrace.Step("je travaille", List.of(calls))));
    }

    @Test
    @DisplayName("les étapes ABOUTIES sont nommées — c'est l'essentiel du contenu qu'on aurait payé")
    void whatSucceededIsNamed() {
        String text = RunnerStopSummary.of(trace(ok("bash"), ok("write_file"), ko("bash")), "CAGIP");

        assertThat(text).startsWith(RunnerStopSummary.PREFIX);
        assertThat(text).contains("CAGIP");
        assertThat(text).contains("- bash").contains("- write_file");
        assertThat(text).contains("Dernier échec : bash");
        assertThat(text).contains("le travail ci-dessus est conservé");
    }

    @Test
    @DisplayName("aucune étape aboutie : on le dit, plutôt que de laisser une liste vide")
    void nothingSucceededIsSaid() {
        String text = RunnerStopSummary.of(trace(ko("bash")), "CAGIP");

        assertThat(text).contains("Aucune étape n'avait encore abouti");
        assertThat(text).contains("Dernier échec : bash");
    }

    @Test
    @DisplayName("un outil réussi plusieurs fois n'est nommé qu'une fois")
    void duplicatesAreCollapsed() {
        String text = RunnerStopSummary.of(trace(ok("bash"), ok("bash"), ok("bash")), null);

        assertThat(text.split("- bash", -1).length - 1).isEqualTo(1);
    }

    @Test
    @DisplayName("au-delà de six outils, la liste est écourtée — sinon elle cesse d'informer")
    void longListsAreTrimmed() {
        String text = RunnerStopSummary.of(
                trace(ok("a"), ok("b"), ok("c"), ok("d"), ok("e"), ok("f"), ok("g"), ok("h")), null);

        assertThat(text).contains("et 2 autre(s)");
        assertThat(text).doesNotContain("- g");
    }

    @Test
    @DisplayName("le CONTENU d'un résultat n'est jamais recopié — ce message est persisté")
    void resultsAreNeverEchoed() {
        AtelierToolTrace secret = new AtelierToolTrace(List.of(new AtelierToolTrace.Step(
                "je travaille",
                List.of(new AtelierToolTrace.Call("c1", "bash", null,
                        "AWS_SECRET_ACCESS_KEY=trescecret", false)))));

        String text = RunnerStopSummary.of(secret, "CAGIP");

        assertThat(text).doesNotContain("trescecret");
        assertThat(text).contains("- bash");
    }

    @Test
    @DisplayName("un poste sans nom, une trace vide ou nulle : le message reste lisible")
    void degradedInputsStayReadable() {
        assertThat(RunnerStopSummary.of(null, null)).contains("Aucune étape");
        assertThat(RunnerStopSummary.of(AtelierToolTrace.empty(), "  ")).contains("Aucune étape");
        assertThat(RunnerStopSummary.of(null, null)).doesNotContain("null");
    }

    @Test
    @DisplayName("l'issue se reconnaît à son début stable, et rien d'autre ne s'y confond")
    void theOutcomeIsRecognisable() {
        assertThat(RunnerStopSummary.isStopped(RunnerStopSummary.of(trace(ok("bash")), "CAGIP")))
                .isTrue();
        assertThat(RunnerStopSummary.isStopped("J'ai arrêté le travail en cours à ta demande."))
                .as("l'interruption utilisateur est une AUTRE cause").isFalse();
        assertThat(RunnerStopSummary.isStopped(null)).isFalse();
        assertThat(RunnerStopSummary.isStopped("")).isFalse();
    }
}
