package fr.claudegateway.jarvis.state.capture;

import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.recall.RecallSemanticProperties;
import fr.claudegateway.rag.provider.EmbeddingProvider;

/**
 * <b>Retrouver ce qui a été dit</b> (F-247 / SF-247-13, porte Z10) : la recherche dans les lignes de captures,
 * <b>toujours sous {@code user_id}</b> et sous le client (ou le sujet pour {@code recall}), par index et avec une limite :
 * <ul>
 * <li><b>par le sens</b> (PostgreSQL et clé d'embeddings F-162 seulement) : plus proches voisins {@code <=>} ;</li>
 * <li><b>par les mots</b> (repli, toujours disponible) : dans une <b>fenêtre bornée</b> des lignes les plus récentes,
 * lue par l'index {@code (user_id, host_id, said_at desc)} — jamais un parcours de tout le registre ;</li>
 * <li><b>par la personne</b> : nom résolu par la passerelle.</li>
 * </ul>
 * Les lignes écrites sont vectorisées <b>hors du chemin</b>, dans le pool d'embeddings F-162 ; une ligne sans vecteur
 * reste trouvable par les mots.
 */
@Component
public class JarvisCaptureSearch {

    private static final Logger log = LoggerFactory.getLogger(JarvisCaptureSearch.class);
    /** La fenêtre de lignes récentes où cherchent les mots (bornée, par index). */
    static final int KEYWORD_WINDOW = 2_000;
    public static final int MAX_LINES = 15;

    private final JdbcTemplate jdbc;
    private final JarvisCaptureStore store;
    private final EmbeddingProvider embeddings;
    private final RecallSemanticProperties semantic;
    private final Executor executor;
    private final boolean postgres;

    public JarvisCaptureSearch(JdbcTemplate jdbc, JarvisCaptureStore store,
            @Qualifier("recallEmbeddingProvider") EmbeddingProvider embeddings, RecallSemanticProperties semantic,
            @Qualifier("atelierEmbeddingExecutor") Executor executor, DataSource dataSource) {
        this.jdbc = jdbc;
        this.store = store;
        this.embeddings = embeddings;
        this.semantic = semantic;
        this.executor = executor;
        this.postgres = isPostgres(dataSource);
        store.onLines(this::embedAsync);
    }

    /** Le sens est-il disponible (PostgreSQL + clé configurée) ? */
    public boolean semanticEnabled() {
        return postgres && semantic.isConfigured();
    }

