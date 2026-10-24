package fr.claudegateway.atelier.sends;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Les blocs « message à envoyer » d'une réponse d'agent</b> (F-211 / SF-211-01), découpés comme
 * l'écran les découpe ({@code frontend/src/app/shared/message-segments.ts}) : même fence, mêmes alias,
 * même règle « fence vide = prose ». Le rang d'un bloc ({@code index}) ne compte que les blocs
 * mail/message : c'est le repère que l'écran envoie.
 *
 * <p>La balise peut porter des attributs après le type : {@code ```mail attente=acces-forge}. En tête
 * du bloc, « Objet : … » et « À : … » (dans n'importe quel ordre) sont lus à part.</p>
 *
 * <p>Pur et déterministe : aucune dépendance.</p>
 */
public final class MessageBlocks {

    /** Même motif que {@code FENCE_REGEX} côté écran (fin de texte = {@code \z}). */
    private static final Pattern FENCE = Pattern.compile(
            "(^|\\n)(`{3,})[ \\t]*([^\\n`]*)\\n([\\s\\S]*?)\\n\\2[ \\t]*(?=\\n|\\z)");

    private static final Set<String> MAIL_TOKENS = Set.of("mail", "email", "eml");
    private static final Set<String> MESSAGE_TOKENS = Set.of("message", "teams", "slack");

    private static final Pattern ATTENTE = Pattern.compile(
            "(?:^|\\s)attente\\s*=\\s*\"?([^\\s\"]+)\"?", Pattern.CASE_INSENSITIVE);

    private static final Pattern SUBJECT_LINE = Pattern.compile(
            "^\\s*(?:\\*\\*|__)?\\s*(?:objet|subject)\\s*(?:\\*\\*|__)?\\s*:\\s*(?:\\*\\*|__)?(.*?)(?:\\*\\*|__)?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern RECIPIENT_LINE = Pattern.compile(
            "^\\s*(?:\\*\\*|__)?\\s*(?:à|a|to|destinataires?)\\s*(?:\\*\\*|__)?\\s*:\\s*(?:\\*\\*|__)?(.*?)(?:\\*\\*|__)?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private MessageBlocks() {
    }

    /** Un bloc mail/message : son rang, sa nature, son objet, son destinataire, sa clé d'attente, son corps. */
    public record Block(int index, MessageBlockSend.Kind kind, String subject, String recipient,
                        String attenteKey, String body) {
    }

    /** Les blocs mail/message de {@code content}, dans l'ordre. Vide pour un texte sans bloc. */
    public static List<Block> parse(String content) {
        List<Block> blocks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return blocks;
        }
        Matcher matcher = FENCE.matcher(content);
        while (matcher.find()) {
            String inner = matcher.group(4) == null ? "" : matcher.group(4);
            if (inner.isBlank()) {
                continue;
            }
            String info = matcher.group(3) == null ? "" : matcher.group(3).strip();
            MessageBlockSend.Kind kind = kindOf(firstToken(info));
            if (kind == null) {
                continue;
            }
            Headers headers = headers(inner);
            blocks.add(new Block(blocks.size(), kind, headers.subject, headers.recipient,
                    attenteOf(info), headers.body));
        }
        return blocks;
    }

    static String firstToken(String info) {
        if (info.isEmpty()) {
            return "";
        }
        String[] parts = info.split("\\s+", 2);
        return parts[0].toLowerCase(Locale.ROOT);
    }

    private static MessageBlockSend.Kind kindOf(String token) {
        if (MAIL_TOKENS.contains(token)) {
            return MessageBlockSend.Kind.MAIL;
        }
        if (MESSAGE_TOKENS.contains(token)) {
            return MessageBlockSend.Kind.MESSAGE;
        }
        return null;
    }

    private static String attenteOf(String info) {
        Matcher matcher = ATTENTE.matcher(info);
        return matcher.find() ? matcher.group(1) : null;
    }

    private record Headers(String subject, String recipient, String body) {
    }

    /** « Objet : » et « À : » en tête, chacun une fois, dans n'importe quel ordre ; le reste est le corps. */
    private static Headers headers(String content) {
        String[] lines = content.split("\n", -1);
        String subject = null;
        String recipient = null;
        int i = 0;
        while (i < lines.length) {
            if (lines[i].isBlank()) {
                i++;
                continue;
            }
            Matcher s = SUBJECT_LINE.matcher(lines[i]);
            if (subject == null && s.matches() && !s.group(1).isBlank()) {
                subject = s.group(1).strip();
                i++;
                continue;
            }
            Matcher r = RECIPIENT_LINE.matcher(lines[i]);
            if (recipient == null && r.matches() && !r.group(1).isBlank()) {
                recipient = r.group(1).strip();
                i++;
                continue;
            }
            break;
        }
        if (subject == null && recipient == null) {
            return new Headers(null, null, content);
        }
        return new Headers(subject, recipient,
                String.join("\n", java.util.Arrays.copyOfRange(lines, i, lines.length)));
    }
}
