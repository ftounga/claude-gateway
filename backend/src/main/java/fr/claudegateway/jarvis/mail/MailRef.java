package fr.claudegateway.jarvis.mail;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>La référence d'un courrier pour les outils d'Opus</b> (F-242 / SF-242-05) — sans état, vérifiable :
 * {@code courriel:<id>} (un courrier du cache, boîte de réception) ou {@code imap:<boîte>:<A|I>:<uidvalidity>:<uid>}
 * (un résultat de recherche, dans « Tous les messages » {@code A} ou la boîte de réception {@code I}). La boîte est
 * toujours revérifiée sous le {@code user_id} du tour : une référence forgée vers la boîte d'autrui est introuvable.
 *
 * @param itemId      le courrier du cache, ou {@code null}
 * @param accountId   la boîte (résolue pour {@code courriel:} par le cache)
 * @param all         vrai : dossier « Tous les messages » ; faux : boîte de réception
 * @param uidvalidity {@code UIDVALIDITY} du dossier
 * @param uid         UID du message
 */
public record MailRef(UUID itemId, UUID accountId, boolean all, long uidvalidity, long uid) {

    private static final Pattern CACHED = Pattern.compile("^courriel:([0-9a-fA-F-]{36})$");
    private static final Pattern IMAP = Pattern.compile("^imap:([0-9a-fA-F-]{36}):([AI]):(\\d{1,19}):(\\d{1,19})$");

    /** La forme d'un résultat de recherche. */
    public static String imap(UUID accountId, boolean all, long uidvalidity, long uid) {
        return "imap:" + accountId + ":" + (all ? "A" : "I") + ":" + uidvalidity + ":" + uid;
    }

    /** {@code courriel:<id>} seul (sans résoudre), ou {@code null}. */
    public static UUID cachedId(String raw) {
        Matcher m = CACHED.matcher(raw == null ? "" : raw.strip());
        if (!m.matches()) {
            return null;
        }
        try {
            return UUID.fromString(m.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code imap:…} sans résoudre la boîte, ou {@code null}. */
    public static MailRef parseImap(String raw) {
        Matcher m = IMAP.matcher(raw == null ? "" : raw.strip());
        if (!m.matches()) {
            return null;
        }
        try {
            return new MailRef(null, UUID.fromString(m.group(1)), "A".equals(m.group(2)), Long.parseLong(m.group(3)),
                    Long.parseLong(m.group(4)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
