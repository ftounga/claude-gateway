package fr.claudegateway.diagrams;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.video.VideoIconSource;

/**
 * <b>Les icônes officielles servies à la scène 3D</b> (F-238 / SF-238-08, cadrage §5.4) : une icône du paquet de la lib
 * {@code diagrams}, par la route {@code /icons} du renderer (F-215) — <b>même client</b>, même liste blanche de
 * fournisseurs, même motif de nom, même borne de taille. Aucune donnée utilisateur : le contenu est public et immuable,
 * d'où un petit cache mémoire.
 *
 * <p><b>Jamais une autre icône</b> : une icône absente est dite absente (404) ; c'est la scène qui dessine alors une
 * boîte neutre (SF-142-12).</p>
 */
@Service
public class DiagramIconProxy {

    /** Le motif de {@code diagram-renderer/icons.js} ({@code NAME}), appliqué AVANT tout appel. */
    static final Pattern NAME = Pattern.compile("^[a-z0-9]+(/[a-z0-9][a-z0-9._-]*){1,3}$");
    static final Set<String> PROVIDERS = Set.of("aws", "azure", "gcp", "k8s", "onprem", "generic");
    static final int CACHE_SIZE = 256;

    /** Un nom refusé (400). */
    public static class InvalidIconNameException extends RuntimeException {
        public InvalidIconNameException() {
            super("Nom d'icône invalide.");
        }
    }

    private final VideoIconSource source;
    private final Map<String, byte[]> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public DiagramIconProxy(VideoIconSource source) {
        this.source = source;
    }

    /** Vrai si le nom est sûr : motif, fournisseur permis, aucun segment {@code .} ou {@code ..}. */
    static boolean valid(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return false;
        }
        String[] segments = name.split("/");
        for (String segment : segments) {
            if (".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return PROVIDERS.contains(segments[0]);
    }

    /**
     * Les octets PNG de l'icône {@code name} (chemin sous {@code resources/}, sans extension), ou vide si elle n'existe
     * pas.
     *
     * @throws InvalidIconNameException             nom hors motif — le renderer n'est pas appelé
     * @throws DiagramRendererUnavailableException service absent ou muet
     */
    public Optional<byte[]> icon(String name) {
        if (!valid(name)) {
            throw new InvalidIconNameException();
        }
        synchronized (cache) {
            byte[] hit = cache.get(name);
            if (hit != null) {
                return Optional.of(hit);
            }
        }
        VideoIconSource.Batch batch;
        try {
            batch = source.fetch(List.of(name));
        } catch (DiagramRejectedException e) {
            throw new InvalidIconNameException();
        }
        Optional<byte[]> png = batch.icons().stream().filter(i -> name.equals(i.name())).map(VideoIconSource.Icon::png)
                .findFirst();
        png.ifPresent(bytes -> {
            synchronized (cache) {
                cache.put(name, bytes);
            }
        });
        return png;
    }
}
