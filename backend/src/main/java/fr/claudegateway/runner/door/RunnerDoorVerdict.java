package fr.claudegateway.runner.door;

/**
 * <b>Ce que la porte décide</b> (F-161 / SF-161-01), avant qu'un seul jeton ne soit dépensé.
 *
 * @param open    vrai si le tour peut s'ouvrir
 * @param reason  pourquoi il ne peut pas, en une phrase qui dit <b>quoi faire</b> ; {@code null} si ouvert
 * @param code    code stable du refus, pour l'écran ; {@code null} si ouvert
 */
public record RunnerDoorVerdict(boolean open, String reason, String code) {

    /** Le poste ne répond plus. */
    public static final String OFFLINE = "runner_offline";
    /** Le runner répond mais ne sait pas faire ce que le tour demanderait. */
    public static final String MISSING_CAPABILITY = "runner_missing_capability";

    static RunnerDoorVerdict opened() {
        return new RunnerDoorVerdict(true, null, null);
    }

    static RunnerDoorVerdict closed(String code, String reason) {
        return new RunnerDoorVerdict(false, reason, code);
    }
}
