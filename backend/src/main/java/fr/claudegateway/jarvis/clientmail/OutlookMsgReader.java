package fr.claudegateway.jarvis.clientmail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.AttachmentChunks;
import org.apache.poi.hsmf.datatypes.ByteChunk;
import org.apache.poi.hsmf.datatypes.Chunk;
import org.apache.poi.hsmf.datatypes.MAPIProperty;
import org.apache.poi.hsmf.datatypes.NameIdChunks;
import org.apache.poi.hsmf.datatypes.PropertyValue;
import org.apache.poi.hsmf.datatypes.RecipientChunks;
import org.apache.poi.hsmf.datatypes.StringChunk;

import fr.claudegateway.jarvis.mail.InboxReader;
import fr.claudegateway.jarvis.mail.MailHeaders;
import fr.claudegateway.jarvis.mail.MailText;

/**
 * <b>Lire un {@code .msg} Outlook déposé</b> (F-249 / SF-249-01, D6) — par Apache POI HSMF, sans rien exécuter ni
 * ouvrir d'autre que le texte et l'invitation.
 *
 * <ul>
 * <li><b>Expéditeur</b> : l'adresse <b>SMTP</b> ({@code PR_SENDER_SMTP_ADDRESS}, sinon celle « de la part de », sinon
 * l'en-tête {@code From:} de transport), jamais le DN X.500 d'un expéditeur Exchange interne.</li>
 * <li><b>Fil</b> : {@code PR_INTERNET_MESSAGE_ID}, {@code PR_IN_REPLY_TO_ID}, {@code PR_INTERNET_REFERENCES}, sinon les
 * en-têtes de transport.</li>
 * <li><b>Invitation</b> : une pièce {@code .ics}/{@code text/calendar} (≤ 256 Kio) ; sinon, pour une demande de réunion
 * Outlook ({@code IPM.Schedule.Meeting.Request|Canceled}), un iCalendar <b>composé</b> depuis ses propriétés : début et
 * fin ({@code PR_START_DATE}/{@code PR_END_DATE}, sinon les propriétés nommées {@code PidLidAppointmentStartWhole/
 * EndWhole}), lieu ({@code PidLidLocation}), identifiant ({@code PidLidCleanGlobalObjectId}, sinon le sujet de
 * conversation). Les invités ne sont jamais lus.</li>
 * </ul>
 */
public final class OutlookMsgReader {

    static final int SUBJECT = 0x0037;
    static final int MESSAGE_CLASS = 0x001A;
    static final int SENT_REPRESENTING_NAME = 0x0042;
    static final int SENT_REPRESENTING_EMAIL = 0x0065;
    static final int CONVERSATION_TOPIC = 0x0070;
    static final int TRANSPORT_HEADERS = 0x007D;
    static final int SENDER_NAME = 0x0C1A;
    static final int SENDER_EMAIL = 0x0C1F;
    static final int BODY = 0x1000;
    static final int HTML = 0x1013;
    static final int INTERNET_MESSAGE_ID = 0x1035;
    static final int INTERNET_REFERENCES = 0x1039;
    static final int IN_REPLY_TO = 0x1042;
    static final int SENDER_SMTP = 0x5D01;
    static final int SENT_REPRESENTING_SMTP = 0x5D02;
    static final int START_DATE = 0x0060;
    static final int END_DATE = 0x0061;
    static final int DELIVERY_TIME = 0x0E06;
    static final int SUBMIT_TIME = 0x0039;
    static final int RECIPIENT_TYPE = 0x0C15;

    /** {@code PSETID_Appointment} : {@code PidLidAppointmentStartWhole}, {@code EndWhole}, {@code Location}. */
    static final long LID_START_WHOLE = 0x820D;
    static final long LID_END_WHOLE = 0x820E;
    static final long LID_LOCATION = 0x8208;
    /** {@code PSETID_Meeting} : {@code PidLidCleanGlobalObjectId}, {@code PidLidGlobalObjectId}. */
    static final long LID_CLEAN_GLOBAL_OBJECT_ID = 0x0023;
    static final long LID_GLOBAL_OBJECT_ID = 0x0003;

