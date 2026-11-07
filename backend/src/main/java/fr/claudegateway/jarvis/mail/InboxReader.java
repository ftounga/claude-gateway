package fr.claudegateway.jarvis.mail;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import jakarta.mail.Address;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;

/**
 * <b>La lecture de la boîte de réception</b> (F-242 / SF-242-02, D4) — sur une boîte IMAP déjà ouverte.
 *
 * <p><b>Rien de neuf, rien de téléchargé</b> : {@code UIDVALIDITY} et {@code UIDNEXT} sont lus sur le dossier
 * <b>fermé</b> (commande {@code STATUS}) ; si rien n'a bougé, le dossier n'est jamais sélectionné et aucun
 * {@code FETCH} ne part. Sinon le dossier est ouvert en <b>lecture seule</b> (aucun drapeau modifié, rien
 * d'irréversible).</p>
 */
public final class InboxReader {

    /** Un courrier lu : en-têtes, extrait borné, indices. Jamais le corps complet. */
    public record Fetched(long uid, String messageId, String threadRef, MailHeaders headers, OffsetDateTime receivedAt,
            boolean hasCalendar, boolean bulk, String flags, byte[] calendar) {

        /** Sans invitation lue (recherche, tests). */
        public Fetched(long uid, String messageId, String threadRef, MailHeaders headers, OffsetDateTime receivedAt,
                boolean hasCalendar, boolean bulk, String flags) {
            this(uid, messageId, threadRef, headers, receivedAt, hasCalendar, bulk, flags, null);
        }
    }

    /** F-243 / SF-243-04 — taille maximale d'une invitation lue (256 Kio). */
    static final int MAX_ICS = 256 * 1024;

    /**
     * Le résultat d'une relève.
     *
     * @param uidValidity {@code UIDVALIDITY} de la boîte
     * @param lastUid     dernier UID connu après la relève
     * @param empty       rien de neuf : aucun {@code SELECT}, aucun {@code FETCH}
     * @param resync      premier passage ou {@code UIDVALIDITY} changée (resynchronisation sur 7 jours)
     * @param mails       les courriers lus (100 au plus)
     */
    public record Read(long uidValidity, long lastUid, boolean empty, boolean resync, List<Fetched> mails) {
    }

    public static final int SNIPPET_MAX = 2_000;
    public static final int MAX_PER_POLL = 100;

    private static final String[] HEADERS = {"Message-ID", "In-Reply-To", "References", "List-Unsubscribe",
            "Precedence", "Auto-Submitted", "Reply-To"};

    private InboxReader() {
    }

