package fr.claudegateway.runner.teams;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Des lectures aux lots</b> (F-100 / SF-100-03) : ce que la collecte a lu, mis dans la forme que la file
 * d'analyse de F-101 reçoit (contrat figé en SF-101-01), découpé pour tenir ses bornes.
 *
 * <p>La clé d'un lot est <b>dérivée de ce qu'il contient</b> (le fil, son premier et son dernier message) :
 * une collecte interrompue puis reprise redépose les mêmes lots sous les mêmes clés, et la file n'en garde
 * qu'un exemplaire.</p>
 */
final class RadarExchanges {

    /** Messages par échange : borne du contrat. */
    static final int MAX_MESSAGES = 200;
    /** Caractères par lot : sous les 400 000 du contrat, avec de la marge pour les champs. */
    static final int MAX_BATCH_CHARS = 350_000;
    /** Caractères par message : au-delà, la file tronquerait ; on tronque avant d'envoyer. */
    static final int MAX_MESSAGE_CHARS = 8_000;
    static final int MAX_TITLE_CHARS = 200;
    static final int MAX_REF_CHARS = 512;

    private RadarExchanges() {
    }

    /** Une ligne lue : un message, ou une réplique de transcription. */
    record Line(String sourceRef, Instant occurredAt, String authorKey, String authorName, boolean fromMe,
            String text, String deepLink) {
    }

    /**
     * Un lot prêt à partir.
     *
     * @param batchKey clé d'idempotence (≤ 128)
     * @param batch    le lot, forme du contrat F-101
     * @param newest   l'instant du message le plus récent : le curseur du fil une fois le lot accepté
     * @param messages nombre de messages
     */
    record Chunk(String batchKey, ObjectNode batch, Instant newest, int messages) {
    }

    /**
     * Découpe les lignes d'un fil (déjà dans l'ordre du temps) en lots.
     *
     * @param source          {@code TEAMS_MESSAGE}, {@code TEAMS_MEETING} ou {@code LOCAL_RECORDING}
     * @param conversationRef identifiant stable du fil ou de la réunion
     */
    static List<Chunk> chunks(ObjectMapper mapper, String source, String conversationRef, String title, String deepLink,
            List<Line> lines) {
        List<Chunk> chunks = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        int chars = 0;
        for (Line line : lines) {
            if (line == null || line.occurredAt() == null || line.text() == null || line.text().isBlank()
                    || line.sourceRef() == null || line.sourceRef().isBlank()) {
                continue;
            }
            int size = Math.min(line.text().length(), MAX_MESSAGE_CHARS);
            if (!current.isEmpty() && (current.size() >= MAX_MESSAGES || chars + size > MAX_BATCH_CHARS)) {
                chunks.add(chunk(mapper, source, conversationRef, title, deepLink, current));
                current = new ArrayList<>();
                chars = 0;
            }
            current.add(line);
            chars += size;
        }
        if (!current.isEmpty()) {
            chunks.add(chunk(mapper, source, conversationRef, title, deepLink, current));
        }
        return chunks;
    }

    private static Chunk chunk(ObjectMapper mapper, String source, String conversationRef, String title,
            String deepLink, List<Line> lines) {
        String prefix = switch (source) {
            case "TEAMS_MEETING" -> "meeting:";
            case "LOCAL_RECORDING" -> "depot:";
            default -> "teams:";
        };
        String key = prefix + digest(conversationRef + '|' + lines.get(0).sourceRef() + '|'
                + lines.get(lines.size() - 1).sourceRef());
        ObjectNode batch = mapper.createObjectNode();
        batch.put("batchKey", key);
        ObjectNode exchange = batch.putArray("exchanges").addObject();
        exchange.put("source", source);
        exchange.put("conversationRef", clip(conversationRef, MAX_REF_CHARS));
        if (title != null && !title.isBlank()) {
            exchange.put("title", clip(title.strip(), MAX_TITLE_CHARS));
        }
        if (deepLink != null && !deepLink.isBlank() && deepLink.length() <= 2048) {
            exchange.put("deepLink", deepLink);
        }
        ArrayNode messages = exchange.putArray("messages");
        Instant newest = null;
        for (Line line : lines) {
            ObjectNode message = messages.addObject();
            message.put("sourceRef", clip(line.sourceRef(), MAX_REF_CHARS));
            message.put("occurredAt", line.occurredAt().toString());
            if (line.authorKey() != null && !line.authorKey().isBlank()) {
                message.put("authorKey", clip(line.authorKey(), 320));
            }
            if (line.authorName() != null && !line.authorName().isBlank()) {
                message.put("authorName", clip(line.authorName(), 200));
            }
            message.put("fromMe", line.fromMe());
            message.put("text", clip(line.text(), MAX_MESSAGE_CHARS));
            if (line.deepLink() != null && !line.deepLink().isBlank() && line.deepLink().length() <= 2048) {
                message.put("deepLink", line.deepLink());
            }
            newest = newest == null || line.occurredAt().isAfter(newest) ? line.occurredAt() : newest;
        }
        return new Chunk(key, batch, newest, lines.size());
    }

    /** Empreinte courte et stable (SHA-256, 48 caractères hexadécimaux). */
    static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 48);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private static String clip(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
