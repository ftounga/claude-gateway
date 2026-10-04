package fr.claudegateway.governance.map.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.governance.map.index.HostMapSectionSplitter.FactLine;
import fr.claudegateway.governance.map.index.HostMapSectionSplitter.Section;

/** Le découpage d'un fichier de carte en sections (F-174 / SF-174-02, D3). */
class HostMapSectionSplitterTest {

    private static final String MAP = """
            # Accès
            > Gabarit : une ligne par fait.

            ## Bastions
            - lzi, constaté le 2026-09-14
            ### Détail
            - bastion-2 ouvert

            ## Commandes
            ```
            ssh lzi
            ```
            - ssh passe par le proxy
            | a | b |
            |---|---|
            | x | y |
            """;

    @Test
    @DisplayName("une section par titre ##, préambule compris, sous-titres inclus")
    void splitsByLevelTwoHeadings() {
        List<Section> sections = HostMapSectionSplitter.split(MAP);
        assertThat(sections).extracting(Section::heading)
                .containsExactly(null, "Bastions", "Commandes");
        assertThat(sections.get(0).facts()).isEmpty(); // Titre et gabarit ne sont pas des faits.
        assertThat(sections.get(1).facts()).extracting(FactLine::text)
                .containsExactly("- lzi, constaté le 2026-09-14", "- bastion-2 ouvert");
        assertThat(sections.get(1).facts().get(0).lineNo()).isEqualTo(5);
    }

    @Test
    @DisplayName("le code et les séparateurs de tableau ne sont pas des faits")
    void codeAndTableSeparatorsAreNotFacts() {
        Section commands = HostMapSectionSplitter.split(MAP).get(2);
        assertThat(commands.facts()).extracting(FactLine::text)
                .containsExactly("- ssh passe par le proxy", "| a | b |", "| x | y |");
    }

    @Test
    @DisplayName("l'empreinte ne change que si le texte de LA section change")
    void fingerprintIsPerSection() {
        List<Section> before = HostMapSectionSplitter.split(MAP);
        List<Section> after = HostMapSectionSplitter.split(MAP.replace("bastion-2 ouvert", "bastion-2 fermé"));
        assertThat(after.get(1).fingerprint()).isNotEqualTo(before.get(1).fingerprint());
        assertThat(after.get(2).fingerprint()).isEqualTo(before.get(2).fingerprint());
        assertThat(HostMapSectionSplitter.split("  ")).isEmpty();
    }
}
