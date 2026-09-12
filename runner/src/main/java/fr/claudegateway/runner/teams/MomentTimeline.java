package fr.claudegateway.runner.teams;

import java.time.Duration;
import java.time.Instant;

/**
 * <b>L'origine du temps de la vidéo</b> (F-90 / SF-90-02), et la conversion entre les deux échelles
 * qu'il faut rapprocher.
 *
 * <h2>Pourquoi c'est une classe et pas une soustraction</h2>
 *
 * <p>Les images portent un <b>décalage en secondes depuis le début de la vidéo</b> (le
 * {@code pts_time} d'{@code ffmpeg}). La transcription porte des <b>instants absolus</b>. Sans
 * savoir à quel instant commence la vidéo, <b>tout l'alignement est faux — et faux silencieusement</b>.
 * C'est exactement ce que ce volet interdit : un compte rendu plausible et faux est pire qu'un
 * compte rendu qui refuse, parce qu'on décide dessus.</p>
 *
 * <p>D'où {@link #unknown()} : une frise <b>sans origine</b> ne calcule rien et se dit telle. Elle
 * n'invente pas de valeur de repli, parce qu'une valeur de repli produirait des horodatages qui ont
 * l'air justes.</p>
 *
 * <h2>Le décalage est dit</h2>
 *
 * <p>Un enregistrement Teams ne commence pas toujours à la seconde où la réunion commence. Un
 * décalage est donc admis — et <b>écrit dans le résultat</b> : le compte rendu porte toujours
 * l'hypothèse sur laquelle il a été bâti, pour que l'écart se voie au lieu de se cacher le jour du
 * premier branchement.</p>
 */
public record MomentTimeline(Instant videoStartedAt, String source) {

    public MomentTimeline {
        source = source == null ? "" : source.strip();
    }

    /** Aucune origine connue : rien ne peut être aligné, et cela se dit. */
    public static MomentTimeline unknown() {
        return new MomentTimeline(null, "");
    }

    /** L'origine donnée explicitement dans la demande. */
    public static MomentTimeline given(Instant videoStartedAt) {
        return videoStartedAt == null ? unknown()
                : new MomentTimeline(videoStartedAt, "l'instant que vous m'avez donné");
    }

    /** L'origine déduite du début de réunion observé au registre. */
    public static MomentTimeline fromMeeting(TeamsMeeting meeting) {
        return meeting == null || meeting.startedAt() == null ? unknown()
                : new MomentTimeline(meeting.startedAt(),
                        "le début de la réunion « " + meeting.subject() + " » observé dans Teams");
    }

    /** La même origine, décalée — un enregistrement démarré après le début de la réunion. */
    public MomentTimeline shiftedBy(double offsetSeconds) {
        if (videoStartedAt == null || offsetSeconds == 0d) {
            return this;
        }
        return new MomentTimeline(
                videoStartedAt.plusMillis(Math.round(offsetSeconds * 1000d)),
                source + ", décalé de " + describeSeconds(offsetSeconds));
    }

    public boolean isKnown() {
        return videoStartedAt != null;
    }

    /** L'instant absolu d'un décalage vidéo. */
    public Instant at(double offsetSeconds) {
        return require().plusMillis(Math.round(offsetSeconds * 1000d));
    }

    /** Le décalage vidéo d'un instant absolu ; négatif quand il précède le début de la vidéo. */
    public double offsetOf(Instant instant) {
        return Duration.between(require(), instant).toMillis() / 1000d;
    }

    /**
     * L'hypothèse sur laquelle le compte rendu est bâti, en toutes lettres. Elle voyage <b>avec</b>
     * le résultat : ce que le produit affirme doit être vérifiable.
     */
    public String describe() {
        return isKnown()
                ? "La vidéo commence à " + videoStartedAt + " — origine prise sur " + source + "."
                : "Je ne sais pas à quel instant commence cette vidéo.";
    }

    private Instant require() {
        if (videoStartedAt == null) {
            throw new IllegalStateException("origine du temps inconnue");
        }
        return videoStartedAt;
    }

    private static String describeSeconds(double seconds) {
        long total = Math.abs(Math.round(seconds));
        String sign = seconds < 0 ? "-" : "+";
        return sign + String.format("%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
    }
}
