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
            "Le travail a été arrêté : vérifiez que le poste est allumé et connecté."),

    /** Récapitulatif quotidien (SF-185-07) : des attentes « Demandé » sont à relancer (F-175). */
    ATTENTES_TO_FOLLOW_UP("Des attentes sont à relancer", "Ouvrez la Forge pour les voir."),

    /**
     * Le point du matin (F-180 / SF-180-06) : une échéance est atteinte ou un accès du registre arrive à
     * terme (avec ou sans relance due). Corps neutre : aucun nom d'accès ni de sujet ne quitte l'application.
     */
    ATTENTES_POINT_DU_MATIN("Votre point du matin sur les attentes",
            "Une échéance ou un accès arrive à terme : ouvrez la Forge."),

    /**
     * Une routine du sujet a tourné et rendu compte (F-183 / SF-183-04). Émise même terminal regardé : une
     * routine n'est pas un tour que l'on suit des yeux.
     */
    ROUTINE_DONE("Une routine a rendu compte", "Ouvrez le sujet pour lire son compte rendu.", true),

    /** Une routine s'est arrêtée sans aboutir : budget atteint, échec, interruption (F-183 / SF-183-04). */
    ROUTINE_STOPPED("Une routine s'est arrêtée", "Ouvrez le sujet pour voir pourquoi.", true),

    /** Une routine n'a pas pu tourner : le poste était hors ligne (F-183 / SF-183-04). */
    ROUTINE_MISSED("Une routine n'a pas pu tourner",
            "Le poste était hors ligne : elle repartira à sa reconnexion.", true),

    /** Un chantier lancé en autonome est allé jusqu'au bout : la clôture est proposée (F-219 / SF-219-02). */
    AUTONOMY_DONE("Un chantier autonome est terminé", "Ouvrez le sujet pour le relire et le clore.", true),

    /**
     * Un chantier lancé en autonome s'est arrêté avant la fin : blocage, échec, plafond de tours (F-219 /
     * SF-219-02). Il attend un geste.
     */
    AUTONOMY_STOPPED("Un chantier autonome s'est arrêté", "Ouvrez le sujet pour voir ce qui le bloque.", true),

    /**
     * Récapitulatif quotidien (F-220 / SF-220-05) : des chantiers ouverts dorment (aucun geste depuis quelques
     * jours, non reportés). Le moins prioritaire du récapitulatif : une seule notification par jour.
     */
    CHANTIERS_DORMANT("Des chantiers dorment", "Ouvrez la Forge pour retrouver ce qui reste à faire.");

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

    /**
     * <b>Attend une action</b> (F-217 / SF-217-01, D1) : les critiques, plus le plan à approuver et la
     * validation à confirmer. Leur notification reste affichée jusqu'au geste ({@code requireInteraction})
     * et part en urgence haute.
     */
    public boolean requiresAction() {
        return critical() || this == PLAN_AWAITING || this == VALIDATION_AWAITING || this == AUTONOMY_STOPPED;
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
