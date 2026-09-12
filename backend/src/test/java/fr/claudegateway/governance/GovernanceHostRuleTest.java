package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-93 / SF-93-01 — les <b>invariants de la racine d'un poste</b>, déclarés une fois.
 *
 * <p>Ce que ces tests protègent : les identifiants sont <b>publiés</b> — F-95 s'y branchera, et le
 * document de règles les cite — et chaque règle porte son <b>action corrective</b>, pas seulement
 * son constat. « Le dossier ne respecte pas la convention » ne se corrige pas ; « déplace-le sous
 * repos/ » se corrige.</p>
 */
class GovernanceHostRuleTest {

    /** La forme que F-95 grep : deux segments minuscules séparés d'une barre. */
    private static final Pattern ID = Pattern.compile("^[a-z0-9-]+/[a-z0-9-]+$");

    @Test
    @DisplayName("les trois règles sont là, dans l'ordre du document")
    void theThreeRulesAreDeclared() {
        assertThat(GovernanceHostRule.values()).hasSize(3);
        assertThat(GovernanceHostRule.ids()).containsExactly("clonage/depot-dans-repos",
                "clonage/projet-sans-git", "clonage/note-hors-depot");
    }

    @Test
    @DisplayName("chaque identifiant a la forme que F-95 saura reconnaître")
    void everyIdIsGreppable() {
        for (GovernanceHostRule rule : GovernanceHostRule.values()) {
            assertThat(ID.matcher(rule.id()).matches())
                    .as("identifiant « %s »", rule.id()).isTrue();
        }
    }

    @Test
    @DisplayName("chaque règle porte un énoncé ET une action corrective")
    void everyRuleCarriesItsCorrection() {
        for (GovernanceHostRule rule : GovernanceHostRule.values()) {
            assertThat(rule.statement()).as("énoncé de « %s »", rule.id()).isNotBlank();
            assertThat(rule.correction()).as("correction de « %s »", rule.id()).isNotBlank();
            // Une action corrective commence par un geste, pas par un constat : elle contient un
            // verbe d'action. C'est grossier, et c'est exactement ce qu'on veut garder vrai.
            assertThat(rule.correction().toLowerCase()).as("geste de « %s »", rule.id())
                    .containsAnyOf("déplace", "supprime", "ouvre", "mv ");
        }
    }

    @Test
    @DisplayName("la règle qui protège le plus renvoie vers la carte, jamais vers le dépôt")
    void theMostProtectiveRulePointsToTheMap() {
        assertThat(GovernanceHostRule.NOTE_HORS_DEPOT.correction()).contains("carte du poste");
    }
}
