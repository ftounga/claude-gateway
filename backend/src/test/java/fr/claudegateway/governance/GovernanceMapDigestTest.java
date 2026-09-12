package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import fr.claudegateway.governance.dto.GovernanceMapSectionView;

/**
 * F-92 / SF-92-02 — le compte des <b>faits</b> d'un fichier de carte.
 *
 * <p>Ce que ces tests protègent : <b>un gabarit livré tel quel compte zéro</b>. C'est la propriété
 * qui fait tenir toute la jauge — si les consignes, les en-têtes de tableau et les cases vides
 * comptaient, la carte afficherait déjà des dizaines de « faits » le jour de son dépôt, et le
 * chiffre ne dirait plus rien de ce que le PO veut voir : que la connaissance <b>augmente</b>.</p>
 */
class GovernanceMapDigestTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = new ClassPathResource("governance/savoir-durable/carte/" + name)
                .getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("les six gabarits livrés comptent ZÉRO fait — et exposent bien leurs sections")
    void theSeededTemplatesCountZeroFacts() throws IOException {
        for (String name : new String[] {"README.md", "acces.md", "reseau.md", "plateformes.md",
                "donnees.md", "exploitation.md"}) {
            GovernanceMapDigest.Digest digest = GovernanceMapDigest.of(resource(name), name);

            assertThat(digest.facts()).as("faits de %s", name).isZero();
            assertThat(digest.sections()).as("sections de %s", name).isNotEmpty();
            assertThat(digest.title()).as("titre de %s", name).isNotBlank().doesNotStartWith("#");
        }
    }

    @Test
    @DisplayName("le titre de premier niveau devient le titre ; sans lui, le nom du fichier")
    void theFirstHeadingIsTheTitle() {
        assertThat(GovernanceMapDigest.of("# Accès\n\n## VPN\n", "acces.md").title())
                .isEqualTo("Accès");
        assertThat(GovernanceMapDigest.of("## VPN\n", "acces.md").title()).isEqualTo("acces.md");
        assertThat(GovernanceMapDigest.of(null, "acces.md").title()).isEqualTo("acces.md");
        assertThat(GovernanceMapDigest.of("   ", "acces.md").facts()).isZero();
    }

    @Test
    @DisplayName("un tableau : l'en-tête et la séparation ne comptent pas, une ligne renseignée si")
    void aTableCountsOnlyItsFilledRows() {
        String content = """
                # Accès

                ## Récapitulatif VPN

                | VPN | Client | Source et date |
                |---|---|---|
                | GlobalProtect | 6.2.1 | courriel réseau, constaté le 2026-09-01 |
                | | | |
                """;

        GovernanceMapDigest.Digest digest = GovernanceMapDigest.of(content, "acces.md");

        assertThat(digest.facts()).isEqualTo(1);
        assertThat(digest.sections()).containsExactly(
                new GovernanceMapSectionView("Récapitulatif VPN", 1));
    }

    @Test
    @DisplayName("une consigne — citation ou italique — ne compte jamais")
    void guidanceNeverCounts() {
        String content = """
                # Carte

                > Faits uniquement, datés, avec leur source.

                ## Le poste en une phrase

                _Chez qui, pour quoi faire, depuis quand._
                """;

        assertThat(GovernanceMapDigest.of(content, "README.md").facts()).isZero();
    }

    @Test
    @DisplayName("une case à cocher vide ne compte pas ; renseignée, elle compte")
    void anEmptyCheckboxDoesNotCount() {
        assertThat(GovernanceMapDigest.of("## Reste\n\n- [ ]\n", "x.md").facts()).isZero();
        assertThat(GovernanceMapDigest.of("## Reste\n\n- [ ] Cartographier le DNS\n", "x.md")
                .facts()).isEqualTo(1);
        assertThat(GovernanceMapDigest.of("## Reste\n\n- [x] DNS cartographié\n", "x.md").facts())
                .isEqualTo(1);
        assertThat(GovernanceMapDigest.of("## Reste\n\n-\n", "x.md").facts()).isZero();
    }

    @Test
    @DisplayName("une ligne de texte ordinaire compte pour un fait, et se range dans sa section")
    void plainTextCountsInItsSection() {
        String content = """
                # Réseau

                ## Environnements

                La recette est joignable depuis le VPN seulement (constaté le 2026-09-02).

                ## Certificats
                """;

        GovernanceMapDigest.Digest digest = GovernanceMapDigest.of(content, "reseau.md");

        assertThat(digest.facts()).isEqualTo(1);
        assertThat(digest.sections()).containsExactly(
                new GovernanceMapSectionView("Environnements", 1),
                new GovernanceMapSectionView("Certificats", 0));
    }

    @Test
    @DisplayName("un fait écrit AVANT toute section compte quand même dans le total")
    void factsBeforeAnySectionStillCount() {
        GovernanceMapDigest.Digest digest =
                GovernanceMapDigest.of("# Carte\n\nBastion unique : bst-01.\n", "README.md");

        assertThat(digest.facts()).isEqualTo(1);
        assertThat(digest.sections()).isEmpty();
    }

    @Test
    @DisplayName("du gras n'est pas une consigne : un fait mis en valeur reste un fait")
    void boldTextIsStillAFact() {
        assertThat(GovernanceMapDigest.of("## Pièges\n\n**Le tunnel coupe le DNS.**\n", "x.md")
                .facts()).isEqualTo(1);
    }
}
