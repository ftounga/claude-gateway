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

        // Les cinq contrôles cités sont fournis par le produit — c'est tout l'objet de ce test.
        assertThat(pkg.controls()).hasSize(5);
        // Le juge indépendant vient EN DERNIER (F-94 / SF-94-03) : c'est le seul qui coûte un appel,
        // et le premier blocage l'emporte. L'ordre est le garde-fou de dépense. L'intégrité du poste
        // (F-95 / SF-95-03) se range juste avant lui : elle coûte des allers-retours vers la
        // machine, jamais un appel au fournisseur.
        assertThat(pkg.controls()).extracting(control -> control.id())
                .containsExactly("commit-sans-trace-llm", "juge-fin-de-tour",
                        "promotion-dette-bloquante", "integrite-du-poste", "juge-independant");
        assertThat(pkg.controls()).allSatisfy(control ->
                assertThat(registry.exists(control.id()))
                        .as("contrôle « %s » inconnu du registre", control.id()).isTrue());

        // `pptx.md` ajouté par F-129 / SF-129-01 (le skill qui produit un vrai .pptx) : la liste
        // attendue ici n'avait pas suivi, et ce test était rouge sur `main`. Il est mis à jour, pas
        // assoupli — il continue d'exiger la liste EXACTE et son ordre, qui est celui de l'annonce
        // à l'écran.
        assertThat(pkg.files()).extracting(file -> file.path())
                .containsExactly("README.md", "acces.md", "reseau.md", "plateformes.md",
                        "donnees.md", "exploitation.md", "GOUVERNANCE.md", "PLAN-ACTION.md",
                        "STATE.md", ".claude/skills/explique.md", ".claude/skills/plan-dashboard.md",
                        ".claude/skills/pptx.md");
        // F-92 / SF-92-01 : la carte est annoncée comme telle. Sans le genre, l'écran ne saurait pas
        // dire qu'elle se pose à la RACINE et non dans chaque dossier.
        assertThat(pkg.files()).filteredOn(file -> "MAP".equals(file.kind()))
                .extracting(file -> file.path())
                .containsExactly("README.md", "acces.md", "reseau.md", "plateformes.md",
                        "donnees.md", "exploitation.md");
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

    /**
     * F-92 / SF-92-04 — le semeur sème <b>au démarrage</b>, chemin de mise à jour compris.
     *
     * <p>Reproduit le bug de production : un paquet <b>déjà déployé</b> sans fichier de carte
     * (l'état figé en prod : version 6, 5 fichiers, aucun {@code MAP}) doit, au prochain démarrage,
     * recevoir ses 6 fichiers {@code MAP}. Avant le correctif, {@link GovernancePackageSeeder#seedOnStartup()}
     * appelait {@code seed()} par auto-invocation → {@code @Transactional} non appliqué →
     * {@code deleteByPackageId} du chemin de mise à jour levait {@code InvalidDataAccessApiUsageException},
     * avalée par le {@code try/catch} → le paquet restait sans carte. Ce test <b>échoue avant</b> le
     * correctif (aucun {@code MAP}), passe après.</p>
     */
    @Test
    @DisplayName("SF-92-04 : au démarrage, un paquet déployé sans carte reçoit ses 6 fichiers MAP")
    void startupSeedsTheMapFilesEvenOnTheUpdatePath() {
        // L'état de prod : le paquet existe, publié, mais sans aucun fichier de carte — et avec des
        // règles différentes, pour que le semis emprunte le chemin de MISE À JOUR (deleteByPackageId
        // + réécriture), celui qui exige la transaction.
        GovernancePackage deployed = packages.saveAndFlush(GovernancePackage.builder()
                .slug("savoir-durable").name("Le savoir durable")
                .summary("un résumé antérieur").rules("une rédaction antérieure, à remplacer")
                .version(6).published(true).build());
        files.saveAndFlush(GovernancePackageFile.builder().packageId(deployed.getId()).position(0)
                .path("GOUVERNANCE.md").kind(GovernanceFileKind.TEMPLATE)
                .content("# un gabarit d'avant\n").generated(true).build());

        // Le geste du démarrage, tel quel — c'est LUI qui court-circuitait le proxy.
        seeder.seedOnStartup();

        GovernancePackage after = packages.findBySlug("savoir-durable").orElseThrow();
        List<GovernancePackageFile> stored = files.findByPackageIdOrderByPositionAsc(after.getId());
        assertThat(stored).extracting(GovernancePackageFile::getKind)
                .filteredOn(GovernanceFileKind.MAP::equals).hasSize(6);
        assertThat(stored).filteredOn(file -> file.getKind() == GovernanceFileKind.MAP)
                .extracting(GovernancePackageFile::getPath)
                .containsExactly("README.md", "acces.md", "reseau.md", "plateformes.md",
                        "donnees.md", "exploitation.md");
        // La version a bien été bumpée : la mise à jour a abouti, atomiquement.
        assertThat(after.getVersion()).isEqualTo(7);
    }
}
