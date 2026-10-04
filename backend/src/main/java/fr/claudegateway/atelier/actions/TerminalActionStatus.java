package fr.claudegateway.atelier.actions;

import java.util.List;

/**
 * L'état d'une attente du terminal (F-175 / SF-175-01, évolution de F-154).
 *
 * <p>Trois états sur le chemin — {@link #A_FAIRE} → {@link #DEMANDE} → {@link #FAIT} — et une sortie
 * latérale, {@link #ANNULE}. L'état « demandé » manquait à F-154 : une demande partie sans réponse
 * restait « à faire », puis passait « faite », et l'agent recréait « obtenir la réponse ».</p>
 */
public enum TerminalActionStatus {

    /** À faire : personne n'a encore rien demandé. */
    A_FAIRE,

    /** Demandé : la demande est partie (à qui, par où, quand) — on attend la réponse. */
    DEMANDE,

    /** Fait — l'utilisateur a rapporté la réponse, ou l'a cochée lui-même. */
    FAIT,

    /** Annulé par l'utilisateur : elle n'avait pas lieu d'être. Sa parole prime. */
    ANNULE;

    /** Les états ouverts, pour les requêtes. */
    public static final List<TerminalActionStatus> OPEN_STATES = List.of(A_FAIRE, DEMANDE);

    /** Vrai tant que l'attente attend encore quelque chose. */
    public boolean isOpen() {
        return this == A_FAIRE || this == DEMANDE;
    }
}
