package fr.claudegateway.jarvis.mail;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * <b>Le « oui » vérifié côté serveur</b> (F-242 / SF-242-06, D9 c) — la même liste fermée que l'écran
 * ({@code jarvis-confirm.ts}, F-231 / SF-231-04) : seul un « oui » net vaut accord, « non » vaut refus, tout le reste
 * vaut <b>rien</b>. Le serveur l'applique à la <b>parole de l'utilisateur</b> (son message, jamais un résultat d'outil
 * ni un texte du modèle).
 */
public final class MailConfirmation {

    /** Ce que vaut la réponse. */
    public enum Answer {
        OUI, NON, DOUTE
    }

    private static final Pattern NEGATIONS = Pattern.compile("\\b(non|nan|pas|attends|attend|mais|sauf|jamais|annule|stop|arrete)\\b");
    private static final Pattern YES = Pattern.compile("^(oui|ouais|oui oui|oui vas y|oui c est bon|oui je confirme|"
            + "oui confirme|oui merci|oui allez|je confirme|confirme|c est confirme)$");
    private static final Pattern NO = Pattern.compile("^(non|nan|non merci|non non|surtout pas|pas question|"
            + "n y touche pas|ne fais rien|annule|je refuse|refuse)$");

    private MailConfirmation() {
    }

    public static Answer classify(String said) {
        String text = normalize(said == null ? "" : said).replaceFirst("^jarvis\\s+", "").strip();
        if (text.isEmpty()) {
            return Answer.DOUTE;
        }
        if (NO.matcher(text).matches()) {
            return Answer.NON;
        }
        if (YES.matcher(text).matches() && !NEGATIONS.matcher(text).find()) {
            return Answer.OUI;
        }
        return Answer.DOUTE;
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").strip();
    }
}
