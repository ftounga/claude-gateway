package fr.claudegateway.help;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Chargeur de documentation d'aide (F-54 / SF-54-01) : la documentation réellement embarquée est
 * chargée, dans l'ordre des noms de fichiers, et un classpath vide refuse le démarrage.
 */
class HelpDocumentLoaderTest {

    @Test
    void chargeLaDocumentationEmbarquee() {
        HelpDocumentLoader loader = new HelpDocumentLoader();

        assertThat(loader.documentation()).isNotBlank();
        // Les sujets des deux jours de mise en service doivent tous être couverts.
        assertThat(loader.documentation())
                .contains("Découvrir Claude Portal")
                .contains("Télécharger le runner")
                .contains("Java : de quelle version ai-je besoin")
                .contains("Proxy, réseau et pare-feu")
                .contains("Appairer une machine")
                .contains("Lancer le runner et exécuter des commandes")
                .contains("Fichiers, confinement et exclusions")
                .contains("Compte, offres et consommation")
                .contains("Messages d'erreur et dépannage");
    }

    @Test
    void concateneLesDocumentsDansLOrdreDeLeursNoms() {
        String documentation = new HelpDocumentLoader().documentation();

        assertThat(documentation.indexOf("Découvrir Claude Portal"))
                .isLessThan(documentation.indexOf("Télécharger le runner"));
        assertThat(documentation.indexOf("Télécharger le runner"))
                .isLessThan(documentation.indexOf("Messages d'erreur et dépannage"));
    }

    @Test
    void laDocumentationNeParlePlusQueDeLaForge() {
        // F-58 / SF-58-01 — le centre d'aide est de la documentation PRODUIT : il porte le mot que
        // l'utilisateur voit à l'écran. Le mot « Atelier » n'y a plus sa place, alors que les
        // identifiants internes (`atelier_*`, /atelier) restent volontairement inchangés ailleurs.
        String documentation = new HelpDocumentLoader().documentation();

        assertThat(documentation).contains("Forge");
        assertThat(documentation).doesNotContain("Atelier");
        assertThat(documentation).doesNotContain("atelier");
    }

    @Test
    void refuseDeDemarrerSansAucunDocument() {
        // Un chatbot d'aide sans documentation répondrait de mémoire : mieux vaut ne pas démarrer.
        assertThatThrownBy(() -> new HelpDocumentLoader("classpath:help-inexistant/*.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Aucun document d'aide");
    }
}
