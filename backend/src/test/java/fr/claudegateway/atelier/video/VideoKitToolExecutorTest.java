package fr.claudegateway.atelier.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.ProjectFileDeposit;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.diagrams.DiagramRendererUnavailableException;

/** F-215 / SF-215-03 — {@code video_briques} : guide, briques et icônes déposés dans le sujet du poste. */
class VideoKitToolExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private ProjectFileDeposit deposit;
    private VideoIconSource icons;
    private VideoKitToolExecutor executor;
    private Workspace workspace;
    private final Map<String, byte[]> deposited = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        deposit = mock(ProjectFileDeposit.class);
        icons = mock(VideoIconSource.class);
        when(icons.isAvailable()).thenReturn(true);
        when(deposit.deposit(eq(userId), any(), anyString(), anyString(), any(), anyString(), eq("video_briques")))
                .thenAnswer(invocation -> {
                    deposited.put(invocation.getArgument(3), invocation.getArgument(4));
                    return invocation.getArgument(3);
                });
        executor = new VideoKitToolExecutor(deposit, icons);
        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setUserId(userId);
        workspace.setHostId(UUID.randomUUID());
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
    }

    private ObjectNode input(String dir) {
        return MAPPER.createObjectNode().put("dossier", dir);
    }

    @Test
    @DisplayName("nominal : le guide (même ressource que le paquet), les briques et les icônes sont déposés")
    void depositsGuideBriquesAndIcons() {
        when(icons.fetch(List.of("aws/network/route-53", "k8s/compute/pod", "aws/network/nope"))).thenReturn(
                new VideoIconSource.Batch(List.of(new VideoIconSource.Icon("aws/network/route-53", new byte[] {1}),
                        new VideoIconSource.Icon("k8s/compute/pod", new byte[] {2})),
                        List.of("aws/network/nope"), List.of()));
        ObjectNode in = input("video/cours-eks");
        in.putArray("icones").add("aws/network/route-53").add("K8S/compute/pod").add("aws/network/nope");

        VideoKitToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c1", in);

        assertThat(outcome.error()).as(outcome.content()).isFalse();
        assertThat(deposited.keySet()).containsExactly(".claude/guides/cours-video.md",
                "video/cours-eks/briques/charte.css", "video/cours-eks/briques/briques.js",
                "video/cours-eks/briques/LISEZMOI.md", "video/cours-eks/icons/route-53.png",
                "video/cours-eks/icons/pod.png");
        String guide = new String(deposited.get(".claude/guides/cours-video.md"), StandardCharsets.UTF_8);
        assertThat(guide).isEqualTo(new String(executor.resource(
                fr.claudegateway.governance.GovernancePackageSeeder.GUIDE_COURS_VIDEO_CLASSPATH), StandardCharsets.UTF_8));
        assertThat(new String(deposited.get("video/cours-eks/briques/charte.css"), StandardCharsets.UTF_8))
                .contains("--orange: #E07B39").contains(".zone").contains(".pkt").contains(".dnsq").contains(".rt");
        assertThat(new String(deposited.get("video/cours-eks/briques/briques.js"), StandardCharsets.UTF_8))
                .contains("paused: true").contains("__timelines").contains("strokeDashoffset").contains("motA");
        assertThat(outcome.content()).contains("icons/route-53.png ← aws/network/route-53")
                .contains("Introuvables : aws/network/nope").contains("chercher")
                .contains("Lis maintenant le guide");
    }

    @Test
    @DisplayName("chercher seul : des noms, rien de déposé")
    void searchOnlyDepositsNothing() {
        when(icons.search("load balancer")).thenReturn(List.of("aws/network/elb-application-load-balancer"));

        VideoKitToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c1",
                MAPPER.createObjectNode().put("chercher", "load balancer"));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content()).contains("- aws/network/elb-application-load-balancer");
        verify(deposit, never()).deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("aucune icône trouvée : l'agent est orienté vers d'autres mots")
    void searchWithoutResultSuggestsOtherWords() {
        when(icons.search("zzz")).thenReturn(List.of());
        assertThat(executor.execute(userId, workspace, "c1", MAPPER.createObjectNode().put("chercher", "zzz"))
                .content()).contains("Aucune icône").contains("en anglais");
    }

    @Test
    @DisplayName("service d'icônes absent ou muet : briques et guide déposés quand même, le manque est dit")
    void iconServiceDownStillDepositsTheKit() {
        when(icons.fetch(any())).thenThrow(new DiagramRendererUnavailableException("muet"));
        ObjectNode in = input("video/c");
        in.putArray("icones").add("aws/network/route-53");

        VideoKitToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c1", in);

        assertThat(outcome.error()).isFalse();
        assertThat(deposited).hasSize(4);
        assertThat(outcome.content()).contains("Icônes indisponibles pour l'instant");

        when(icons.isAvailable()).thenReturn(false);
        assertThat(executor.execute(userId, workspace, "c2", MAPPER.createObjectNode().put("chercher", "dns"))
                .error()).isTrue();
    }

    @Test
    @DisplayName("dossier absent, absolu, avec « .. » ou un lecteur : refusé, rien de déposé")
    void unsafeDirectoriesAreRefused() {
        for (String dir : List.of("", "/etc", "../voisin", "video/../../x", "C:/Users", "vidéo cours")) {
            assertThat(executor.execute(userId, workspace, "c", input(dir)).error()).as(dir).isTrue();
        }
        assertThat(VideoKitToolExecutor.cleanDir("./video/cours/")).isEqualTo("video/cours");
        assertThat(deposited).isEmpty();
    }

    @Test
    @DisplayName("plus de 40 icônes : refusé avec le motif")
    void tooManyIconsAreRefused() {
        ObjectNode in = input("video/c");
        var array = in.putArray("icones");
        for (int i = 0; i < 41; i++) {
            array.add("aws/network/x" + i);
        }
        VideoKitToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c", in);
        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("40 au plus");
        assertThat(deposited).isEmpty();
    }

    @Test
    @DisplayName("dépôt en échec (poste hors ligne, runner ancien) : erreur dite")
    void failedDepositIsSaid() {
        when(deposit.deposit(eq(userId), any(), anyString(), anyString(), any(), anyString(), eq("video_briques")))
                .thenReturn(null);
        VideoKitToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c", input("video/c"));
        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("Dépôt des briques sur le poste impossible");
    }

    @Test
    @DisplayName("sujet hébergé : refusé (les briques vont sur un poste)")
    void hostedSubjectIsRefused() {
        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        assertThat(executor.execute(userId, workspace, "c", input("video/c")).error()).isTrue();
        assertThat(deposited).isEmpty();
    }

    @Test
    @DisplayName("homonymes de deux fournisseurs : pas d'écrasement")
    void homonymsDoNotOverwriteEachOther() {
        var used = new HashSet<String>();
        List<String> files = new ArrayList<>();
        files.add(VideoKitToolExecutor.iconFileName("aws/network/firewall", used));
        files.add(VideoKitToolExecutor.iconFileName("azure/network/firewall", used));
        assertThat(files).containsExactly("firewall.png", "azure-firewall.png");
        assertThat(VideoKitToolExecutor.iconNames(MAPPER.createObjectNode().put("icones", "a/b/c, a/b/c d/e/f")))
                .containsExactly("a/b/c", "d/e/f");
    }
}