    /** Vectorise des lignes écrites, dans le pool F-162 ; jamais d'exception, jamais d'attente. */
    void embedAsync(List<JarvisCaptureStore.StoredLine> lines) {
        if (!semanticEnabled() || lines == null || lines.isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    List<String> texts = lines.stream().map(l -> truncate(l.speaker() + " : " + l.text())).toList();
                    List<float[]> vectors = embeddings.embed(texts);
                    for (int i = 0; i < Math.min(vectors.size(), lines.size()); i++) {
                        jdbc.update("UPDATE jarvis_capture_lines SET embedding = CAST(? AS vector) WHERE id = ? "
                                + "AND user_id = ?", literal(vectors.get(i)), lines.get(i).id(), lines.get(i).userId());
                    }
                } catch (RuntimeException e) {
                    log.debug("Captures : vectorisation non faite ({})", e.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException e) {
            log.debug("Captures : vectorisation non planifiée ({})", e.getClass().getSimpleName());
        }
    }

    /**
     * Les lignes d'un client qui répondent à la recherche et/ou à la personne ; sans l'une ni l'autre, les plus
     * récentes. {@code speakers} : les noms retenus pour la personne (déjà résolus), vide sinon.
     */
    public List<JarvisCaptureStore.LineView> search(UUID userId, UUID hostId, String needle, List<String> speakers,
            int limit) {
        int n = Math.max(1, Math.min(limit, MAX_LINES));
        String q = needle == null ? "" : needle.strip();
        boolean bySpeaker = speakers != null && !speakers.isEmpty();
        if (q.isEmpty() && !bySpeaker) {
            return store.recentLines(userId, hostId, n);
        }
        if (!q.isEmpty() && !bySpeaker && semanticEnabled()) {
            List<UUID> ids = nearest("host_id", hostId, userId, q, n);
            if (!ids.isEmpty()) {
                return store.linesByIds(userId, hostId, ids);
            }
        }
        return keyword("host_id", hostId, userId, q, speakers, n);
    }

    /** {@code recall} : les lignes du <b>sujet</b> (union avec l'historique du fil). */
    public List<JarvisCaptureStore.LineView> searchSubject(UUID userId, UUID workspaceId, String needle, int limit) {
        int n = Math.max(1, Math.min(limit, MAX_LINES));
        String q = needle == null ? "" : needle.strip();
        if (q.isEmpty()) {
            return List.of();
        }
        if (semanticEnabled()) {
            List<UUID> ids = nearest("workspace_id", workspaceId, userId, q, n);
            if (!ids.isEmpty()) {
                List<JarvisCaptureStore.LineView> rows = jdbc.query(JarvisCaptureStore.LINE_SELECT
                        + " WHERE l.user_id = ? AND l.workspace_id = ? AND l.id IN (" + marks(ids.size()) + ")",
                        (rs, i) -> JarvisCaptureStore.line(rs), args(userId, workspaceId, ids));
                rows.sort(java.util.Comparator.comparingInt(r -> ids.indexOf(r.id())));
                return rows;
            }
        }
        return keyword("workspace_id", workspaceId, userId, q, List.of(), n);
    }

    private List<UUID> nearest(String scopeColumn, UUID scope, UUID userId, String q, int n) {
        try {
            List<float[]> v = embeddings.embed(List.of(truncate(q)));
            if (v.isEmpty()) {
                return List.of();
            }
            return jdbc.query("SELECT id FROM jarvis_capture_lines WHERE user_id = ? AND " + scopeColumn + " = ? "
                    + "AND embedding IS NOT NULL ORDER BY embedding <=> CAST(? AS vector) ASC LIMIT ?",
                    (rs, i) -> rs.getObject(1, UUID.class), userId, scope, literal(v.get(0)), n);
        } catch (RuntimeException e) {
            log.debug("Captures : recherche par le sens indisponible, repli mots ({})", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Les mots, dans la fenêtre des lignes les plus récentes (par index), puis filtre ; borné. */
    private List<JarvisCaptureStore.LineView> keyword(String scopeColumn, UUID scope, UUID userId, String q,
            List<String> speakers, int n) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(scope);
        args.add(KEYWORD_WINDOW);
        if (!q.isEmpty()) {
            List<String> words = words(q);
            if (words.isEmpty()) {
                words = List.of(q.toLowerCase(Locale.ROOT));
            }
            where.append(" AND (");
            for (int i = 0; i < words.size(); i++) {
                where.append(i == 0 ? "" : " OR ").append("LOWER(w.text) LIKE ?");
                args.add("%" + escape(words.get(i)) + "%");
            }
            where.append(")");
        }
        if (speakers != null && !speakers.isEmpty()) {
            where.append(" AND (");
            for (int i = 0; i < speakers.size(); i++) {
                where.append(i == 0 ? "" : " OR ").append("LOWER(w.speaker) LIKE ?");
                args.add("%" + escape(speakers.get(i).toLowerCase(Locale.ROOT)) + "%");
            }
            where.append(")");
        }
        args.add(n);
        String sql = "SELECT w.id FROM (SELECT id, text, speaker, said_at FROM jarvis_capture_lines WHERE user_id = ? AND "
                + scopeColumn + " = ? ORDER BY said_at DESC LIMIT ?) w WHERE 1 = 1" + where
                + " ORDER BY w.said_at DESC LIMIT ?";
        List<UUID> ids = jdbc.query(sql, (rs, i) -> rs.getObject(1, UUID.class), args.toArray());
        if (ids.isEmpty()) {
            return List.of();
        }
        List<JarvisCaptureStore.LineView> rows = jdbc.query(JarvisCaptureStore.LINE_SELECT + " WHERE l.user_id = ? AND l."
                + scopeColumn + " = ? AND l.id IN (" + marks(ids.size()) + ")", (rs, i) -> JarvisCaptureStore.line(rs),
                args(userId, scope, ids));
        rows.sort(java.util.Comparator.comparing(JarvisCaptureStore.LineView::saidAt).reversed());
        return rows;
    }

    /** Les mots porteurs d'une recherche (3 lettres au moins, mots vides écartés), 6 au plus. */
    static List<String> words(String q) {
        java.util.Set<String> stop = java.util.Set.of("les", "des", "une", "est", "que", "qui", "dans", "pour", "sur",
                "avec", "dit", "m'a", "moi", "the", "and", "quoi", "conversation");
        List<String> out = new ArrayList<>();
        for (String w : q.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}\\-!#]+")) {
            if (w.length() >= 3 && !stop.contains(w) && !out.contains(w)) {
                out.add(w);
            }
            if (out.size() == 6) {
                break;
            }
        }
        return out;
    }

    private static Object[] args(UUID userId, UUID scope, List<UUID> ids) {
        List<Object> a = new ArrayList<>();
        a.add(userId);
        a.add(scope);
        a.addAll(ids);
        return a.toArray();
    }

    private static String marks(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static String escape(String s) {
        return s.replace("\\", "").replace("%", "").replace("_", "");
    }

    private static String truncate(String s) {
        String t = s.strip();
        return t.length() > RecallSemanticProperties.MAX_EMBED_CHARS ? t.substring(0,
                RecallSemanticProperties.MAX_EMBED_CHARS) : t;
    }

    static String literal(float[] v) {
        StringBuilder b = new StringBuilder(v.length * 8 + 2).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(v[i]);
        }
        return b.append(']').toString();
    }

    private static boolean isPostgres(DataSource ds) {
        if (ds == null) {
            return false;
        }
        try {
            String p = JdbcUtils.extractDatabaseMetaData(ds, DatabaseMetaData::getDatabaseProductName);
            return p != null && p.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (Exception e) {
            return false;
        }
    }
}
