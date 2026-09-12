package fr.claudegateway.runner.teams;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>La déclaration de portée</b> (F-88 / SF-88-01, décision <b>D1</b> du cadrage).
 *
 * <p>Avant le premier traitement des paroles de tiers d'une conversation ou d'une réunion, le volet
 * <b>nomme ce qui sera lu et où cela ira</b>. C'est la doctrine de la déclaration de portée du runner
 * (F-57), transposée.</p>
 *
 * <p><b>Une fois</b>, et pas à chaque tour : une annonce répétée cesse d'être lue, et c'est
 * exactement ce que D1 refuse. La mémoire est celle de la liaison — elle repart avec elle.</p>
 */
final class TeamsScopeNotice {

    private final Set<String> announced = ConcurrentHashMap.newKeySet();

    /**
     * La déclaration pour ce fil ou cette réunion, ou {@code ""} si elle a déjà été faite.
     *
     * @param subject identifiant du fil ou de la réunion — la portée est déclarée par sujet
     * @param what    ce qui sera lu, en clair (« les messages de ce fil », « la transcription »)
     * @param label   le nom lisible du sujet, s'il est connu
     */
    String announceOnce(String subject, String what, String label) {
        String key = subject == null || subject.isBlank() ? "(fil affiché)" : subject.strip();
        if (!announced.add(key)) {
            return "";
        }
        String name = label == null || label.isBlank() ? key : label.strip();
        return "Portée de cette lecture : je vais lire " + what + " de « " + name + " », sur la "
                + "période demandée. Ce qui en sort — le texte des messages et leurs auteurs — "
                + "remonte dans cette conversation pour y produire un compte rendu, et vit avec "
                + "lui : il est supprimé avec lui. Rien n'est mis en cache, et ni vos cookies ni "
                + "vos jetons Microsoft ne quittent cette machine.";
    }

    /** Remet le compteur à zéro. Utilisé quand la liaison est refaite. */
    void forget() {
        announced.clear();
    }
}
