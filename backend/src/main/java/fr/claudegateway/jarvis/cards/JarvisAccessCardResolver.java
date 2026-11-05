package fr.claudegateway.jarvis.cards;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * <b>Le plein écran d'un accès relié</b> (F-248 / SF-248-09, D8) — {@code fait:acces:<source>.<id>} : relu sous le
 * compte, ne rend que la liste blanche (compte, type, validité, état, portée). Jamais la valeur, même partielle.
 */
@Component
public class JarvisAccessCardResolver implements JarvisCardResolver {

    private final JarvisAccessCards accesses;

    public JarvisAccessCardResolver(JarvisAccessCards accesses) {
        this.accesses = accesses;
    }

    @Override
    public String family() {
        return "acces";
    }

    @Override
    public Optional<JarvisCardDetail> detail(UUID userId, String ref) {
        String id = "fait:acces:" + ref;
        return accesses.accesses(userId, List.of()).stream().map(JarvisAccessCards.Access::card)
                .filter(c -> c.id().equals(id)).findFirst().map(c -> {
                    Map<String, String> f = new LinkedHashMap<>();
                    c.fields().forEach(x -> f.put(x.label(), x.value()));
                    return new JarvisCardDetail(c, JarvisCardTemplates.detailFields(f), null, null, null, null);
                });
    }
}
