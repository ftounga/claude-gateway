package fr.claudegateway.runner.teams;

/**
 * <b>Un refus de capturer</b> (F-91 / SF-91-01), qui porte toujours <b>pourquoi</b> et
 * <b>quoi faire</b>.
 *
 * <p>Un refus nu (« impossible ») laisse l'utilisateur devant une porte sans poignée, et c'est la
 * faute que ce volet a corrigée partout ailleurs. Ici elle coûterait plus cher qu'ailleurs : le
 * refus arrive <b>pendant une réunion</b>, quand la personne n'a ni le temps ni l'envie de
 * chercher.</p>
 *
 * <p>Le {@link #code()} sert au journal et aux tests ; ce que l'utilisateur lit, ce sont
 * {@link #getMessage()} et {@link #remedy()}.</p>
 */
public class CaptureRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** L'usage n'a pas été nommé, ou n'est pas reconnu. */
    public static final String NO_PURPOSE = "capture_purpose_missing";
    /** Une réunion à plusieurs, sans confirmation d'avoir prévenu. */
    public static final String NOT_CONFIRMED = "capture_not_confirmed";
    /** Pas de filigrane possible : <b>donc pas de capture</b>. */
    public static final String NO_WATERMARK = "capture_watermark_impossible";
    /** Aucun moyen de capturer l'écran ou le son sur ce système. */
    public static final String NO_DEVICE = "capture_device_unknown";
    /** Une capture tourne déjà. */
    public static final String ALREADY_RUNNING = "capture_already_running";
    /** {@code ffmpeg} n'est ni présent ni rapatriable. */
    public static final String NO_TOOL = "capture_tool_unavailable";
    /** La capture n'a pas démarré, ou est morte aussitôt. */
    public static final String NOT_STARTED = "capture_not_started";
    /** Aucune capture ne porte cet identifiant. */
    public static final String UNKNOWN = "capture_unknown";

    private final String code;
    private final String remedy;

    public CaptureRefusedException(String code, String message, String remedy) {
        this(code, message, remedy, null);
    }

    public CaptureRefusedException(String code, String message, String remedy, Throwable cause) {
        super(message, cause);
        this.code = code == null || code.isBlank() ? NOT_STARTED : code;
        this.remedy = remedy == null ? "" : remedy.strip();
    }

    public String code() {
        return code;
    }

    /** Ce qu'il y a à faire. Jamais vide en pratique — c'est la raison d'être de cette classe. */
    public String remedy() {
        return remedy;
    }

    /** Le refus en une phrase lisible : la raison, puis le remède. */
    public String sentence() {
        return remedy.isEmpty() ? getMessage()
                : getMessage() + System.lineSeparator() + remedy;
    }
}
