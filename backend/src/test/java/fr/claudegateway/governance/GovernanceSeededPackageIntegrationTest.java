package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.governance.dto.GovernancePackageView;

/**
 * F-52 / SF-52-03 — le premier paquet, semé pour de vrai.
 *
 * <p>Ce que ce test vérifie et qu'aucun autre ne peut : que le paquet livré par le produit cite des
 * contrôles que le <b>registre réel</b> fournit. Un identifiant mal orthographié serait ignoré en
 * silence, et le paquet promettrait un verrou qui ne se refermerait jamais.</p>
 *
 * <p>Le semeur est appelé <b>explicitement</b> plutôt qu'attendu au démarrage : le contexte Spring
 * est partagé entre classes de test, et d'autres vident ces tables. Un test qui dépendrait de l'ordre
 * ne défendrait rien.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class GovernanceSeededPackageIntegrationTest {

    @Autowired
    private GovernancePackageSeeder seeder;
    @Autowired
    private GovernancePackageService packageService;
    @Autowired
    private GovernancePackageRepository packages;
    @Autowired
    private GovernancePackageFileRepository files;
    @Autowired
    private GovernanceSelectionRepository selections;
    @Autowired
    private GovernanceActivationRepository activations;
    @Autowired
    private GovernanceControlRegistry registry;

    @BeforeEach
    void setUp() {
        files.deleteAll();
        packages.deleteAll();
        selections.deleteAll();
        activations.deleteAll();
    }

    @Test
    @DisplayName("le paquet semé est publié, complet, et ses contrôles existent vraiment")
    void theSeededPackageIsPublishedCompleteAndItsControlsExist() {
        assertThat(seeder.seed()).isTrue();

        List<GovernancePackageView> published = packageService.listPublished();
        assertThat(published).hasSize(1);
        GovernancePackageView pkg = published.get(0);
        assertThat(pkg.slug()).isEqualTo("savoir-durable");
        assertThat(pkg.version()).isEqualTo(1);
        assertThat(pkg.rules()).contains("Le travail est jetable, le savoir est durable");

        // Les trois contrôles cités sont fournis par le produit — c'est tout l'objet de ce test.
        assertThat(pkg.controls()).hasSize(3);
        assertThat(pkg.controls()).allSatisfy(control ->
                assertThat(registry.exists(control.id()))
                        .as("contrôle « %s » inconnu du registre", control.id()).isTrue());

        assertThat(pkg.files()).extracting(file -> file.path())
                .containsExactly("GOUVERNANCE.md", "PLAN-ACTION.md", "STATE.md",
                        ".claude/skills/explique.md", ".claude/skills/plan-dashboard.md");
    }

    @Test
    @DisplayName("publié, jamais activé : personne ne subit le paquet sans l'avoir choisi")
    void publishedNeverActivated() {
        seeder.seed();

        assertThat(selections.count()).isZero();
        assertThat(activations.count()).isZero();
    }

    @Test
    @DisplayName("semer deux fois ne change rien la seconde fois")
    void seedingTwiceChangesNothing() {
        assertThat(seeder.seed()).isTrue();
        assertThat(seeder.seed()).isFalse();
        assertThat(packages.findBySlug("savoir-durable").orElseThrow().getVersion()).isEqualTo(1);
    }
}
