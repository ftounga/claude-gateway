package fr.claudegateway.teams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;

/**
 * <b>La garde du volet Teams</b> (F-89 / SF-89-01, cadrage §5.4).
 *
 * <p>C'est le test le plus important de la subfeature, et il tient une phrase du cadrage :
 * <i>« sans l'option, les outils {@code teams_*} ne sont simplement pas donnés »</i>. La
 * conséquence, elle, est ce qui se voit à l'écran : <b>l'agent ne refuse pas, il n'a pas la
 * capacité</b> — il ne dira jamais « je pourrais mais vous n'avez pas payé ».</p>
 *
 * <p>Les assertions portent sur le <b>préfixe</b> et non sur une liste d'outils : le catalogue
 * grandira (F-88, SF-89-02), et un test qui l'énumérerait cesserait de protéger ce qu'il protège.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamsToolCatalogTest {

    @Mock private TeamsAccessService teamsAccess;

    private TeamsToolCatalog catalog;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        catalog = new TeamsToolCatalog(teamsAccess);
    }

    private static Workspace teamsTerminal() {
        Workspace workspace = new Workspace();
        workspace.setTeamsTerminal(true);
        return workspace;
    }

    private static Workspace projectTerminal() {
        return new Workspace();
    }

    @Test
    @DisplayName("terminal Teams + droit ouvert : le catalogue est donné")
    void theCatalogIsGivenWhenBothConditionsHold() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);

        assertThat(catalog.toolsFor(userId, teamsTerminal()))
                .extracting(AgentTool::name)
                .contains(TeamsToolCatalog.STATUS);
    }

    @Test
    @DisplayName("sans le droit : AUCUN outil teams_*, et pas davantage un outil qui refuserait")
    void noToolAtAllWithoutTheRight() {
        when(teamsAccess.hasAccess(userId)).thenReturn(false);

        assertThat(catalog.toolsFor(userId, teamsTerminal())).isEmpty();
    }

    @Test
    @DisplayName("SF-89-04 : terminal Teams sans droit — fermé, la consigne devra le dire")
    void aTeamsTerminalWithoutTheRightIsClosed() {
        when(teamsAccess.hasAccess(userId)).thenReturn(false);

        assertThat(catalog.isClosedFor(userId, teamsTerminal())).isTrue();
        assertThat(TeamsToolCatalog.CLOSED_NOTICE)
                .contains("n'est pas actif")
                .contains("Ne cherche pas la réponse sur la machine")
                .contains("code d'accès");
    }

    @Test
    @DisplayName("SF-89-04 : terminal Teams avec droit — pas fermé")
    void aTeamsTerminalWithTheRightIsNotClosed() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);

        assertThat(catalog.isClosedFor(userId, teamsTerminal())).isFalse();
    }

    @Test
    @DisplayName("SF-89-04 : un terminal de projet n'est jamais fermé, et le droit n'est pas lu")
    void aProjectTerminalIsNeverClosed() {
        assertThat(catalog.isClosedFor(userId, projectTerminal())).isFalse();
        org.mockito.Mockito.verifyNoInteractions(teamsAccess);
        assertThat(TeamsToolCatalog.none().isClosedFor(userId, teamsTerminal())).isFalse();
    }

    @Test
    @DisplayName("sur un terminal de projet, AUCUN outil teams_* — même avec l'option")
    void noTeamsToolOnAProjectTerminal() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);

        assertThat(catalog.toolsFor(userId, projectTerminal())).isEmpty();
    }

    @Test
    @DisplayName("le catalogue vide de none() ne donne jamais rien")
    void noneGivesNothing() {
        assertThat(TeamsToolCatalog.none().toolsFor(userId, teamsTerminal())).isEmpty();
    }

    @Test
    @DisplayName("tout ce que le catalogue rend porte le préfixe teams_ — la garde reste vérifiable")
    void everyToolCarriesThePrefix() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);

        assertThat(catalog.toolsFor(userId, teamsTerminal()))
                .isNotEmpty()
                .allSatisfy(tool -> assertThat(tool.name()).startsWith(TeamsToolCatalog.PREFIX));
    }

    @Test
    @DisplayName("les trois outils de PRÉSENTATION sont donnés — et seulement là (F-89 / SF-89-02)")
    void thePresentationToolsAreGivenToo() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);

        assertThat(catalog.toolsFor(userId, teamsTerminal())).extracting(AgentTool::name)
                .contains(TeamsToolCatalog.MEETING_CARD, TeamsToolCatalog.LIST,
                        TeamsToolCatalog.MOMENTS);
        assertThat(catalog.toolsFor(userId, projectTerminal())).isEmpty();
    }

    @Test
    @DisplayName("AUCUN SCHÉMA D'OUTIL NE PORTE DE SCORE : la certitude est une énumération de mots")
    void noSchemaCarriesAScore() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);

        for (AgentTool tool : catalog.toolsFor(userId, teamsTerminal())) {
            String schema = tool.inputSchema().toString().toLowerCase(java.util.Locale.ROOT);
            assertThat(schema)
                    .as("un chiffre donnerait une apparence de mesure à une interprétation (%s)",
                            tool.name())
                    .doesNotContain("score")
                    .doesNotContain("confidence")
                    .doesNotContain("probabilit")
                    .doesNotContain("pourcentage")
                    .doesNotContain("\"number\"")
                    .doesNotContain("\"integer\"");
        }
    }

    @Test
    @DisplayName("la panoplie d'un terminal Teams = les outils de LECTURE, puis ceux de PRÉSENTATION")
    void theBeltIsTheTwoListsInOrder() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);

        java.util.List<String> expected = new java.util.ArrayList<>(TeamsToolCatalog.CATALOG);
        expected.addAll(TeamsToolCatalog.PRESENTATION);

        assertThat(catalog.toolsFor(userId, teamsTerminal())).extracting(AgentTool::name)
                .containsExactlyElementsOf(expected);
    }

    // ------------------------------------------------------------------ F-91 : l'enregistrement

    @Test
    @DisplayName("les trois outils d'ENREGISTREMENT LOCAL sont donnés (F-91 / SF-91-02)")
    void theCaptureToolsAreGivenToo() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);

        assertThat(catalog.toolsFor(userId, teamsTerminal())).extracting(AgentTool::name)
                .contains(TeamsToolCatalog.CAPTURE_START, TeamsToolCatalog.CAPTURE_STOP,
                        TeamsToolCatalog.CAPTURE_STATUS);
    }

    @Test
    @DisplayName("sans le droit : AUCUN outil d'enregistrement non plus — la garde est la même")
    void noCaptureToolWithoutTheRight() {
        when(teamsAccess.hasAccess(userId)).thenReturn(false);

        assertThat(catalog.toolsFor(userId, teamsTerminal()))
                .extracting(AgentTool::name)
                .doesNotContain(TeamsToolCatalog.CAPTURE_START);
        assertThat(catalog.toolsFor(userId, teamsTerminal())).isEmpty();
    }

    @Test
    @DisplayName("la description de teams_capture_start DIT les deux gestes, et le non-garanti")
    void theStartDescriptionCarriesTheDoctrine() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);

        AgentTool start = catalog.toolsFor(userId, teamsTerminal()).stream()
                .filter(tool -> TeamsToolCatalog.CAPTURE_START.equals(tool.name()))
                .findFirst().orElseThrow();

        // C'est le SEUL endroit où le modèle apprend la différence de nature de cet outil.
        assertThat(start.description())
                .contains("SEUL outil du volet qui CRÉE")
                .contains("DEUX USAGES, DEUX GESTES")
                .contains("NE COCHE JAMAIS cette confirmation")
                .contains("de vive voix")
                .contains("recordingNotice");
        // Et l'usage est OBLIGATOIRE dans le schéma : il n'est jamais deviné.
        assertThat(start.inputSchema().toString()).contains("purpose");
        assertThat(String.valueOf(start.inputSchema().get("required"))).contains("purpose");
    }

    @Test
    @DisplayName("isCapture ne reconnaît QUE les trois outils qui créent un enregistrement")
    void onlyTheCaptureToolsAreCapture() {
        assertThat(TeamsToolCatalog.isCapture(TeamsToolCatalog.CAPTURE_START)).isTrue();
        assertThat(TeamsToolCatalog.isCapture(TeamsToolCatalog.CAPTURE_STOP)).isTrue();
        assertThat(TeamsToolCatalog.isCapture(TeamsToolCatalog.CAPTURE_STATUS)).isTrue();
        assertThat(TeamsToolCatalog.isCapture(TeamsToolCatalog.READ_CONVERSATION)).isFalse();
        assertThat(TeamsToolCatalog.isCapture("bash")).isFalse();
        assertThat(TeamsToolCatalog.isCapture(null)).isFalse();
    }

    @Test
    @DisplayName("isPresentation ne reconnaît QUE les trois outils qui posent un bloc")
    void onlyThePresentationToolsArePresentation() {
        assertThat(TeamsToolCatalog.isPresentation(TeamsToolCatalog.MEETING_CARD)).isTrue();
        assertThat(TeamsToolCatalog.isPresentation(TeamsToolCatalog.LIST)).isTrue();
        assertThat(TeamsToolCatalog.isPresentation(TeamsToolCatalog.MOMENTS)).isTrue();
        assertThat(TeamsToolCatalog.isPresentation(TeamsToolCatalog.STATUS)).isFalse();
        assertThat(TeamsToolCatalog.isPresentation("bash")).isFalse();
        assertThat(TeamsToolCatalog.isPresentation(null)).isFalse();
    }

    @Test
    @DisplayName("F-108 : isWrite ne reconnaît QUE les six outils qui écrivent dans Microsoft 365")
    void onlyTheWriteToolsAreWrite() {
        assertThat(TeamsToolCatalog.WRITE).containsExactly(
                TeamsToolCatalog.CREATE_FOLDER, TeamsToolCatalog.UPLOAD_FILE,
                TeamsToolCatalog.RENAME, TeamsToolCatalog.MOVE, TeamsToolCatalog.DELETE,
                TeamsToolCatalog.REPLACE_VERSION);
        TeamsToolCatalog.WRITE.forEach(tool -> assertThat(TeamsToolCatalog.isWrite(tool)).isTrue());
        // Ni lecture, ni capture, ni présentation, ni bash, ni null ne sont des écritures.
        assertThat(TeamsToolCatalog.isWrite(TeamsToolCatalog.READ_CONVERSATION)).isFalse();
        assertThat(TeamsToolCatalog.isWrite(TeamsToolCatalog.MEETING_RECORDING)).isFalse();
        assertThat(TeamsToolCatalog.isWrite(TeamsToolCatalog.CAPTURE_START)).isFalse();
        assertThat(TeamsToolCatalog.isWrite(TeamsToolCatalog.MEETING_CARD)).isFalse();
        assertThat(TeamsToolCatalog.isWrite("bash")).isFalse();
        assertThat(TeamsToolCatalog.isWrite(null)).isFalse();
        // Poster un message reste hors périmètre : il n'existe pas comme écriture.
        assertThat(TeamsToolCatalog.isWrite("teams_post_message")).isFalse();
    }

    @Test
    @DisplayName("F-108 / SF-108-03 : les deux outils fichiers sont donnés, et ce sont des LECTURES")
    void theFileReadingToolsAreGivenAndAreReads() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);

        assertThat(catalog.toolsFor(userId, teamsTerminal())).extracting(AgentTool::name)
                .contains(TeamsToolCatalog.LIST_FILES, TeamsToolCatalog.READ_FILE);
        assertThat(TeamsToolCatalog.isWrite(TeamsToolCatalog.LIST_FILES)).isFalse();
        assertThat(TeamsToolCatalog.isWrite(TeamsToolCatalog.READ_FILE)).isFalse();
        AgentTool read = catalog.toolsFor(userId, teamsTerminal()).stream()
                .filter(tool -> TeamsToolCatalog.READ_FILE.equals(tool.name()))
                .findFirst().orElseThrow();
        assertThat(read.description()).contains("CHROME").contains("aucune confirmation");
    }

    @Test
    @DisplayName("F-108 / SF-108-03 : sans le droit, aucun outil fichiers non plus")
    void noFileToolWithoutTheRight() {
        when(teamsAccess.hasAccess(userId)).thenReturn(false);

        assertThat(catalog.toolsFor(userId, teamsTerminal())).isEmpty();
    }

    @Test
    @DisplayName("F-108 / SF-108-04 : les six écritures sont données, sous le droit, et restent des écritures")
    void theWriteToolsAreGivenBehindTheRight() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);

        assertThat(catalog.toolsFor(userId, teamsTerminal())).extracting(AgentTool::name)
                .containsAll(TeamsToolCatalog.WRITE);
        assertThat(TeamsToolCatalog.CATALOG).containsAll(TeamsToolCatalog.WRITE);
        catalog.toolsFor(userId, teamsTerminal()).stream()
                .filter(tool -> TeamsToolCatalog.isWrite(tool.name()))
                .forEach(tool -> assertThat(tool.description()).contains("AUTORISER"));
        // Poster un message reste hors périmètre : aucun outil de ce genre n'est donné.
        assertThat(catalog.toolsFor(userId, teamsTerminal())).extracting(AgentTool::name)
                .noneMatch(name -> name.contains("post") || name.contains("message")
                        || name.contains("reply") || name.contains("react"));
        when(teamsAccess.hasAccess(userId)).thenReturn(false);
        assertThat(catalog.toolsFor(userId, teamsTerminal())).isEmpty();
    }

    @Test
    @DisplayName("F-108 / SF-108-04 : le libellé de chaque écriture — ancien/nouveau nom, fichier local, lieu lisible")
    void describeWriteCallNamesEverythingTheUserMustSee() {
        String general = "https://contoso.sharepoint.com/sites/ProjetIAM/Shared%20Documents/General";
        String plan = general + "/plan.docx";
        java.util.function.Function<java.util.Map<String, String>, java.util.function.Function<String, String>>
                args = map -> map::get;

        assertThat(TeamsToolCatalog.describeWriteCall(TeamsToolCatalog.CREATE_FOLDER,
                args.apply(java.util.Map.of("location", general, "name", "Livrables"))))
                .isEqualTo("Créer le dossier « Livrables » dans ProjetIAM › Shared Documents › General");
        assertThat(TeamsToolCatalog.describeWriteCall(TeamsToolCatalog.UPLOAD_FILE,
                args.apply(java.util.Map.of("file", "/home/u/secret/rapport.docx", "location", general,
                        "name", "anodin.docx"))))
                .isEqualTo("Déposer le fichier local « /home/u/secret/rapport.docx » sous le nom "
                        + "« anodin.docx » dans ProjetIAM › Shared Documents › General");
        assertThat(TeamsToolCatalog.describeWriteCall(TeamsToolCatalog.RENAME,
                args.apply(java.util.Map.of("target", plan, "name", "plan-v2.docx"))))
                .isEqualTo("Renommer « plan.docx » en « plan-v2.docx » dans ProjetIAM › Shared Documents › General");
        assertThat(TeamsToolCatalog.describeWriteCall(TeamsToolCatalog.MOVE,
                args.apply(java.util.Map.of("target", plan, "destination", general + "/Archives"))))
                .isEqualTo("Déplacer « plan.docx » vers ProjetIAM › Shared Documents › General › Archives");
        assertThat(TeamsToolCatalog.describeWriteCall(TeamsToolCatalog.DELETE,
                args.apply(java.util.Map.of("target", plan))))
                .isEqualTo("Supprimer « plan.docx » dans ProjetIAM › Shared Documents › General (corbeille du site)");
        assertThat(TeamsToolCatalog.describeWriteCall(TeamsToolCatalog.REPLACE_VERSION,
                args.apply(java.util.Map.of("target", plan, "file", "/home/u/plan.docx"))))
                .isEqualTo("Remplacer la version de « plan.docx » dans ProjetIAM › Shared Documents › General "
                        + "par le fichier local « /home/u/plan.docx »");
        // Un emplacement déjà en clair est gardé ; OneDrive est nommé.
        assertThat(TeamsToolCatalog.readableLocation("Équipe Projet IAM › Général › Fichiers"))
                .isEqualTo("Équipe Projet IAM › Général › Fichiers");
        assertThat(TeamsToolCatalog.readableLocation(
                "https://contoso-my.sharepoint.com/personal/f_x/Documents/Livrables"))
                .isEqualTo("OneDrive › Documents › Livrables");
    }

    @Test
    @DisplayName("F-108 : describeWrite nomme l'action et l'emplacement en clair")
    void describeWriteNamesActionAndLocation() {
        assertThat(TeamsToolCatalog.describeWrite(TeamsToolCatalog.CREATE_FOLDER, "Livrables",
                "Équipe Projet IAM › Général › Fichiers"))
                .isEqualTo("Créer le dossier « Livrables » dans Équipe Projet IAM › Général › Fichiers");
        assertThat(TeamsToolCatalog.describeWrite(TeamsToolCatalog.DELETE, "vieux.docx", "Général"))
                .isEqualTo("Supprimer « vieux.docx » dans Général");
        assertThat(TeamsToolCatalog.describeWrite(TeamsToolCatalog.MOVE, "note.md", "Archives"))
                .isEqualTo("Déplacer « note.md » vers Archives");
        // Emplacement inconnu : la phrase reste lisible, sans « dans » orphelin.
        assertThat(TeamsToolCatalog.describeWrite(TeamsToolCatalog.RENAME, "a.txt", ""))
                .isEqualTo("Renommer « a.txt »");
    }

    /**
     * F-107 / SF-107-06 : <b>l'administrateur a tout</b>, y compris hors requête. La chaîne est la
     * vraie ({@link TeamsAccessService} → {@code SpaceEntitlementService}) et <b>aucun principal</b>
     * n'est présent — le cas d'une relance du runner ou de la synchro de nuit, où le bypass par
     * principal ne voyait rien et où l'agent d'un administrateur perdait ses outils.
     */
    @Test
    @DisplayName("SF-107-06 : ADMIN sans option et sans principal — les outils teams_* sont donnés")
    void anAdministratorGetsTheToolsWithoutAPrincipal() {
        fr.claudegateway.auth.CurrentUser currentUser =
                org.mockito.Mockito.mock(fr.claudegateway.auth.CurrentUser.class);
        when(currentUser.principal()).thenReturn(java.util.Optional.empty());
        fr.claudegateway.billing.SubscriptionService subscriptions =
                org.mockito.Mockito.mock(fr.claudegateway.billing.SubscriptionService.class);
        fr.claudegateway.access.AccessGrantService grants =
                org.mockito.Mockito.mock(fr.claudegateway.access.AccessGrantService.class);
        fr.claudegateway.billing.AdministratorEntitlement administrators =
                org.mockito.Mockito.mock(fr.claudegateway.billing.AdministratorEntitlement.class);
        when(administrators.isAdministrator(userId)).thenReturn(true);
        TeamsToolCatalog real = new TeamsToolCatalog(new TeamsAccessService(currentUser,
                new fr.claudegateway.billing.SpaceEntitlementService(subscriptions, grants, administrators)));

        assertThat(real.toolsFor(userId, teamsTerminal()))
                .extracting(AgentTool::name)
                .contains(TeamsToolCatalog.STATUS);

        when(administrators.isAdministrator(userId)).thenReturn(false);
        when(subscriptions.getOrCreateForUser(userId)).thenReturn(fr.claudegateway.billing.Subscription
                .builder().userId(userId).planCode(fr.claudegateway.billing.PlanCode.GOLD)
                .status(fr.claudegateway.billing.SubscriptionStatus.ACTIVE).build());
        assertThat(real.toolsFor(userId, teamsTerminal()))
                .as("USER sans option : refus inchangé").isEmpty();
    }
}
