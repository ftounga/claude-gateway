package fr.claudegateway.videos;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>Une ligne de la fiche des sources</b> d'une vidéo (F-215 / SF-215-04) : une affirmation technique de la
 * narration et d'où elle vient (fait de carte, page, fichier du dépôt, compte rendu) — ou, faute de source,
 * marquée « à confirmer ». La fiche est affichée sous le lecteur ; elle est rangée par version.
 *
 * @param affirmation ce que la vidéo affirme
 * @param source      d'où vient l'affirmation, ou {@code null} si elle est à confirmer
 * @param aConfirmer  vrai si l'affirmation n'est pas vérifiée
 */
public record VideoSource(String affirmation, String source, @JsonProperty("aConfirmer") boolean aConfirmer) {

    /** Nombre maximal de lignes d'une fiche. */
    public static final int MAX_SOURCES = 80;

    /** Longueur maximale d'une affirmation ou d'une source. */
    public static final int MAX_CHARS = 300;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Lit et valide la fiche d'un appel d'outil ({@code [{affirmation, source?, a_confirmer?}]}) : liste vide si
     * absente.
     *
     * @throws VideoRejectedException si la fiche est mal formée ou dépasse une borne (message pour l'agent)
     */
    public static List<VideoSource> parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new VideoRejectedException("sources doit être une liste de {affirmation, source, a_confirmer}.");
        }
        if (node.size() > MAX_SOURCES) {
            throw new VideoRejectedException("Fiche des sources trop longue : " + MAX_SOURCES + " lignes au plus.");
        }
        List<VideoSource> sources = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                throw new VideoRejectedException("Chaque ligne de sources est un objet {affirmation, source, a_confirmer}.");
            }
            String affirmation = clean(item.get("affirmation"));
            if (affirmation.isEmpty()) {
                throw new VideoRejectedException("Chaque ligne de sources a une affirmation.");
            }
            String source = clean(item.get("source"));
            boolean toConfirm = item.path("a_confirmer").asBoolean(false);
            if (affirmation.length() > MAX_CHARS || source.length() > MAX_CHARS) {
                throw new VideoRejectedException("Affirmation ou source trop longue : " + MAX_CHARS
                        + " caractères au plus.");
            }
            if (source.isEmpty() && !toConfirm) {
                throw new VideoRejectedException("« " + affirmation + " » n'a pas de source : donne-la (fait de "
                        + "carte, page, fichier:ligne, compte rendu), ou marque-la a_confirmer: true, ou retire-la "
                        + "de la narration.");
            }
            sources.add(new VideoSource(affirmation, source.isEmpty() ? null : source, toConfirm));
        }
        return List.copyOf(sources);
    }

    /** La fiche en JSON, ou {@code null} si elle est vide. */
    public static String toJson(List<VideoSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(sources);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Fiche des sources non sérialisable", e);
        }
    }

    /** La fiche relue du JSON rangé ; liste vide si absente ou illisible. */
    public static List<VideoSource> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(MAPPER.readValue(json, new TypeReference<List<VideoSource>>() { }));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** Le nombre de lignes « à confirmer ». */
    public static long toConfirmCount(List<VideoSource> sources) {
        return sources == null ? 0 : sources.stream().filter(VideoSource::aConfirmer).count();
    }

    private static String clean(JsonNode value) {
        return value == null || value.isNull() ? "" : value.asText("").replaceAll("\\s+", " ").strip();
    }
}