    /**
     * Relève la boîte de réception.
     *
     * @param knownValidity {@code UIDVALIDITY} connue, ou {@code null} au premier passage
     * @param knownLastUid  dernier UID connu, ou {@code null}
     * @param since         borne de la resynchronisation (7 jours)
     */
    public static Read read(Store store, Long knownValidity, Long knownLastUid, Instant since)
            throws MessagingException {
        Folder folder = store.getFolder("INBOX");
        if (!(folder instanceof UIDFolder uids)) {
            throw new MessagingException("INBOX sans UID");
        }
        // Dossier fermé : STATUS (UIDVALIDITY UIDNEXT), sans SELECT.
        long validity = uids.getUIDValidity();
        long next = uids.getUIDNext();
        boolean resync = knownValidity == null || knownLastUid == null || knownValidity != validity;
        if (!resync && next - 1 <= knownLastUid) {
            return new Read(validity, knownLastUid, true, false, List.of());
        }
        folder.open(Folder.READ_ONLY);
        try {
            Message[] candidates = resync
                    ? folder.search(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(since)))
                    : uids.getMessagesByUID(knownLastUid + 1, UIDFolder.MAXUID);
            FetchProfile profile = new FetchProfile();
            profile.add(UIDFolder.FetchProfileItem.UID);
            folder.fetch(candidates, profile);
            long floor = resync ? 0 : knownLastUid;
            List<Message> fresh = new ArrayList<>();
            long maxUid = resync ? 0 : knownLastUid;
            for (Message m : candidates) {
                long uid = uids.getUID(m);
                if (uid > floor) {
                    fresh.add(m);
                    maxUid = Math.max(maxUid, uid);
                }
            }
            // 100 au plus : les plus récents (UID les plus hauts).
            fresh.sort(Comparator.comparingLong(m -> uidOf(uids, m)));
            if (fresh.size() > MAX_PER_POLL) {
                fresh = new ArrayList<>(fresh.subList(fresh.size() - MAX_PER_POLL, fresh.size()));
            }
            Message[] batch = fresh.toArray(Message[]::new);
            FetchProfile details = new FetchProfile();
            details.add(FetchProfile.Item.ENVELOPE);
            details.add(FetchProfile.Item.FLAGS);
            details.add(FetchProfile.Item.CONTENT_INFO);
            for (String h : HEADERS) {
                details.add(h);
            }
            folder.fetch(batch, details);
            List<Fetched> mails = new ArrayList<>();
            for (Message m : batch) {
                mails.add(fetched(uids.getUID(m), m));
            }
            // Tout UID < UIDNEXT a été vu (ou volontairement laissé au-delà des 100) : la relève suivante ne le relit pas.
            long lastUid = Math.max(maxUid, next - 1);
            return new Read(validity, lastUid, false, resync, mails);
        } finally {
            if (folder.isOpen()) {
                folder.close(false); // jamais d'EXPUNGE
            }
        }
    }

    /** Les en-têtes et l'extrait d'un courrier (aussi utilisé par la recherche, SF-242-05). */
    public static Fetched fetched(long uid, Message m) throws MessagingException {
        InternetAddress from = first(m.getFrom());
        String fromAddr = from == null ? "" : lower(from.getAddress());
        String fromName = from == null || from.getPersonal() == null ? "" : from.getPersonal();
        InternetAddress replyTo = first(m.getReplyTo());
        String reply = replyTo == null || lower(replyTo.getAddress()).equals(fromAddr) ? null
                : lower(replyTo.getAddress());
        MailHeaders headers = new MailHeaders(fromAddr, fromName, addresses(m.getRecipients(Message.RecipientType.TO)),
                addresses(m.getRecipients(Message.RecipientType.CC)), reply, m.getSubject(),
                MailText.text(m, SNIPPET_MAX));
        Date when = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
        OffsetDateTime received = (when == null ? Instant.now() : when.toInstant()).atOffset(ZoneOffset.UTC);
        String messageId = clip(header(m, "Message-ID"), 500);
        String threadRef = clip(threadRef(m), 500);
        boolean calendar = MailText.hasCalendar(m);
        return new Fetched(uid, messageId, threadRef, headers, received, calendar, isBulk(m, fromAddr),
                flags(m.getFlags()), calendar ? MailText.calendarBytes(m, MAX_ICS) : null);
    }

    /** D6 — indice « courrier de masse » : {@code List-Unsubscribe}, {@code Precedence: bulk}, expéditeur automatique. */
    static boolean isBulk(Message m, String fromAddr) throws MessagingException {
        return isBulk(header(m, "List-Unsubscribe"), header(m, "Precedence"), fromAddr);
    }

    /** D6 — la même règle sur des en-têtes déjà lus (F-249 : un {@code .msg} déposé). */
    public static boolean isBulk(String listUnsubscribe, String precedence, String fromAddr) {
        if (listUnsubscribe != null) {
            return true;
        }
        if (precedence != null && precedence.toLowerCase(Locale.ROOT).matches(".*\\b(bulk|list|junk)\\b.*")) {
            return true;
        }
        String from = fromAddr == null ? "" : fromAddr;
        String local = from.contains("@") ? from.substring(0, from.indexOf('@')) : from;
        return local.matches("(?i).*(no-?reply|do-?not-?reply|ne-?pas-?repondre|mailer-daemon).*");
    }

    private static String threadRef(Message m) throws MessagingException {
        String inReplyTo = header(m, "In-Reply-To");
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            return inReplyTo.strip();
        }
        String refs = header(m, "References");
        if (refs == null || refs.isBlank()) {
            return null;
        }
        String[] parts = refs.strip().split("\\s+");
        return parts[parts.length - 1];
    }

    static String header(Message m, String name) throws MessagingException {
        String[] values = m.getHeader(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    private static InternetAddress first(Address[] addresses) {
        if (addresses == null) {
            return null;
        }
        return Arrays.stream(addresses).filter(InternetAddress.class::isInstance).map(InternetAddress.class::cast)
                .findFirst().orElse(null);
    }

    static List<String> addresses(Address[] addresses) {
        if (addresses == null) {
            return List.of();
        }
        return Arrays.stream(addresses).filter(InternetAddress.class::isInstance).map(InternetAddress.class::cast)
                .map(a -> lower(a.getAddress())).filter(a -> !a.isEmpty()).limit(50).toList();
    }

    private static String flags(Flags flags) {
        if (flags == null) {
            return "";
        }
        List<String> out = new ArrayList<>();
        if (flags.contains(Flags.Flag.SEEN)) {
            out.add("SEEN");
        }
        if (flags.contains(Flags.Flag.ANSWERED)) {
            out.add("ANSWERED");
        }
        if (flags.contains(Flags.Flag.FLAGGED)) {
            out.add("FLAGGED");
        }
        return String.join(",", out);
    }

    private static long uidOf(UIDFolder uids, Message m) {
        try {
            return uids.getUID(m);
        } catch (MessagingException e) {
            return 0;
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }

    private static String clip(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
