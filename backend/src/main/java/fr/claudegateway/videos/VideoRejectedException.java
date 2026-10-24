package fr.claudegateway.videos;

/** Une contrainte d'une vidéo n'est pas tenue (F-213) : le message dit quoi corriger. */
public class VideoRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VideoRejectedException(String message) {
        super(message);
    }
}
