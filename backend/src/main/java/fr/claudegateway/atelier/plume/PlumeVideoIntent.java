package fr.claudegateway.atelier.plume;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * <b>La demande vise-t-elle une vidéo ?</b> (F-215 / SF-215-05) — classement <b>déterministe</b> par mots-clés,
 * insensible à la casse et aux accents : gratuit, prévisible, testable (arbitrage A1). Une phrase classée vidéo
 * reçoit un cahier de réalisation ; toute autre garde l'enrichissement F-193.
 */
public final class PlumeVideoIntent {

    /**
     * Les marques d'une demande de vidéo, sur le texte sans accents : vidéo(s), cours ou tutoriel animé, voix off,
     * motion design, screencast, série d'épisodes, épisode(s) d'un cours.
     */
    private static final Pattern VIDEO = Pattern.compile(
            "\\b(videos?|voix[ -]off|motion[ -]design|screencasts?|(cours|tutoriels?|explications?) animee?s?"
                    + "|series? d'episodes|episodes? (du|d'un|de la) (cours|tutoriel|serie))\\b");

    private PlumeVideoIntent() {
    }

    /** Vrai si la phrase demande une vidéo (cours, explication animée, tutoriel en épisodes…). */
    public static boolean isVideo(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('’', '\'').toLowerCase(Locale.ROOT);
        return VIDEO.matcher(plain).find();
    }
}
