package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * F-89 / SF-89-20 — <b>recaler l'adaptateur Teams v2 : lecture de la liste des conversations ET d'un fil</b>.
 *
 * <p>Le repli lecture-écran (SF-89-06) lisait 0 conversation sur Teams v2 : ses sélecteurs ciblaient une
 * structure antérieure. Les relevés de forme SF-89-16→19 (prod CAGIP 2026-09-20) ont donné les ancres
 * <b>réelles</b> v2 : rail {@code simple-collab-dnd-rail} ({@code role=tree}) fait de {@code role=treeitem}
 * nommés ; fil {@code message-pane-list-runway} fait de {@code chat-pane-item} portant auteur
 * ({@code message-author-name}), horodatage ({@code time}) et le marqueur sémantique {@code ChatMyMessage}
 * pour « moi ».</p>
 *
 * <p>Ces DOM modèles v2 restent <b>à confirmer sur poste réel</b> : ils éprouvent la logique de sélection et
 * d'extraction recalée, pas que Teams sert exactement cette structure. Le repli ancien reste couvert par
 * {@link TeamsScreenFallbackTest} (DOM {@code conversations.html} / {@code fil-ecran-1.html}).</p>
 */
class TeamsScreenV2Test {

    private final ObjectMapper mapper = new ObjectMapper();

    private static List<String> texts(JsonNode messages) {
        List<String> out = new ArrayList<>();
        messages.forEach(message -> out.add(message.path("text").asText()));
        return out;
    }

    // ------------------------------------------------------------------ la liste (rail v2)

    @Test
    @DisplayName("Liste v2 : le rail simple-collab-dnd-rail (tree) est lu, chaque treeitem donne son nom (aria-label et texte)")
    void the_v2_rail_is_read_with_conversation_names() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.screen("", PaperScreen.of("conversations-v2.html"));

        String rendered = teams.tools().execute(TeamsTools.FIND_CONVERSATIONS, mapper.createObjectNode()).content();
        JsonNode json = mapper.readTree(rendered);

        assertEquals("ecran", json.path("source").asText());
        assertEquals(2, json.path("conversations").size(), rendered);
        // 1er treeitem : nom accessible via aria-label ; id porté par l'attribut id (préfixe retiré).
        assertEquals("19:fabrique@thread.v2", json.path("conversations").get(0).path("id").asText());
        assertEquals("Migration IAM", json.path("conversations").get(0).path("topic").asText());
        // 2e treeitem : imbriqué (role=group), SANS aria-label → nom lu depuis le texte de l'item.
        assertEquals("Paul Durand", json.path("conversations").get(1).path("topic").asText());
        assertFalse(rendered.contains("SECRET-RECHERCHE-EN-COURS"), "un champ de recherche n'est jamais lu");
    }

    // ------------------------------------------------------------------ le fil (runway v2)

    @Test
    @DisplayName("Fil v2 : runway + chat-pane-item lus avec auteur, horodatage, texte, et le drapeau moi/autre depuis ChatMyMessage")
    void the_v2_thread_is_read_with_author_and_the_me_flag() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.screen("", PaperScreen.of("fil-ecran-v2-1.html"));

        String rendered = teams.tools().execute(TeamsTools.READ_CONVERSATION, mapper.createObjectNode()
                .put("conversation_id", PaperTeams.THREAD).put("from", "2026-09-01T00:00:00Z")
                .put("to", "2026-09-30T00:00:00Z")).content();
        JsonNode json = mapper.readTree(rendered);

        assertEquals("ecran", json.path("source").asText());
        assertEquals(TeamsScreen.VERSION, json.path("screenVersion").asText());
        assertEquals(List.of("On valide le lot 3 demain ?", "Oui, je t'envoie le plan demain."),
                texts(json.path("messages")), rendered);
        // L'auteur — le vrai nom, « qui parle » — remonte pour chaque message.
        assertEquals("Paul Durand", json.path("messages").get(0).path("author").asText());
        assertEquals("Francky Tounga", json.path("messages").get(1).path("author").asText());
        assertEquals("2026-09-11T09:30:00Z", json.path("messages").get(0).path("sentAt").asText());
        // Drapeau moi/autre : le 2e message porte fui-ChatMyMessage → « moi » ; le 1er, non.
        assertFalse(json.path("messages").get(0).path("self").asBoolean(), "message d'un autre");
        assertTrue(json.path("messages").get(1).path("self").asBoolean(), "mon message (ChatMyMessage)");
        for (String secret : List.of("SECRET-BROUILLON", "SECRET-MOT-DE-PASSE")) {
            assertFalse(rendered.contains(secret), secret + " : un champ de saisie n'est jamais lu");
        }
    }

    // ------------------------------------------------------------------ les modes de champ, au plus près

    @Test
    @DisplayName("Mode présence : « true » si le marqueur est présent (item ou descendant), rien sinon")
    void the_presence_field_reads_a_marker_without_reading_a_value() {
        PaperScreen screen = PaperScreen.of("fil-ecran-v2-1.html");
        JsonNode read = screen.evaluate(TeamsScreen.readScript(mapper, TeamsScreen.MESSAGES));
        TeamsScreen.Reading reading = TeamsScreen.refilter(TeamsScreen.MESSAGES, read);

        assertEquals(2, reading.items().size());
        assertFalse(reading.items().get(0).containsKey("self"), "message d'un autre : pas de drapeau");
        assertEquals("true", reading.items().get(1).get("self"), "mon message : drapeau présent");
    }

    @Test
    @DisplayName("Repli ancien : la structure d'avant (message-pane-list-viewport) reste lisible")
    void the_old_structure_still_reads_through_the_fallback() {
        PaperScreen screen = PaperScreen.of("fil-ecran-1.html");
        JsonNode read = screen.evaluate(TeamsScreen.readScript(mapper, TeamsScreen.MESSAGES));
        TeamsScreen.Reading reading = TeamsScreen.refilter(TeamsScreen.MESSAGES, read);

        assertTrue(reading.found(), "le conteneur d'avant est toujours trouvé (repli)");
        assertEquals("Paul Durand", reading.items().get(0).get("author"));
        assertFalse(reading.items().get(0).containsKey("self"), "l'ancien fil ne porte pas le marqueur ChatMyMessage");
    }
}
