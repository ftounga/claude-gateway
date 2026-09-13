package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
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
import fr.claudegateway.governance.control.JugeIndependantControl;
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

    @Mock private GovernanceMapDestinations destinations;

    private GovernanceControlRegistry fullRegistry;

    @BeforeEach
    void setUp() {
        fullRegistry = new GovernanceControlRegistry(List.of(new CommitSansTraceLlmControl(),
                new JugeFinDeTourControl(destinations),
                new PromotionDetteBloquanteControl(destinations),
                new JugeIndependantControl(null, null, destinations)));
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
                // La forme annoncée au modèle est celle que le produit lit (F-93 : « promu »).
                .contains(fr.claudegateway.governance.control.FinDeTourMarker.FORME
                        .replace("<!-- ", "").replace(" -->", ""));
        // Le juge indépendant vient EN DERNIER : c'est le seul qui coûte un appel, et le premier
        // blocage l'emporte (F-50). L'ordre est le garde-fou de dépense (F-94 / SF-94-03).
        assertThat(pkg.controlIdList()).containsExactly("commit-sans-trace-llm", "juge-fin-de-tour",
                "promotion-dette-bloquante", "juge-independant");

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
                new GovernanceControlRegistry(List.of(new JugeFinDeTourControl(destinations)));

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
    @DisplayName("le texte de règles CITE les trois invariants de la racine (F-93)")
    void theRulesDocumentCitesTheThreeInvariants() {
        String rules = seeder(fullRegistry, true).readResource("regles.md");

        assertThat(rules).isNotNull();
        assertThat(GovernancePackageSeeder.ruleMissingFrom(rules)).isNull();
        for (String id : GovernanceHostRule.ids()) {
            assertThat(rules).contains(id);
        }
    }

    @Test
    @DisplayName("une règle annoncée mais absente du texte fait renoncer : un paquet ne ment pas")
    void aRuleMissingFromTheDocumentCancelsEverything() {
        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.empty());
        GovernancePackageSeeder amputated =
                new GovernancePackageSeeder(packages, files, fullRegistry, true) {
                    @Override
                    String readResource(String name) {
                        String content = super.readResource(name);
                        return "regles.md".equals(name)
                                ? content.replace(GovernanceHostRule.NOTE_HORS_DEPOT.id(), "")
                                : content;
                    }
                };

        assertThat(amputated.seed()).isFalse();
        verify(packages, never()).save(any());
    }

    @Test
    @DisplayName("le texte de règles tient sous la borne : il part dans la consigne à CHAQUE tour")
    void theRulesStayUnderTheLimit() {
        String rules = seeder(fullRegistry, true).readResource("regles.md");

        // Sans ce test, dépasser la borne ne se verrait qu'à l'exécution : le semeur se contente
        // d'un avertissement, et le catalogue resterait silencieusement vide.
        assertThat(rules.length()).isLessThanOrEqualTo(GovernancePackage.MAX_RULES_LENGTH);
    }

    @Test
    @DisplayName("le gabarit STATE.md porte la trace de promotion, avec sa destination (F-93)")
    void theStateTemplateCarriesThePromotionTrace() {
        String state = seeder(fullRegistry, true).readResource("STATE.md");

        assertThat(state).contains("## Promotions").contains("- [ ]")
                .contains("-> promu dans plateformes.md");
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

    @Test
    @DisplayName("tout ce que le produit livre est déclaré ARTEFACT GÉNÉRÉ (F-96)")
    void everySeededFileIsDeclaredGenerated() {
        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.empty());
        when(packages.save(any())).thenAnswer(invocation -> {
            GovernancePackage saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        ArgumentCaptor<GovernancePackageFile> captor =
                ArgumentCaptor.forClass(GovernancePackageFile.class);

        assertThat(seeder(fullRegistry, true).seed()).isTrue();

        verify(files, atLeastOnce()).save(captor.capture());
        // Un skill, un gabarit, un fichier de carte : le produit les a écrits, il a donc le droit
        // de les corriger — mais SEULEMENT là où ils sont restés intacts. Sans cette déclaration,
        // aucune correction n'atteindrait jamais un poste déjà activé.
        assertThat(captor.getAllValues()).isNotEmpty()
                .allSatisfy(file -> assertThat(file.isGenerated()).isTrue());
    }

    // ------------------------- F-96 / SF-96-02 : les empreintes publiées

    /** L'empreinte du gabarit STATE.md tel qu'il était publié AVANT F-95 — la dette à rattraper. */
    private static final String STATE_AVANT_F95 =
            "bf3c724344bf4de0d2382be71946fdfcd1da2fff2abff43751f96315ae10d9d3";

    @Test
    @DisplayName("le paquet embarque les empreintes de ce qu'il a DÉJÀ publié (dette F-95)")
    void theSeededPackageCarriesItsPublishedDigests() {
        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.empty());

        assertThat(seeder(fullRegistry, true).seed()).isTrue();

        GovernancePackageFile state = captureFiles().stream()
                .filter(file -> "STATE.md".equals(file.getPath())).findFirst().orElseThrow();
        // Sans cette ligne, un poste activé avant F-96 garderait à jamais un gabarit sans la
        // section « Statut » — et son contrôle de dette/clôture ne se déclencherait jamais.
        assertThat(state.knownDigestList()).contains(STATE_AVANT_F95);
    }

    @Test
    @DisplayName("republier RETIENT le contenu remplacé, et n'oublie pas ce qu'on savait déjà")
    void republishingRemembersWhatItReplaces() {
        GovernancePackage existing = GovernancePackage.builder().id(UUID.randomUUID())
                .slug(GovernancePackageSeeder.SLUG).name("Le savoir durable").version(4)
                .published(true).rules("vieilles règles").build();
        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.of(existing));
        GovernancePackageFile stored = GovernancePackageFile.builder().packageId(existing.getId())
                .position(8).path("STATE.md").kind(GovernanceFileKind.TEMPLATE)
                .content("# Un gabarit d'avant\n").build();
        stored.setKnownDigestList(List.of("a".repeat(64)));
        when(files.findByPackageIdOrderByPositionAsc(existing.getId()))
                .thenReturn(List.of(stored));

        assertThat(seeder(fullRegistry, true).seed()).isTrue();

        GovernancePackageFile state = captureFiles().stream()
                .filter(file -> "STATE.md".equals(file.getPath())).findFirst().orElseThrow();
        assertThat(state.knownDigestList())
                // Le contenu qu'on vient de remplacer, EN TÊTE : c'est celui qu'un poste porte.
                .startsWith(GovernanceDigest.of("# Un gabarit d'avant\n"))
                // Ce qu'on savait déjà n'est pas perdu…
                .contains("a".repeat(64))
                // …et ce que le produit déclare non plus.
                .contains(STATE_AVANT_F95);
    }

    @Test
    @DisplayName("une ressource d'empreintes absente ne fait pas échouer le semeur")
    void aMissingDigestResourceIsNotFatal() {
        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.empty());
        GovernancePackageSeeder blind =
                new GovernancePackageSeeder(packages, files, fullRegistry, true) {
                    @Override
                    String readResource(String name) {
                        return "empreintes-anterieures.txt".equals(name) ? null
                                : super.readResource(name);
                    }
                };

        // Une empreinte manquante coûte un fichier non reconnu — donc CONSERVÉ. Un démarrage raté
        // coûterait le produit.
        assertThat(blind.seed()).isTrue();
        assertThat(captureFiles()).isNotEmpty()
                .allSatisfy(file -> assertThat(file.knownDigestList()).isEmpty());
    }

    @Test
    @DisplayName("une ligne d'empreinte mal formée est ignorée, le reste est chargé")
    void aMalformedDigestLineIsIgnored() {
        when(packages.findBySlug(GovernancePackageSeeder.SLUG)).thenReturn(Optional.empty());
        GovernancePackageSeeder noisy =
                new GovernancePackageSeeder(packages, files, fullRegistry, true) {
                    @Override
                    String readResource(String name) {
                        if (!"empreintes-anterieures.txt".equals(name)) {
                            return super.readResource(name);
                        }
                        return "# un commentaire\n\npas-une-empreinte STATE.md\n"
                                + "deadbeef\n"
                                + STATE_AVANT_F95 + " STATE.md\n";
                    }
                };

        assertThat(noisy.seed()).isTrue();

        GovernancePackageFile state = captureFiles().stream()
                .filter(file -> "STATE.md".equals(file.getPath())).findFirst().orElseThrow();
        assertThat(state.knownDigestList()).containsExactly(STATE_AVANT_F95);
    }
}
