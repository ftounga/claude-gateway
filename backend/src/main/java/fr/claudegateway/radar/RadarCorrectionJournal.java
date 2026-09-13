package fr.claudegateway.radar;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.radar.dto.RadarViews.CorrectionView;

/**
 * L'écriture et la lecture du journal des corrections (F-99 / SF-99-02, partagé avec SF-99-03) :
 * valeurs avant / après en JSON, et la vue REST d'une ligne.
 */
@Component
public class RadarCorrectionJournal {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private final RadarCorrectionRepository corrections;
    private final ObjectMapper objectMapper;

    public RadarCorrectionJournal(RadarCorrectionRepository corrections, ObjectMapper objectMapper) {
        this.corrections = corrections;
        this.objectMapper = objectMapper;
    }

    /** Écrit une ligne du journal. */
    public RadarCorrection record(RadarScope scope, UUID subjectId, RadarCorrectionAction.Target kind,
            UUID targetId, RadarCorrectionAction action, Map<String, Object> before, Map<String, Object> after) {
        return corrections.save(RadarCorrection.builder()
                .userId(scope.userId()).hostId(scope.hostId()).subjectId(subjectId)
                .targetKind(kind).targetId(targetId).action(action)
                .beforeValues(write(before)).afterValues(write(after))
                .createdAt(OffsetDateTime.now()).build());
    }

    /** Les valeurs d'une ligne, dans l'ordre où elles ont été écrites. */
    public Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, MAP);
        } catch (Exception e) {
            throw new IllegalStateException("Journal des corrections illisible", e);
        }
    }

    /** La vue REST d'une ligne. */
    public CorrectionView view(RadarCorrection c) {
        return new CorrectionView(c.getId(), c.getSubjectId(), c.getTargetKind(), c.getTargetId(), c.getAction(),
                tree(c.getBeforeValues()), tree(c.getAfterValues()), c.getCreatedAt(), c.getUndoneAt());
    }

    private String write(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("Journal des corrections illisible", e);
        }
    }

    private JsonNode tree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
