package fr.claudegateway.jarvis.state.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.actions.InterlocuteurService;
import fr.claudegateway.atelier.recall.CaptureRecall;
import fr.claudegateway.jarvis.state.JarvisConversationLookup;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-247 / SF-247-13 — l'outil conversations et l'union de recall : datés par la passerelle, isolés, bornés. */
@SpringBootTest
@ActiveProfiles("test")
class JarvisConversationIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private JarvisConversationLookup lookup;
    @Autowired private CaptureRecall recall;
    @Autowired private JarvisCaptureStore store;
    @Autowired private JarvisStateRegistry registry;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private UserRepository users;
    @Autowired private InterlocuteurService interlocutors;

    private UUID alice;
    private UUID bob;
    private UUID cagip;
    private UUID edenred;
    private Workspace sso;

    @BeforeEach
    void setUp() {
        alice = user();
        bob = user();
        cagip = host(alice, "CAGIP");
        edenred = host(alice, "EdenRed");
        sso = workspaces.save(Workspace.builder().userId(alice).name("SSO").hostId(cagip)
                .createdAt(OffsetDateTime.now()).build());
        Workspace pay = workspaces.save(Workspace.builder().userId(alice).name("Paiement").hostId(edenred)
                .createdAt(OffsetDateTime.now()).build());
        interlocutors.resolve(alice, cagip, "Rémi Dupont");
        OffsetDateTime yesterday = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).withHour(13).withMinute(32);
        store.save(alice, cagip, sso.getId(), UUID.randomUUID(), "TEAMS", "Migration SSO", yesterday.plusHours(2),
                "LUE", BigDecimal.ZERO, List.of(
                        new JarvisCaptureStore.NewLine("Rémi Dupont", true, yesterday, false,
                                "Le ticket PROJ-123 passe en revue demain."),
                        new JarvisCaptureStore.NewLine("Sophie Martin", false, yesterday.plusMinutes(3), false,
                                "La recette est validée.")));
        store.save(alice, edenred, pay.getId(), UUID.randomUUID(), "MAIL", "Facturation", yesterday, "LUE",
                BigDecimal.ZERO, List.of(new JarvisCaptureStore.NewLine("Karim", false, yesterday, true,
                        "Le lot de factures part vendredi.")));
    }

    private UUID user() {
        return users.save(User.builder().email("conv-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build()).getId();
    }

    private UUID host(UUID userId, String name) {
        return hosts.save(RunnerHost.builder().userId(userId).name(name).rootName("dev").os("linux").shell("posix")
                .elevated(false).build()).getId();
    }

    private static ObjectNode input(String recherche, String interlocuteur, String client) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("recherche", recherche);
        n.put("interlocuteur", interlocuteur);
        n.put("client", client);
        return n;
    }

    @Test
    @DisplayName("« Qu'a dit Rémi ? » : la personne résolue par la passerelle, la ligne datée « hier 14:32 »")
    void bySpeaker() {
        String out = lookup.read(alice, cagip, input("", "remi", ""), false);
        assertThat(out).startsWith(JarvisConversationLookup.OPEN).endsWith(JarvisConversationLookup.CLOSE);
        assertThat(out).contains("Rémi Dupont").contains("PROJ-123").contains("capture Teams du")
                .contains("« Migration SSO »").contains("hier ").doesNotContain("recette");
    }

    @Test
    @DisplayName("Recherche par mots, client nommé, plusieurs clients ; rien de capturé est dit")
    void byWordsAndClients() {
        assertThat(lookup.read(alice, cagip, input("recette", "", ""), false)).contains("Sophie Martin")
                .doesNotContain("PROJ-123");
        assertThat(lookup.read(alice, cagip, input("factures", "", "edenred"), false)).contains("vers ")
                .contains("courriel");
        assertThat(lookup.read(alice, null, input("", "", ""), false)).contains("(CAGIP)").contains("(EdenRed)");
        assertThat(lookup.read(alice, cagip, input("kubernetes", "", ""), false)).contains("Rien de capturé");
    }

    @Test
    @DisplayName("Isolation, présentation, coupure : rien d'un autre compte, rien en présentation, rien après couper")
    void isolation() {
        assertThat(lookup.read(bob, cagip, input("", "", ""), false)).doesNotContain("PROJ-123");
        assertThat(lookup.read(alice, cagip, input("", "", ""), true))
                .isEqualTo(JarvisConversationLookup.PRESENTATION);
        assertThat(recall.extracts(bob, sso.getId(), "PROJ-123")).isEmpty();
        registry.setEnabled(alice, cagip, false);
        assertThat(lookup.read(alice, cagip, input("", "", ""), false)).contains("Rien de capturé");
        registry.setEnabled(alice, cagip, true);
    }

    @Test
    @DisplayName("recall en union : les captures du sujet seulement, balisées, datées")
    void recallUnion() {
        String found = recall.extracts(alice, sso.getId(), "PROJ-123");
        assertThat(found).contains("Captures de conversations").contains("Rémi Dupont").contains("PROJ-123");
        assertThat(recall.extracts(alice, sso.getId(), "factures")).isEmpty();
        assertThat(recall.extracts(alice, sso.getId(), " ")).isEmpty();
    }

    @Test
    @DisplayName("Mots porteurs : mots vides écartés, bornés à 6")
    void words() {
        assertThat(JarvisCaptureSearch.words("Qu'est-ce que Rémi m'a dit sur la migration SSO ?"))
                .contains("rémi", "migration", "sso").doesNotContain("que", "dit");
    }
}
