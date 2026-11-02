package fr.claudegateway.jarvis.fastbrain;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * <b>La garde de la relecture</b> (F-239 / SF-239-05, D3) — déterministe. Le cerveau rapide relit la parole transcrite
 * (ligne {@code compris:}) comme la dictée de Claude Code : noms propres, mots mal entendus, ponctuation. Il ne doit
 * <b>jamais inventer de demande</b>. La relecture est refusée (la parole transcrite est gardée) si :
 * <ul>
 *   <li>elle est vide ;</li>
 *   <li>elle s'écarte trop de la parole : distance d'édition par mot &gt; {@value #MAX_DISTANCE_RATIO} × le nombre de
 *       mots de la parole (au moins 2 mots tolérés) ;</li>
 *   <li>elle ajoute un verbe d'action absent de la parole (liste fermée de racines).</li>
 * </ul>
 */
public final class JarvisUnderstanding {

    static final double MAX_DISTANCE_RATIO = 0.4;
    static final int MAX_CHARS = 1_000;

    /** Racines (normalisées) des verbes qui engagent : jamais ajoutées par une relecture. */
    static final List<String> ACTIONS = List.of("envo", "lanc", "supprim", "cre", "publi", "pouss", "valid", "ferm",
            "relanc", "effac", "deploi", "deploy", "fusion", "merg", "annul", "pai", "pay", "vir", "achet", "command",
            "reserv", "signe", "sign", "transfer", "partag", "archiv", "redemarr", "arret", "install", "desinstall",
            "repond", "ecri", "modifi", "chang", "renomm", "deplac", "commit", "push", "planifi");

    private JarvisUnderstanding() {
    }

    /** La relecture si elle est sûre, sinon {@code null} (garder la parole transcrite). */
    public static String accept(String said, String understood) {
        if (understood == null || said == null) {
            return null;
        }
        String clean = understood.strip();
        if (clean.isEmpty() || clean.length() > MAX_CHARS) {
            return null;
        }
        String[] raw = words(said);
        String[] read = words(clean);
        if (read.length == 0) {
            return null;
        }
        int limit = Math.max(2, (int) Math.floor(raw.length * MAX_DISTANCE_RATIO));
        if (distance(raw, read) > limit) {
            return null;
        }
        for (String w : read) {
            if (isAction(w) && Arrays.stream(raw).noneMatch(r -> stem(r).equals(stem(w)))) {
                return null;
            }
        }
        return clean;
    }

    private static boolean isAction(String word) {
        return ACTIONS.stream().anyMatch(word::startsWith);
    }

    /** Une racine grossière : les 5 premières lettres (« envoie », « envoyer », « envoyez » se confondent). */
    private static String stem(String word) {
        return word.length() <= 5 ? word : word.substring(0, 5);
    }

    static String[] words(String text) {
        String n = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9]+", " ").strip();
        return n.isEmpty() ? new String[0] : n.split(" ");
    }

    /** Distance d'édition par mot (Levenshtein). */
    static int distance(String[] a, String[] b) {
        int[] previous = new int[b.length + 1];
        int[] current = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length; i++) {
            current[0] = i;
            for (int j = 1; j <= b.length; j++) {
                int cost = a[i - 1].equals(b[j - 1]) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length];
    }
}
