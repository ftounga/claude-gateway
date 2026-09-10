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
}
