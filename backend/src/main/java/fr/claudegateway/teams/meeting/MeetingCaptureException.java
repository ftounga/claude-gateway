package fr.claudegateway.teams.meeting;

/**
 * L'ordre de capture (rejoindre l'onglet dans le Chrome managé) n'a pas abouti — 409
 * (F-128 / SF-128-01). Le {@link #code()} distingue le poste injoignable du Chrome managé injoignable,
 * pour que l'écran guide vers la bonne action (« Rejoindre & capturer » dans la Vigie).
 */
public class MeetingCaptureException extends RuntimeException {

    /** Le runner (poste) n'est pas joignable. */
    public static final String RUNNER_UNAVAILABLE = "runner_unavailable";
    /** Le Chrome managé est injoignable / l'onglet Teams est perdu : rejoindre depuis la Vigie. */
    public static final String MANAGED_CHROME_UNREACHABLE = "managed_chrome_unreachable";

    private final String code;

    public MeetingCaptureException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
