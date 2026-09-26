package fr.claudegateway.office;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;

/**
 * F-129 / SF-129-07 — ce que l'agent APPREND des documents Office.
 *
 * <p>Ces tests protègent la doctrine, pas le rendu (vérifié côté service) : n'installe rien, décris
 * et appelle l'outil, la charte est le défaut, et sans service configuré on ne promet rien.</p>
 */
class OfficeToolCatalogTest {

    @Test
    @DisplayName("LE CRITÈRE : le guide dit de NE RIEN INSTALLER et nomme les deux outils")
    void theguideForbidsInstalling() {
        assertThat(OfficeToolCatalog.GUIDE)
                .contains("N'installe RIEN")
                .contains("python-docx")
                .contains("openpyxl")
                .contains(OfficeToolCatalog.BUILD_DOCUMENT)
                .contains(OfficeToolCatalog.BUILD_SPREADSHEET)
                .as("le rendu est gratuit — sans le dire, l'agent l'évitera comme generate_image")
                .contains("GRATUIT");
    }

    @Test
    @DisplayName("le guide dit que la charte est le DÉFAUT, et nomme la sortie neutre")
    void theguideNamesTheCharte() {
        assertThat(OfficeToolCatalog.GUIDE).contains("DÉFAUT à la charte").contains("plain");
    }

    @Test
    @DisplayName("le guide dit de laisser les NOMBRES en nombres — un classeur texte ne se somme pas")
    void theguideKeepsNumbersAsNumbers() {
        assertThat(OfficeToolCatalog.GUIDE).contains("NOMBRES");
    }

    @Test
    @DisplayName("deux outils, deux schémas : « spec » requis, « theme » et « filename » facultatifs")
    void twoToolsTwoSchemas() {
        for (AgentTool tool : List.of(OfficeToolCatalog.document(), OfficeToolCatalog.spreadsheet())) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            assertThat(properties).containsKey("spec").containsKey("filename");
            assertThat(String.valueOf(tool.inputSchema().get("required")))
                    .contains("spec").doesNotContain("theme");
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) properties.get("spec");
            assertThat(String.valueOf(spec.get("description"))).contains("theme").contains("plain");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> docProperties =
                (Map<String, Object>) OfficeToolCatalog.document().inputSchema().get("properties");
        assertThat(docProperties)
                .as("les images ne concernent que le document")
                .containsKey("images");
        @SuppressWarnings("unchecked")
        Map<String, Object> sheetProperties =
                (Map<String, Object>) OfficeToolCatalog.spreadsheet().inputSchema().get("properties");
        assertThat(sheetProperties).doesNotContainKey("images");
    }

    @Test
    @DisplayName("les noms d'outils sont reconnus, et eux seuls")
    void thetoolNamesAreRecognized() {
        assertThat(OfficeToolCatalog.isOfficeTool(OfficeToolCatalog.BUILD_DOCUMENT)).isTrue();
        assertThat(OfficeToolCatalog.isOfficeTool(OfficeToolCatalog.BUILD_SPREADSHEET)).isTrue();
        assertThat(OfficeToolCatalog.isOfficeTool("build_presentation")).isFalse();
        assertThat(OfficeToolCatalog.isOfficeTool(null)).isFalse();
        assertThat(OfficeFormat.ofTool(OfficeToolCatalog.BUILD_DOCUMENT)).isEqualTo(OfficeFormat.DOCX);
        assertThat(OfficeFormat.ofTool(OfficeToolCatalog.BUILD_SPREADSHEET)).isEqualTo(OfficeFormat.XLSX);
    }

    @Test
    @DisplayName("sans constructeur configuré, les outils ne sont JAMAIS proposés")
    void withoutABuilderTheToolsAreNotOffered() {
        OfficeToolCatalog none = OfficeToolCatalog.none();
        assertThat(none.isOpenFor(UUID.randomUUID(), new Workspace())).isFalse();
        assertThat(none.toolsFor(UUID.randomUUID(), new Workspace())).isEmpty();

        OfficeToolCatalog closed = new OfficeToolCatalog(builder(false));
        assertThat(closed.toolsFor(UUID.randomUUID(), new Workspace())).isEmpty();

        OfficeToolCatalog open = new OfficeToolCatalog(builder(true));
        assertThat(open.toolsFor(UUID.randomUUID(), new Workspace())).hasSize(2);
        assertThat(open.isOpenFor(null, new Workspace())).isFalse();
        assertThat(open.isOpenFor(UUID.randomUUID(), null)).isFalse();
    }

    private OfficeBuilder builder(boolean available) {
        return new OfficeBuilder() {
            @Override
            public byte[] build(OfficeFormat format, com.fasterxml.jackson.databind.JsonNode spec) {
                return new byte[0];
            }

            @Override
            public boolean isAvailable() {
                return available;
            }
        };
    }
}
