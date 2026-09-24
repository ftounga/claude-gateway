package fr.claudegateway.atelier.actions;

/**
 * L'état d'une action du terminal (F-151 / SF-151-01). Trois états, et trois seulement.
 *
 * <p>Pas d'état « en cours » : une action que l'utilisateur a commencée reste {@link #OPEN} tant
 * qu'elle n'a pas abouti. Un état de plus n'apporterait rien à l'œil et une transition de plus au
 * code.</p>
 */
public enum TerminalActionStatus {

    /** À faire. Le seul état qui apparaît dans le menu du terminal. */
    OPEN,

    /** Faite — l'utilisateur a rapporté la réponse, ou l'a cochée lui-même. */
    DONE,

    /** Annulée par l'utilisateur : elle n'avait pas lieu d'être. Sa parole prime. */
    CANCELLED
}
