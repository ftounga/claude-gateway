package fr.claudegateway.push;

/**
 * <b>Le catalogue des événements notifiés</b> (F-185 / SF-185-02) : un type par chose qui vous
 * attend, avec un titre et un corps <b>neutres</b> (D1 de F-185, D4 de F-153) — aucun nom de sujet,
 * aucun contenu de tour ne quitte l'application. Le détail se lit après ouverture authentifiée.
 *
 * <p>Le code ({@link #name()}) voyage dans la charge ({@code data.event}) : la présence (SF-185-03),
 * le centre de notifications (SF-185-04) et les préférences (SF-185-06) s'en servent.</p>
 */
public enum PushEvent {

    /** Le tour s'est terminé normalement : une réponse vous attend. */
    TURN_DONE("Une réponse est prête", "Votre tâche est terminée."),

    /** Le tour est suspendu sur une autorisation ; le silence vaut refus. */
    AUTHORIZATION_REQUESTED("Une autorisation est demandée",
            "Ouvrez l'application pour autoriser ou refuser."),

    /** L'agent pose une question structurée (outil {@code demander}). */
    QUESTION_ASKED("Une question vous attend", "Ouvrez l'application pour répondre."),

    /**
     * Une question attend toujours, deux minutes avant son échéance (SF-185-05) : le dernier filet
     * avant une décision prise par défaut. Toujours émis, même terminal regardé.
     */
    QUESTION_REMINDER("Une question attend toujours",
            "Sans réponse dans 2 minutes, l'agent décidera par défaut.", true),

    /** L'agent a soumis son plan ({@code exit_plan_mode}) : rien n'avance sans votre accord. */
    PLAN_AWAITING("Un plan attend votre accord", "Ouvrez l'application pour l'approuver ou le corriger."),

    /** Le tour se termine sur une décision à confirmer : passation, gouvernance, attente à fermer. */
    VALIDATION_AWAITING("Une validation vous attend", "L'agent vous propose une décision à confirmer."),

    /** Une question ou une autorisation est restée sans réponse : l'agent a tranché par défaut. */
    CONTINUED_WITHOUT_YOU("L'agent a continué sans vous",
            "Une demande est restée sans réponse dans le délai."),

    /** Le tour s'est arrêté sans aboutir : plafond atteint, erreur. */
    WORK_STOPPED("Le travail s'est arrêté", "Ouvrez l'application pour voir pourquoi."),

    /** Le poste ne répond plus : le tour a été arrêté. */
    MACHINE_LOST("Votre poste ne répond plus",
            "Le travail a été arrêté : vérifiez que le poste est allumé et connecté.");

    private final String title;
    private final String body;
    private final boolean alwaysDelivered;

    PushEvent(String title, String body) {
        this(title, body, false);
    }

    PushEvent(String title, String body, boolean alwaysDelivered) {
        this.title = title;
        this.body = body;
        this.alwaysDelivered = alwaysDelivered;
    }

    /**
     * <b>Critique</b> (D5 de F-185) : ce qui coûte une décision — autorisation, question, rappel. Jamais
     * mis en sourdine, jamais coupé par les heures calmes.
     */
    public boolean critical() {
        return this == AUTHORIZATION_REQUESTED || this == QUESTION_ASKED || this == QUESTION_REMINDER;
    }

    /** Émis même si le terminal est regardé (D7 ne s'applique pas). */
    public boolean alwaysDelivered() {
        return alwaysDelivered;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }
}
