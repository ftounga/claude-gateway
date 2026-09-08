package fr.claudegateway.runner;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Ce que le runner dit quand la <b>reprise</b> n'aboutit pas (F-46 / SF-46-01).
 *
 * <p>Un refus de reprise a une propriété que n'a pas un refus d'usage : l'utilisateur a déjà réussi
 * une fois. Le message doit donc nommer <b>ce qui manque</b> et <b>le geste</b> qui répare, jamais
 * se contenter de redire la syntaxe. Et surtout : il ne redemande pas silencieusement un code
 * d'appairage — un jeton expiré reste expiré, le dire est le service rendu (D3).</p>
 */
final class ResumeMessages {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm");

    private ResumeMessages() {
    }

    /**
     * Rien de mémorisé : les deux gestes possibles, dans l'ordre où on les tente. Ajouté au message
     * d'usage existant plutôt que substitué — la syntaxe reste utile à qui n'a jamais appairé.
     */
    static String noMemoryHint() {
        return System.lineSeparator() + System.lineSeparator()
                + "Aucune configuration mémorisée n'a été trouvée à partir de ce dossier."
                + System.lineSeparator()
                + "Sur une machine déjà appairée : relancez le runner depuis le dossier du projet, "
                + "sans aucun argument." + System.lineSeparator()
                + "Sinon : reprenez la commande complète dans l'application "
                + "(Atelier > Connecter une machine).";
    }

    /** Mémoire trouvée, mais la racine qu'elle désigne n'existe plus et rien ne la remplace. */
    static String rootGone(SessionMemory.Located located) {
        return "La configuration mémorisée (" + located.file() + ") désigne une racine introuvable : "
                + located.recordedRoot() + System.lineSeparator()
                + "Le projet a-t-il été déplacé ? Relancez depuis son nouveau dossier, "
                + "ou précisez --workspace <racine>.";
    }

    /**
     * Aucun jeton exploitable pour cette racine. Deux causes, deux messages : <b>expiré</b> (on
     * connaît la date, on la donne) ou <b>absent</b> (jamais appairé ici, ou jeton effacé).
     */
    static String cannotResume(Path tokenFile, OffsetDateTime expiredAt) {
        if (expiredAt != null) {
            return "Le jeton mémorisé pour ce projet a expiré le " + DATE.format(expiredAt)
                    + " (" + tokenFile + ")." + System.lineSeparator()
                    + "Générez un nouveau code dans l'application, puis relancez avec "
                    + "--code <code-appairage>.";
        }
        return "Aucun jeton stocké pour cette racine (" + tokenFile
                + ") et aucun --code fourni : impossible de s'appairer." + System.lineSeparator()
                + "Générez un code dans l'application (Atelier > Connecter une machine), "
                + "puis relancez avec --code <code-appairage>.";
    }

    /** Ligne de console annonçant que la configuration vient de la mémoire, et d'où exactement. */
    static String resumedFrom(Path memoryFile) {
        return "Reprise   : configuration mémorisée (" + memoryFile + ")";
    }
}
