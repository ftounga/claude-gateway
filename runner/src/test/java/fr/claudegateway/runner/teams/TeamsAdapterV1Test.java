package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * F-87 / SF-87-01 — l'adaptateur unique, éprouvé sur des échantillons <b>fabriqués</b>
 * (voir {@code src/test/resources/teams/PROVENANCE.md}).
 *
 * <p>Ce que ces tests garantissent : que la <b>traduction</b> est juste, et surtout qu'une lecture
 * incomplète <b>se voit</b>. Ce qu'ils ne garantissent pas : que Microsoft sert bien cette forme —
 * c'est le travail de la sonde de santé au premier branchement.</p>
 */
class TeamsAdapterV1Test {

    private final TeamsAdapter adapter = TeamsAdapters.current().forUser(TeamsSamples.SELF);

    // ------------------------------------------------------------------ nominal

    @Test
    @DisplayName("Une page nominale rend des messages complets, et rien ne manque")
    void reads_a_nominal_page() {
        TeamsReading<TeamsMessage> reading = adapter.messages(TeamsSamples.MESSAGES_URL,
                TeamsSamples.read("conversation-messages.json"), TeamsSamples.wideWindow());

        assertEquals(3, reading.items().size());
        assertEquals(0, reading.missedCount(), "aucun manque attendu sur la page nominale");
        assertEquals(TeamsHealthVerdict.FULL, reading.health().verdict());

        TeamsMessage first = reading.items().get(0);
        assertEquals("1757662323000", first.id());
        assertEquals("19:fabrique@thread.v2", first.conversationId());
        assertEquals("8:orgid:00000000-0000-0000-0000-000000000001", first.author().id());
        assertEquals("Paul Durand", first.author().displayName());
        assertFalse(first.author().self());
        assertEquals(Instant.parse("2026-09-05T09:12:03.412Z"), first.sentAt());
        assertEquals(TeamsMessageKind.RICH_TEXT, first.kind());
        assertEquals("Bonjour Francky, peux-tu relire le plan de migration ?", first.text());
        assertEquals(1, first.mentions().size());
        assertEquals(TeamsMentionKind.PERSON, first.mentions().get(0).kind());
        assertEquals(List.of(new TeamsReaction("like", 1)), first.reactions());
    }

    @Test
    @DisplayName("L'auteur relié est reconnu comme tel — sans quoi « on m'a mentionné » n'existe pas")
    void marks_the_linked_user() {
        TeamsReading<TeamsMessage> reading = adapter.messages(TeamsSamples.MESSAGES_URL,
                TeamsSamples.read("conversation-messages.json"), TeamsSamples.wideWindow());
        TeamsMessage mine = reading.items().get(1);
        assertTrue(mine.author().self());
        assertTrue(reading.items().get(0)
                .mentions(new TeamsParticipant(TeamsSamples.SELF, "Francky", null, true)));
    }

    @Test
    @DisplayName("Pièce jointe par référence, réponse dans un fil, et heure de modification")
    void reads_attachments_threads_and_edits() {
        TeamsMessage message = adapter.messages(TeamsSamples.MESSAGES_URL,
                TeamsSamples.read("conversation-messages.json"), TeamsSamples.wideWindow())
                .items().get(1);

        assertEquals("1757662323000", message.parentId());
        assertEquals(1, message.attachments().size());
        TeamsAttachmentRef file = message.attachments().get(0);
        assertEquals("plan-migration.pdf", file.name());
        assertEquals(184_320L, file.sizeBytes());
        assertNotNull(message.editedAt());
    }

    @Test
    @DisplayName("La fenêtre réellement lue est portée par le résultat (D4)")
    void carries_the_window_actually_read() {
        TeamsReadWindow window = adapter.messages(TeamsSamples.MESSAGES_URL,
                TeamsSamples.read("conversation-messages.json"), TeamsSamples.wideWindow())
                .window();

        assertEquals(Instant.parse("2026-09-05T09:12:03.412Z"), window.actualFrom());
        assertEquals(Instant.parse("2026-09-12T07:05:10Z"), window.actualTo());
        assertFalse(window.capReached());
        assertFalse(window.reachedStartOfConversation(),
                "une page suivante existe : on n'a pas atteint le début de la conversation");
        assertTrue(window.describe().startsWith("du "));
    }

    @Test
    @DisplayName("La page suivante est connue de l'adaptateur, et de lui seul")
    void knows_how_to_paginate() {
        assertTrue(adapter.nextPage(TeamsSamples.MESSAGES_URL,
                TeamsSamples.read("conversation-messages.json")).isPresent());
        assertTrue(adapter.nextPage(TeamsSamples.MESSAGES_URL,
                TeamsSamples.read("conversation-messages-partial.json")).isEmpty());
    }

