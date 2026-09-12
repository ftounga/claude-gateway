package fr.claudegateway.runner.teams;

/**
 * Les <b>trois</b> issues de la sonde de santé (F-87, cadrage §3.2). Trois, pas deux : « on
 * travaille » et « on refuse » laisseraient passer le cas dangereux — celui où l'on lit une partie
 * seulement et où le silence ferait croire à un compte rendu complet.
 */
public enum TeamsHealthVerdict {

    /** Tout est reconnu : on travaille. */
    FULL("Teams est lu normalement."),

    /** Une partie est reconnue : on travaille <b>et on le dit</b>, à chaque résultat. */
    PARTIAL("Teams a changé : une partie de ce qui est lu n'est plus reconnue."),

    /** Rien n'est reconnu : on refuse, en nommant ce qui a changé. */
    NONE("Teams a changé : le produit ne sait plus lire ses réponses.");

    private final String sentence;

    TeamsHealthVerdict(String sentence) {
        this.sentence = sentence;
    }

    /** La phrase écrite à l'utilisateur. Elle ne parle jamais de code ni de champ JSON. */
    public String sentence() {
        return sentence;
    }

    /** Vrai si l'on a le droit de produire un résultat — même en le qualifiant. */
    public boolean canWork() {
        return this != NONE;
    }
}
