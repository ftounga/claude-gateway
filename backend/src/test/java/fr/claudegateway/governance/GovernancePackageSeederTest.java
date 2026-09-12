package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.governance.control.CommitSansTraceLlmControl;
import fr.claudegateway.governance.control.JugeFinDeTourControl;
import fr.claudegateway.governance.control.PromotionDetteBloquanteControl;

/**
 * Le semeur du premier paquet (F-52 / SF-52-03).
 *
 * <p>Ce que ces tests protègent : qu'un redémarrage ne fasse <b>rien</b> quand rien n'a changé, qu'un
 * paquet rangé par l'admin ne soit pas remis en rayon dans son dos, et qu'une ressource manquante
 * fasse renoncer <b>entièrement</b> plutôt que semer un paquet à moitié — qui aurait l'air complet.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GovernancePackageSeederTest {

    @Mock private GovernancePackageRepository packages;
    @Mock private GovernancePackageFileRepository files;

    private GovernanceControlRegistry fullRegistry;

    @BeforeEach
    void setUp() {
        fullRegistry = new GovernanceControlRegistry(List.of(new CommitSansTraceLlmControl(),
                new JugeFinDeTourControl(), new PromotionDetteBloquanteControl()));
        when(packages.save(any(GovernancePackage.class))).thenAnswer(invocation -> {
            GovernancePackage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        when(files.save(any(GovernancePackageFile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private GovernancePackageSeeder seeder(GovernanceControlRegistry registry, boolean enabled) {
        return new GovernancePackageSeeder(packages, files, registry, enabled);
    }

    private GovernancePackage capturePackage() {
        ArgumentCaptor<GovernancePackage> captor = ArgumentCaptor.forClass(GovernancePackage.class);
        verify(packages).save(captor.capture());
        return captor.getValue();
    }

    private List<GovernancePackageFile> captureFiles() {
        ArgumentCaptor<GovernancePackageFile> captor =
                ArgumentCaptor.forClass(GovernancePackageFile.class);
        verify(files, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("au premier passage, le paquet est créé et publié en version 1")
    void firstPassCreatesAndPublishes() {
        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.empty());

        assertThat(seeder(fullRegistry, true).seed()).isTrue();

        GovernancePackage pkg = capturePackage();
        assertThat(pkg.getSlug()).isEqualTo("savoir-durable");
        assertThat(pkg.getVersion()).isEqualTo(1);
        assertThat(pkg.isPublished()).isTrue();
        assertThat(pkg.getPublishedAt()).isNotNull();
        assertThat(pkg.getRules()).contains("Le travail est jetable, le savoir est durable")
                .contains("fin-de-tour: promotion=aucune; dette=0");
        assertThat(pkg.controlIdList()).containsExactly("commit-sans-trace-llm", "juge-fin-de-tour",
                "promotion-dette-bloquante");

        List<GovernancePackageFile> written = captureFiles();
        assertThat(written).extracting(GovernancePackageFile::getPath)
                .containsExactly("README.md", "acces.md", "reseau.md", "plateformes.md",
                        "donnees.md", "exploitation.md", "GOUVERNANCE.md", "PLAN-ACTION.md",
                        "STATE.md", ".claude/skills/explique.md", ".claude/skills/plan-dashboard.md");
        assertThat(written).extracting(GovernancePackageFile::getKind)
                .containsExactly(GovernanceFileKind.MAP, GovernanceFileKind.MAP,
                        GovernanceFileKind.MAP, GovernanceFileKind.MAP, GovernanceFileKind.MAP,
                        GovernanceFileKind.MAP, GovernanceFileKind.TEMPLATE,
                        GovernanceFileKind.TEMPLATE, GovernanceFileKind.TEMPLATE,
                        GovernanceFileKind.SKILL, GovernanceFileKind.SKILL);
    }

    @Test
    @DisplayName("F-92 : la carte est STRUCTURÉE et VIDE — des en-têtes, la règle d'écriture, aucun fait")
    void theMapIsStructuredButEmpty() {
        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.empty());

        seeder(fullRegistry, true).seed();

        List<GovernancePackageFile> map = captureFiles().stream()
                .filter(file -> file.getKind() == GovernanceFileKind.MAP).toList();
        assertThat(map).hasSize(6);
        for (GovernancePackageFile file : map) {
            // Un chemin PLAT : la carte, ce sont les fichiers de la racine. Un dossier serait pris
            // pour un projet — exactement la confusion que cette disposition évite.
            assertThat(file.getPath()).doesNotContain("/");
            // La règle d'écriture est dans le gabarit lui-même : un modèle qui ouvre le fichier la
            // lit, même s'il n'a jamais vu les règles du paquet.
            assertThat(file.getContent()).containsIgnoringCase("faits").contains("datés")
                    .contains("leur source");
            assertThat(file.getContent()).startsWith("# ");
        }
        GovernancePackageFile readme = map.get(0);
        assertThat(readme.getPath()).isEqualTo("README.md");
        assertThat(readme.getContent()).contains("## Contacts").contains("## Les grands domaines")
                .contains("## Annuaire des projets").contains("constaté le AAAA-MM-JJ");
        GovernancePackageFile acces = map.get(1);
        assertThat(acces.getPath()).isEqualTo("acces.md");
        assertThat(acces.getContent()).contains("## Récapitulatif VPN").contains("## Les pièges")
                .contains("| Constaté le |");
    }

    @Test
    @DisplayName("un second passage sans changement n'écrit RIEN — pas même un incrément de version")
    void secondPassWritesNothing() {
        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.empty());
        GovernancePackageSeeder seeder = seeder(fullRegistry, true);
        seeder.seed();
        GovernancePackage created = capturePackage();
        List<GovernancePackageFile> written = captureFiles();
        clearInvocations(packages, files);

        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.of(created));
        when(files.findByPackageIdOrderByPositionAsc(created.getId())).thenReturn(written);

        assertThat(seeder.seed()).isFalse();
        verify(packages, never()).save(any());
        verify(files, never()).save(any());
        verify(files, never()).deleteByPackageId(any());
    }

    @Test
    @DisplayName("un contenu différent met à jour et incrémente la version")
    void changedContentBumpsTheVersion() {
        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.empty());
        GovernancePackageSeeder seeder = seeder(fullRegistry, true);
        seeder.seed();
        GovernancePackage created = capturePackage();
        List<GovernancePackageFile> written = captureFiles();
        clearInvocations(packages, files);

        created.setRules("une rédaction précédente");
        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.of(created));
        when(files.findByPackageIdOrderByPositionAsc(created.getId())).thenReturn(written);

        assertThat(seeder.seed()).isTrue();
        assertThat(capturePackage().getVersion()).isEqualTo(2);
        verify(files).deleteByPackageId(created.getId());
    }

    @Test
    @DisplayName("un paquet dépublié par l'admin n'est jamais remis en rayon")
    void anUnpublishedPackageStaysUnpublished() {
        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.empty());
        GovernancePackageSeeder seeder = seeder(fullRegistry, true);
        seeder.seed();
        GovernancePackage created = capturePackage();
        clearInvocations(packages, files);

        created.setPublished(false);
        created.setRules("une rédaction précédente"); // force la mise à jour
        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.of(created));
        when(files.findByPackageIdOrderByPositionAsc(created.getId())).thenReturn(List.of());

        seeder.seed();

        assertThat(capturePackage().isPublished()).isFalse();
    }

    @Test
    @DisplayName("un contrôle absent du produit est ignoré, les autres restent cités")
    void anAbsentControlIsIgnored() {
        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.empty());
        GovernanceControlRegistry partial =
                new GovernanceControlRegistry(List.of(new JugeFinDeTourControl()));

        seeder(partial, true).seed();

        assertThat(capturePackage().controlIdList()).containsExactly("juge-fin-de-tour");
    }

    @Test
    @DisplayName("une ressource absente fait renoncer ENTIÈREMENT")
    void oneMissingResourceCancelsEverything() {
        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.empty());
        GovernancePackageSeeder amputated =
                new GovernancePackageSeeder(packages, files, fullRegistry, true) {
                    @Override
                    String readResource(String name) {
                        return "STATE.md".equals(name) ? null : super.readResource(name);
                    }
                };

        assertThat(amputated.seed()).isFalse();
        verify(packages, never()).save(any());
        verify(files, never()).save(any());
    }

    @Test
    @DisplayName("le semeur désactivé ne touche à rien")
    void aDisabledSeederDoesNothing() {
        assertThat(seeder(fullRegistry, false).seed()).isFalse();
        verifyNoInteractions(packages, files);
    }

    @Test
    @DisplayName("une base indisponible n'empêche pas le produit de démarrer")
    void anUnavailableDatabaseNeverBlocksStartup() {
        when(packages.findBySlug(GovernancePackageSeeder.SLUG))
                .thenThrow(new IllegalStateException("base indisponible"));

        assertThatCode(() -> seeder(fullRegistry, true).seedOnStartup()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("le gabarit de la carte ne porte AUCUNE case non cochée : il serait une dette dès son dépôt")
    void theProjectMapTemplateCarriesNoDebt() {
        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.empty());
        seeder(fullRegistry, true).seed();

        String map = captureFiles().stream()
                .filter(file -> "PLAN-ACTION.md".equals(file.getPath()))
                .findFirst().orElseThrow().getContent();

        assertThat(map).doesNotContain("- [ ]");
    }
}
