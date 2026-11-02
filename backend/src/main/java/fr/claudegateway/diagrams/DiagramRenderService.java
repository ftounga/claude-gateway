package fr.claudegateway.diagrams;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.journey.SubjectJourneyRepository;
import fr.claudegateway.atelier.storage.WorkspaceStorage;

/**
 * <b>Conserver les schémas</b> (F-237 / SF-237-03) : après un rendu <b>réussi</b> de {@code render_diagram}, la
 * description reçue et les fichiers rendus sont gardés côté passerelle (S3
 * {@code diagrams/{u}/{ws}/{id}.svg|png|drawio}) pour que Jarvis puisse les montrer. L'outil et sa description ne
 * changent pas (préfixe de cache intact).
 *
 * <p><b>Au mieux :</b> une conservation en échec ne fait jamais échouer le rendu — elle est journalisée (sans contenu)
 * et le schéma, déjà déposé dans le projet, reste livré.</p>
 */
@Service
public class DiagramRenderService {

    private static final Logger LOG = LoggerFactory.getLogger(DiagramRenderService.class);

    static final String PREFIX = "diagrams/";
    /** Plafond de la description conservée (une spec raisonnable fait quelques Ko). */
    static final int MAX_SOURCE_CHARS = 200_000;
    static final int MAX_TITLE = 200;

    /** Les fichiers rendus d'un schéma — chacun peut manquer. */
    public record Files(byte[] svg, byte[] png, byte[] drawio) {
    }

    private final DiagramRenderRepository repository;
    private final WorkspaceStorage storage;
    private final SubjectJourneyRepository journeys;
    private final Clock clock;

    public DiagramRenderService(DiagramRenderRepository repository, WorkspaceStorage storage,
            SubjectJourneyRepository journeys, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.journeys = journeys;
        this.clock = clock;
    }

    /** Le préfixe de stockage des schémas d'un sujet — effacé avec lui. */
    public static String prefixOf(UUID userId, UUID workspaceId) {
        return PREFIX + userId + "/" + workspaceId + "/";
    }

    /**
     * Conserve un schéma rendu. Rend son identifiant, ou vide si la conservation a échoué (jamais d'exception).
     *
     * @param engine   {@code mermaid}, {@code cloud} ou {@code drawio}
     * @param spec     la description reçue (cloud, drawio), {@code null} pour Mermaid
     * @param code     le code Mermaid, {@code null} sinon
     * @param fileName le nom proposé, pour le titre de repli
     */
    public Optional<UUID> record(UUID userId, Workspace workspace, String callId, String engine, JsonNode spec,
            String code, String fileName, Files files) {
        try {
            UUID id = UUID.randomUUID();
            String prefix = prefixOf(userId, workspace.getId());
            String svgKey = put(prefix + id + ".svg", files.svg(), "image/svg+xml");
            String pngKey = put(prefix + id + ".png", files.png(), "image/png");
            String drawioKey = put(prefix + id + ".drawio", files.drawio(), "application/xml");
            Integer chantier = journeys.findByUserIdAndWorkspaceId(userId, workspace.getId())
                    .map(j -> j.getChantierNumber()).orElse(null);
            repository.save(DiagramRender.builder().id(id).userId(userId).workspaceId(workspace.getId())
                    .callId(clip(callId, 120)).chantierNumber(chantier)
                    .engine(engine == null || engine.isBlank() ? "mermaid" : engine.toLowerCase(java.util.Locale.ROOT))
                    .title(clip(titleOf(spec, code, fileName), MAX_TITLE))
                    .specJson(spec == null || spec.isNull() || spec.isMissingNode() ? null
                            : clip(spec.toString(), MAX_SOURCE_CHARS))
                    .code(clip(code == null || code.isBlank() ? null : code, MAX_SOURCE_CHARS))
                    .svgKey(svgKey).pngKey(pngKey).drawioKey(drawioKey)
                    .createdAt(OffsetDateTime.now(clock)).build());
            return Optional.of(id);
        } catch (RuntimeException e) {
            LOG.warn("Schéma non conservé (sujet {}) : {}", workspace.getId(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Un schéma du compte. */
    @Transactional(readOnly = true)
    public Optional<DiagramRender> find(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId);
    }

    /** Les schémas d'un sujet du compte, du plus récent au plus ancien (≤ 50). */
    @Transactional(readOnly = true)
    public List<DiagramRender> list(UUID userId, UUID workspaceId) {
        return repository.findTop50ByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId);
    }

    /** Les octets d'un fichier conservé (clé lue de la ligne du compte, jamais venue du client). */
    public Optional<byte[]> bytes(String key) {
        return key == null ? Optional.empty() : storage.getFile(key);
    }

    /** Le titre : celui de la spec, sinon la première ligne du code, sinon le nom proposé. */
    static String titleOf(JsonNode spec, String code, String fileName) {
        if (spec != null && spec.path("title").isTextual() && !spec.path("title").asText().isBlank()) {
            return spec.path("title").asText().strip();
        }
        if (code != null && !code.isBlank()) {
            String first = code.strip().lines().findFirst().orElse("").strip();
            if (!first.isBlank()) {
                return first;
            }
        }
        return fileName == null || fileName.isBlank() ? "Schéma" : fileName;
    }

    private String put(String key, byte[] content, String type) {
        if (content == null || content.length == 0) {
            return null;
        }
        storage.putFile(key, content, type);
        return key;
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
