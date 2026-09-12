package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * F-87 / SF-87-01 — <b>le verrou de sécurité du volet Teams</b>.
 *
 * <p>L'avantage de l'approche navigateur est que les cookies et les jetons Microsoft de la session
 * <b>ne sont jamais rapatriés</b> : c'est la session de l'utilisateur qui parle, et rien de ce qui
 * l'authentifie ne quitte sa machine. Cet avantage ne se préserve pas par intention — il se
 * verrouille par un test.</p>
 *
 * <p>La garantie est tenue <b>par construction</b> : l'adaptateur recopie uniquement les champs
 * qu'il déclare connaître, et aucun d'eux n'est un secret. Une réponse empoisonnée de jetons et de
 * cookies traverse donc la couche sans que rien n'en ressorte — y compris si Microsoft ajoutait
 * demain un champ que nous ne connaissons pas.</p>
 */
class AucunSecretNeSortTest {

    /** Les marqueurs glissés dans l'échantillon empoisonné, et ceux que porte un vrai Teams. */
    private static final List<String> FORBIDDEN = List.of("secret-", "skypetoken", "access_token",
            "refresh_token", "set-cookie", "bearer ", "authtoken");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("Une réponse empoisonnée de jetons ne laisse RIEN passer")
    void a_poisoned_body_leaks_nothing() throws Exception {
        JsonNode poisoned = TeamsSamples.read("conversation-messages-secrets.json");

        TeamsReading<TeamsMessage> reading = TeamsAdapters.current()
                .messages(TeamsSamples.MESSAGES_URL, poisoned, TeamsSamples.wideWindow());

        assertEquals(1, reading.items().size(), "le message ordinaire est lu normalement");
        assertNoSecretIn(mapper.writeValueAsString(reading));
        assertNoSecretIn(reading.toString());
        assertNoSecretIn(reading.summary("messages"));
        assertNoSecretIn(reading.health().describe());
    }

    @Test
    @DisplayName("La santé ne nomme que des champs attendus, jamais un champ observé")
    void health_never_echoes_an_observed_field_name() {
        TeamsHealth health = TeamsAdapters.current().inspect(TeamsSamples.MESSAGES_URL,
                TeamsSamples.read("conversation-messages-secrets.json"));

        assertNoSecretIn(health.describe());
        assertNoSecretIn(String.join(" ", health.missingFields()));
    }

    @Test
    @DisplayName("Même une URL porteuse d'un jeton ne se retrouve dans aucun résultat")
    void a_token_bearing_url_never_reaches_a_result() throws Exception {
        String url = TeamsSamples.MESSAGES_URL + "&skypetoken=SECRET-DANS-L-URL";

        TeamsReading<TeamsMessage> reading = TeamsAdapters.current().messages(url,
                TeamsSamples.read("conversation-messages.json"), TeamsSamples.wideWindow());

        assertNoSecretIn(mapper.writeValueAsString(reading));
        assertNoSecretIn(reading.health().describe());
    }

    @Test
    @DisplayName("Le refus d'une forme inconnue ne recopie pas le corps observé")
    void a_refusal_never_quotes_the_body() throws Exception {
        TeamsReading<TeamsMessage> refused = TeamsAdapters.current().messages(
                TeamsSamples.MESSAGES_URL, TeamsSamples.read("conversation-messages-unknown.json"),
                TeamsSamples.wideWindow());

        String rendered = mapper.writeValueAsString(refused) + refused.summary("messages");
        assertFalse(rendered.contains("Microsoft a tout changé"),
                "un refus dit ce qui a changé, il ne recrache pas ce qu'il a lu");
    }

    private static void assertNoSecretIn(String rendered) {
        String lower = rendered.toLowerCase(Locale.ROOT);
        for (String marker : FORBIDDEN) {
            assertTrue(lower.indexOf(marker) < 0,
                    "Un secret a franchi l'adaptateur Teams (« " + marker + " ») : " + rendered);
        }
    }
}
