package fr.claudegateway.runner.teams;

/**
 * Les façons dont une lecture peut être <b>incomplète</b> (F-87 / SF-87-01).
 *
 * <p>Liste close, et volontairement explicite : « un trou se voit, un trou silencieux ne se voit
 * jamais ». Chaque valeur doit pouvoir être <b>écrite en français</b> à l'utilisateur — c'est à quoi
 * sert {@link #label()}.</p>
 */
public enum TeamsGapKind {

    /** Une réponse de Teams dont la forme n'est plus reconnue du tout. */
    UNRECOGNIZED_PAYLOAD("réponse de Teams non reconnue"),

    /** Un genre de message que l'adaptateur ne sait pas cartographier. */
    UNKNOWN_MESSAGE_KIND("genre de message inconnu"),

    /** Un champ obligatoire absent : l'objet n'est pas rendu. */
    MISSING_FIELD("champ obligatoire absent"),

    /** La pagination s'est arrêtée avant d'avoir tout lu. */
    PAGINATION_STOPPED("pagination interrompue"),

    /** Le défilement n'a plus rien fait venir alors que la fenêtre n'était pas couverte. */
    SCROLL_EXHAUSTED("défilement sans effet"),

    /** Le corps de la réponse n'a pas pu être récupéré auprès du navigateur. */
    BODY_UNAVAILABLE("corps de réponse indisponible"),

    /** Le plafond annoncé a été atteint : ce qui précède n'a pas été lu (D4). */
    CAP_REACHED("plafond de remontée atteint");

    private final String label;

    TeamsGapKind(String label) {
        this.label = label;
    }

    /** Libellé français, écrit tel quel dans ce que rend un outil. */
    public String label() {
        return label;
    }
}
