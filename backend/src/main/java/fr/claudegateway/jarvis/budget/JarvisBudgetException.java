package fr.claudegateway.jarvis.budget;

/**
 * <b>Le budget de Jarvis arrête une délégation AVANT qu'elle ne parte</b> (F-244 / SF-244-03) : soit il faut un « oui »
 * (plafond atteint, {@link #CONFIRM}), soit le plafond dur est atteint ({@link #HARD}). Jamais levée pendant un tour :
 * un tour en cours n'est jamais coupé par le budget. Le message est la phrase fixe que Jarvis dit.
 */
public class JarvisBudgetException extends RuntimeException {

    /** Plafond atteint : la délégation attend un « oui ». */
    public static final String CONFIRM = "jarvis_budget_confirm";
    /** Plafond dur (2 ×) : aucune délégation. */
    public static final String HARD = "jarvis_budget_hard";

    private final String code;

    public JarvisBudgetException(String code, String phrase) {
        super(phrase);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
