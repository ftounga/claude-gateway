package fr.claudegateway.help;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Base de connaissance de l'aide produit (F-54 / SF-54-01) : les fichiers Markdown de
 * {@code classpath:help/} sont lus <b>une seule fois au démarrage</b>, triés par nom de fichier et
 * concaténés en une chaîne unique injectée ensuite dans la consigne système.
 *
 * <p><b>Statique, sans vectorisation</b> : la documentation d'usage tient dans une consigne. Aucun
 * embedding, aucun index, aucun {@code pgvector} — un moteur de recherche vectoriel serait de la
 * machinerie sans bénéfice à cette taille.</p>
 *
 * <p>Les documents sont volontairement <b>écrits pour l'utilisateur</b> et non copiés de la
 * documentation d'ingénierie de {@code docs/} : celle-ci porte feuille de route, décisions non
 * tranchées et noms d'infrastructure, qui n'ont rien à faire dans une consigne exposée à tout compte
 * connecté (cf. {@code docs/features/F-54/F-54-cadrage.md}, D1).</p>
 */
@Component
public class HelpDocumentLoader {

    private static final Logger log = LoggerFactory.getLogger(HelpDocumentLoader.class);

    /** Emplacement des documents d'aide sur le classpath. */
    static final String LOCATION_PATTERN = "classpath:help/*.md";

    private final String documentation;

    public HelpDocumentLoader() {
        this(LOCATION_PATTERN);
    }

    /** Constructeur de test : permet de charger un autre emplacement de classpath. */
    HelpDocumentLoader(String locationPattern) {
        this.documentation = load(locationPattern);
        log.info("Documentation d'aide chargée : {} caractères", documentation.length());
    }

    /** Documentation d'aide complète, prête à être injectée dans une consigne système. */
    public String documentation() {
        return documentation;
    }

    private static String load(String locationPattern) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(locationPattern);
        } catch (IOException ex) {
            // Emplacement absent du classpath : traité comme « aucun document », ce que la garde de
            // fin transforme en refus de démarrage explicite.
            resources = new Resource[0];
        }
        // Tri par nom de fichier : les documents sont numérotés, l'ordre de lecture est celui voulu
        // par l'auteur — et il doit être déterministe d'un démarrage à l'autre.
        Arrays.sort(resources, Comparator.comparing(resource ->
                resource.getFilename() == null ? "" : resource.getFilename()));

        StringBuilder sb = new StringBuilder();
        for (Resource resource : resources) {
            try (var in = resource.getInputStream()) {
                sb.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append("\n\n");
            } catch (IOException ex) {
                throw new UncheckedIOException(
                        "Document d'aide illisible : " + resource.getFilename(), ex);
            }
        }

        String documentation = sb.toString().trim();
        if (documentation.isEmpty()) {
            // Refuser le démarrage plutôt que servir une aide sans documentation : sans elle, le
            // modèle répondrait de mémoire, c'est-à-dire exactement ce que la feature interdit.
            throw new IllegalStateException(
                    "Aucun document d'aide trouvé sur " + locationPattern
                            + " : le chatbot d'aide ne peut pas démarrer sans documentation.");
        }
        return documentation;
    }
}