    // ------------------------------------------------------------------ jamais à moitié faux

    @Test
    @DisplayName("Un message amputé n'est JAMAIS rendu : il devient un manque nommé")
    void never_renders_a_half_read_message() {
        TeamsReading<TeamsMessage> reading = adapter.messages(TeamsSamples.MESSAGES_URL,
                TeamsSamples.read("conversation-messages-partial.json"), TeamsSamples.wideWindow());

        assertEquals(2, reading.items().size(), "seuls les messages complets sont rendus");
        assertEquals(3, reading.missedCount(), "les trois autres sont déclarés non lus");
        assertFalse(reading.complete());

        List<TeamsGapKind> kinds = reading.gaps().stream().map(TeamsGap::kind).toList();
        assertTrue(kinds.contains(TeamsGapKind.MISSING_FIELD));
        assertTrue(kinds.contains(TeamsGapKind.UNKNOWN_MESSAGE_KIND));
        assertTrue(reading.summary("messages").contains("2 messages lus"));
        assertTrue(reading.summary("messages").contains("3 non lus"));
    }

    @Test
    @DisplayName("Une forme inconnue fait REFUSER la lecture, pas rendre une liste vide")
    void refuses_an_unknown_shape() {
        TeamsReading<TeamsMessage> reading = adapter.messages(TeamsSamples.MESSAGES_URL,
                TeamsSamples.read("conversation-messages-unknown.json"), TeamsSamples.wideWindow());

        assertTrue(reading.items().isEmpty());
        assertEquals(TeamsHealthVerdict.NONE, reading.health().verdict());
        assertEquals(TeamsGapKind.UNRECOGNIZED_PAYLOAD, reading.gaps().get(0).kind());
        assertTrue(reading.health().describe().contains("Teams a changé"));
        assertTrue(reading.health().describe().contains("adaptateur v1"),
                "un refus nomme l'hypothèse qui s'est révélée fausse");
    }

    @Test
    @DisplayName("Un corps vide ou nul ne lève jamais")
    void never_throws_on_garbage() {
        assertEquals(TeamsHealthVerdict.NONE,
                adapter.messages(TeamsSamples.MESSAGES_URL, null, null).health().verdict());
        assertTrue(adapter.conversations(TeamsSamples.CONVERSATIONS_URL,
                new ObjectMapper().createObjectNode()).items().isEmpty());
        assertTrue(adapter.nextPage(TeamsSamples.MESSAGES_URL, null).isEmpty());
    }

