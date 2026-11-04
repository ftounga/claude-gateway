package fr.claudegateway.jarvis.mail;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Une proposition de rendez-vous repérée au tri</b> (F-243 / SF-243-04, D7) — le champ facultatif {@code rdv} du même
 * appel du cerveau rapide (aucun appel de plus). C'est une <b>donnée non fiable</b> : bornée, sans balise ni lien. La date
 * n'est <b>pas</b> calculée par le modèle quand le courriel dit un jour de la semaine : la passerelle la résout par
 * rapport à la date du courriel (Europe/Paris).
 *
 * @param title     objet du rendez-vous (≤ 80)
 * @param weekday   jour de la semaine dit ({@code lundi}…{@code dimanche}) ou vide
 * @param date      date ISO dite explicitement ({@code AAAA-MM-JJ}) ou vide
 * @param hour      heure dite ({@code HH:MM}) ou vide
 * @param minutes   durée dite en minutes, 0 si inconnue
 * @param location  lieu (≤ 80) ou vide
 * @param certain   certitude haute (jour ET heure précis, sans alternative)
 */
public record MailRdv(String title, String weekday, String date, String hour, int minutes, String location,
        boolean certain) {

    static final int MAX_TEXT = 80;

    /** Lit le champ {@code rdv} d'un verdict ; {@code null} s'il est absent ou vide. */
    public static MailRdv parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        // SF-243-05 — défense en profondeur : aucune adresse électronique (un invité injecté) dans le texte dit.
        String title = cleanText(node.path("titre").asText(""));
        String weekday = clean(node.path("jour_semaine").asText("")).toLowerCase(Locale.ROOT);
        String date = node.path("date").asText("").strip();
        String hour = node.path("heure").asText("").strip();
        int minutes = node.path("duree_minutes").canConvertToInt() ? Math.max(0, node.path("duree_minutes").asInt()) : 0;
        String location = cleanText(node.path("lieu").asText(""));
        boolean certain = "haute".equalsIgnoreCase(node.path("certitude").asText("").strip());
        if (title.isEmpty() && weekday.isEmpty() && date.isEmpty() && hour.isEmpty()) {
            return null;
        }
        return new MailRdv(title.isEmpty() ? "Rendez-vous" : title, weekday, date.matches("\\d{4}-\\d{2}-\\d{2}") ? date : "",
                hour.matches("\\d{1,2}:\\d{2}") ? hour : "", Math.min(minutes, 24 * 60), location, certain);
    }

    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern
            .compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}");

    /** Le titre ou le lieu d'un rendez-vous : nettoyé comme {@link #clean}, sans aucune adresse électronique. */
    static String cleanText(String raw) {
        return clean(raw == null ? "" : EMAIL.matcher(raw).replaceAll(" "));
    }

    public static String clean(String raw) {
        String s = raw == null ? "" : raw.replaceAll("<[^>]*>", " ").replaceAll("https?://\\S+", " ")
                .replaceAll("[\\p{Cntrl}\"`<>]", " ").replaceAll("\\s+", " ").strip();
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT).strip();
    }
}
