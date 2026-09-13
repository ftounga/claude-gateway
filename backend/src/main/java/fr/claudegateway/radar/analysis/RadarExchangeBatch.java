package fr.claudegateway.radar.analysis;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import fr.claudegateway.radar.InvalidRadarInputException;
import fr.claudegateway.radar.RadarEvidence;
import fr.claudegateway.radar.RadarEvidenceSource;
import fr.claudegateway.radar.RadarPerson;
import fr.claudegateway.radar.RadarText;

/**
 * <b>Le contrat d'entrée de l'analyse</b> (F-101 / SF-101-01) : un lot d'échanges remonté par la
 * collecte du runner (F-100).
 *
 * <p>La forme JSON du cadre runner est exactement la sérialisation Jackson de ces records ; la
 * mini-spec {@code docs/features/F-101/SF-101-01-la-file-d-analyse.md} en fait la référence de F-100.
 * {@link #normalized()} est la seule porte : rognage, troncatures, bornes, doublons. Un lot qui en sort
 * est sûr à stocker et à soumettre au modèle.</p>
 *
 * @param batchKey  clé d'idempotence du lot dans le poste
 * @param exchanges les échanges : les messages nouveaux d'une conversation, d'une réunion ou d'un
 *                  enregistrement
 */
public record RadarExchangeBatch(String batchKey, List<Exchange> exchanges) {

    public static final int MAX_BATCH_KEY_LENGTH = 128;
    public static final int MAX_EXCHANGES = 50;
    public static final int MAX_MESSAGES_PER_EXCHANGE = 200;
    public static final int MAX_MESSAGES = 500;
    public static final int MAX_MESSAGE_CHARS = 8_000;
    public static final int MAX_TOTAL_CHARS = 400_000;
    public static final int MAX_TITLE_LENGTH = 200;

    /** Les sources qu'une synchro peut remonter ; les notes et courriels collés entrent par F-104. */
    static final Set<RadarEvidenceSource> SYNC_SOURCES = Set.of(RadarEvidenceSource.TEAMS_MESSAGE,
            RadarEvidenceSource.TEAMS_MEETING, RadarEvidenceSource.LOCAL_RECORDING);

    /**
     * Un échange.
     *
     * @param source          d'où il vient
     * @param conversationRef identifiant stable du fil, de la réunion ou de l'enregistrement
     * @param title           titre lisible, facultatif
     * @param deepLink        lien vers le fil, facultatif
     * @param messages        messages nouveaux, dans l'ordre chronologique
     */
    public record Exchange(RadarEvidenceSource source, String conversationRef, String title,
            String deepLink, List<Message> messages) {
    }

    /**
     * Un message, une intervention de réunion ou un passage de transcription.
     *
     * @param sourceRef   identifiant stable dans la source — devient {@code radar_evidence.source_ref}
     * @param occurredAt  instant, avec fuseau
     * @param authorKey   identité de source de l'auteur, facultative
     * @param authorName  nom affiché, facultatif
     * @param authorTitle fonction, facultative
     * @param fromMe      vrai si l'utilisateur du poste l'a écrit
     * @param text        le texte brut, tronqué à {@value #MAX_MESSAGE_CHARS} caractères
     * @param deepLink    lien au message ou à la seconde de réunion, facultatif
     */
    public record Message(String sourceRef, OffsetDateTime occurredAt, String authorKey,
            String authorName, String authorTitle, boolean fromMe, String text, String deepLink) {
    }

    /** Nombre total de messages du lot. */
    public int messageCount() {
        return exchanges == null ? 0 : exchanges.stream()
                .mapToInt(e -> e.messages() == null ? 0 : e.messages().size()).sum();
    }

