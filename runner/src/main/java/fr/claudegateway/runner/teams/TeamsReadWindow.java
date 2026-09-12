package fr.claudegateway.runner.teams;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * La fenêtre <b>demandée</b> et la fenêtre <b>réellement lue</b> (F-87 / SF-87-01, décision D4).
 *
 * <p>Le plafond existe parce que c'est le défilement qui décide du temps passé et du risque de
 * casse. Il est <b>annoncé</b>, <b>négociable dans la demande</b> — « remonte jusqu'au 1er
 * septembre » doit marcher —, et <b>jamais silencieux</b> : un résultat porte toujours la fenêtre
 * qu'il couvre vraiment, pas celle qu'on espérait.</p>
 *
 * @param requestedFrom              début demandé, ou {@code null} (« aussi loin que le plafond »)
 * @param requestedTo                fin demandée, ou {@code null} (« jusqu'à maintenant »)
 * @param actualFrom                 plus ancien élément réellement lu, ou {@code null} si rien
 * @param actualTo                   plus récent élément réellement lu, ou {@code null} si rien
 * @param cap                        plafond d'éléments appliqué à cette lecture
 * @param capReached                 vrai si la lecture s'est arrêtée sur le plafond
 * @param reachedStartOfConversation vrai si on a atteint le début de la conversation
 */
public record TeamsReadWindow(Instant requestedFrom, Instant requestedTo, Instant actualFrom,
        Instant actualTo, int cap, boolean capReached, boolean reachedStartOfConversation) {

    /** Plafond de temps par défaut, annoncé à l'utilisateur : une semaine (D4). */
    public static final int DEFAULT_DAYS = 7;

    /** Plafond de volume par défaut, annoncé à l'utilisateur : 500 messages (D4). */
    public static final int DEFAULT_MAX_MESSAGES = 500;

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH);

    public TeamsReadWindow {
        cap = cap <= 0 ? DEFAULT_MAX_MESSAGES : cap;
    }

    /** Fenêtre par défaut : une semaine en arrière, 500 éléments, rien encore lu. */
    public static TeamsReadWindow standard(Instant now) {
        Instant from = now == null ? null : now.minus(Duration.ofDays(DEFAULT_DAYS));
        return new TeamsReadWindow(from, now, null, null, DEFAULT_MAX_MESSAGES, false, false);
    }

    /** La même fenêtre, une fois la lecture faite : ce qu'on a vraiment couvert. */
    public TeamsReadWindow covering(Instant oldest, Instant newest, boolean capReached,
            boolean reachedStart) {
        return new TeamsReadWindow(requestedFrom, requestedTo, oldest, newest, cap, capReached,
                reachedStart);
    }

    /**
     * Vrai si la fenêtre demandée a été <b>entièrement</b> couverte. Faux dès que le plafond a mordu
     * ou que la lecture s'arrête après le début demandé sans avoir atteint le début de la
     * conversation — et c'est alors un fait à écrire, pas à taire.
     */
    public boolean fullyCovered() {
        if (capReached) {
            return false;
        }
        if (requestedFrom == null) {
            return reachedStartOfConversation;
        }
        return reachedStartOfConversation
                || (actualFrom != null && !actualFrom.isAfter(requestedFrom));
    }

    /** « du 5 au 12 septembre » — ce que la lecture couvre réellement, en français. */
    public String describe() {
        if (actualFrom == null || actualTo == null) {
            return "aucune période lue";
        }
        ZoneId zone = ZoneId.systemDefault();
        String start = DAY.format(actualFrom.atZone(zone));
        String end = DAY.format(actualTo.atZone(zone));
        return start.equals(end) ? "le " + start : "du " + start + " au " + end;
    }
}
