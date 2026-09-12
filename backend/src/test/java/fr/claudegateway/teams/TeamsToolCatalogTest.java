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
}
