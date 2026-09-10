package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.governance.dto.GovernancePackageAdminView;
import fr.claudegateway.governance.dto.GovernancePackageFileRequest;
import fr.claudegateway.governance.dto.GovernancePackageRequest;

/**
 * F-51 / SF-51-01 — ce qu'un paquet a le droit d'être. La validation a lieu à la <b>rédaction</b>,
 * une fois, avant que le contenu n'existe pour quiconque : un paquet est appliqué sur la machine
 * d'autres personnes, et un refus arrivé chez elles porterait sur une faute qu'elles n'ont pas
 * commise.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GovernancePackageServiceTest {

    @Mock
    private GovernancePackageRepository packages;
    @Mock
    private GovernancePackageFileRepository files;
    @Mock
    private AdminService adminService;

    private GovernancePackageService service;

    @BeforeEach
    void setUp() {
        when(packages.save(any())).thenAnswer(invocation -> {
            GovernancePackage pkg = invocation.getArgument(0);
            if (pkg.getId() == null) {
                pkg.setId(UUID.randomUUID());
            }
            return pkg;
        });
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(packages.findBySlug(any())).thenReturn(Optional.empty());
        service = newService(GovernanceControlRegistry.empty());
    }

    private GovernancePackageService newService(GovernanceControlRegistry registry) {
        return new GovernancePackageService(packages, files, registry, adminService);
    }

    private static GovernancePackageRequest minimal() {
        return new GovernancePackageRequest("gouvernance-livrables", "Livrables sans trace", null,
                "Aucun livrable ne doit suggérer un LLM.", List.of(), List.of());
    }

    @Test
    @DisplayName("un paquet naît en version 1, non publié")
    void createsUnpublishedVersionOne() {
        GovernancePackageAdminView view = service.create(minimal());

        assertThat(view.version()).isEqualTo(1);
        assertThat(view.published()).isFalse();
        assertThat(view.slug()).isEqualTo("gouvernance-livrables");
        verify(adminService).assertAdmin();
    }

    @Test
    @DisplayName("modifier remplace le contenu et incrémente la version ; le slug ne bouge pas")
    void updateBumpsVersionAndKeepsSlug() {
        GovernancePackage existing = GovernancePackage.builder()
                .id(UUID.randomUUID()).slug("gouvernance-livrables").name("Ancien").version(2)
                .rules("ancien").build();
        when(packages.findById(existing.getId())).thenReturn(Optional.of(existing));

        GovernancePackageAdminView view = service.update(existing.getId(),
                new GovernancePackageRequest("tentative-de-renommage", "Nouveau", "Résumé",
                        "nouvelles règles", List.of(), List.of()));

        assertThat(view.version()).isEqualTo(3);
        assertThat(view.slug()).isEqualTo("gouvernance-livrables");
        assertThat(view.name()).isEqualTo("Nouveau");
        // Remplacement intégral : les fichiers précédents sont effacés avant réécriture.
        verify(files).deleteByPackageId(existing.getId());
    }

    @Test
    @DisplayName("un paquet qui n'apporte rien est refusé")
    void rejectsEmptyPackage() {
        assertThatThrownBy(() -> service.create(new GovernancePackageRequest("vide", "Vide", null,
                "   ", List.of(), List.of())))
                .isInstanceOf(InvalidGovernancePackageException.class)
                .hasMessageContaining("au moins une règle");
    }

    @Test
    @DisplayName("un identifiant de contrôle inconnu du serveur est refusé")
    void rejectsUnknownControl() {
        assertThatThrownBy(() -> service.create(new GovernancePackageRequest("paquet", "Paquet", null,
                null, List.of("juge-de-fin-de-tour"), List.of())))
                .isInstanceOf(InvalidGovernancePackageException.class)
                .hasMessageContaining("Contrôle inconnu");
    }

    @Test
    @DisplayName("un identifiant de contrôle fourni par le serveur est accepté")
    void acceptsKnownControl() {
        service = newService(new GovernanceControlRegistry(List.of(stubControl("juge-de-fin-de-tour"))));

        GovernancePackageAdminView view = service.create(new GovernancePackageRequest("paquet",
                "Paquet", null, null, List.of("juge-de-fin-de-tour", "juge-de-fin-de-tour"), List.of()));

        assertThat(view.controls()).singleElement()
                .satisfies(control -> {
                    assertThat(control.id()).isEqualTo("juge-de-fin-de-tour");
                    assertThat(control.known()).isTrue();
                });
    }

    @Test
    @DisplayName("un chemin de fichier qui sort du projet est refusé")
    void rejectsEscapingPath() {
        assertThatThrownBy(() -> service.create(withFiles(
                new GovernancePackageFileRequest("../voisin/STATE.md", "TEMPLATE", "x"))))
                .isInstanceOf(InvalidGovernancePackageException.class)
                .hasMessageContaining("Chemin de fichier invalide");
    }

    @Test
    @DisplayName("deux fichiers au même chemin sont refusés")
    void rejectsDuplicatePath() {
        assertThatThrownBy(() -> service.create(withFiles(
                new GovernancePackageFileRequest("STATE.md", "TEMPLATE", "a"),
                new GovernancePackageFileRequest("./STATE.md", "TEMPLATE", "b"))))
                .isInstanceOf(InvalidGovernancePackageException.class)
                .hasMessageContaining("double");
    }

    @Test
    @DisplayName("un genre de fichier inconnu est refusé")
    void rejectsUnknownKind() {
        assertThatThrownBy(() -> service.create(withFiles(
                new GovernancePackageFileRequest("STATE.md", "HOOK", "a"))))
                .isInstanceOf(InvalidGovernancePackageException.class)
                .hasMessageContaining("Genre de fichier inconnu");
    }

    @Test
    @DisplayName("un fichier trop volumineux est refusé")
    void rejectsOversizedFile() {
        String huge = "x".repeat(GovernancePackageFile.MAX_CONTENT_LENGTH + 1);

        assertThatThrownBy(() -> service.create(withFiles(
                new GovernancePackageFileRequest("STATE.md", "TEMPLATE", huge))))
                .isInstanceOf(InvalidGovernancePackageException.class)
                .hasMessageContaining("trop volumineux");
    }

    @Test
    @DisplayName("un slug hors convention est refusé")
    void rejectsBadSlug() {
        assertThatThrownBy(() -> service.create(new GovernancePackageRequest("Pas Un Slug", "X", null,
                "règles", List.of(), List.of())))
                .isInstanceOf(InvalidGovernancePackageException.class)
                .hasMessageContaining("Identifiant invalide");
    }

    @Test
    @DisplayName("un slug déjà pris rend un conflit")
    void rejectsDuplicateSlug() {
        when(packages.findBySlug("gouvernance-livrables"))
                .thenReturn(Optional.of(GovernancePackage.builder().slug("gouvernance-livrables").build()));

        assertThatThrownBy(() -> service.create(minimal()))
                .isInstanceOf(GovernancePackageConflictException.class);
    }

    @Test
    @DisplayName("publier horodate la première publication ; dépublier ne l'efface pas")
    void publishStampsOnce() {
        GovernancePackage pkg = GovernancePackage.builder()
                .id(UUID.randomUUID()).slug("p").name("P").version(1).rules("r").build();
        when(packages.findById(pkg.getId())).thenReturn(Optional.of(pkg));

        GovernancePackageAdminView published = service.setPublished(pkg.getId(), true);
        assertThat(published.published()).isTrue();
        assertThat(published.publishedAt()).isNotNull();

        GovernancePackageAdminView unpublished = service.setPublished(pkg.getId(), false);
        assertThat(unpublished.published()).isFalse();
        assertThat(unpublished.publishedAt()).isNotNull();
    }

    @Test
    @DisplayName("supprimer un paquet publié est refusé — dépublier d'abord")
    void refusesDeletingPublishedPackage() {
        GovernancePackage pkg = GovernancePackage.builder()
                .id(UUID.randomUUID()).slug("p").name("P").version(1).published(true).build();
        when(packages.findById(pkg.getId())).thenReturn(Optional.of(pkg));

        assertThatThrownBy(() -> service.delete(pkg.getId()))
                .isInstanceOf(GovernancePackageConflictException.class);
        verify(packages, never()).delete(any());
    }

    @Test
    @DisplayName("un paquet inexistant rend « introuvable »")
    void missingPackageIsNotFound() {
        UUID id = UUID.randomUUID();
        when(packages.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.require(id))
                .isInstanceOf(GovernancePackageNotFoundException.class);
    }

    @Test
    @DisplayName("un paquet non publié n'existe pas pour un utilisateur")
    void unpublishedPackageIsInvisible() {
        GovernancePackage draft = GovernancePackage.builder()
                .id(UUID.randomUUID()).slug("p").name("P").version(1).published(false).build();
        when(packages.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.requirePublished(draft.getId()))
                .isInstanceOf(GovernancePackageNotFoundException.class);
    }

    @Test
    @DisplayName("un contrôle cité mais retiré du produit est signalé, pas masqué")
    void reportsControlRemovedFromProduct() {
        GovernancePackage pkg = GovernancePackage.builder()
                .id(UUID.randomUUID()).slug("p").name("P").version(1).controlIds("disparu").build();
        when(packages.findAllByOrderByNameAsc()).thenReturn(List.of(pkg));
        when(files.findByPackageIdInOrderByPositionAsc(any())).thenReturn(List.of());

        List<GovernancePackageAdminView> views = service.listForAdmin();

        assertThat(views).singleElement()
                .satisfies(view -> assertThat(view.controls()).singleElement()
                        .satisfies(control -> {
                            assertThat(control.id()).isEqualTo("disparu");
                            assertThat(control.known()).isFalse();
                        }));
    }

    private static GovernancePackageRequest withFiles(GovernancePackageFileRequest... files) {
        return new GovernancePackageRequest("paquet", "Paquet", null, null, List.of(),
                List.of(files));
    }

    private static GovernanceControl stubControl(String id) {
        return new GovernanceControl() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public AtelierCheckpointKind kind() {
                return AtelierCheckpointKind.END_OF_TURN;
            }

            @Override
            public String description() {
                return "Contrôle de test.";
            }

            @Override
            public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
                return AtelierCheckpointVerdict.proceed();
            }
        };
    }
}