    private static final Pattern ADDRESS = Pattern.compile("<?([^<>\\s\"]+@[^<>\\s\"]+)>?");
    private static final DateTimeFormatter ICS_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private OutlookMsgReader() {
    }

    public static DepositedMail read(byte[] bytes) {
        MAPIMessage msg;
        try {
            msg = new MAPIMessage(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            throw new DepositedMail.UnreadableMailException("Fichier Outlook illisible.");
        }
        try {
            msg.setReturnNullOnMissingChunk(true);
            try {
                msg.guess7BitEncoding();
            } catch (RuntimeException e) {
                // chaînes 8 bits laissées telles quelles
            }
            Map<Integer, String> strings = strings(msg);
            Map<Integer, Object> fixed = fixed(msg);
            Map<String, String> transport = transportHeaders(strings.get(TRANSPORT_HEADERS));

            String subject = nz(strings.get(SUBJECT));
            String messageClass = nz(strings.get(MESSAGE_CLASS));
            String fromAddr = sender(strings, transport);
            String fromName = firstNonBlank(strings.get(SENDER_NAME), strings.get(SENT_REPRESENTING_NAME));
            if (fromName.isBlank()) {
                fromName = displayName(transport.get("from"));
            }
            String messageId = DepositedMail.clip(firstNonBlank(strings.get(INTERNET_MESSAGE_ID),
                    transport.get("message-id")).strip(), 500);
            String threadRef = DepositedMail.clip(threadRef(firstNonBlank(strings.get(IN_REPLY_TO),
                    transport.get("in-reply-to")), firstNonBlank(strings.get(INTERNET_REFERENCES),
                    transport.get("references"))), 500);
            if (subject.isBlank() && fromAddr.isBlank() && (messageId == null || messageId.isBlank())
                    && messageClass.isBlank()) {
                throw new DepositedMail.UnreadableMailException("Aucun en-tête de courrier.");
            }

            List<String> to = new ArrayList<>();
            List<String> cc = new ArrayList<>();
            recipients(msg, transport, to, cc);
            String replyTo = address(transport.get("reply-to"));
            if (replyTo != null && replyTo.equals(fromAddr)) {
                replyTo = null;
            }
            String body = body(strings, msg);
            MailHeaders headers = new MailHeaders(fromAddr, fromName, to, cc, replyTo, subject,
                    MailText.plain(body, DepositedMail.SNIPPET_MAX));
            OffsetDateTime received = time(fixed.get(DELIVERY_TIME));
            if (received == null) {
                received = time(fixed.get(SUBMIT_TIME));
            }
            if (received == null) {
                received = messageDate(msg);
            }
            boolean bulk = InboxReader.isBulk(transport.get("list-unsubscribe"), transport.get("precedence"), fromAddr)
                    || autoSubmitted(transport.get("auto-submitted"));
            byte[] invitation = attachedCalendar(msg);
            if (invitation == null && messageClass.toLowerCase(Locale.ROOT).startsWith("ipm.schedule.meeting")) {
                invitation = meeting(msg, fixed, strings, messageClass, subject, fromAddr);
            }
            return new DepositedMail(messageId == null || messageId.isBlank() ? null : messageId,
                    threadRef == null || threadRef.isBlank() ? null : threadRef, headers, received, bulk,
                    MailText.plain(body, DepositedMail.BODY_MAX), invitation);
        } catch (DepositedMail.UnreadableMailException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DepositedMail.UnreadableMailException("Fichier Outlook illisible.");
        }
    }

    /** Toutes les chaînes du message, par identifiant de propriété (la première trouvée). */
    static Map<Integer, String> strings(MAPIMessage msg) {
        Map<Integer, String> out = new HashMap<>();
        for (Chunk c : msg.getMainChunks().getChunks()) {
            if (c instanceof StringChunk sc) {
                String v = sc.getValue();
                if (v != null) {
                    out.putIfAbsent(sc.getChunkId(), v);
                }
            } else if (c instanceof ByteChunk bc && c.getChunkId() == HTML && bc.getValue() != null) {
                out.putIfAbsent(HTML, new String(bc.getValue(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /** Les propriétés de taille fixe (dates, nombres), par identifiant. */
    static Map<Integer, Object> fixed(MAPIMessage msg) {
        Map<Integer, Object> out = new HashMap<>();
        Map<MAPIProperty, List<PropertyValue>> props = msg.getMainChunks().getProperties();
        if (props == null) {
            return out;
        }
        props.forEach((prop, values) -> {
            if (prop != null && values != null && !values.isEmpty() && values.get(0) != null) {
                out.putIfAbsent(prop.id, values.get(0).getValue());
            }
        });
        return out;
    }

    /** Les en-têtes de transport (internet) d'un message reçu : noms en minuscules, lignes repliées dépliées. */
    static Map<String, String> transportHeaders(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String unfolded = raw.replace("\r\n", "\n").replaceAll("\n[ \t]+", " ");
        for (String line : unfolded.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            if (name.contains(" ")) {
                continue;
            }
            out.putIfAbsent(name, line.substring(colon + 1).strip());
        }
        return out;
    }

    private static String sender(Map<Integer, String> s, Map<String, String> transport) {
        for (String candidate : new String[] {s.get(SENDER_SMTP), s.get(SENT_REPRESENTING_SMTP),
                address(transport.get("from")), s.get(SENDER_EMAIL), s.get(SENT_REPRESENTING_EMAIL)}) {
            String a = address(candidate);
            if (a != null) {
                return a;
            }
        }
        return "";
    }

    /** Une adresse e-mail lisible (jamais un DN X.500 {@code /O=…}), en minuscules ; sinon {@code null}. */
    static String address(String raw) {
        if (raw == null || raw.isBlank() || raw.strip().startsWith("/")) {
            return null;
        }
        Matcher m = ADDRESS.matcher(raw);
        String found = null;
        while (m.find()) {
            found = m.group(1);
            if (raw.contains("<" + found + ">")) {
                break;
            }
        }
        return found == null ? null : found.strip().toLowerCase(Locale.ROOT);
    }

    private static String displayName(String from) {
        if (from == null) {
            return "";
        }
        int lt = from.indexOf('<');
        return lt > 0 ? from.substring(0, lt).replace("\"", "").strip() : "";
    }

    private static void recipients(MAPIMessage msg, Map<String, String> transport, List<String> to, List<String> cc) {
        RecipientChunks[] all = msg.getRecipientDetailsChunks();
        if (all != null) {
            for (RecipientChunks r : all) {
                String a = address(r.getRecipientSMTPChunk() != null ? r.getRecipientSMTPChunk().getValue()
                        : r.getRecipientEmailAddress());
                if (a == null || to.size() + cc.size() >= 50) {
                    continue;
                }
                (recipientType(r) == 2 ? cc : to).add(a);
            }
        }
        if (to.isEmpty() && cc.isEmpty()) {
            addAll(to, transport.get("to"));
            addAll(cc, transport.get("cc"));
        }
    }

    private static int recipientType(RecipientChunks r) {
        try {
            Map<MAPIProperty, List<PropertyValue>> props = r.getProperties();
            if (props != null) {
                for (Map.Entry<MAPIProperty, List<PropertyValue>> e : props.entrySet()) {
                    if (e.getKey().id == RECIPIENT_TYPE && !e.getValue().isEmpty()
                            && e.getValue().get(0).getValue() instanceof Number n) {
                        return n.intValue();
                    }
                }
            }
        } catch (RuntimeException e) {
            // type inconnu : « à »
        }
        return 1;
    }

    private static void addAll(List<String> out, String header) {
        if (header == null) {
            return;
        }
        for (String part : header.split(",")) {
            String a = address(part);
            if (a != null && out.size() < 50) {
                out.add(a);
            }
        }
    }

    private static String body(Map<Integer, String> s, MAPIMessage msg) {
        String plain = s.get(BODY);
        if (plain != null && !plain.isBlank()) {
            return plain;
        }
        String html = s.get(HTML);
        if (html == null) {
            try {
                html = msg.getHtmlBody();
            } catch (Exception e) {
                html = null;
            }
        }
        return html == null ? "" : MailText.htmlToText(html);
    }

    private static String threadRef(String inReplyTo, String references) {
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            return inReplyTo.strip();
        }
        if (references == null || references.isBlank()) {
            return null;
        }
        String[] parts = references.strip().split("\\s+");
        return parts[parts.length - 1];
    }

    private static boolean autoSubmitted(String value) {
        return value != null && !value.isBlank() && !value.strip().toLowerCase(Locale.ROOT).startsWith("no");
    }

    static OffsetDateTime time(Object value) {
        if (value instanceof Calendar c) {
            return c.toInstant().atOffset(ZoneOffset.UTC);
        }
        if (value instanceof java.util.Date d) {
            return d.toInstant().atOffset(ZoneOffset.UTC);
        }
        return null;
    }

    private static OffsetDateTime messageDate(MAPIMessage msg) {
        try {
            Calendar c = msg.getMessageDate();
            if (c != null) {
                return c.toInstant().atOffset(ZoneOffset.UTC);
            }
        } catch (Exception e) {
            // pas de date : maintenant
        }
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** La première pièce {@code .ics} / {@code text/calendar} (≤ 256 Kio), ou {@code null}. */
    static byte[] attachedCalendar(MAPIMessage msg) {
        AttachmentChunks[] attachments = msg.getAttachmentFiles();
        if (attachments == null) {
            return null;
        }
        for (AttachmentChunks a : attachments) {
            String name = firstNonBlank(value(a.getAttachLongFileName()), value(a.getAttachFileName()))
                    .toLowerCase(Locale.ROOT);
            String mime = value(a.getAttachMimeTag()).toLowerCase(Locale.ROOT);
            if (!name.endsWith(".ics") && !mime.startsWith("text/calendar")) {
                continue;
            }
            byte[] data = a.getAttachData() == null ? null : a.getAttachData().getValue();
            if (data != null && data.length > 0 && data.length <= DepositedMail.MAX_ICS) {
                return data;
            }
        }
        return null;
    }

    private static String value(StringChunk chunk) {
        return chunk == null || chunk.getValue() == null ? "" : chunk.getValue();
    }

    /**
     * Une demande (ou annulation) de réunion Outlook sans {@code .ics} : un iCalendar minimal, composé des seules
     * propriétés lues (aucun invité). {@code null} si le début manque.
     */
    static byte[] meeting(MAPIMessage msg, Map<Integer, Object> fixed, Map<Integer, String> strings,
            String messageClass, String subject, String organizer) {
        NameIdChunks names = msg.getNameIdChunks();
        Map<Integer, Object> named = new HashMap<>();
        Map<Integer, String> namedStrings = new HashMap<>();
        Integer startId = namedId(names, NameIdChunks.PredefinedPropertySet.PSETID_APPOINTMENT, LID_START_WHOLE);
        Integer endId = namedId(names, NameIdChunks.PredefinedPropertySet.PSETID_APPOINTMENT, LID_END_WHOLE);
        Integer locationId = namedId(names, NameIdChunks.PredefinedPropertySet.PSETID_APPOINTMENT, LID_LOCATION);
        Integer cleanId = namedId(names, NameIdChunks.PredefinedPropertySet.PSETID_MEETING, LID_CLEAN_GLOBAL_OBJECT_ID);
        Integer globalId = namedId(names, NameIdChunks.PredefinedPropertySet.PSETID_MEETING, LID_GLOBAL_OBJECT_ID);
        for (Chunk c : msg.getMainChunks().getChunks()) {
            if (c instanceof StringChunk sc && sc.getValue() != null) {
                namedStrings.putIfAbsent(sc.getChunkId(), sc.getValue());
            } else if (c instanceof ByteChunk bc && bc.getValue() != null) {
                named.putIfAbsent(bc.getChunkId(), bc.getValue());
            }
        }
        OffsetDateTime start = time(fixed.get(START_DATE));
        if (start == null && startId != null) {
            start = time(fixed.get(startId));
        }
        OffsetDateTime end = time(fixed.get(END_DATE));
        if (end == null && endId != null) {
            end = time(fixed.get(endId));
        }
        if (start == null) {
            return null;
        }
        if (end == null || end.isBefore(start)) {
            end = start.plusHours(1);
        }
        String location = locationId == null ? "" : nz(namedStrings.get(locationId));
        byte[] uidBytes = cleanId != null && named.get(cleanId) instanceof byte[] b ? b
                : globalId != null && named.get(globalId) instanceof byte[] g ? g : null;
        String topic = firstNonBlank(strings.get(CONVERSATION_TOPIC), subject);
        String uid = uidBytes != null && uidBytes.length > 0 ? HexFormat.of().withUpperCase().formatHex(uidBytes)
                : "outlook-" + HexFormat.of().formatHex(sha256((topic.strip().toLowerCase(Locale.ROOT) + "|"
                        + nz(organizer)).getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        boolean cancel = messageClass.toLowerCase(Locale.ROOT).contains("canceled")
                || messageClass.toLowerCase(Locale.ROOT).contains("cancelled");
        StringBuilder ics = new StringBuilder();
        ics.append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//claude-gateway//F-249 Outlook//FR\r\n")
                .append("METHOD:").append(cancel ? "CANCEL" : "REQUEST").append("\r\n")
                .append("BEGIN:VEVENT\r\n")
                .append("UID:").append(uid).append("\r\n")
                .append("DTSTAMP:").append(ICS_UTC.format(Instant.now())).append("\r\n")
                .append("DTSTART:").append(ICS_UTC.format(start.toInstant())).append("\r\n")
                .append("DTEND:").append(ICS_UTC.format(end.toInstant())).append("\r\n")
                .append("SUMMARY:").append(escape(topic)).append("\r\n");
        if (!location.isBlank()) {
            ics.append("LOCATION:").append(escape(location)).append("\r\n");
        }
        if (cancel) {
            ics.append("STATUS:CANCELLED\r\n");
        }
        ics.append("END:VEVENT\r\nEND:VCALENDAR\r\n");
        return ics.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * L'identifiant (0x8000 + index) d'une propriété nommée numérique, lu dans {@code __nameid_version1.0} selon
     * [MS-OXMSG] 2.2.3 : flux des GUID ({@code 0002}), flux des entrées ({@code 0003}, 8 octets : LID, puis
     * {@code index << 16 | guid << 1 | genre}). {@code null} si absente.
     */
    static Integer namedId(NameIdChunks names, NameIdChunks.PredefinedPropertySet set, long lid) {
        if (names == null) {
            return null;
        }
        byte[] guids = null;
        byte[] entries = null;
        for (Chunk c : names.getChunks()) {
            if (c instanceof ByteChunk bc) {
                if (c.getChunkId() == 0x0002) {
                    guids = bc.getValue();
                } else if (c.getChunkId() == 0x0003) {
                    entries = bc.getValue();
                }
            }
        }
        if (entries == null) {
            return null;
        }
        byte[] wanted = new byte[16];
        set.getClassID().write(wanted, 0);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(entries).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        while (buf.remaining() >= 8) {
            long id = buf.getInt() & 0xFFFFFFFFL;
            int info = buf.getInt();
            int kind = info & 1;
            int guidIndex = (info >>> 1) & 0x7FFF;
            int propIndex = (info >>> 16) & 0xFFFF;
            if (kind != 0 || id != lid || guidIndex < 3 || guids == null) {
                continue;
            }
            int offset = (guidIndex - 3) * 16;
            if (offset + 16 <= guids.length
                    && java.util.Arrays.equals(guids, offset, offset + 16, wanted, 0, 16)) {
                return 0x8000 + propIndex;
            }
        }
        return null;
    }

    private static String escape(String s) {
        String clean = s == null ? "" : s.replace("\r", " ").replace("\n", " ");
        clean = clean.length() > 200 ? clean.substring(0, 200) : clean;
        return clean.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,");
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
