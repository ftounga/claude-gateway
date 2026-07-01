package fr.claudegateway.rag.store;

import java.util.UUID;

/**
 * Résultat élémentaire d'une recherche vectorielle (F-07) : l'identifiant d'un chunk et sa distance
 * au vecteur requête (plus la distance est faible, plus le chunk est pertinent). Neutre vis-à-vis du
 * moteur de stockage — le domaine ne manipule jamais le vecteur brut ni le SQL pgvector.
 *
 * @param chunkId  identifiant du chunk ({@code chunks.id})
 * @param distance distance au vecteur requête (métrique L2, cohérente avec l'index ivfflat de 011)
 */
public record ScoredChunk(UUID chunkId, double distance) {
}
