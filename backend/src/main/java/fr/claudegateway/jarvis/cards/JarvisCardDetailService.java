package fr.claudegateway.jarvis.cards;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * <b>Le plein écran d'une pancarte</b> (F-248 / SF-248-06, D6) — aiguille {@code fait:<famille>:<ref>} vers la famille
 * qui la relit sous {@code user_id}. Identifiant illisible ⇒ refus ; famille inconnue ou ligne absente ⇒ vide (404).
 */
@Service
public class JarvisCardDetailService {

    private static final Logger log = LoggerFactory.getLogger(JarvisCardDetailService.class);
    /** {@code fait:<famille>:<ref>} — famille en minuscules, référence sans espace, 300 caractères au plus. */
    static final Pattern ID = Pattern.compile("^fait:([a-z]{2,20}):([^\\s/\\\\]{1,280})$");

    private final Map<String, JarvisCardResolver> resolvers = new LinkedHashMap<>();

    public JarvisCardDetailService(List<JarvisCardResolver> resolvers) {
        for (JarvisCardResolver r : resolvers) {
            this.resolvers.put(r.family(), r);
        }
    }

    /** Vrai si l'identifiant a la forme d'une pancarte. */
    public static boolean wellFormed(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** Le détail, ou vide (inconnu, autre compte). */
    public Optional<JarvisCardDetail> detail(UUID userId, String id) {
        var m = ID.matcher(id == null ? "" : id);
        if (userId == null || !m.matches()) {
            return Optional.empty();
        }
        JarvisCardResolver r = resolvers.get(m.group(1));
        if (r == null) {
            return Optional.empty();
        }
        try {
            return r.detail(userId, m.group(2));
        } catch (RuntimeException e) {
            log.debug("Pancarte : détail illisible ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Les familles servies (diagnostic, tests). */
    public java.util.Set<String> families() {
        return java.util.Collections.unmodifiableSet(resolvers.keySet());
    }
}