    /**
     * Le lot normalisé.
     *
     * @throws InvalidRadarInputException si le lot sort du contrat
     */
    public RadarExchangeBatch normalized() {
        String key = RadarText.required(batchKey, MAX_BATCH_KEY_LENGTH, "batchKey");
        if (exchanges == null || exchanges.isEmpty()) {
            throw new InvalidRadarInputException("Un lot compte au moins un échange.");
        }
        if (exchanges.size() > MAX_EXCHANGES) {
            throw new InvalidRadarInputException("Un lot compte au plus " + MAX_EXCHANGES + " échanges.");
        }
        Set<String> refs = new HashSet<>();
        int total = 0;
        int chars = 0;
        List<Exchange> clean = new ArrayList<>(exchanges.size());
        for (Exchange exchange : exchanges) {
            if (exchange == null || exchange.source() == null || !SYNC_SOURCES.contains(exchange.source())) {
                throw new InvalidRadarInputException(
                        "Source d'échange attendue : TEAMS_MESSAGE, TEAMS_MEETING ou LOCAL_RECORDING.");
            }
            String conversation = RadarText.required(exchange.conversationRef(),
                    RadarEvidence.MAX_SOURCE_REF_LENGTH, "conversationRef");
            List<Message> messages = exchange.messages();
            if (messages == null || messages.isEmpty()) {
                throw new InvalidRadarInputException("Un échange compte au moins un message.");
            }
            if (messages.size() > MAX_MESSAGES_PER_EXCHANGE) {
                throw new InvalidRadarInputException(
                        "Un échange compte au plus " + MAX_MESSAGES_PER_EXCHANGE + " messages.");
            }
            total += messages.size();
            if (total > MAX_MESSAGES) {
                throw new InvalidRadarInputException("Un lot compte au plus " + MAX_MESSAGES + " messages.");
            }
            List<Message> cleanMessages = new ArrayList<>(messages.size());
            for (Message message : messages) {
                Message m = message(message);
                if (!refs.add(m.sourceRef())) {
                    throw new InvalidRadarInputException("Identifiant de message en double dans le lot.");
                }
                chars += m.text().length();
                cleanMessages.add(m);
            }
            if (chars > MAX_TOTAL_CHARS) {
                throw new InvalidRadarInputException(
                        "Un lot porte au plus " + MAX_TOTAL_CHARS + " caractères de texte : découpez-le.");
            }
            clean.add(new Exchange(exchange.source(), conversation,
                    truncate(exchange.title(), MAX_TITLE_LENGTH), RadarText.deepLink(exchange.deepLink()),
                    List.copyOf(cleanMessages)));
        }
        return new RadarExchangeBatch(key, List.copyOf(clean));
    }

    private static Message message(Message message) {
        if (message == null) {
            throw new InvalidRadarInputException("Message absent.");
        }
        String ref = RadarText.required(message.sourceRef(), RadarEvidence.MAX_SOURCE_REF_LENGTH, "sourceRef");
        if (message.occurredAt() == null) {
            throw new InvalidRadarInputException("L'instant du message est requis.");
        }
        String text = message.text() == null ? "" : message.text().strip();
        if (text.isEmpty()) {
            throw new InvalidRadarInputException("Le texte du message est requis.");
        }
        String authorKey = truncateOrNull(message.authorKey(), RadarPerson.MAX_SOURCE_KEY_LENGTH);
        return new Message(ref, message.occurredAt(), authorKey,
                truncate(message.authorName(), RadarPerson.MAX_NAME_LENGTH),
                truncate(message.authorTitle(), RadarPerson.MAX_NAME_LENGTH), message.fromMe(),
                truncate(text, MAX_MESSAGE_CHARS), RadarText.deepLink(message.deepLink()));
    }

    /** Rogné et tronqué ; {@code null} s'il est vide. */
    static String truncate(String value, int max) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max).strip();
    }

    /** Une identité trop longue n'est pas tronquée : coupée, elle désignerait quelqu'un d'autre. */
    private static String truncateOrNull(String value, int max) {
        String trimmed = value == null ? "" : value.strip();
        return trimmed.isEmpty() || trimmed.length() > max ? null : trimmed;
    }
}
