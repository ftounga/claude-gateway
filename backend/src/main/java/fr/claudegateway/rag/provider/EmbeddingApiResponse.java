package fr.claudegateway.rag.provider;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Réponse d'une API d'embeddings au format OpenAI-compatible. Seuls les champs nécessaires sont
 * mappés (les autres sont ignorés). Confiné au package {@code provider}.
 *
 * @param data liste des vecteurs, un par texte d'entrée
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingApiResponse(List<Item> data) {

    /**
     * @param index     position du texte dans la requête (pour réordonner)
     * @param embedding valeurs du vecteur
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(Integer index, List<Double> embedding) {
    }
}
