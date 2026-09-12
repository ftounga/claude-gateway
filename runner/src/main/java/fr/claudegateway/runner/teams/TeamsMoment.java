package fr.claudegateway.runner.teams;

import java.nio.file.Path;
import java.time.Instant;

/**
 * <b>Un moment</b> (F-90 / SF-90-02) : l'image de ce qui était à l'écran, à côté de la phrase
 * prononcée <b>pendant qu'elle l'était</b>.
 *
 * <p>C'est l'objet qui porte toute la valeur de F-90. Les images seules ne valent rien — une galerie
 * en bas de page, personne ne la regarde. Ce qu'on vient chercher, c'est <i>« à 14 h 32, il présente
 * le planning de migration [capture] et annonce le décalage au T3 »</i>, et personne ne le fait à la
 * main parce que c'est fastidieux.</p>
 *
 * <p><b>{@link #quote} n'est jamais vide</b>, et ce n'est pas une commodité : un moment sans
 * citation n'est pas un moment, c'est une image — et le bloc moment de F-89 le refuserait à
 * l'émission. Un moment est construit <b>uniquement</b> quand une parole a été prononcée pendant que
 * cette image était affichée.</p>
 *
 * <p><b>{@link #image} est un chemin local</b>, et il le reste ici : la remontée est le travail de
 * SF-90-03. Les octets ne quittent la machine qu'à ce moment-là, et seulement eux.</p>
 *
 * @param at             instant <b>absolu</b> de la phrase — c'est ce que le bloc de F-89 affiche
 * @param offsetSeconds  décalage de l'image dans la vidéo, pour ouvrir l'enregistrement au bon endroit
 * @param quote          ce qui a été dit ; jamais vide
 * @param speaker        qui parlait, ou {@code ""}
 * @param image          fichier local de l'image en vigueur, ou {@code null} s'il n'y en a pas
 * @param otherCues      nombre d'autres répliques prononcées pendant que cette image était affichée
 */
public record TeamsMoment(Instant at, double offsetSeconds, String quote, String speaker,
        Path image, int otherCues) {

    public TeamsMoment {
        quote = quote == null ? "" : quote.strip();
        speaker = speaker == null ? "" : speaker.strip();
        offsetSeconds = Math.max(0d, offsetSeconds);
        otherCues = Math.max(0, otherCues);
    }

    /** L'endroit de la vidéo, tel qu'on l'écrit : {@code 00:14:32}. */
    public String describeOffset() {
        long total = (long) offsetSeconds;
        return String.format("%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
    }

    /**
     * La phrase du moment, telle qu'on la lit — <b>et le nombre d'autres répliques du même règne</b>.
     *
     * <p>Elles sont <b>comptées, jamais concaténées</b> : une citation recomposée n'est plus
     * citable, et la borne de longueur du bloc la ferait tronquer — or <i>une phrase tronquée peut
     * dire le contraire de la phrase</i>.</p>
     */
    public String describe() {
        StringBuilder text = new StringBuilder(describeOffset()).append(" — ");
        if (!speaker.isEmpty()) {
            text.append(speaker).append(" : ");
        }
        text.append('«').append(' ').append(quote).append(' ').append('»');
        if (otherCues > 0) {
            text.append(" (").append(otherCues)
                    .append(otherCues > 1 ? " autres répliques" : " autre réplique")
                    .append(" pendant que cette image était affichée)");
        }
        return text.toString();
    }
}
