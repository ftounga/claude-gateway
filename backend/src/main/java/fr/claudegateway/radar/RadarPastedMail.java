package fr.claudegateway.radar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Un courriel collé par l'utilisateur</b> (F-104 / SF-104-02, cadrage §10) : l'en-tête reconnu, pour dater
 * la preuve <b>du courriel</b> et rattacher son expéditeur à l'annuaire.
 *
 * <p>Outlook a été retiré par le PO : un courriel entre dans le Radar parce que l'utilisateur le colle. Ce
 * qu'on colle depuis Outlook (bureau ou web), Gmail ou un client quelconque commence par un bloc de lignes
 * {@code De :} / {@code From:}, {@code Envoyé :} / {@code Sent:} / {@code Date :}, {@code À :} / {@code To:},
 * {@code Objet :} / {@code Subject:}, en français ou en anglais. Rien d'autre n'est deviné : sans expéditeur
 * et sans ligne de date, ce n'est pas un courriel, c'est une note.</p>
 *
 * <p>Classe sans état ni dépendance : elle se teste sans base.</p>
 */
public final class RadarPastedMail {

    /** Lignes non vides examinées pour trouver l'en-tête. */
    static final int HEADER_LINES = 12;

    private static final Pattern HEADER = Pattern.compile(
            "^\\s*(de|from|exp[ée]diteur|envoy[ée]|sent|date|[àa]|to|cc|cci|bcc|objet|subject)\\s*:\\s*(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern ADDRESS = Pattern.compile("[\\w.+'-]+@[\\w-]+(\\.[\\w-]+)+");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("janvier", 1), Map.entry("janv", 1), Map.entry("january", 1), Map.entry("jan", 1),
            Map.entry("février", 2), Map.entry("fevrier", 2), Map.entry("févr", 2), Map.entry("fevr", 2),
            Map.entry("february", 2), Map.entry("feb", 2),
            Map.entry("mars", 3), Map.entry("march", 3), Map.entry("mar", 3),
            Map.entry("avril", 4), Map.entry("avr", 4), Map.entry("april", 4), Map.entry("apr", 4),
            Map.entry("mai", 5), Map.entry("may", 5),
            Map.entry("juin", 6), Map.entry("june", 6), Map.entry("jun", 6),
            Map.entry("juillet", 7), Map.entry("juil", 7), Map.entry("july", 7), Map.entry("jul", 7),
            Map.entry("août", 8), Map.entry("aout", 8), Map.entry("august", 8), Map.entry("aug", 8),
            Map.entry("septembre", 9), Map.entry("sept", 9), Map.entry("september", 9), Map.entry("sep", 9),
            Map.entry("octobre", 10), Map.entry("oct", 10), Map.entry("october", 10),
            Map.entry("novembre", 11), Map.entry("nov", 11), Map.entry("november", 11),
            Map.entry("décembre", 12), Map.entry("decembre", 12), Map.entry("déc", 12), Map.entry("dec", 12),
            Map.entry("december", 12));

    /** « 9 septembre 2026 », « September 9, 2026 », « 9 Sep 2026 ». */
    private static final Pattern WORD_DATE = Pattern.compile(
            "(?:(\\d{1,2})(?:er)?\\s+([\\p{L}]+)\\.?\\s+(\\d{4})|([\\p{L}]+)\\.?\\s+(\\d{1,2}),?\\s+(\\d{4}))",
            Pattern.UNICODE_CASE);
    /** « 09/09/2026 », « 9.9.2026 », « 2026-09-09 ». */
    private static final Pattern NUMERIC_DATE = Pattern.compile(
            "(?:(\\d{1,2})[/.](\\d{1,2})[/.](\\d{4})|(\\d{4})-(\\d{2})-(\\d{2}))");
    /** « 14:32 », « 14:32:10 », « 2:32 PM », « 14h32 ». */
    private static final Pattern TIME = Pattern.compile(
            "(\\d{1,2})\\s*[:h]\\s*(\\d{2})(?::(\\d{2}))?\\s*([ap]\\.?m\\.?)?", Pattern.CASE_INSENSITIVE);
    /** « +0200 », « +02:00 », « GMT+2 ». */
    private static final Pattern OFFSET = Pattern.compile("(?:GMT|UTC)?\\s*([+-])(\\d{1,2}):?(\\d{2})?(?!\\d)");

    /**
     * Ce qui a été lu de l'en-tête.
     *
     * @param senderName    nom de l'expéditeur (à défaut, son adresse)
     * @param senderAddress adresse de l'expéditeur, ou {@code null}
     * @param sentAt        date du courriel, ou {@code null} si la ligne de date est illisible
     * @param subject       objet, ou {@code null}
     * @param body          le texte après l'en-tête
     */
    public record Mail(String senderName, String senderAddress, OffsetDateTime sentAt, String subject, String body) {

        /** La clé d'annuaire de l'expéditeur : son adresse, sinon son nom. */
        public String senderKey() {
            return "mail:" + (senderAddress != null ? senderAddress.toLowerCase(Locale.ROOT) : RadarText.key(senderName));
        }

        /** La citation courte : l'objet, puis le début du corps (tronquée par le registre). */
        public String quote() {
            String start = body == null ? "" : body.strip().replaceAll("\\s+", " ");
            String head = subject == null || subject.isBlank() ? "" : subject.strip();
            String text = head.isEmpty() ? start : start.isEmpty() ? head : head + " — " + start;
            return text.isBlank() ? "(courriel sans texte)" : text;
        }

        /** Identifiant stable : coller deux fois le même courriel désigne la même preuve. */
        public String sourceRef() {
            String material = RadarText.key(senderKey()) + '|' + (sentAt == null ? "" : sentAt.toInstant()) + '|'
                    + RadarText.key(subject) + '|' + RadarText.key(body);
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
                return "mail:" + HexFormat.of().formatHex(digest, 0, 16);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private RadarPastedMail() {
    }

    /**
     * Lit l'en-tête d'un texte collé.
     *
     * @param text texte donné par l'utilisateur
     * @param zone fuseau du poste, pour une date sans décalage
     * @return le courriel, ou vide si le texte ne commence pas par un en-tête (expéditeur <b>et</b> date)
     */
    public static Optional<Mail> parse(String text, ZoneId zone) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        String from = null;
        String sent = null;
        String subject = null;
        boolean sentLineSeen = false;
        int examined = 0;
        int lastHeader = -1;
        for (int i = 0; i < lines.length && examined < HEADER_LINES; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                if (lastHeader >= 0 && from != null && sentLineSeen) {
                    break;
                }
                continue;
            }
            examined++;
            Matcher header = HEADER.matcher(line);
            if (!header.matches()) {
                if (lastHeader >= 0 && from != null && sentLineSeen) {
                    break;
                }
                continue;
            }
            String name = header.group(1).toLowerCase(Locale.ROOT);
            String value = header.group(2).strip();
            lastHeader = i;
            switch (name) {
                case "de", "from", "expéditeur", "expediteur" -> from = from == null ? value : from;
                case "envoyé", "envoye", "sent", "date" -> {
                    sentLineSeen = true;
                    sent = sent == null ? value : sent;
                }
                case "objet", "subject" -> subject = subject == null ? value : subject;
                default -> {
                    // destinataires : lus pour reconnaître le bloc, non conservés
                }
            }
        }
        if (from == null || from.isBlank() || !sentLineSeen) {
            return Optional.empty();
        }
        StringBuilder body = new StringBuilder();
        for (int i = lastHeader + 1; i < lines.length; i++) {
            body.append(lines[i]).append('\n');
        }
        String[] sender = sender(from);
        return Optional.of(new Mail(sender[0], sender[1], date(sent, zone).orElse(null),
                subject == null || subject.isBlank() ? null : subject, body.toString().strip()));
    }

    /** « Sophie Martin &lt;sophie@x.fr&gt; », « Martin, Sophie », « sophie@x.fr », « "Martin Sophie" [mailto:…] ». */
    static String[] sender(String raw) {
        String value = raw.strip();
        Matcher address = ADDRESS.matcher(value);
        String mail = address.find() ? address.group() : null;
        String name = value.replaceAll("<[^>]*>", "").replaceAll("\\[[^]]*]", "").replaceAll("\\([^)]*@[^)]*\\)", "")
                .replace("\"", "").replace("'", "").strip();
        if (mail != null) {
            name = name.replace(mail, "").strip();
        }
        if (name.matches("[^,]+,\\s*[^,]+")) {
            String[] parts = name.split(",\\s*");
            name = parts[1].strip() + " " + parts[0].strip();
        }
        if (name.isBlank()) {
            name = mail == null ? value : mail;
        }
        if (name.length() > RadarPerson.MAX_NAME_LENGTH) {
            name = name.substring(0, RadarPerson.MAX_NAME_LENGTH);
        }
        return new String[] {name, mail};
    }

    /** La date d'une ligne d'en-tête, ou vide si elle ne se lit pas. */
    static Optional<OffsetDateTime> date(String raw, ZoneId zone) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.strip();
        int year;
        int month;
        int day;
        String rest;
        Matcher words = WORD_DATE.matcher(value);
        Matcher numbers = NUMERIC_DATE.matcher(value);
        if (numbers.find()) {
            if (numbers.group(4) != null) {
                year = Integer.parseInt(numbers.group(4));
                month = Integer.parseInt(numbers.group(5));
                day = Integer.parseInt(numbers.group(6));
            } else {
                // Ordre français : jour / mois / année (la clientèle visée).
                day = Integer.parseInt(numbers.group(1));
                month = Integer.parseInt(numbers.group(2));
                year = Integer.parseInt(numbers.group(3));
            }
            rest = value.substring(numbers.end());
        } else {
            Optional<int[]> found = wordDate(words);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            day = found.get()[0];
            month = found.get()[1];
            year = found.get()[2];
            rest = value.substring(found.get()[3]);
        }
        int hour = 0;
        int minute = 0;
        int second = 0;
        Matcher time = TIME.matcher(rest);
        if (time.find()) {
            hour = Integer.parseInt(time.group(1));
            minute = Integer.parseInt(time.group(2));
            second = time.group(3) == null ? 0 : Integer.parseInt(time.group(3));
            String meridiem = time.group(4) == null ? "" : time.group(4).toLowerCase(Locale.ROOT).replace(".", "");
            if ("pm".equals(meridiem) && hour < 12) {
                hour += 12;
            } else if ("am".equals(meridiem) && hour == 12) {
                hour = 0;
            }
            rest = rest.substring(time.end());
        }
        try {
            LocalDateTime local = LocalDateTime.of(year, month, day, hour, minute, second);
            Matcher offset = OFFSET.matcher(rest);
            if (offset.find()) {
                int hours = Integer.parseInt(offset.group(2));
                int minutes = offset.group(3) == null ? 0 : Integer.parseInt(offset.group(3));
                int sign = "-".equals(offset.group(1)) ? -1 : 1;
                return Optional.of(local.atOffset(ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)));
            }
            return Optional.of(local.atZone(zone == null ? ZoneId.of("Europe/Paris") : zone).toOffsetDateTime());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Jour, mois, année et fin de la correspondance d'une date écrite en toutes lettres. */
    private static Optional<int[]> wordDate(Matcher words) {
        List<int[]> candidates = new ArrayList<>();
        while (words.find()) {
            String monthWord = words.group(2) != null ? words.group(2) : words.group(4);
            Integer month = MONTHS.get(monthWord.toLowerCase(Locale.ROOT));
            if (month == null) {
                continue;
            }
            int day = Integer.parseInt(words.group(1) != null ? words.group(1) : words.group(5));
            int year = Integer.parseInt(words.group(3) != null ? words.group(3) : words.group(6));
            candidates.add(new int[] {day, month, year, words.end()});
        }
        return candidates.stream().findFirst();
    }
}
