package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Extraction de la description d'un skill pour le catalogue de la consigne système
 * (F-39 / SF-39-02). C'est la seule partie du skill qui part chez le fournisseur : elle doit tenir
 * sur une ligne, être bornée, et ne jamais laisser passer le corps du fichier.
 */
class AtelierSkillDescriptionTest {

    private static final String SKILL_BODY = """
            ---
            name: deploy
            description: Déploie le projet sur l'environnement cible.
            ---

            # Déploiement

            Étape 1 : lancer le pipeline.
            """;

    @Test
    void descriptionComesFromYamlFrontMatterWhenPresent() {
        assertThat(AtelierChatService.describeSkill(SKILL_BODY))
                .isEqualTo("Déploie le projet sur l'environnement cible.");
    }

    @Test
    void quotedYamlDescriptionLosesItsQuotes() {
        assertThat(AtelierChatService.describeSkill("---\ndescription: \"Fait X\"\n---\ncorps"))
                .isEqualTo("Fait X");
    }

    @Test
    void withoutFrontMatterTheFirstUsefulLineIsUsedAndTitlesAreIgnored() {
        String body = "# Titre du skill\n\n\nRésume la journée en trois points.\nsuite";
        assertThat(AtelierChatService.describeSkill(body)).isEqualTo("Résume la journée en trois points.");
    }

    @Test
    void descriptionIsFlattenedToOneLineAndBoundedTo200Characters() {
        String longLine = "a".repeat(250);
        assertThat(AtelierChatService.describeSkill("---\ndescription: " + longLine + "\n---\n"))
                .hasSize(201)
                .endsWith("…");
        assertThat(AtelierChatService.describeSkill("Une   phrase\tsur\nplusieurs lignes"))
                .isEqualTo("Une phrase sur");
    }

    @Test
    void frontMatterWithoutDescriptionFallsBackOnTheFirstUsefulLine() {
        assertThat(AtelierChatService.describeSkill("---\nname: x\n---\n")).isEqualTo("name: x");
    }

    @Test
    void emptySkillHasNoDescription() {
        assertThat(AtelierChatService.describeSkill("")).isEmpty();
        assertThat(AtelierChatService.describeSkill(null)).isEmpty();
    }
}
