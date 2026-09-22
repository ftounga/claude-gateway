package fr.claudegateway.pages;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;

/**
 * F-142 / SF-142-05 — la bibliothèque des diagrammes est servie par la gateway.
 *
 * <p><b>Ce que ces tests protègent, et qui manquait</b> : les tests d'origine vérifiaient que le HTML
 * servi <b>contenait la balise</b> {@code <script src=…>}. C'était vrai — et l'adresse répondait
 * <b>404</b>. Un test peut passer sur une URL morte. Ici, on vérifie le <b>fichier</b> : qu'il existe,
 * qu'il pèse ce qu'il doit peser, et qu'il expose bien l'objet que le runtime cherche.</p>
 */
class PageLibraryControllerTest {

    private final PageLibraryController controller = new PageLibraryController();

    @Test
    @DisplayName("LE TEST QUI MANQUAIT : la bibliothèque existe VRAIMENT dans le jar, et expose l'objet attendu")
    void thelibraryExistsAndExposesTheObject() throws IOException {
        ClassPathResource resource = new ClassPathResource(PageLibraryController.CLASSPATH);

        assertThat(resource.exists())
                .as("la bibliothèque doit être embarquée : une page qui promet un diagramme doit pouvoir le rendre")
                .isTrue();

        byte[] content;
        try (InputStream in = resource.getInputStream()) {
            content = in.readAllBytes();
        }
        // Un bundle Mermaid pèse quelques mégaoctets : un fichier d'erreur ou une page HTML de 404
        // n'en ferait que quelques centaines d'octets.
        assertThat(content.length).isGreaterThan(1_000_000);
        String head = new String(content, 0, 400, StandardCharsets.UTF_8);
        assertThat(head)
                .as("le bundle v11 pose son objet dans un namespace esbuild — c'est ce que le runtime cherche")
                .contains("__esbuild_esm_mermaid_nm")
                .contains("mermaid");
    }

    @Test
    @DisplayName("le runtime pointe vers la route locale, et le nom servi est exactement celui-là")
    void theruntimePointsToTheServedFile() {
        assertThat(PageMermaidRuntime.SCRIPT_URL)
                .contains(PageLibraryController.MERMAID_FILE)
                .doesNotContain("cdnjs")
                .doesNotContain("jsdelivr");
        assertThat(PageLibraryController.MERMAID_FILE)
                .isEqualTo("mermaid-" + PageMermaidRuntime.MERMAID_VERSION + ".min.js");
    }

    @Test
    @DisplayName("le fichier est servi en JavaScript, avec un cache long ; un autre nom est refusé")
    void servesJavaScriptAndRefusesAnythingElse() throws IOException {
        ResponseEntity<byte[]> served = controller.library(PageLibraryController.MERMAID_FILE);

        assertThat(served.getStatusCode().value()).isEqualTo(200);
        assertThat(served.getHeaders().getContentType()).isNotNull();
        assertThat(served.getHeaders().getContentType().toString()).contains("javascript");
        assertThat(served.getHeaders().getCacheControl()).contains("max-age=").contains("immutable");
        assertThat(served.getBody()).isNotNull().hasSizeGreaterThan(1_000_000);

        // Liste close : le contrôleur ne lit jamais un chemin venu de la requête.
        assertThat(controller.library("mermaid-10.9.1.min.js").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.library("../application.yml").getStatusCode().value()).isEqualTo(404);
    }
}
