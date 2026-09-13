package fr.claudegateway.runner.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.host.dto.RunnerUpdateView;

/** F-111 / SF-111-01 — où en est le runner d'un poste. */
class RunnerUpdateAdvisorTest {

    private static final String SERVED = "1.1.0-202609200900-bbb2222";

    private static RunnerHost host(String version, Integer contract, Boolean launcher, Integer java,
            String capabilities) {
        return RunnerHost.builder().id(UUID.randomUUID()).userId(UUID.randomUUID()).name("Poste")
                .runnerVersion(version).runnerContract(contract).runnerLauncher(launcher)
                .runnerJava(java).runnerCapabilities(capabilities).build();
    }

    private static RunnerUpdateView advise(RunnerHost host, boolean usesTeams) {
        return RunnerUpdateAdvisor.advise(host, SERVED, 21, usesTeams);
    }

    @Test
    @DisplayName("à jour quand le runner est au moins aussi récent que celui servi")
    void aJour() {
        assertThat(advise(host(SERVED, 1, true, 21, "files,bash"), false).status())
                .isEqualTo("UP_TO_DATE");
        assertThat(advise(host("1.2.0-202601010000-ccc", 1, true, 21, "files"), false).status())
                .isEqualTo("UP_TO_DATE");
    }

    @Test
    @DisplayName("disponible d'un clic : plus ancien, sous lanceur, Java suffisant")
    void disponible() {
        RunnerUpdateView view = advise(host("1.0.0-202609131412-aaa1111", 1, true, 21, "files"), false);

        assertThat(view.status()).isEqualTo("AVAILABLE");
        assertThat(view.installedVersion()).isEqualTo("1.0.0");
        assertThat(view.servedVersion()).isEqualTo("1.1.0");
        assertThat(view.required()).isFalse();
    }

    @Test
    @DisplayName("manuelle une dernière fois : un runner sans lanceur")
    void manuelleUneDerniereFois() {
        assertThat(advise(host("0.0.1", null, null, null, "files,bash"), false).status())
                .isEqualTo("MANUAL_LAST_TIME");
        assertThat(advise(host("1.0.0-202609131412-aaa", 1, false, 21, "files"), false).status())
                .as("--no-launcher : pas de mise à jour d'un clic")
                .isEqualTo("MANUAL_LAST_TIME");
    }

    @Test
    @DisplayName("manuelle faute de Java : la version servie exige un Java plus récent")
    void manuelleFauteDeJava() {
        RunnerUpdateView view = RunnerUpdateAdvisor.advise(
                host("1.0.0-202609131412-aaa", 1, true, 21, "files"), SERVED, 25, false);

        assertThat(view.status()).isEqualTo("MANUAL_JAVA");
        assertThat(view.requiredJava()).isEqualTo(25);
        assertThat(view.installedJava()).isEqualTo(21);
    }

    @Test
    @DisplayName("inconnu : rien de servi, rien de déclaré, ou une version illisible")
    void inconnu() {
        assertThat(RunnerUpdateAdvisor.advise(host("1.0.0", 1, true, 21, "files"), null, 21, false)
                .status()).isEqualTo("UNKNOWN");
        assertThat(advise(host(null, null, null, null, null), false).status()).isEqualTo("UNKNOWN");
        assertThat(advise(host("maison-du-12", null, null, null, null), false).status())
                .isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("requise : un runner d'avant F-111 sans `teams` sur un poste qui sert Teams")
    void requise() {
        RunnerHost ancien = host("0.0.1", null, null, null, "files,bash");

        assertThat(advise(ancien, true).required()).isTrue();
        assertThat(advise(ancien, false).required())
                .as("un poste qui ne sert pas Teams n'a pas besoin de `teams`").isFalse();
        assertThat(advise(ancien, false).withTeamsUse(true).required())
                .as("client actif dans la Vigie, appris après coup").isTrue();
        assertThat(advise(host("0.0.1", null, null, null, "files,teams"), true).required()).isFalse();
        assertThat(advise(host("1.0.0-202609131412-aaa", 1, true, 21, "files"), true).required())
                .as("un runner récent sans `teams` a été lancé avec --no-teams : une mise à jour n'y "
                        + "changerait rien")
                .isFalse();
        assertThat(advise(host(SERVED, null, null, null, "files"), true).required())
                .as("à jour : rien n'est requis").isFalse();
    }

    @Test
    @DisplayName("aucune note tant que le manifeste ne les apporte pas")
    void pasDeNotes() {
        assertThat(advise(host("0.0.1", null, null, null, null), false).notes()).isEqualTo(List.of());
    }
}
