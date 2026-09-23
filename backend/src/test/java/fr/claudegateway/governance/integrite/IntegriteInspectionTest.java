package fr.claudegateway.governance.integrite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.governance.GovernanceFileKind;
import fr.claudegateway.governance.GovernanceHostFiles;
import fr.claudegateway.governance.GovernanceHostFiles.HostFileRead;
import fr.claudegateway.governance.GovernanceHostFiles.Presence;
import fr.claudegateway.governance.GovernanceHostRef;
import fr.claudegateway.governance.GovernanceHostScope;
import fr.claudegateway.governance.GovernanceMapDestinations;
import fr.claudegateway.governance.GovernancePackageFile;
import fr.claudegateway.governance.GovernanceProjectFiles;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * F-95 / SF-95-02 — <b>l'intégrité d'un poste, lue sur la machine</b>.
 *
 * <p>Ce que ces tests protègent :</p>
 * <ul>
 *   <li>une machine muette rend un rapport <b>silencieux</b>, jamais un rapport sain — afficher
 *       « tout va bien » sur un poste dont on n'a rien lu serait le pire des deux résultats ;</li>
 *   <li>un dépôt égaré parmi les sujets ne se voit pas réclamer un {@code STATE.md} : ce n'en est
 *       pas un, et la correction dit déjà où il doit aller ;</li>
 *   <li>la commande émise est une <b>constante</b>, sans interpolation du nom du dépôt ;</li>
 *   <li>rien n'est conclu de ce qui n'a pas été lu : ni d'un fichier illisible, ni d'un listage
 *       probablement tronqué, ni d'un budget épuisé.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IntegriteInspectionTest {

    @Mock
    private GovernanceMapDestinations destinations;
    @Mock
    private GovernanceHostFiles hostFiles;
    @Mock
    private GovernanceHostScope hostScope;
    @Mock
    private GovernanceProjectFiles projectFiles;
    @Mock
    private RunnerToolGateway gateway;
    @Mock
    private RunnerAuditService auditService;

    private IntegriteInspection inspection;

    private final UUID alice = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final GovernanceHostRef host = GovernanceHostRef.of(hostId);
    private Workspace projet;

    private static final String CARTE_SAINE = "# Accès\n\n## VPN\n\nAucun, constaté le 2026-09-13.\n";
    private static final String STATE_SAIN = "# État\n\n## Statut\n\n`en cours`\n\n## Promotions\n";

    @BeforeEach
    void setUp() {
        inspection = new IntegriteInspection(destinations, hostFiles, hostScope, projectFiles,
                gateway, auditService);
        projet = projet("migration-dns", "Migration DNS");

        when(hostFiles.supports(host)).thenReturn(true);
        when(destinations.filesOf(alice, host)).thenReturn(carte("acces.md"));
        when(destinations.citedForProject(eq(alice), any())).thenReturn("acces.md");
        when(hostFiles.read(alice, host, "acces.md")).thenReturn(presente(CARTE_SAINE));
        when(hostScope.projectsOf(alice, host)).thenReturn(List.of(projet));
        when(projectFiles.listPaths(alice, projet))
                .thenReturn(Optional.of(Set.of("STATE.md", "NOTES.md")));
        when(projectFiles.read(alice, projet, "STATE.md")).thenReturn(Optional.of(STATE_SAIN));
    }

    // ------------------------------------------------------------- le silence

    @Test
    @DisplayName("un poste sans machine n'est pas inspecté, et rien n'est émis")
    void aHostWithoutMachineIsNeverProbed() {
        when(hostFiles.supports(GovernanceHostRef.HOSTED)).thenReturn(false);

        IntegriteRapport rapport = inspection.dePoste(alice, GovernanceHostRef.HOSTED);

        assertThat(rapport.inspecte()).isFalse();
        verifyNoInteractions(gateway);
        verify(hostFiles, never()).read(any(), eq(GovernanceHostRef.HOSTED), anyString());
    }

    @Test
    @DisplayName("un poste sans gouvernance active n'attend aucune carte")
    void anUngovernedHostIsSilent() {
        when(destinations.filesOf(alice, host)).thenReturn(Map.of());

        assertThat(inspection.dePoste(alice, host).inspecte()).isFalse();
        verify(hostFiles, never()).read(any(), any(), anyString());
    }

    @Test
    @DisplayName("une machine muette rend un rapport silencieux, pas un rapport sain")
    void anUnreachableMachineIsSilentNotHealthy() {
        when(destinations.filesOf(alice, host)).thenReturn(carte("acces.md", "reseau.md"));
        when(hostFiles.read(alice, host, "acces.md"))
                .thenReturn(new HostFileRead(Presence.UNREACHABLE, null, false));

        IntegriteRapport rapport = inspection.dePoste(alice, host);

        assertThat(rapport.inspecte()).isFalse();
        assertThat(rapport.rienASignaler()).isFalse();
        verify(hostFiles, never()).read(alice, host, "reseau.md");
    }

    @Test
    @DisplayName("un poste sain est inspecté et ne dit rien")
    void aHealthyHostSaysNothing() {
        IntegriteRapport rapport = inspection.dePoste(alice, host);

        assertThat(rapport.inspecte()).isTrue();
        assertThat(rapport.constats()).isEmpty();
    }

    // ---------------------------------------------------------------- la carte

    @Test
    @DisplayName("un fichier de carte absent est une erreur, un fichier illisible ne dit rien")
    void missingMapFileIsAnErrorUnreadableIsNot() {
        when(destinations.filesOf(alice, host)).thenReturn(carte("acces.md", "reseau.md"));
        when(hostFiles.read(alice, host, "acces.md"))
                .thenReturn(new HostFileRead(Presence.ABSENT, null, false));
        when(hostFiles.read(alice, host, "reseau.md"))
                .thenReturn(new HostFileRead(Presence.UNKNOWN, null, false));

        IntegriteRapport rapport = inspection.dePoste(alice, host);

        assertThat(rapport.erreurs()).extracting(IntegriteConstat::regle)
                .containsExactly(IntegriteRegle.CARTE_ABSENTE);
        assertThat(rapport.erreurs().get(0).cible()).isEqualTo("acces.md");
    }

    @Test
    @DisplayName("une carte sans section est signalée, sans bloquer")
    void aMapFileWithoutSectionsIsWarnedAbout() {
        when(hostFiles.read(alice, host, "acces.md")).thenReturn(presente("# Accès\n\nEn vrac.\n"));

        IntegriteRapport rapport = inspection.dePoste(alice, host);

        assertThat(rapport.bloque()).isFalse();
        assertThat(rapport.avertissements()).extracting(IntegriteConstat::regle)
                .contains(IntegriteRegle.CARTE_SANS_STRUCTURE);
    }

    @Test
    @DisplayName("l'index n'est signalé qu'au-delà du seuil, et un gabarit livré compte zéro fait")
    void onlyAnOverloadedIndexIsFlagged() {
        when(destinations.filesOf(alice, host)).thenReturn(carte("README.md"));
        when(hostFiles.read(alice, host, "README.md")).thenReturn(presente(index(10)));

        assertThat(inspection.dePoste(alice, host).constats()).isEmpty();

        when(hostFiles.read(alice, host, "README.md"))
                .thenReturn(presente(index(IntegriteConstat.SEUIL_INDEX_FAITS + 5)));

        assertThat(inspection.dePoste(alice, host).avertissements())
                .extracting(IntegriteConstat::regle)
                .containsExactly(IntegriteRegle.CARTE_INDEX_SURCHARGE);
    }

    // -------------------------------------------------------------- les projets

    @Test
    @DisplayName("un dépôt égaré parmi les sujets est une erreur, et on ne lui réclame pas de STATE")
    void aRepositoryAmongSubjectsIsAnErrorAndOwesNoState() {
        when(projectFiles.listPaths(alice, projet))
                .thenReturn(Optional.of(Set.of(".git/HEAD", "src/main.java")));

        IntegriteRapport rapport = inspection.dePoste(alice, host);

        assertThat(rapport.erreurs()).extracting(IntegriteConstat::regle)
                .containsExactly(IntegriteRegle.PROJET_DEPOT_GIT);
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("un projet sans STATE.md est une erreur ; un projet illisible ne dit rien")
    void aProjectWithoutStateIsAnError() {
        when(projectFiles.listPaths(alice, projet)).thenReturn(Optional.of(Set.of("NOTES.md")));

        assertThat(inspection.dePoste(alice, host).erreurs()).extracting(IntegriteConstat::regle)
                .containsExactly(IntegriteRegle.PROJET_SANS_STATE);

        when(projectFiles.listPaths(alice, projet)).thenReturn(Optional.empty());

        assertThat(inspection.dePoste(alice, host).constats()).isEmpty();
    }

    @Test
    @DisplayName("un listage qui frôle la borne du runner ne fait pas conclure une absence")
    void aTruncatedListingNeverConcludesAbsence() {
        Set<String> enorme = new java.util.HashSet<>();
        for (int i = 0; i < IntegriteInspection.SEUIL_LISTAGE_INCOMPLET + 1; i++) {
            enorme.add("fichier-" + i + ".txt");
        }
        when(projectFiles.listPaths(alice, projet)).thenReturn(Optional.of(enorme));

        assertThat(inspection.dePoste(alice, host).constats()).isEmpty();
    }

    @Test
    @DisplayName("la dette distingue la clôture de l'en-cours, et PLAN-ACTION.md compte")
    void debtSeparatesClosureFromWorkAndCountsBothFiles() {
        when(projectFiles.listPaths(alice, projet))
                .thenReturn(Optional.of(Set.of("STATE.md", "PLAN-ACTION.md")));
        when(projectFiles.read(alice, projet, "STATE.md"))
                .thenReturn(Optional.of("## Statut\n\n`en cours`\n\n- [ ] bastion\n"));
        when(projectFiles.read(alice, projet, "PLAN-ACTION.md"))
                .thenReturn(Optional.of("- [ ] plage 10.0.4.0/24\n"));

        IntegriteRapport enCours = inspection.dePoste(alice, host);

        assertThat(enCours.bloque()).isFalse();
        assertThat(enCours.avertissements()).extracting(IntegriteConstat::regle)
                .containsExactly(IntegriteRegle.DETTE_EN_COURS);
        assertThat(enCours.avertissements().get(0).message()).contains("2 cases");

        when(projectFiles.read(alice, projet, "STATE.md"))
                .thenReturn(Optional.of("## Statut\n\n`clos`\n\n- [ ] bastion\n"));

        IntegriteRapport clos = inspection.dePoste(alice, host);

        assertThat(clos.erreurs()).extracting(IntegriteConstat::regle)
                .containsExactly(IntegriteRegle.DETTE_A_LA_CLOTURE);
    }

    // -------------------------------------------------- les notes chez le client

    @Test
    @DisplayName("un .md non versionné à la racine d'un dépôt client est une erreur")
    void anUntrackedNoteAtTheRootOfAClientRepositoryIsAnError() {
        Workspace depot = depotClient();
        when(gateway.bash(any(), anyString(), anyString(), any(), anyLong(), any()))
                .thenReturn(sortie("?? notes.md\n M src/Main.java\n"));

        IntegriteRapport rapport = inspection.dePoste(alice, host);

        assertThat(rapport.erreurs()).extracting(IntegriteConstat::regle)
                .containsExactly(IntegriteRegle.NOTE_HORS_DEPOT);
        assertThat(rapport.erreurs().get(0).message()).contains("notes.md");
        assertThat(rapport.erreurs().get(0).cible()).isEqualTo(depot.getProjectPath());
    }

    @Test
    @DisplayName("la commande émise est la constante du serveur, sans interpolation")
    void theEmittedCommandIsAServerConstant() {
        depotClient();
        when(gateway.bash(any(), anyString(), anyString(), any(), anyLong(), any()))
                .thenReturn(sortie(""));

        inspection.dePoste(alice, host);

        ArgumentCaptor<String> commande = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RunnerTarget> cible = ArgumentCaptor.forClass(RunnerTarget.class);
        verify(gateway).bash(cible.capture(), anyString(), commande.capture(), any(), anyLong(),
                any());
        assertThat(commande.getValue()).isEqualTo("git status --porcelain");
        assertThat(cible.getValue().projectPath()).isEqualTo("repos/portail-client");
    }

    @Test
    @DisplayName("ni un fichier hors racine, ni un fichier qui n'est pas un .md")
    void onlyRootMarkdownFilesCount() {
        depotClient();
        when(gateway.bash(any(), anyString(), anyString(), any(), anyLong(), any()))
                .thenReturn(sortie("?? src/notes.md\n?? brouillon.txt\n?? .env\n"));

        assertThat(inspection.dePoste(alice, host).constats()).isEmpty();
    }

    @Test
    @DisplayName("bash refusé par la machine ne produit aucun constat")
    void aRefusedBashSaysNothing() {
        depotClient();
        when(gateway.bash(any(), anyString(), anyString(), any(), anyLong(), any()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.UNSUPPORTED_TOOL,
                        "L'exécution de commandes n'est pas activée sur ce runner."));

        assertThat(inspection.dePoste(alice, host).constats()).isEmpty();
    }

    // ----------------------------------------------------------- les liens morts

    @Test
    @DisplayName("une référence introuvable est un lien mort, un dossier n'est même pas lu (SF-148-09)")
    void aMissingReferenceIsADeadLinkADirectoryIsNot() {
        when(hostFiles.read(alice, host, "acces.md")).thenReturn(presente(
                "# Accès\n\n## VPN\n\nLe sujet vit dans `vieux-sujet/STATE.md`, "
                        + "et les dépôts dans `repos/portail-client`.\n"));
        when(hostFiles.presence(alice, host, "vieux-sujet/STATE.md")).thenReturn(Presence.ABSENT);

        IntegriteRapport rapport = inspection.dePoste(alice, host);

        assertThat(rapport.avertissements()).extracting(IntegriteConstat::regle)
                .containsExactly(IntegriteRegle.CARTE_LIEN_MORT);
        assertThat(rapport.avertissements().get(0).message()).contains("vieux-sujet/STATE.md");
        // Un dossier n'est pas un fichier de carte : on ne le lit même pas (plus de « is_directory »).
        verify(hostFiles, never()).presence(alice, host, "repos/portail-client");
    }

    // ----------------------------------- F-125 / SF-125-03 : carte non déclarée, tolérée

    @Test
    @DisplayName("un .md non déclaré à la racine est un avertissement toléré, jamais un blocage")
    void anUndeclaredRootMapFileIsAToleratedWarning() {
        when(hostFiles.listRoot(alice, host))
                .thenReturn(List.of("acces.md", "enjeux.md", "migration-dns"));

        IntegriteRapport rapport = inspection.dePoste(alice, host);

        assertThat(rapport.bloque()).isFalse();
        assertThat(rapport.avertissements()).extracting(IntegriteConstat::regle)
                .containsExactly(IntegriteRegle.CARTE_NON_DECLAREE);
        assertThat(rapport.avertissements().get(0).cible()).isEqualTo("enjeux.md");
        assertThat(rapport.avertissements().get(0).message()).contains("toléré");
    }

    @Test
    @DisplayName("un fichier référencé par l'index README n'est pas « non déclaré »")
    void aFileReferencedByTheIndexIsNotUndeclared() {
        when(destinations.filesOf(alice, host)).thenReturn(carte("README.md"));
        when(hostFiles.read(alice, host, "README.md")).thenReturn(presente(
                "# La carte\n\n## Domaines\n\n- [enjeux](enjeux.md) : les enjeux du poste\n"));
        when(hostFiles.listRoot(alice, host)).thenReturn(List.of("README.md", "enjeux.md"));

        assertThat(inspection.dePoste(alice, host).constats()).isEmpty();
    }

    @Test
    @DisplayName("un fichier de carte attendu (paquet actif) n'est jamais « non déclaré »")
    void anExpectedMapFileIsNeverUndeclared() {
        when(hostFiles.listRoot(alice, host)).thenReturn(List.of("acces.md"));

        assertThat(inspection.dePoste(alice, host).constats()).isEmpty();
    }

    @Test
    @DisplayName("un .md sous un sous-dossier de projet est ignoré : la règle porte sur la racine")
    void aMarkdownUnderAProjectIsIgnored() {
        // listRoot ne rend que le premier niveau ; un chemin avec « / » n'en fait pas partie.
        when(hostFiles.listRoot(alice, host)).thenReturn(List.of("acces.md"));

        assertThat(inspection.dePoste(alice, host).constats()).isEmpty();
    }

    @Test
    @DisplayName("une racine non listable ne fait conclure aucun fichier non déclaré")
    void anUnlistableRootConcludesNothing() {
        when(hostFiles.listRoot(alice, host)).thenReturn(List.of());

        assertThat(inspection.dePoste(alice, host).constats()).isEmpty();
    }

    // ---------------------------------------------------------------- le budget

    @Test
    @DisplayName("le nombre d'appels est borné, et le budget épuisé n'invente aucun constat")
    void theNumberOfCallsIsBounded() {
        String[] fichiers = new String[IntegriteInspection.MAX_APPELS + 10];
        for (int i = 0; i < fichiers.length; i++) {
            fichiers[i] = "domaine-" + i + ".md";
            when(hostFiles.read(alice, host, fichiers[i])).thenReturn(presente(CARTE_SAINE));
        }
        when(destinations.filesOf(alice, host)).thenReturn(carte(fichiers));

        IntegriteRapport rapport = inspection.dePoste(alice, host);

        assertThat(rapport.constats()).isEmpty();
        verify(hostFiles, never()).read(alice, host,
                "domaine-" + (IntegriteInspection.MAX_APPELS + 5) + ".md");
    }

    @Test
    @DisplayName("le poste d'un projet d'autrui ne rend rien et n'émet aucun appel")
    void anotherUsersProjectYieldsNothing() {
        UUID bob = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        when(hostScope.hostOf(bob, workspaceId))
                .thenThrow(new IllegalStateException("projet non possédé"));

        assertThat(inspection.deProjet(bob, workspaceId).inspecte()).isFalse();
        verifyNoInteractions(gateway);
        verify(hostFiles, never()).read(eq(bob), any(), anyString());
    }

    // ---------------------------------------------------------------- fabriques

    private Workspace projet(String chemin, String nom) {
        return Workspace.builder().id(UUID.randomUUID()).userId(alice).hostId(hostId).name(nom)
                .projectPath(chemin).build();
    }

    /** Un dépôt client rangé à sa place, avec son {@code .git/} : le seul cas où bash part. */
    private Workspace depotClient() {
        Workspace depot = projet("repos/portail-client", "Portail client");
        when(hostScope.projectsOf(alice, host)).thenReturn(List.of(depot));
        when(projectFiles.listPaths(alice, depot))
                .thenReturn(Optional.of(Set.of(".git/HEAD", "README.md")));
        return depot;
    }

    /** Une sortie de commande aboutie, telle que le runner la rend. */
    private static RunnerCallResult sortie(String contenu) {
        return new RunnerCallResult(true, contenu, false, 0, 12L, null, null, null, contenu, false);
    }

    private static HostFileRead presente(String contenu) {
        return new HostFileRead(Presence.PRESENT, contenu, false);
    }

    private static Map<String, GovernancePackageFile> carte(String... chemins) {
        Map<String, GovernancePackageFile> fichiers = new java.util.LinkedHashMap<>();
        for (String chemin : chemins) {
            fichiers.put(chemin, GovernancePackageFile.builder().id(UUID.randomUUID())
                    .path(chemin).kind(GovernanceFileKind.MAP).content("").build());
        }
        return fichiers;
    }

    /** Un index portant {@code faits} faits — des lignes que {@code GovernanceMapDigest} compte. */
    private static String index(int faits) {
        StringBuilder contenu = new StringBuilder("# La carte du poste\n\n## Contacts\n\n");
        for (int i = 0; i < faits; i++) {
            contenu.append("- contact ").append(i).append(", constaté le 2026-09-13\n");
        }
        return contenu.toString();
    }
}
