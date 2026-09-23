package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * F-142 / SF-142-08 — <b>les skills ne demandent plus d'installer quoi que ce soit pour dessiner</b>.
 *
 * <p>Un agent fait ce qu'on lui écrit. Tant que la recette disait « installe `mermaid-cli` », il
 * installait — et sur un poste de banque il échouait, livrant un deck sans ses diagrammes. Le rendu
 * est passé côté gateway (SF-142-06 / 07) ; cette garde empêche la vieille recette de revenir par
 * inadvertance, au détour d'une réécriture.</p>
 */
class SkillsInstallFreeTest {

    /** Le skill qui portait les recettes d'installation. */
    private static final String PPTX = "governance/savoir-durable/pptx.md";

    /** Ce qui ne doit plus jamais apparaître pour un DIAGRAMME. */
    private static final List<String> FORBIDDEN = List.of(
            "npm install", "apt-get install", "brew install",
            "pip install diagrams", "mermaid-cli", "mmdc",
            // F-129 / SF-129-05 : le FICHIER lui aussi est construit par la gateway.
            "pip install python-pptx");

    private String skill(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        assertThat(resource.exists()).as("le skill %s doit exister", path).isTrue();
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("LA GARDE : aucune consigne d'installation de moteur de rendu dans le skill pptx")
    void nomoreInstallRecipes() throws IOException {
        String content = skill(PPTX);

        for (String forbidden : FORBIDDEN) {
            assertThat(content)
                    .as("« %s » demande au client d'installer un moteur de rendu : la gateway rend "
                            + "désormais les diagrammes (F-142 / SF-142-06 et 07)", forbidden)
                    .doesNotContain(forbidden);
        }
    }

    @Test
    @DisplayName("le skill enseigne l'outil de la gateway, et ses DEUX moteurs")
    void theskillTeachesTheGatewayTool() throws IOException {
        String content = skill(PPTX);

        assertThat(content)
                .contains("render_diagram")
                .contains("engine: \"cloud\"")
                .contains("aws.rds")
                // SF-129-05 : la voie recommandée pour le fichier lui-même.
                .contains("build_presentation")
                .as("le rendu est gratuit — sans le dire, l'agent l'évitera comme generate_image")
                .contains("GRATUIT");
    }

    @Test
    @DisplayName("la doctrine de fond est CONSERVÉE : jamais d'image IA pour un schéma, et le factuel")
    void thedoctrineIsKept() throws IOException {
        String content = skill(PPTX);

        assertThat(content)
                // La frontière de F-142 : une image IA n'est JAMAIS un schéma.
                .contains("N'utilise **JAMAIS** `generate_image` pour un **schéma")
                .contains("FACTUEL")
                .contains("(supposé)");
    }
}
