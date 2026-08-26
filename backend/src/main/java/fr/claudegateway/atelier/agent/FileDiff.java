package fr.claudegateway.atelier.agent;

/**
 * Modification d'un fichier constatée à la resynchronisation d'un tour d'exécution (F-37 / SF-37-01).
 *
 * <p>Produit à l'instant — le seul — où l'ancienne et la nouvelle version d'un fichier coexistent :
 * juste avant la réécriture du workspace. C'est une donnée d'<b>affichage</b>, relayée sur
 * l'événement de fin de run et conservée avec le tour, jamais requêtée.</p>
 *
 * <p>Un fichier réécrit <b>à l'identique</b> ne produit aucune entrée : une session persistante
 * réexpose ses sorties à chaque tour, et l'annoncer comme modifié serait faux.</p>
 *
 * @param path         chemin relatif au workspace, tel que résolu par le remap de sortie
 * @param added        vrai si le fichier n'existait pas avant ce tour (ajout intégral)
 * @param diff         diff unifié (lignes {@code @@}, {@code ' '}, {@code '-'}, {@code '+'}), séparé
 *                     par des sauts de ligne ; vide quand {@code unreadable}
 * @param addedLines   nombre de lignes {@code +} <b>effectivement présentes</b> dans {@code diff}
 * @param removedLines nombre de lignes {@code -} <b>effectivement présentes</b> dans {@code diff}
 * @param omittedLines lignes de diff écartées par la borne par fichier ; {@code 0} si le diff est
 *                     complet. Mentionné à l'écran, jamais passé sous silence — un diff tronqué sans
 *                     le dire laisserait croire que rien d'autre n'a changé
 * @param unreadable   contenu non textuel : aucune comparaison n'a été tentée. Le fichier est
 *                     malgré tout écrit et listé dans les fichiers modifiés
 */
public record FileDiff(String path, boolean added, String diff, int addedLines, int removedLines,
        int omittedLines, boolean unreadable) {

    /** Entrée d'un fichier dont le contenu n'est pas comparable (binaire ou non décodable). */
    public static FileDiff unreadable(String path, boolean added) {
        return new FileDiff(path, added, "", 0, 0, 0, true);
    }
}
