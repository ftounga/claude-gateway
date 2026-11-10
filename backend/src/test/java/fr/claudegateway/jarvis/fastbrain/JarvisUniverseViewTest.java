package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.jarvis.universe.JarvisUniverse;

/** F-253 / SF-253-02 — l'action {@code univers} : validée, vérifiée contre l'univers du compte, résultat réel. */
class JarvisUniverseViewTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JarvisUniverse.Chantier chantier(int n, List<String> waiting, boolean step) {
        return new JarvisUniverse.Chantier(n, "Chantier " + n, "EXECUTION", "Exécution", 1, 4,
                step ? new JarvisUniverse.Step(2, "Valider") : null, false, false, waiting);
    }

    static JarvisUniverse universe() {
        JarvisUniverse.Client cagip = new JarvisUniverse.Client(UUID.randomUUID(), "CAGIP", false, false, "ACTIVE",
                List.of(new JarvisUniverse.Subject(UUID.randomUUID(), "data-platform", false, false,
                        List.of(chantier(3, List.of(), true))),
                        new JarvisUniverse.Subject(UUID.randomUUID(), "agenor", false, false,
                                List.of(chantier(6, List.of("Killian"), true)))),
                List.of(), List.of("Point Loki"), false);
        JarvisUniverse.Client edenred = new JarvisUniverse.Client(UUID.randomUUID(), "Edenred", false, true, "ACTIVE",
                List.of(new JarvisUniverse.Subject(UUID.randomUUID(), "helios", false, false, List.of())), List.of(),
                List.of(), false);
        return new JarvisUniverse(OffsetDateTime.now(), List.of(cagip, edenred), 1);
    }

    private static JarvisShowCommand cmd(String raw) throws Exception {
        return JarvisShowCommand.parse(JSON.readTree(raw), 3).command();
    }

    @Test
    void aViewWithoutKnownViewIsRefused() throws Exception {
        JarvisShowCommand.Parsed parsed = JarvisShowCommand.parse(JSON.readTree("""
                {"action":"univers","vue":"","client":"","raison":"demande"}"""), 3);
        assertThat(parsed.command()).isNull();
        assertThat(parsed.refusal()).isEqualTo(JarvisShowCommand.NO_VIEW);
        assertThat(JarvisShowCommand.parse(JSON.readTree("{\"action\":\"univers\",\"vue\":\"planetes\"}"), 3)
                .command()).isNull();
    }

    @Test
    void clientsAreListedAsTheyWillAppear() throws Exception {
        JarvisUniverseView.Check check = JarvisUniverseView.check(
                cmd("{\"action\":\"univers\",\"vue\":\"clients\",\"client\":\"\"}"), universe());
        assertThat(check.command().view()).isEqualTo("clients");
        assertThat(check.command().pieces()).isEmpty();
        assertThat(check.result()).isEqualTo("affiché : 2 clients — CAGIP, Edenred");
    }

    @Test
    void aClientSaidAsTranscribedIsMatchedToItsRealName() throws Exception {
        JarvisUniverseView.Check check = JarvisUniverseView.check(
                cmd("{\"action\":\"univers\",\"vue\":\"sujets\",\"client\":\"Kajip\"}"), universe());
        assertThat(check.command().client()).isEqualTo("CAGIP");
        assertThat(check.result()).isEqualTo("affiché : 2 sujets chez CAGIP — data-platform (CAGIP), agenor (CAGIP)");
    }

    @Test
    void anUnknownClientIsRefusedWithTheKnownOnes() throws Exception {
        JarvisUniverseView.Check check = JarvisUniverseView.check(
                cmd("{\"action\":\"univers\",\"vue\":\"clients\",\"client\":\"Orange\"}"), universe());
        assertThat(check.command()).isNull();
        assertThat(check.result()).isEqualTo("refusé : client « Orange » inconnu ; clients : CAGIP, Edenred");
    }

    @Test
    void anEmptyViewIsNotRelayedButDisconnectedHostsAreAlwaysShown() throws Exception {
        JarvisUniverseView.Check none = JarvisUniverseView.check(
                cmd("{\"action\":\"univers\",\"vue\":\"chantiers-bloques\",\"client\":\"Edenred\"}"), universe());
        assertThat(none.command()).isNull();
        assertThat(none.result()).startsWith("rien à montrer : aucun chantier bloqué chez Edenred");
        JarvisUniverseView.Check blocked = JarvisUniverseView.check(
                cmd("{\"action\":\"univers\",\"vue\":\"chantiers-bloques\",\"client\":\"\"}"), universe());
        assertThat(blocked.result()).isEqualTo("affiché : 1 chantier bloqué — chantier 6 de agenor (attend Killian)");
        JarvisUniverseView.Check on = JarvisUniverseView.check(
                cmd("{\"action\":\"univers\",\"vue\":\"connectes\",\"client\":\"CAGIP\"}"), universe());
        assertThat(on.command()).isNotNull();
        assertThat(on.result()).isEqualTo("affiché : 0 poste connecté sur 1 chez CAGIP");
    }

    @Test
    void theToolResultIsTheCheckedResultNotAPrincipleOk() throws Exception {
        JarvisUniverseView.Check check = JarvisUniverseView.check(
                cmd("{\"action\":\"univers\",\"vue\":\"a-envoyer\",\"client\":\"\"}"), universe());
        JarvisShowCommand.Parsed parsed = new JarvisShowCommand.Parsed(check.command(), null, 0, 0, check.result());
        assertThat(parsed.toolResult()).isEqualTo("affiché : 1 message à envoyer — Point Loki (CAGIP)");
        JarvisShowCommand.Parsed refused = new JarvisShowCommand.Parsed(null, "refusé : client « X » inconnu", 0, 0);
        assertThat(refused.toolResult()).isEqualTo("refusé : client « X » inconnu");
    }

    @Test
    void namesMatchToleratesAccentsCaseDashesAndSmallTranscriptionErrors() {
        List<String> known = List.of("data-platform", "agenor", "CAGIP", "Edenred");
        assertThat(JarvisNameMatch.match("data platform", known, n -> n).found()).isEqualTo("data-platform");
        assertThat(JarvisNameMatch.match("Agénor", known, n -> n).found()).isEqualTo("agenor");
        assertThat(JarvisNameMatch.match("Kajip", known, n -> n).found()).isEqualTo("CAGIP");
        assertThat(JarvisNameMatch.match("Eden red", known, n -> n).found()).isEqualTo("Edenred");
        assertThat(JarvisNameMatch.match("Orange", known, n -> n).none()).isTrue();
        assertThat(JarvisNameMatch.match("helio", List.of("helios", "hélios-bis"), n -> n).found())
                .isEqualTo("helios");
        assertThat(JarvisNameMatch.match("run", List.of("orion-run", "edenred-run"), n -> n).none()).isTrue();
    }
}
