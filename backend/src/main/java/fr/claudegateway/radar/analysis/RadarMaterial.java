package fr.claudegateway.radar.analysis;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import fr.claudegateway.radar.RadarEvidenceSource;

/**
 * La mise en forme de la matière soumise au modèle (F-101). Aucune dépendance, aucun état.
 *
 * <p>Les instants sont rendus en UTC et <b>avec leur jour de semaine</b> : c'est ce qui permet au
 * modèle de résoudre « jeudi » depuis la date du message (SF-101-04) sans rien deviner.</p>
 */
final class RadarMaterial {

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm 'UTC'",
            java.util.Locale.ENGLISH);

    private RadarMaterial() {
    }

    /** L'en-tête d'un échange : libellé, nature, titre. */
    static String exchangeHeader(String label, RadarExchangeBatch.Exchange exchange) {
        StringBuilder header = new StringBuilder("=== ").append(label).append(" · ").append(kind(exchange.source()));
        if (exchange.title() != null) {
            header.append(" · « ").append(oneLine(exchange.title())).append(" »");
        }
        return header.append(" ===").toString();
    }

    /**
     * Une ligne de message.
     *
     * @param label       libellé du message ({@code M3}) ou {@code null}
     * @param authorLabel libellé de l'auteur ({@code P2}) ou {@code null}
     * @param maxChars    troncature du texte
     */
    static String messageLine(String label, RadarExchangeBatch.Message message, String authorLabel, int maxChars) {
        StringBuilder line = new StringBuilder();
        if (label != null) {
            line.append('[').append(label).append("] ");
        }
        line.append('[').append(AT.format(message.occurredAt().withOffsetSameInstant(ZoneOffset.UTC))).append("] ");
        line.append(author(message));
        if (authorLabel != null) {
            line.append(" (").append(authorLabel).append(')');
        }
        // Un message qui contiendrait un marqueur de bloc ne doit pas pouvoir en imiter un.
        String text = message.text().replace("===", "=");
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars) + " […]";
        }
        return line.append(" : ").append(text).toString();
    }

    static String author(RadarExchangeBatch.Message message) {
        if (message.fromMe()) {
            return "MOI";
        }
        return message.authorName() != null ? oneLine(message.authorName()) : "(auteur inconnu)";
    }

    static String kind(RadarEvidenceSource source) {
        return switch (source) {
            case TEAMS_MEETING -> "réunion Teams";
            case LOCAL_RECORDING -> "enregistrement";
            default -> "conversation Teams";
        };
    }

    /** Un titre ou un nom venu d'une source ne casse jamais la structure de la matière. */
    static String oneLine(String value) {
        return value.replaceAll("[\\r\\n]+", " ").replace("===", "=").strip();
    }
}
