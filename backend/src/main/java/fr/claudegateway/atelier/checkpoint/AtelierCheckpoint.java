package fr.claudegateway.atelier.checkpoint;

/**
 * Un contrôle branché sur un point d'accroche de la boucle maison (F-50 / SF-50-01).
 *
 * <p><b>C'est un composant du serveur, jamais du code reçu de l'extérieur.</b> Une implémentation
 * est une classe de ce dépôt, déclarée comme bean Spring et découverte au démarrage. Rien, dans ce
 * mécanisme, ne charge de script, ne lance de processus, ni n'évalue une source fournie par un
 * utilisateur — c'est une limite de périmètre explicite de F-50, et elle écarte d'emblée la classe
 * de failles qu'un système de crochets ouvert introduirait. F-51 décidera quels contrôles s'activent
 * pour qui ; il ne permettra pas d'en apporter de nouveaux.</p>
 *
 * <p><b>Contrat d'implémentation</b> — trois règles :</p>
 * <ol>
 *   <li><b>Rapide et synchrone.</b> L'appel se fait dans le tour de l'utilisateur, entre deux appels
 *       au fournisseur : le temps passé ici est du temps d'attente à l'écran, et il est pris sur le
 *       budget de tour.</li>
 *   <li><b>Sans effet de bord.</b> Un contrôle juge, il n'écrit pas. Il ne modifie ni le projet, ni
 *       la conversation, ni l'état du tour.</li>
 *   <li><b>Le verdict porte le geste.</b> Voir {@link AtelierCheckpointVerdict} : bloquer sans dire
 *       quoi corriger fait tourner le modèle en rond.</li>
 * </ol>
 *
 * <p>Une implémentation qui lève une exception est <b>ignorée</b> ({@link AtelierCheckpointRunner}) :
 * un contrôle défaillant ne prend jamais en otage le tour d'un utilisateur.</p>
 */
public interface AtelierCheckpoint {

    /** Le point d'accroche auquel ce contrôle s'applique ; il n'en sert qu'un. */
    AtelierCheckpointKind kind();

    /**
     * Juge la situation décrite par le contexte.
     *
     * @return {@link AtelierCheckpointVerdict#proceed()} pour laisser passer, ou
     *         {@link AtelierCheckpointVerdict#block(String)} avec l'action corrective. Un
     *         {@code null} est traité comme « passe »
     */
    AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context);

    /**
     * Ce contrôle juge-t-il aussi un tour qui n'a <b>rien écrit</b> ? (F-121 / SF-121-17)
     *
     * <p><b>Le défaut est {@code false}, et c'est la norme.</b> Un contrôle de fin de tour juge le
     * <b>travail produit</b> : les fichiers écrits pendant le tour. L'interroger sur un tour qui n'a
     * fait que répondre à une question ne peut rien produire d'utile — et le renvoi au travail qui
     * s'ensuivrait est précisément l'écart de parité que SF-121-17 corrige (Claude Code ne relance
     * jamais un tour de pure réponse).</p>
     *
     * <p><b>Rendre {@code true} est une exception assumée</b>, réservée à un contrôle qui juge le
     * <b>tour lui-même</b> plutôt que ce qu'il a écrit : c'est le cas de la porte de complétude
     * (SF-121-05), qui compare l'état du plan et doit continuer de refuser la clôture d'un tour
     * d'investigation laissant des étapes en plan.</p>
     *
     * <p>Sans effet en mode Réponse/Plan : ce tour-là n'interroge <b>aucun</b> contrôle de fin de
     * tour, quelle que soit cette déclaration. Sans effet non plus sur les autres points
     * d'accroche ({@code AFTER_FILE_WRITE}, {@code BEFORE_COMMAND}), qui portent leur propre
     * contexte.</p>
     *
     * <p>Une implémentation qui lève ici est <b>ignorée</b>, comme partout ailleurs dans F-50 : le
     * contrôle est écarté du tour, il ne le casse pas.</p>
     */
    default boolean judgesTurnWithoutWrites() {
        return false;
    }
}
