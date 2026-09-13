package fr.claudegateway.runner.teams;

/**
 * <b>Aucun témoin ne peut être montré sur ce poste</b> (F-91 / SF-91-02).
 *
 * <p>Ce n'est pas une gêne d'affichage : c'est la disparition du <b>garde-fou n° 3</b>. Sans fenêtre
 * au premier plan, rien n'empêche plus la capture oubliée qui tourne trois heures — et c'est la
 * seule chose que ce garde-fou avait à empêcher. L'appelant en fait donc un <b>refus de
 * capturer</b>, pas un avertissement.</p>
 *
 * <p>Le cas réel est cohérent avec ce refus : un poste sans environnement graphique n'a pas d'écran
 * à capturer.</p>
 */
public class CaptureWitnessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String remedy;

    public CaptureWitnessException(String message, String remedy) {
        this(message, remedy, null);
    }

    public CaptureWitnessException(String message, String remedy, Throwable cause) {
        super(message, cause);
        this.remedy = remedy == null ? "" : remedy.strip();
    }

    public String remedy() {
        return remedy;
    }
}
