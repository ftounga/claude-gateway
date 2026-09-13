package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

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

    /**
     * <b>Une ligne par règle</b> (F-89 / SF-89-08), dans l'ordre de {@code TeamsUrls.CHAT_RULES} : l'adresse
     * réelle qui l'a fait naître, et la nature attendue. Ajouter une règle = une ligne là-bas, une ligne ici.
     */
    static final List<String[]> RULE_EXAMPLES = List.of(
            new String[] {"https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/conversations/19:x@thread.v2/messages?pageSize=50", "CONVERSATION_MESSAGES"},
            new String[] {"https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/conversations?view=x", "CONVERSATION_LIST"},
            new String[] {"https://teams.microsoft.com/api/csa/emea/api/v2/teams/users/me/conversations/updates", "CONVERSATION_LIST"},
            new String[] {"https://teams.microsoft.com/api/mt/emea/beta/users/me/activityfeed?pageSize=50", "ACTIVITY_FEED"},
            new String[] {"https://teams.cloud.microsoft/api/mt/emea/beta/activity/feed/items", "ACTIVITY_FEED"},
            new String[] {"https://teams.microsoft.com/api/searchservice/emea/v1/search/messages", "SEARCH_RESULTS"},
            new String[] {"https://teams.microsoft.com/api/mt/emea/beta/search", "SEARCH_RESULTS"},
            new String[] {"https://teams.microsoft.com/api/mt/emea/beta/meetings/M1/transcripts/T1?format=json", "MEETING_TRANSCRIPT"},
            new String[] {"https://teams.microsoft.com/api/mcps/eu/collab/readcollabobject/V2/a/b/c?x=1", "MEETING_COLLAB_OBJECT"},
            new String[] {"https://teams.microsoft.com/api/mt/emea/v2.0/me/calendars/events/iCalUId/0400000082?$select=subject", "CALENDAR_EVENT"},
            new String[] {"https://teams.microsoft.com/api/mt/emea/v1.0/me/events?startDateTime=2026-09-01", "CALENDAR_EVENT"},
            new String[] {"https://teams.microsoft.com/api/mt/emea/beta/meetings/MTG-1", "MEETING_DETAILS"},
            new String[] {"https://teams.microsoft.com/api/mt/emea/v1/schedulingService/meetings?startDate=2026-09-01", "MEETING_DETAILS"},
            new String[] {"https://teams.microsoft.com/api/mt/emea/v1/calling/meetingsinfo", "MEETING_DETAILS"},
            new String[] {"https://teams.microsoft.com/api/mt/emea/beta/users/8:orgid:0000/profile", "PROFILE"},
            new String[] {"https://teams.microsoft.com/api/mt/emea/beta/users/8:orgid:0000/properties", "PROFILE"});

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> ruleExamples() {
        return java.util.stream.IntStream.range(0, RULE_EXAMPLES.size()).mapToObj(index -> org.junit.jupiter.params
                .provider.Arguments.of(index, RULE_EXAMPLES.get(index)[0], RULE_EXAMPLES.get(index)[1]));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "règle {0} → {2}")
    @org.junit.jupiter.params.provider.MethodSource("ruleExamples")
    @DisplayName("Une ligne par règle : l'adresse réelle est classée, et c'est BIEN cette règle-là qui la classe")
    void each_rule_has_its_line(int index, String url, String kind) {
        assertEquals(TeamsPayloadKind.valueOf(kind), TeamsUrls.classify(url), url);
        String path = url.toLowerCase(java.util.Locale.ROOT).replaceFirst("^https://[^/]+", "").replaceFirst("[?#].*$", "");
        int first = -1;
        for (int rule = 0; rule < TeamsUrls.CHAT_RULES.size() && first < 0; rule++) {
            first = TeamsUrls.CHAT_RULES.get(rule).matches(path) ? rule : -1;
        }
        assertEquals(index, first, "l'exemple doit être classé par sa propre règle, pas par une précédente");
    }

    @Test
    @DisplayName("Chaque règle de la table a son exemple : pas de règle sans ligne de test")
    void no_rule_without_its_line() {
        assertEquals(TeamsUrls.CHAT_RULES.size(), RULE_EXAMPLES.size());
    }

    @Test
    @DisplayName("La reconnaissance porte sur le chemin, jamais sur les paramètres")
    void query_string_never_decides() {
        assertEquals(TeamsPayloadKind.CONVERSATION_MESSAGES, TeamsUrls.classify(
                "https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/conversations/19:x/messages"
                        + "?skypetoken=peu-importe&pageSize=50"));
    }
}