    @Test
    @DisplayName("Le plafond annoncé s'applique, et il est DIT (D4)")
    void applies_and_announces_the_cap() {
        TeamsReadWindow small = new TeamsReadWindow(Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-30T00:00:00Z"), null, null, 2, false, false);
        TeamsReading<TeamsMessage> reading = adapter.messages(TeamsSamples.MESSAGES_URL,
                TeamsSamples.read("conversation-messages.json"), small);

        assertEquals(2, reading.items().size());
        assertTrue(reading.window().capReached());
        assertFalse(reading.window().fullyCovered());
        assertTrue(reading.summary("messages").contains("Plafond de 2 atteint"));
    }

    @Test
    @DisplayName("Une fenêtre demandée exclut ce qui est en dehors, sans le compter comme perdu")
    void honours_the_requested_window() {
        TeamsReadWindow narrow = new TeamsReadWindow(Instant.parse("2026-09-10T00:00:00Z"),
                Instant.parse("2026-09-30T00:00:00Z"), null, null, 500, false, false);
        TeamsReading<TeamsMessage> reading = adapter.messages(TeamsSamples.MESSAGES_URL,
                TeamsSamples.read("conversation-messages.json"), narrow);

        assertEquals(1, reading.items().size());
        assertEquals(Instant.parse("2026-09-12T07:05:10Z"), reading.window().actualFrom());
    }

    // ------------------------------------------------------------------ les autres lectures

    @Test
    @DisplayName("Les conversations sont lues, nommées, et celle sans identifiant est déclarée")
    void reads_conversations() {
        TeamsReading<TeamsConversation> reading = adapter.conversations(
                TeamsSamples.CONVERSATIONS_URL, TeamsSamples.read("conversation-list.json"));

        assertEquals(3, reading.items().size());
        assertEquals(1, reading.missedCount());
        assertEquals(TeamsConversationKind.ONE_ON_ONE, reading.items().get(0).kind());
        assertEquals("Paul Durand", reading.items().get(0).label(),
                "un tête-à-tête se nomme par la personne en face, pas par un identifiant");
        assertEquals(TeamsConversationKind.CHANNEL, reading.items().get(1).kind());
        assertEquals("Migration MFA", reading.items().get(1).label());
        assertEquals(TeamsConversationKind.MEETING_CHAT, reading.items().get(2).kind());
    }

    @Test
    @DisplayName("Le flux d'activité rend les mentions, et seulement elles")
    void reads_mentions_from_the_activity_feed() {
        TeamsReading<TeamsMentionEvent> reading = adapter.mentions(TeamsSamples.ACTIVITY_URL,
                TeamsSamples.read("activity-feed.json"), TeamsSamples.wideWindow());

        assertEquals(2, reading.items().size(), "la réaction n'est pas une mention");
        assertEquals(1, reading.missedCount(), "la mention sans message est déclarée non lue");
        TeamsMentionEvent first = reading.items().get(0);
        assertEquals("Claire Martin", first.author().displayName());
        assertEquals("Migration MFA", first.conversationLabel());
        assertEquals("@Francky peux-tu confirmer la date de bascule ?", first.preview());
        assertEquals(TeamsMentionKind.CHANNEL, reading.items().get(1).mention().kind());
    }

    @Test
    @DisplayName("Une réunion enregistrée et sa transcription annoncée sont deux choses différentes")
    void reads_meetings() {
        TeamsReading<TeamsMeeting> reading = adapter.meetings(TeamsSamples.MEETINGS_URL,
                TeamsSamples.read("meetings.json"));

        assertEquals(1, reading.items().size());
        assertEquals(1, reading.missedCount());
        TeamsMeeting meeting = reading.items().get(0);
        assertEquals("Comité de migration", meeting.subject());
        assertTrue(meeting.recorded());
        assertTrue(meeting.transcriptAvailable());
        assertEquals(2, meeting.participants().size());
        assertTrue(meeting.participants().get(1).self());
    }

    @Test
    @DisplayName("Une transcription est horodatée à la seconde — c'est ce qui rendra F-90 possible")
    void reads_transcript_cues() {
        TeamsReading<TeamsTranscriptCue> reading = adapter.transcript(TeamsSamples.TRANSCRIPT_URL,
                TeamsSamples.read("transcript.json"));

        assertEquals(2, reading.items().size());
        assertEquals(1, reading.missedCount());
        TeamsTranscriptCue cue = reading.items().get(0);
        assertEquals(Instant.parse("2026-09-10T09:00:05.120Z"), cue.at());
        assertEquals(4_280L, cue.durationMs());
        assertEquals("Paul Durand", cue.speakerDisplayName());
    }

    @Test
    @DisplayName("Un résultat de recherche est un message, enveloppé ou non")
    void reads_search_results() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = mapper.createObjectNode();
        body.putArray("value").addObject().set("message",
                TeamsSamples.read("conversation-messages.json").get("messages").get(0));

        TeamsReading<TeamsMessage> reading =
                adapter.searchResults(TeamsSamples.SEARCH_URL, body, TeamsSamples.wideWindow());

        assertEquals(1, reading.items().size());
        assertEquals("Paul Durand", reading.items().get(0).author().displayName());
    }

    // ------------------------------------------------------------------ santé

    @Test
    @DisplayName("La santé compte les champs reconnus, et nomme ceux qui manquent")
    void counts_recognized_fields() {
        TeamsHealth full = adapter.inspect(TeamsSamples.MESSAGES_URL,
                TeamsSamples.read("conversation-messages.json"));
        assertEquals(TeamsHealthVerdict.FULL, full.verdict());
        assertEquals(full.expectedFields(), full.recognizedFields());
        assertTrue(full.observedApiVersions().contains("v1"));

        TeamsHealth none = adapter.inspect(TeamsSamples.MESSAGES_URL,
                TeamsSamples.read("conversation-messages-unknown.json"));
        assertEquals(TeamsHealthVerdict.NONE, none.verdict());
        assertFalse(none.missingFields().isEmpty());
        assertFalse(none.verdict().canWork());
    }

    @Test
    @DisplayName("Une forme à moitié reconnue donne « partiellement », jamais « tout va bien »")
    void half_recognized_is_partial() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = mapper.createObjectNode();
        body.putArray("messages").addObject().put("id", "1").put("from", "u/1")
                .put("imdisplayname", "Paul");

        TeamsHealth health = adapter.inspect(TeamsSamples.MESSAGES_URL, body);

        assertEquals(TeamsHealthVerdict.PARTIAL, health.verdict());
        assertTrue(health.verdict().canWork(), "on travaille — et on le dit");
        assertTrue(health.describe().contains("Teams a changé"));
        assertTrue(health.missingFields().contains("originalarrivaltime"));
    }
}
