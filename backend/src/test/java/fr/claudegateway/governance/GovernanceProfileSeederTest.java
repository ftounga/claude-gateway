package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Les profils métier au catalogue (F-138 / SF-138-01).
 *
 * <p>Ce que ce test protège : un profil est une <b>doctrine</b>, pas un outillage. Il ne dépose
 * <b>rien</b> sur la machine d'un client, n'arme aucun contrôle de fin de tour, et n'est
 * <b>jamais</b> retenu par défaut — choisir un métier est une décision, pas un réglage.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class GovernanceProfileSeederTest {

    @Autowired private GovernanceProfileSeeder seeder;
    @Autowired private GovernancePackageRepository packages;
    @Autowired private GovernancePackageFileRepository files;
    @Autowired private GovernanceSelectionRepository selections;

    /**
     * Le semeur tourne au démarrage du contexte — mais <b>d'autres tests effacent tous les paquets</b>
     * ({@code packages.deleteAll()}), si bien que ce test ne passait que placé avant eux. On re-sème
     * donc explicitement : l'opération est idempotente (c'est même ce que vérifie
     * {@code seedingTwiceChangesNothing}), et le test cesse de dépendre de l'ordre d'exécution.
     */
    @BeforeEach
    void reseed() {
        seeder.seedOnStartup();
    }

    @AfterEach
    void tearDown() {
        // Le semeur tourne au démarrage du contexte : on ne détruit pas son travail pour les autres.
    }

    private GovernancePackage profile(String slug) {
        return packages.findBySlug(slug).orElseThrow(() -> new AssertionError("profil absent : " + slug));
    }

    @Test
    @DisplayName("les quatre profils du domaine sont publiés, avec leur doctrine")
    void thefourProfilesArePublished() {
        for (GovernanceProfileSeeder.Profile declared : GovernanceProfileSeeder.PROFILES) {
            GovernancePackage pkg = profile(declared.slug());
            assertThat(pkg.isPublished()).isTrue();
            assertThat(pkg.getRules()).isNotBlank();
            assertThat(pkg.getSummary()).isNotBlank();
        }
        assertThat(GovernanceProfileSeeder.PROFILES).hasSize(4);
    }

    @Test
    @DisplayName("LE CRITÈRE : un profil ne dépose RIEN sur la machine du client")
    void aprofileDepositsNothing() {
        for (GovernanceProfileSeeder.Profile declared : GovernanceProfileSeeder.PROFILES) {
            UUID id = profile(declared.slug()).getId();
            assertThat(files.findByPackageIdOrderByPositionAsc(id))
                    .as("le profil « %s » n'apporte aucun fichier", declared.slug())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("un profil n'arme aucun contrôle de fin de tour")
    void aprofileArmsNoCheckpoint() {
        for (GovernanceProfileSeeder.Profile declared : GovernanceProfileSeeder.PROFILES) {
            assertThat(profile(declared.slug()).controlIdList()).isEmpty();
        }
    }

    @Test
    @DisplayName("aucun profil n'est retenu par défaut : activer reste un geste")
    void noProfileIsSelectedByDefault() {
        List<String> profileSlugs = GovernanceProfileSeeder.PROFILES.stream()
                .map(GovernanceProfileSeeder.Profile::slug).toList();

        assertThat(selections.findAll())
                .allSatisfy(selection -> assertThat(profileSlugs)
                        .doesNotContain(packages.findById(selection.getPackageId())
                                .map(GovernancePackage::getSlug).orElse("")));
    }

    @Test
    @DisplayName("le semis rejoué ne duplique rien et n'incrémente pas la version")
    void seedingTwiceChangesNothing() {
        GovernanceProfileSeeder.Profile declared = GovernanceProfileSeeder.PROFILES.get(0);
        int versionBefore = profile(declared.slug()).getVersion();

        assertThat(seeder.seed(declared)).isFalse();

        assertThat(profile(declared.slug()).getVersion()).isEqualTo(versionBefore);
        assertThat(packages.findAll().stream()
                .filter(pkg -> declared.slug().equals(pkg.getSlug()))).hasSize(1);
    }

    @Test
    @DisplayName("chaque doctrine tient dans la borne de la consigne système")
    void eachDoctrineFitsInTheSystemPromptBudget() {
        // Ce texte part à CHAQUE tour. « Savoir durable » + un profil doivent tenir largement sous
        // la borne du bloc de règles (12 000), sinon les conventions du projet seraient tronquées.
        for (GovernanceProfileSeeder.Profile declared : GovernanceProfileSeeder.PROFILES) {
            assertThat(profile(declared.slug()).getRules().length())
                    .as("doctrine « %s »", declared.slug())
                    .isLessThan(GovernanceRulesProvider.MAX_RULES_BLOCK_CHARS / 3);
        }
    }

    @Test
    @DisplayName("les doctrines disent ce qui vaut preuve, chacune dans son métier")
    void eachDoctrineSaysWhatCountsAsProof() {
        // Sans cela, un profil ne serait qu'un titre : c'est CETTE phrase qui change le raisonnement.
        assertThat(profile("profil-architecte").getRules())
                .contains("constaté").contains("rapporté").contains("supposé");
        assertThat(profile("profil-infrastructure-production").getRules())
                .contains("revenir en arrière");
        assertThat(profile("profil-securite").getRules())
                .contains("droit effectif")
                // Et la règle qui ne souffre aucune exception dans ce métier.
                .contains("jamais");
        assertThat(profile("profil-donnees").getRules()).contains("restauration");
    }
}
