package fr.claudegateway.atelier.checkpoint;

/**
 * Les deux points d'accroche de la boucle maison (F-50).
 *
 * <p>Ils sont <b>fermés</b> : la liste est celle du cadrage, et rien ne permet d'en déclarer un
 * troisième depuis l'extérieur. Un crochet est un endroit de la boucle où le produit accepte qu'une
 * décision lui soit imposée — ce n'est pas une extension ouverte.</p>
 */
public enum AtelierCheckpointKind {

    /**
     * Après chaque écriture de fichier <b>aboutie</b> ({@code write_file} / {@code edit_file}).
     *
     * <p>Bloquer ici transforme le résultat d'outil en <b>erreur</b> portant l'action corrective :
     * le fichier est écrit, et le modèle est tenu d'y revenir avant de continuer.</p>
     */
    AFTER_FILE_WRITE,

    /**
     * En fin de tour, quand le modèle rend sa réponse finale sans demander d'outil (F-50 / SF-50-02).
     *
     * <p>Bloquer ici <b>empêche le tour de se terminer</b> : la correction est déposée comme message
     * utilisateur et la boucle repart.</p>
     */
    END_OF_TURN,

    /**
     * <b>Avant</b> l'exécution d'une commande {@code bash}, et avant toute émission vers la machine
     * de l'utilisateur (F-52 / SF-52-01).
     *
     * <p>F-50 avait écarté {@code bash}, au motif qu'il a « déjà sa porte (SF-38-08) et son
     * journal ». Le motif ne tient pas pour une vérification <b>mécanique</b> : la porte de
     * confirmation demande une autorisation et n'inspecte <b>rien</b> du contenu de la commande, et
     * SF-38-20 l'a rendue débrayable par projet. Elle ne peut donc pas porter un verrou
     * déterministe — d'où ce troisième point.</p>
     *
     * <p>Bloquer ici <b>empêche la commande de partir</b> : rien n'est émis, le {@code tool_result}
     * rendu au modèle est une erreur portant l'action corrective, et l'appel est journalisé comme
     * refusé avant émission.</p>
     */
    BEFORE_COMMAND
}
