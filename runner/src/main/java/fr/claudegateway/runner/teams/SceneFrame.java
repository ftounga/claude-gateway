package fr.claudegateway.runner.teams;

import java.nio.file.Path;

/**
 * <b>Une image retenue</b> d'un enregistrement de réunion (F-90 / SF-90-01).
 *
 * <p>{@link #offsetSeconds} vient du {@code pts_time} du filtre {@code showinfo} d'{@code ffmpeg},
 * <b>jamais</b> du numéro de fichier. La distinction n'est pas cosmétique : le numéro de fichier ne
 * dit que l'ordre, et F-90 ne vaut que par l'alignement — <i>c'est l'alignement qui fait la valeur,
 * pas l'extraction</i>. Une image dont on ne connaîtrait que le rang ne pourrait être rapprochée
 * d'aucune phrase.</p>
 *
 * <p>{@link #file} est un chemin <b>sur la machine de l'utilisateur</b>, et il y reste : seule
 * l'image retenue remonte, et seulement à l'étape de remontée (SF-90-03).</p>
 *
 * @param offsetSeconds décalage depuis le début de la vidéo, en secondes
 * @param file          fichier local de l'image
 * @param fingerprint   empreinte perceptuelle 64 bits, ou {@code 0} si elle n'a pas pu être calculée
 */
public record SceneFrame(double offsetSeconds, Path file, long fingerprint) {

    public SceneFrame {
        offsetSeconds = Math.max(0d, offsetSeconds);
    }

    /** La même image, avec son empreinte. */
    public SceneFrame withFingerprint(long value) {
        return new SceneFrame(offsetSeconds, file, value);
    }

    /** L'heure telle qu'on l'écrit dans un compte rendu : {@code 14:32:05} de vidéo. */
    public String describeOffset() {
        long total = (long) offsetSeconds;
        return String.format("%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
    }
}
