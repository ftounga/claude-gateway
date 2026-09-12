package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * F-87 / SF-87-02 — <b>les cookies et les jetons Microsoft ne sont jamais rapatriés</b>.
 *
 * <p>C'est l'avantage propre de l'approche navigateur : la session reste chez l'utilisateur, et le
 * produit ne détient <b>rien</b> qui permettrait de se faire passer pour lui. Un avantage de ce
 * genre ne se conserve pas par intention : il se verrouille.</p>
 *
 * <p>Le faux navigateur de ces tests émet des réponses <b>délibérément</b> porteuses de
 * {@code Set-Cookie}, d'{@code Authorization} et d'un jeton de session dans leurs en-têtes. Rien ne
 * doit en ressortir — et rien ne le peut, puisque l'observation ne lit que l'adresse et le corps.</p>
 */
class AucunCookieNeRemonteTest {

    private static final List<String> FORBIDDEN =
            List.of("secret-", "set-cookie", "authorization", "bearer ", "skypetoken", "authtoken");

    private static final String MESSAGES_URL =
            "https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/conversations/19:x/messages";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final FakeCdpConnection browser = new FakeCdpConnection();
    private final NetworkObserver observer =
            new NetworkObserver(browser, TeamsAdapters.current());

    @Test
    @DisplayName("Les en-têtes d'une réponse ne franchissent jamais l'observation")
    void headers_never_cross_the_observer() throws Exception {
        observer.start();
        browser.emitResponse("req-1", MESSAGES_URL, "{\"messages\":[]}");

        List<ObservedResponse> observed = observer.collect();

        assertNoSecretIn(mapper.writeValueAsString(observed));
        assertNoSecretIn(observed.toString());
    }

    @Test
    @DisplayName("Une adresse porteuse d'un jeton perd sa chaîne de requête AVANT d'entrer")
    void a_token_bearing_url_is_truncated_on_entry() {
        observer.start();
        browser.emitResponse("req-1", MESSAGES_URL + "?skypetoken=SECRET-DANS-L-URL&pageSize=50",
                "{\"messages\":[]}");

        List<ObservedResponse> observed = observer.collect();

        assertEquals(MESSAGES_URL, observed.get(0).url(),
                "un jeton qui n'entre jamais dans l'objet ne peut pas en sortir");
        assertNoSecretIn(observed.toString());
    }

    @Test
    @DisplayName("Un manque situe la réponse sans recopier son adresse complète")
    void a_gap_never_quotes_a_full_url() {
        observer.start();
        browser.purge("req-1");
        browser.emitResponse("req-1", MESSAGES_URL + "?skypetoken=SECRET-DANS-L-URL", null);
        observer.collect();

        assertNoSecretIn(observer.gaps().get(0).describe());
    }

    @Test
    @DisplayName("L'objet d'observation n'a PAS de champ d'en-têtes : rien à oublier de vider")
    void the_observation_record_has_no_header_field() {
        List<String> fields = java.util.Arrays.stream(ObservedResponse.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase(Locale.ROOT))
                .toList();

        assertEquals(List.of("url", "kind", "body"), fields,
                "ajouter un champ d'en-têtes ici rouvrirait la porte que ce test ferme");
    }

    private static void assertNoSecretIn(String rendered) {
        String lower = rendered.toLowerCase(Locale.ROOT);
        for (String marker : FORBIDDEN) {
            assertTrue(lower.indexOf(marker) < 0,
                    "Un élément de session a franchi l'observation (« " + marker + " ») : "
                            + rendered);
        }
    }
}
