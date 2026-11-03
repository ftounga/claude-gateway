package fr.claudegateway.jarvis.mail;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>Les dossiers d'une boîte</b> (F-242 / SF-242-01, D3) — trouvés par les attributs <b>SPECIAL-USE</b> (RFC 6154) :
 * {@code \Drafts}, {@code \Trash}, {@code \All}, {@code \Sent}. Les noms sont localisés chez Google
 * (« [Gmail]/Corbeille ») et ne sont <b>jamais</b> écrits en dur.
 *
 * <p>Repli : si le serveur n'annonce <b>aucun</b> attribut SPECIAL-USE (serveur de test, fournisseur ancien), les noms
 * anglais usuels sont reconnus. Gmail annonce toujours ses attributs : le repli n'y joue jamais.</p>
 *
 * @param drafts dossier des brouillons, ou {@code null}
 * @param trash  corbeille, ou {@code null}
 * @param all    « Tous les messages » (archive Gmail), ou {@code null}
 * @param sent   messages envoyés, ou {@code null}
 */
public record MailFolders(String drafts, String trash, String all, String sent) {

    /** Un dossier tel que listé par le serveur : son nom complet et ses attributs ({@code \Trash}, …). */
    public record FolderInfo(String fullName, List<String> attributes) {
    }

    public static final MailFolders NONE = new MailFolders(null, null, null, null);

    private static final Set<String> SPECIAL = Set.of("\\drafts", "\\trash", "\\all", "\\sent", "\\junk",
            "\\flagged", "\\archive", "\\important");

    /** Découverte : attributs d'abord ; repli par nom seulement si aucun attribut SPECIAL-USE n'existe. */
    public static MailFolders discover(List<FolderInfo> folders) {
        if (folders == null || folders.isEmpty()) {
            return NONE;
        }
        boolean announced = folders.stream().anyMatch(f -> f.attributes() != null
                && f.attributes().stream().anyMatch(a -> a != null && SPECIAL.contains(a.toLowerCase(Locale.ROOT))));
        if (announced) {
            return new MailFolders(byAttribute(folders, "\\Drafts"), byAttribute(folders, "\\Trash"),
                    firstNonNull(byAttribute(folders, "\\All"), byAttribute(folders, "\\Archive")),
                    byAttribute(folders, "\\Sent"));
        }
        return new MailFolders(byName(folders, "Drafts"), byName(folders, "Trash", "Deleted Items", "Deleted"),
                byName(folders, "All Mail", "Archive"), byName(folders, "Sent", "Sent Items", "Sent Mail"));
    }

    private static String byAttribute(List<FolderInfo> folders, String attribute) {
        for (FolderInfo f : folders) {
            if (f.attributes() != null && f.attributes().stream().anyMatch(attribute::equalsIgnoreCase)) {
                return f.fullName();
            }
        }
        return null;
    }

    private static String byName(List<FolderInfo> folders, String... names) {
        for (String name : names) {
            for (FolderInfo f : folders) {
                String full = f.fullName() == null ? "" : f.fullName();
                int slash = Math.max(full.lastIndexOf('/'), full.lastIndexOf('.'));
                String leaf = slash >= 0 ? full.substring(slash + 1) : full;
                if (leaf.equalsIgnoreCase(name)) {
                    return full;
                }
            }
        }
        return null;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
