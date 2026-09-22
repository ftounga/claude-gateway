package fr.claudegateway.pages;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>Les bibliothèques des pages, servies par la gateway</b> (F-142 / SF-142-05).
 *
 * <p>Une page publiée charge sa bibliothèque de diagrammes <b>ici</b>, et non plus sur un CDN public.
 * Trois raisons, dans cet ordre :</p>
 * <ol>
 *   <li><b>Chez le client, un CDN public est injoignable</b> — poste verrouillé, proxy d'entreprise.
 *       C'est le cas d'usage réel, pas une exception.</li>
 *   <li>Une adresse de CDN peut <b>disparaître</b> : la version épinglée de SF-142-01 répondait 404,
 *       et aucun test ne pouvait le voir puisqu'ils vérifiaient la présence de la balise, pas du fichier.</li>
 *   <li>Le fichier servi ici est <b>figé et versionné</b> : ce qui est testé est ce qui est servi.</li>
 * </ol>
 *
 * <p><b>Liste close.</b> Un seul nom est servi, comparé caractère par caractère : ce contrôleur ne lit
 * jamais un chemin venu de la requête, et ne peut donc pas servir autre chose que cette bibliothèque.</p>
 *
 * <p><b>Public, et c'est voulu</b> : une page publiée se lit sans compte ; sa bibliothèque aussi. Rien
 * de ce qui est servi ici n'appartient à un utilisateur.</p>
 */
@RestController
@RequestMapping("/pages/lib")
public class PageLibraryController {

    /** Le seul fichier servi, et son emplacement dans le jar. */
    public static final String MERMAID_FILE = "mermaid-" + PageMermaidRuntime.MERMAID_VERSION + ".min.js";
    static final String CLASSPATH = "pages/" + MERMAID_FILE;

    /** La version est dans le nom : le fichier ne change jamais, le cache peut être long. */
    static final Duration CACHE = Duration.ofDays(365);

    @GetMapping("/{file}")
    public ResponseEntity<byte[]> library(@PathVariable String file) throws IOException {
        if (!MERMAID_FILE.equals(file)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        ClassPathResource resource = new ClassPathResource(CLASSPATH);
        if (!resource.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        byte[] content;
        try (InputStream in = resource.getInputStream()) {
            content = in.readAllBytes();
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "javascript", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.maxAge(CACHE).cachePublic().immutable())
                .body(content);
    }

}
