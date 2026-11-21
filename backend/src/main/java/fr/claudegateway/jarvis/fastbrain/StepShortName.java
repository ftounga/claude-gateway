package fr.claudegateway.jarvis.fastbrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>Le nom court d'une étape</b> (F-271 / SF-271-05) — l'étoile d'une étape dans la constellation du chantier porte un
 * nom, jamais une phrase : « Créer le topic SNS d'ingestion » ⇒ « SNS », « Appliquer le Terraform du compte » ⇒
 * « Terraform », « Test en dev » ⇒ « Test dev », « Ouvrir la MR » ⇒ « MR ».
 */
public final class StepShortName {

    static final int MAX = 14;

    private static final Set<String> STOP = Set.of("le", "la", "les", "l", "de", "du", "des", "d", "un", "une", "en",
            "et", "a", "à", "au", "aux", "sur", "pour", "dans", "avec", "par", "son", "sa", "ses", "ce", "cet", "cette",
            "the", "of", "to", "in", "on", "for", "and", "puis", "si", "qui", "que", "via");

    private StepShortName() {
    }

    /** Le nom court, ou « étape N » si le titre est vide. */
    public static String of(String title, int number) {
        String text = title == null ? "" : title.strip();
        List<String> words = new ArrayList<>();
        for (String w : text.split("[^\\p{L}\\p{N}-]+")) {
            String clean = w.replaceAll("^-+|-+$", "");
            if (!clean.isEmpty()) {
                words.add(clean);
            }
        }
        if (words.isEmpty()) {
            return "étape " + number;
        }
        // 1. Un sigle technique (SNS, IAM, MR, DNS) — jamais une clé de ticket ni un nombre.
        for (String w : words) {
            if (w.matches("[A-Z][A-Z0-9]{1,5}") && !w.matches("\\d+")) {
                return w;
            }
        }
        // 2. Le verbe de tête est écarté (« Créer », « Appliquer ») ; les mots vides aussi.
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            String lower = w.toLowerCase(Locale.ROOT);
            if (i == 0 && words.size() > 1 && isVerb(lower)) {
                continue;
            }
            if (STOP.contains(lower) || lower.matches("\\d+")) {
                continue;
            }
            rest.add(w);
        }
        if (rest.isEmpty()) {
            rest.add(words.get(0));
        }
        // 3. Un nom propre ou technique (majuscule hors tête) suffit seul : « Terraform », « Kafka ».
        for (int i = 0; i < rest.size(); i++) {
            String w = rest.get(i);
            if (Character.isUpperCase(w.charAt(0)) && !(i == 0 && w.equals(words.get(0)))) {
                return clip(w);
            }
        }
        String name = rest.size() == 1 ? rest.get(0) : rest.get(0) + " " + rest.get(1);
        if (name.length() > MAX) {
            name = rest.get(0);
        }
        return clip(Character.toUpperCase(name.charAt(0)) + name.substring(1));
    }

    private static boolean isVerb(String w) {
        return w.length() > 3 && (w.endsWith("er") || w.endsWith("ir") || w.endsWith("re") || w.endsWith("oir"));
    }

    private static String clip(String s) {
        return s.length() <= MAX ? s : s.substring(0, MAX - 1) + "…";
    }
}
