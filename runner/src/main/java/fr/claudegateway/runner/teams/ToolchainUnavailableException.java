package fr.claudegateway.runner.teams;

/**
 * <b>L'outil local n'est pas là, et ne peut pas l'être</b> (F-90 / SF-90-01).
 *
 * <p>Elle porte toujours un <b>remède</b>, et c'est sa raison d'être. « ffmpeg est introuvable » ne
 * dit rien à quelqu'un qui veut un compte rendu de réunion ; « installez-le avec
 * {@code brew install ffmpeg}, puis redemandez » le débloque. C'est la même doctrine que la liaison
 * au navigateur (SF-87-02), qui rend la ligne de commande exacte à coller.</p>
 */
public class ToolchainUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String remedy;

    public ToolchainUnavailableException(String message, String remedy) {
        super(message);
        this.remedy = remedy == null ? "" : remedy.strip();
    }

    public ToolchainUnavailableException(String message, String remedy, Throwable cause) {
        super(message, cause);
        this.remedy = remedy == null ? "" : remedy.strip();
    }

    /** Ce que l'utilisateur peut faire. Jamais vide en pratique. */
    public String remedy() {
        return remedy;
    }

    /** La phrase complète : ce qui manque, et quoi faire. */
    public String sentence() {
        return remedy.isEmpty() ? getMessage() : getMessage() + " " + remedy;
    }
}
