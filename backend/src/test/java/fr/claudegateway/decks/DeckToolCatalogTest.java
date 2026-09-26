package fr.claudegateway.decks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.agent.AgentTool;

/**
 * F-129 / SF-129-04 — ce que l'agent APPREND de la charte.
 *
 * <p>La charte ne vaut que si elle est le défaut <b>et</b> que l'agent sait qu'il n'a pas à la
 * choisir. Ces tests protègent la doctrine, pas le rendu (vérifié côté constructeur).</p>
 */
class DeckToolCatalogTest {

    @Test
    @DisplayName("le guide dit que la charte est le DÉFAUT, et nomme la sortie neutre")
    void theguideNamesTheCharte() {
        assertThat(DeckToolCatalog.GUIDE)
                .contains("DÉFAUT à la charte")
                .contains("plain");
    }

    @Test
    @DisplayName("le schéma annonce « theme » sans le rendre obligatoire")
    void theschemaAnnouncesTheme() {
        AgentTool tool = DeckToolCatalog.definition();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) properties.get("spec");
        assertThat(String.valueOf(spec.get("description"))).contains("theme").contains("cg");
        assertThat(String.valueOf(tool.inputSchema().get("required"))).doesNotContain("theme");
    }

    @Test
    @DisplayName("sans constructeur configuré, l'outil n'est jamais proposé")
    void withoutABuilderTheToolIsNotOffered() {
        assertThat(DeckToolCatalog.none().isOpenFor(java.util.UUID.randomUUID(),
                new fr.claudegateway.atelier.Workspace())).isFalse();
    }
}
