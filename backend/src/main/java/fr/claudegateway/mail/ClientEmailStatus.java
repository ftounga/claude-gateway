package fr.claudegateway.mail;

/** L'état d'un courriel du client (F-110 / SF-110-02). */
public enum ClientEmailStatus {
    /** En file, en attente de sa prochaine tentative. */
    PENDING,
    /** Pris par un travailleur, sous bail. */
    SENDING,
    /** Accepté par le relais — tout ce que SMTP permet de savoir. */
    SENT,
    /** Refusé définitivement, ou échecs passagers épuisés. */
    FAILED;

    /** Vrai si l'envoi est terminé. */
    public boolean isFinal() {
        return this == SENT || this == FAILED;
    }
}
