package fr.claudegateway.videos;

import java.util.List;
import java.util.UUID;

/**
 * <b>Le bloc « audio » du fil</b> (F-213 / SF-213-01) : des lecteurs compacts empilés, un libellé par piste
 * (« Voix 1 — grave, posée »). Sert aux auditions de voix et aux extraits sonores ; jamais de galerie.
 *
 * @param title  titre du lot, ou {@code null}
 * @param tracks pistes publiées par cet appel
 */
public record AudioBlock(String title, List<Track> tracks) {

    /**
     * Une piste.
     *
     * @param audioId identifiant (ligne {@code videos} de genre AUDIO)
     * @param label   libellé, ou le titre de la piste
     * @param version version publiée
     * @param mime    type servi
     */
    public record Track(UUID audioId, String label, int version, String mime) {
    }
}
