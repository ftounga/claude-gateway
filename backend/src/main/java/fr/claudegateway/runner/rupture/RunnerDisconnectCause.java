package fr.claudegateway.runner.rupture;

/**
 * <b>Pourquoi le canal d'un runner s'est fermé</b> (F-161 / SF-161-03).
 *
 * <p>Ces cinq causes étaient <b>déjà distinguées par le code</b> — et jetées. Les nommer est tout
 * l'objet de cette subfeature : sans elles, « le runner a décroché » est un constat qui n'oriente
 * aucune correction.</p>
 */
public enum RunnerDisconnectCause {

    /** Le runner a raccroché lui-même ({@code POST /runner/disconnect}). Le cas sain. */
    ARRET_PROPRE,

    /** Le long-polling n'interroge plus : poste éteint, mis en veille, ou proxy qui coupe les POST. */
    INACTIVITE,

    /** Une reconnexion a remplacé le canal précédent — le poste est revenu, pas parti. */
    REMPLACE,

    /** La socket a été fermée par le pair ; son {@code CloseStatus} dit souvent par quoi. */
    SOCKET_FERMEE,

    /** La socket était encore ouverte mais le poste ne battait plus : la moitié morte d'une socket. */
    SOCKET_MUETTE
}
