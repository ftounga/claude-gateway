package fr.claudegateway.runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Droits sous lesquels le runner s'exécute (F-38 / SF-38-18).
 *
 * <p>Le runner agit avec les droits de l'utilisateur qui l'a lancé — <b>rien de plus, rien de
 * moins</b>. Il ne bride aucun privilège, et ne prétend pas le faire. Ce que cette classe fournit,
 * c'est l'information : sous quel compte on tourne, et si ce compte est celui de l'administrateur.</p>
 *
 * <p><b>À ne pas confondre avec une garde.</b> Un {@code sudo} interactif échoue sous le runner
 * parce que {@code BashTool} ferme l'entrée standard du processus : il n'y a pas de terminal pour
 * saisir un mot de passe. C'est un <b>effet de bord</b>, pas une protection — avec {@code NOPASSWD},
 * ou en {@code root}, la commande passe. Ce qui protège réellement, c'est la porte de confirmation :
 * aucune commande ne part sans un geste de l'utilisateur.</p>
 *
 * <p>La détection ne lève jamais : une information sur les droits ne doit pas coûter la connexion.</p>
 */
public final class Privileges {

    /** Fichier Linux portant l'uid réel du processus — exact, sans processus externe. */
    private static final Path SELF_STATUS = Path.of("/proc/self/status");

    private final String userName;
    private final boolean elevated;

    private Privileges(String userName, boolean elevated) {
        this.userName = userName;
        this.elevated = elevated;
    }

    /** Nom du compte sous lequel le runner tourne, tel que la JVM le rapporte. */
    public String userName() {
        return userName;
    }

    /** Vrai si ce compte est celui de l'administrateur (uid 0, ou nom {@code root} en repli). */
    public boolean elevated() {
        return elevated;
    }

    /** Détecte les droits courants. Ne lève jamais : en cas de doute, « non élevé ». */
    public static Privileges detect() {
        String user = System.getProperty("user.name", "");
        return new Privileges(user, isElevated(user));
    }

    /** Détection sur un contenu de {@code /proc/self/status} donné — le point testable. */
    static boolean elevatedFrom(String statusContent, String userName) {
        Integer uid = realUid(statusContent);
        if (uid != null) {
            return uid == 0;
        }
        // Repli : la plateforme n'expose pas /proc (macOS, Windows). Le nom du compte est une
        // approximation, mais mieux vaut une approximation qu'un silence sur un poste où l'on est
        // effectivement root.
        return "root".equals(userName);
    }

    private static boolean isElevated(String userName) {
        String content = null;
        try {
            if (Files.isReadable(SELF_STATUS)) {
                content = Files.readString(SELF_STATUS);
            }
        } catch (Exception ignored) {
            // Illisible : on retombe sur le nom du compte. Jamais d'échec de démarrage pour ça.
        }
        return elevatedFrom(content, userName);
    }

    /**
     * Uid <b>réel</b> lu dans {@code /proc/self/status} : la ligne {@code Uid:} porte quatre entiers
     * (réel, effectif, sauvegardé, système de fichiers) et c'est le premier qui dit sous quel compte
     * le processus a été lancé.
     *
     * @return l'uid, ou {@code null} si le contenu est absent ou malformé
     */
    private static Integer realUid(String statusContent) {
        if (statusContent == null || statusContent.isBlank()) {
            return null;
        }
        for (String line : List.of(statusContent.split("\n"))) {
            if (!line.startsWith("Uid:")) {
                continue;
            }
            String[] parts = line.substring(4).trim().split("\\s+");
            if (parts.length == 0 || parts[0].isBlank()) {
                return null;
            }
            try {
                return Integer.valueOf(parts[0]);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
