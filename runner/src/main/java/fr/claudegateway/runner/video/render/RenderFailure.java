package fr.claudegateway.runner.video.render;

/**
 * <b>Un échec nommé de la chaîne de rendu</b> (F-215 / SF-215-02) : ce qui manque <b>et quoi faire</b>. Jamais
 * un échec nu — sur un poste verrouillé, l'utilisateur doit savoir à qui demander quoi.
 */
public final class RenderFailure extends RuntimeException {

    private final String remedy;

    public RenderFailure(String message, String remedy) {
        super(message);
        this.remedy = remedy == null ? "" : remedy.strip();
    }

    public String remedy() {
        return remedy;
    }
}
