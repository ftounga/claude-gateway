package fr.claudegateway.runner.teams;

import java.io.InputStream;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Les échantillons <b>fabriqués</b> du volet Teams (F-87 / SF-87-01).
 *
 * <p>Leur provenance et ce qu'ils prouvent — et surtout ce qu'ils <b>ne</b> prouvent pas — sont
 * écrits dans {@code src/test/resources/teams/PROVENANCE.md}. En deux mots : nous n'avons aucun
 * compte Teams de test, ces fichiers sont écrits à la main, et c'est la sonde de santé qui
 * confrontera l'hypothèse au réel.</p>
 */
final class TeamsSamples {

    static final String MESSAGES_URL = "https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/"
            + "conversations/19:fabrique@thread.v2/messages?pageSize=50";
    static final String CONVERSATIONS_URL =
            "https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/conversations";
    static final String ACTIVITY_URL =
            "https://teams.microsoft.com/api/mt/emea/beta/users/me/activityfeed?pageSize=50";
    static final String SEARCH_URL =
            "https://teams.microsoft.com/api/searchservice/emea/v1/search/messages";
    static final String MEETINGS_URL =
            "https://teams.microsoft.com/api/mt/emea/beta/meetings/MTG-FABRIQUE-0001";
    static final String TRANSCRIPT_URL = "https://teams.microsoft.com/api/mt/emea/beta/meetings/"
            + "MTG-FABRIQUE-0001/transcripts/T1";

    /** L'identifiant de l'utilisateur relié, dans les échantillons. */
    static final String SELF = "8:orgid:00000000-0000-0000-0000-000000000002";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TeamsSamples() {
    }

    static JsonNode read(String name) {
        try (InputStream stream = TeamsSamples.class.getResourceAsStream("/teams/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("Échantillon absent : " + name);
            }
            return MAPPER.readTree(stream);
        } catch (Exception e) {
            throw new IllegalStateException("Échantillon illisible : " + name, e);
        }
    }

    /** Fenêtre large : les échantillons sont datés, ils ne doivent pas dépendre du jour du test. */
    static TeamsReadWindow wideWindow() {
        return new TeamsReadWindow(Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-30T00:00:00Z"), null, null, 500, false, false);
    }
}
