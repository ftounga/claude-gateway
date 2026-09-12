package fr.claudegateway.terminals;

/**
 * <b>Ce qu'un terminal vivant est en train de faire</b> (F-76 / SF-76-01).
 *
 * <p>Quatre valeurs, et pas un texte libre. Ce n'est pas de l'avarice : l'écran doit pouvoir
 * <b>trier</b> (ce qui attend passe devant), <b>signaler franchement</b> (la tuile en attente
 * d'autorisation porte une pastille écrite) et <b>compter</b> (« 1 terminal attend votre
 * autorisation »). Aucune de ces trois règles ne se tient sur une phrase libre.</p>
 *
 * <p>Le <b>détail</b>, lui, est libre et borné : c'est lui qui dit « npm test ». Le vocabulaire dit
 * la <i>nature</i> de l'attente, le détail dit <i>quoi</i>.</p>
 *
 * <p><b>Une valeur inconnue est lue comme {@link #IDLE}</b> plutôt que refusée : un écran déployé
 * avant la gateway enverrait sinon un aperçu entier au rebut, et l'aperçu est du décor — il ne doit
 * jamais faire échouer le battement de cœur qui, lui, tient la place.</p>
 */
public enum TerminalActivity {

    /** Rien ne tourne : le terminal est ouvert et attend une demande. */
    IDLE,

    /** Un tour est lancé, l'agent réfléchit — aucune commande n'est encore en cours. */
    THINKING,

    /** Une commande tourne. Le détail la nomme (« npm test »). */
    RUNNING,

    /**
     * <b>Le terminal attend une autorisation.</b> C'est le seul état que l'utilisateur <b>doit</b>
     * voir : le 2026-09-08, une demande d'autorisation est restée douze heures sans réponse parce
     * que rien, hors de l'onglet concerné, ne la disait (F-47).
     */
    AWAITING_APPROVAL;

    /**
     * Lit une étiquette reçue de l'écran. Jamais d'exception : une valeur vide, nulle ou inconnue
     * vaut {@link #IDLE}.
     */
    public static TerminalActivity parse(String value) {
        if (value == null || value.isBlank()) {
            return IDLE;
        }
        for (TerminalActivity activity : values()) {
            if (activity.name().equalsIgnoreCase(value.trim())) {
                return activity;
            }
        }
        return IDLE;
    }

    /** Vrai pour le seul état qui réclame une décision de l'utilisateur. */
    public boolean awaitsApproval() {
        return this == AWAITING_APPROVAL;
    }
}
