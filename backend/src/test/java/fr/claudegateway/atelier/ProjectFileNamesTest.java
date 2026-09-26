package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-129 / SF-129-07 — les noms et les chemins qui viennent du modèle, éprouvés <b>une fois pour
 * toutes</b> : le deck, le document et le classeur passent désormais par ici.
 */
class ProjectFileNamesTest {

    @Test
    @DisplayName("ISOLATION : un chemin absolu, une lettre de lecteur ou une remontée sont REFUSÉS")
    void apathOutsideTheProjectIsRefused() {
        assertThat(ProjectFileNames.refusePath("/etc/passwd", "d'image")).contains("absolu refusé");
        assertThat(ProjectFileNames.refusePath("\\\\serveur\\part", "d'image")).contains("absolu refusé");
        assertThat(ProjectFileNames.refusePath("C:/secrets.png", "d'image")).contains("absolu refusé");
        assertThat(ProjectFileNames.refusePath("../../secrets.png", "d'image")).contains("hors du projet");
        assertThat(ProjectFileNames.refusePath("", "d'image")).contains("vide");
        assertThat(ProjectFileNames.refusePath(null, "d'image")).contains("vide");
    }

    @Test
    @DisplayName("un chemin relatif du projet passe")
    void aprojectPathPasses() {
        assertThat(ProjectFileNames.refusePath("schemas/archi.png", "d'image")).isNull();
    }

    @Test
    @DisplayName("le nom déposé est nettoyé, porte SON extension, et ne peut pas cacher un chemin")
    void thenameIsCleaned() {
        assertThat(ProjectFileNames.clean(null, "Compte rendu (v2)", "docx", "document"))
                .isEqualTo("Compte-rendu-v2.docx");
        assertThat(ProjectFileNames.clean("../../sortie.xlsx", "x", "xlsx", "classeur"))
                .isEqualTo("sortie.xlsx");
        assertThat(ProjectFileNames.clean(null, "", "docx", "document")).isEqualTo("document.docx");
        assertThat(ProjectFileNames.clean("Cible AWS", null, "pptx", "presentation"))
                .isEqualTo("Cible-AWS.pptx");
    }

    @Test
    @DisplayName("un titre démesuré est borné — un nom illisible n'est pas un nom")
    void alongTitleIsBounded() {
        String name = ProjectFileNames.clean(null, "x".repeat(400), "docx", "document");
        assertThat(name).hasSize(ProjectFileNames.MAX_NAME + ".docx".length());
    }
}
