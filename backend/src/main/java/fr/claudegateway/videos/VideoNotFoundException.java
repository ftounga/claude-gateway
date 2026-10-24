package fr.claudegateway.videos;

/** Vidéo, version ou série introuvable pour ce compte — indiscernable d'une absence (F-213). */
public class VideoNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VideoNotFoundException() {
        super("Vidéo introuvable.");
    }
}
