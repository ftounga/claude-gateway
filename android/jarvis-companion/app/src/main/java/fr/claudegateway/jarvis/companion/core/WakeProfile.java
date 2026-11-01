package fr.claudegateway.jarvis.companion.core;

import java.util.Locale;

/**
 * Les gabarits appris et le seuil d'acceptation, gardés <b>sur l'appareil seulement</b> (préférences
 * privées de l'application). Codage texte compact, sans dépendance JSON pour rester pur Java :
 * {@code seuil|gabarit;gabarit} où un gabarit est {@code trame/trame} et une trame
 * {@code v,v,…} (trois décimales, comme SF-231-05).
 */
public final class WakeProfile {

    // Classe et non record : rester sur le Java le plus sûr pour le desugaring Android (minSdk 26).
    private final double[][][] templates;
    private final double threshold;

    public WakeProfile(double[][][] templates, double threshold) {
        this.templates = templates;
        this.threshold = threshold;
    }

    public double[][][] templates() {
        return templates;
    }

    public double threshold() {
        return threshold;
    }

    /** Construit le profil à partir des répétitions apprises (seuil calculé). */
    public static WakeProfile learn(double[][][] templates) {
        return new WakeProfile(templates, WakeEngine.enrollThreshold(templates));
    }

    public String encode() {
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "%.4f", threshold)).append('|');
        for (int t = 0; t < templates.length; t++) {
            if (t > 0) {
                out.append(';');
            }
            for (int f = 0; f < templates[t].length; f++) {
                if (f > 0) {
                    out.append('/');
                }
                for (int c = 0; c < templates[t][f].length; c++) {
                    if (c > 0) {
                        out.append(',');
                    }
                    out.append(String.format(Locale.ROOT, "%.3f", templates[t][f][c]));
                }
            }
        }
        return out.toString();
    }

    /** Relit un profil ; {@code null} si le texte est absent ou abîmé (on réapprendra). */
    public static WakeProfile decode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            int bar = raw.indexOf('|');
            double threshold = Double.parseDouble(raw.substring(0, bar));
            String[] parts = raw.substring(bar + 1).split(";");
            double[][][] templates = new double[parts.length][][];
            for (int t = 0; t < parts.length; t++) {
                String[] frames = parts[t].split("/");
                templates[t] = new double[frames.length][];
                for (int f = 0; f < frames.length; f++) {
                    String[] values = frames[f].split(",");
                    templates[t][f] = new double[values.length];
                    for (int c = 0; c < values.length; c++) {
                        templates[t][f][c] = Double.parseDouble(values[c]);
                    }
                }
            }
            if (templates.length == 0 || !(threshold > 0)) {
                return null;
            }
            return new WakeProfile(templates, threshold);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
