package fr.claudegateway.videos;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>Un chapitre</b> d'une vidéo (F-213 / SF-213-01) : un instant ({@code t}, en secondes) et un titre.
 * Sous le lecteur, chaque chapitre est cliquable.
 *
 * @param t     début, en secondes (≥ 0)
 * @param titre titre court
 */
public record VideoChapter(int t, String titre) {

    /** Nombre maximal de chapitres. */
    public static final int MAX_CHAPTERS = 60;

    /** Longueur maximale d'un titre de chapitre. */
    public static final int MAX_TITLE_CHARS = 120;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Lit et valide les chapitres d'un appel d'outil : liste vide si absents.
     *
     * @throws VideoRejectedException si la liste est mal formée, trop longue ou non croissante
     */
    public static List<VideoChapter> parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new VideoRejectedException("chapitres doit être une liste de {t, titre}.");
        }
        if (node.size() > MAX_CHAPTERS) {
            throw new VideoRejectedException("Trop de chapitres : " + MAX_CHAPTERS + " au plus.");
        }
        List<VideoChapter> chapters = new ArrayList<>();
        int previous = -1;
        for (JsonNode item : node) {
            JsonNode rawT = item.get("t");
            if (rawT == null || !rawT.isNumber() || rawT.asDouble() < 0) {
                throw new VideoRejectedException("Chaque chapitre a un t en secondes (nombre positif ou nul).");
            }
            int t = (int) Math.floor(rawT.asDouble());
            String titre = item.hasNonNull("titre") ? item.get("titre").asText("").replaceAll("\\s+", " ").strip() : "";
            if (titre.isEmpty()) {
                throw new VideoRejectedException("Chaque chapitre a un titre.");
            }
            if (titre.length() > MAX_TITLE_CHARS) {
                throw new VideoRejectedException("Titre de chapitre trop long : " + MAX_TITLE_CHARS + " caractères au plus.");
            }
            if (t <= previous) {
                throw new VideoRejectedException("Les chapitres vont dans l'ordre, chacun après le précédent (t croissant).");
            }
            previous = t;
            chapters.add(new VideoChapter(t, titre));
        }
        return List.copyOf(chapters);
    }

    /** Les chapitres en JSON, ou {@code null} s'il n'y en a pas. */
    public static String toJson(List<VideoChapter> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(chapters);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Chapitres non sérialisables", e);
        }
    }

    /** Les chapitres relus du JSON rangé ; liste vide si absent ou illisible. */
    public static List<VideoChapter> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(MAPPER.readValue(json, new TypeReference<List<VideoChapter>>() { }));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
