package fr.claudegateway.atelier.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** F-266 / SF-266-01 — le bloc injecté, la clé de titre, l'extraction de section, les validations. */
class AccountGovernanceServiceTest {

    private static AccountGovernanceRule rule(String title, String content) {
        return AccountGovernanceRule.builder().title(title).titleKey(AccountGovernanceService.titleKey(title))
                .content(content).build();
    }

    @Test
    void titleKeyIgnoresCaseAndSpaces() {
        assertThat(AccountGovernanceService.titleKey("  Style  des Preuves ")).isEqualTo("style des preuves");
    }

    @Test
    void renderIsEmptyWithoutRules() {
        assertThat(AccountGovernanceService.render(List.of(), List.of())).isEmpty();
    }

    @Test
    void renderFramesRulesInOrderAndOmitsTitlesOfTheHost() {
        String block = AccountGovernanceService.render(
                List.of(rule("A règle", "Texte A."), rule("B règle", "Texte B.")),
                List.of("# Gouvernance\n\n## b RÈGLE\n\nDu poste."));

        assertThat(block).startsWith(AccountGovernanceService.HEADER)
                .endsWith(AccountGovernanceService.FOOTER)
                .contains("## A règle\n\nTexte A.")
                .doesNotContain("Texte B.");
    }

    @Test
    void renderIsEmptyWhenEveryRuleIsShadowed() {
        assertThat(AccountGovernanceService.render(List.of(rule("A", "x")), List.of("## a\n\ny"))).isEmpty();
    }

    @Test
    void renderIsBoundedAndSaysTheCut() {
        String block = AccountGovernanceService.render(List.of(rule("A", "a".repeat(3_000)),
                rule("B", "b".repeat(3_000)), rule("C", "c".repeat(3_000))), List.of());
        assertThat(block).contains(AccountGovernanceService.TRUNCATION).doesNotContain("c".repeat(3_000));
    }

    @Test
    void sectionExtractsTheBodyUntilTheNextHeading() {
        String file = "# Gouvernance\n\n## Langue\n\nFrançais.\n\n## Style des preuves (capturer_preuves)\n\n"
                + "- Fond navy.\n- 3 à 5 blocs.\n\n## Autre\n\nz\n";
        assertThat(AccountGovernanceService.section(file, "style des preuves (capturer_preuves)"))
                .contains("- Fond navy.\n- 3 à 5 blocs.");
        assertThat(AccountGovernanceService.section(file, "Absente")).isEmpty();
    }

    @Test
    void titleAndContentAreValidated() {
        assertThatThrownBy(() -> AccountGovernanceService.cleanTitle(" ")).isInstanceOf(InvalidProposalException.class);
        assertThatThrownBy(() -> AccountGovernanceService.cleanTitle("x".repeat(121)))
                .isInstanceOf(InvalidProposalException.class);
        assertThat(AccountGovernanceService.cleanTitle("## Style ")).isEqualTo("Style");
        assertThatThrownBy(() -> AccountGovernanceService.cleanContent(""))
                .isInstanceOf(InvalidProposalException.class);
        assertThatThrownBy(() -> AccountGovernanceService.cleanContent("x".repeat(4_001)))
                .isInstanceOf(InvalidProposalException.class);
    }
}
