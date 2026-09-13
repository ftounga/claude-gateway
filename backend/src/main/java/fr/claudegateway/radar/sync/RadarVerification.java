package fr.claudegateway.radar.sync;

import java.time.OffsetDateTime;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>La vérification guidée</b> (F-100 / SF-100-01) : quatre cases, et la règle qui les fusionne d'un
 * appel à l'autre.
 *
 * <p>L'utilisateur ouvre un fil, puis une réunion, puis sa transcription : ce qui a été vu une fois
 * <b>reste vu</b>. La session, elle, n'est jamais collante — elle dit toujours l'état du dernier
 * appel.</p>
 *
 * @param verifiedAt    instant du dernier appel réussi, ou {@code null}
 * @param session       ✓ session Microsoft active
 * @param conversations ✓ conversations
 * @param meetings      ✓ réunions
 * @param transcripts   ✓ transcriptions ({@code state} : {@code SEEN}, {@code ACCESS_DENIED},
 *                      {@code DISABLED_OR_NOT_PRODUCED}, {@code NOT_SEEN})
 */
public record RadarVerification(OffsetDateTime verifiedAt, Check session, Check conversations, Check meetings,
        Check transcripts) {

    static final int MAX_SENTENCE_CHARS = 500;
    static final int MAX_COUNT = 1_000_000;

    /**
     * Une case.
     *
     * @param ok       cochée
     * @param state    état court (lettres majuscules et soulignés), ou {@code ""}
     * @param count    ce qui a été vu (compteur, jamais un objet)
     * @param sentence la phrase à afficher
     * @param okSince  instant où la case a été cochée, ou {@code null}
     */
    public record Check(boolean ok, String state, int count, String sentence, OffsetDateTime okSince) {

        static Check empty(String sentence) {
            return new Check(false, "", 0, sentence, null);
        }
    }

    /** Aucune vérification faite. */
    public static RadarVerification none() {
        return new RadarVerification(null,
                Check.empty("Session non vérifiée."),
                Check.empty("Ouvrez un fil de conversation dans Teams."),
                Check.empty("Ouvrez une réunion passée dans le calendrier de Teams."),
                new Check(false, "NOT_SEEN", 0, "Ouvrez l'onglet Transcription d'une réunion passée.", null));
    }

    /** Les quatre cases cochées. */
    public boolean complete() {
        return session.ok() && conversations.ok() && meetings.ok() && transcripts.ok();
    }

    /**
     * Lit la réponse du runner ({@code teams_radar_verify}).
     *
     * @throws IllegalArgumentException si la forme n'est pas celle attendue
     */
    static RadarVerification fromRunner(JsonNode root, OffsetDateTime now) {
        if (root == null || !root.isObject() || !root.path("checks").isObject()) {
            throw new IllegalArgumentException("réponse sans cases");
        }
        JsonNode checks = root.path("checks");
        return new RadarVerification(now, check(checks, "session", now), check(checks, "conversations", now),
                check(checks, "meetings", now), check(checks, "transcripts", now));
    }

    private static Check check(JsonNode checks, String name, OffsetDateTime now) {
        JsonNode node = checks.get(name);
        if (node == null || !node.isObject() || !node.path("ok").isBoolean()) {
            throw new IllegalArgumentException("case absente : " + name);
        }
        boolean ok = node.path("ok").asBoolean();
        long count = node.path("count").asLong(0);
        String state = node.path("reason").asText(node.path("state").asText(""));
        return new Check(ok, cleanState(state), (int) Math.max(0, Math.min(MAX_COUNT, count)),
                clip(node.path("sentence").asText("")), ok ? now : null);
    }

    /**
     * Fusionne ce qui vient d'être vu avec ce qui l'avait déjà été : une case cochée reste cochée (avec
     * son instant), la session dit le dernier appel.
     */
    RadarVerification mergedOnto(RadarVerification previous) {
        if (previous == null) {
            return this;
        }
        Check mergedSession = session.ok() && previous.session().ok()
                ? new Check(true, session.state(), session.count(), session.sentence(), previous.session().okSince())
                : session;
        return new RadarVerification(verifiedAt, mergedSession, sticky(previous.conversations(), conversations),
                sticky(previous.meetings(), meetings), sticky(previous.transcripts(), transcripts));
    }

    private static Check sticky(Check before, Check now) {
        if (!before.ok()) {
            return now;
        }
        if (now.ok()) {
            return new Check(true, now.state(), Math.max(before.count(), now.count()), now.sentence(),
                    before.okSince());
        }
        return before;
    }

    private static String cleanState(String raw) {
        String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        return value.matches("[A-Z_]{1,32}") ? value : "";
    }

    private static String clip(String sentence) {
        String value = sentence == null ? "" : sentence.strip();
        return value.length() <= MAX_SENTENCE_CHARS ? value : value.substring(0, MAX_SENTENCE_CHARS);
    }
}
