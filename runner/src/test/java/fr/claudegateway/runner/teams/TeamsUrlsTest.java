package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-87 / SF-87-01 — <b>quelles URL portent quoi</b>, le premier savoir de l'adaptateur.
 *
 * <p>Le point important n'est pas qu'une adresse connue soit reconnue : c'est qu'une adresse
 * <b>inconnue</b> le reste ({@code UNKNOWN}, on ne lit pas le corps) et qu'une adresse <b>sans
 * intérêt</b> soit nommée comme telle ({@code IGNORED}). Confondre les deux ferait crier la sonde de
 * santé à chaque feuille de style chargée par la page.</p>
 */
class TeamsUrlsTest {

    @Test
    @DisplayName("Les sept familles de réponses sont reconnues")
    void classifies_the_seven_families() {
        assertEquals(TeamsPayloadKind.CONVERSATION_MESSAGES,
                TeamsUrls.classify(TeamsSamples.MESSAGES_URL));
        assertEquals(TeamsPayloadKind.CONVERSATION_LIST,
                TeamsUrls.classify(TeamsSamples.CONVERSATIONS_URL));
        assertEquals(TeamsPayloadKind.ACTIVITY_FEED,
                TeamsUrls.classify(TeamsSamples.ACTIVITY_URL));
        assertEquals(TeamsPayloadKind.SEARCH_RESULTS, TeamsUrls.classify(TeamsSamples.SEARCH_URL));
        assertEquals(TeamsPayloadKind.MEETING_DETAILS,
                TeamsUrls.classify(TeamsSamples.MEETINGS_URL));
        assertEquals(TeamsPayloadKind.MEETING_TRANSCRIPT,
                TeamsUrls.classify(TeamsSamples.TRANSCRIPT_URL));
        assertEquals(TeamsPayloadKind.PROFILE, TeamsUrls.classify(
                "https://teams.microsoft.com/api/mt/emea/beta/users/8:orgid:0000/profile"));
    }

    @Test
    @DisplayName("Le bruit de la page est reconnu comme sans intérêt, pas comme inconnu")
    void known_noise_is_ignored_not_unknown() {
        assertEquals(TeamsPayloadKind.IGNORED,
                TeamsUrls.classify("https://statics.teams.cdn.office.net/hashed/app.js"));
        assertEquals(TeamsPayloadKind.IGNORED,
                TeamsUrls.classify("https://teams.microsoft.com/assets/logo.svg"));
        assertEquals(TeamsPayloadKind.IGNORED,
                TeamsUrls.classify("https://browser.pipe.aria.microsoft.com/Collector/3.0/"));
        assertEquals(TeamsPayloadKind.IGNORED, TeamsUrls.classify(
                "https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/presence"));
    }

    @Test
    @DisplayName("Une adresse étrangère au service reste inconnue : son corps n'est pas lu")
    void foreign_urls_stay_unknown() {
        assertEquals(TeamsPayloadKind.UNKNOWN, TeamsUrls.classify("https://example.com/api/things"));
        assertEquals(TeamsPayloadKind.UNKNOWN, TeamsUrls.classify(""));
        assertEquals(TeamsPayloadKind.UNKNOWN, TeamsUrls.classify(null));
        assertEquals(TeamsPayloadKind.UNKNOWN,
                TeamsUrls.classify("https://teams.microsoft.com/api/quelque/chose/de/nouveau"));
    }

    @Test
    @DisplayName("La version d'interface se lit dans le chemin : elle sera nommée dans un refus")
    void reads_api_versions_from_the_path() {
        assertTrue(TeamsUrls.apiVersions(TeamsSamples.MESSAGES_URL).contains("v1"));
        assertTrue(TeamsUrls.apiVersions(TeamsSamples.ACTIVITY_URL).contains("beta"));
        assertTrue(TeamsUrls.apiVersions("https://example.com/rien").isEmpty());
    }

    @Test
    @DisplayName("La reconnaissance porte sur le chemin, jamais sur les paramètres")
    void query_string_never_decides() {
        assertEquals(TeamsPayloadKind.CONVERSATION_MESSAGES, TeamsUrls.classify(
                "https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/conversations/19:x/messages"
                        + "?skypetoken=peu-importe&pageSize=50"));
    }
}
