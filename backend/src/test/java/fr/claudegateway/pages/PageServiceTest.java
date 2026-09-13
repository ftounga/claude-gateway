package fr.claudegateway.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.atelier.storage.InMemoryWorkspaceStorage;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Ranger une page (F-109 / SF-109-01) : versions, purge, bornes, isolation. Les bornes sont
 * <b>réduites</b> et injectées pour tester le quota sans écrire 500 Mo.
 */
@SpringBootTest
@ActiveProfiles("test")
class PageServiceTest {

    @Autowired private PageRepository pageRepository;
    @Autowired private PageVersionRepository versionRepository;
    @Autowired private PageEventRepository eventRepository;
    @Autowired private UserRepository userRepository;

    private InMemoryWorkspaceStorage storage;
    private PageService service;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() {
        versionRepository.deleteAll();
        pageRepository.deleteAll();
        alice = seedUser("alice-pages-service@example.com");
        bob = seedUser("bob-pages-service@example.com");
        storage = new InMemoryWorkspaceStorage();
        // 1 000 octets par page, 2 500 par compte, 3 versions, 2 pièces jointes.
        service = new PageService(pageRepository, versionRepository, new PageStore(storage),
                new PageLimits(1_000L, 2_500L, 3, 2, null), eventRepository);
    }

    private UUID seedUser(String email) {
        return userRepository.findByEmail(email).map(User::getId).orElseGet(() -> userRepository.save(
                User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER)
                        .build()).getId());
    }

    private PagePlace place(UUID userId) {
        return new PagePlace(userId, PageSpace.FORGE, UUID.randomUUID(), UUID.randomUUID());
    }

    private static String html(int bytes) {
        return "x".repeat(bytes);
    }

    @Test
    @DisplayName("CA1 — publier crée la version 1, republier la version 2 et met le titre à jour")
    void publishAndRepublish() {
        PageService.PublishedPage first = service.publish(place(alice), null, "  Maquette   Forge ", null,
                "<h1>v1</h1>", Map.of());
        PageService.PublishedPage second = service.publish(place(alice), first.page().getId(), "Maquette v2",
                "Une phrase.", "<h1>v2</h1>", Map.of());

        assertThat(first.version().getVersion()).isEqualTo(1);
        assertThat(first.page().getTitle()).isEqualTo("Maquette Forge");
        assertThat(second.version().getVersion()).isEqualTo(2);
        assertThat(second.page().getId()).isEqualTo(first.page().getId());
        assertThat(service.require(alice, first.page().getId()).getTitle()).isEqualTo("Maquette v2");
        assertThat(service.versions(alice, first.page().getId())).extracting(PageVersion::getVersion)
                .containsExactly(2, 1);
        assertThat(new String(service.html(alice, first.page().getId(), null).content(), StandardCharsets.UTF_8))
                .isEqualTo("<h1>v2</h1>");
        assertThat(new String(service.html(alice, first.page().getId(), 1).content(), StandardCharsets.UTF_8))
                .isEqualTo("<h1>v1</h1>");
    }

    @Test
    @DisplayName("CA2 — au-delà de la borne de versions, la plus ancienne est purgée (ligne ET objets)")
    void purgeOldestVersions() {
        UUID pageId = service.publish(place(alice), null, "P", null, "v1", Map.of("a.css", "b{}".getBytes()))
                .page().getId();
        service.publish(place(alice), pageId, "P", null, "v2", Map.of());
        service.publish(place(alice), pageId, "P", null, "v3", Map.of());
        service.publish(place(alice), pageId, "P", null, "v4", Map.of());

        assertThat(service.versions(alice, pageId)).extracting(PageVersion::getVersion).containsExactly(4, 3, 2);
        assertThat(storage.listKeys(PageStore.versionPrefix(alice, pageId, 1))).isEmpty();
        assertThat(storage.listKeys(PageStore.versionPrefix(alice, pageId, 2))).isNotEmpty();
        assertThatThrownBy(() -> service.html(alice, pageId, 1)).isInstanceOf(PageNotFoundException.class);
    }

    @Test
    @DisplayName("CA3 — une version au-delà de la borne de taille est refusée, pièces jointes comprises")
    void pageTooLarge() {
        assertThatThrownBy(() -> service.publish(place(alice), null, "P", null, html(1_001), Map.of()))
                .isInstanceOf(PageRejectedException.class).hasMessageContaining("trop volumineuse");
        assertThatThrownBy(() -> service.publish(place(alice), null, "P", null, html(900),
                Map.of("x.css", new byte[101])))
                .isInstanceOf(PageRejectedException.class);
        assertThat(service.publish(place(alice), null, "P", null, html(1_000), Map.of()).version().getSizeBytes())
                .isEqualTo(1_000);
    }

    @Test
    @DisplayName("CA3 — le quota du compte est tenu, et compté APRÈS la purge que la publication provoque")
    void accountQuota() {
        service.publish(place(alice), null, "P1", null, html(1_000), Map.of());
        service.publish(place(alice), null, "P2", null, html(1_000), Map.of());

        assertThatThrownBy(() -> service.publish(place(alice), null, "P3", null, html(600), Map.of()))
                .isInstanceOf(PageQuotaExceededException.class);
        // Bob a son propre compteur.
        assertThat(service.publish(place(bob), null, "B", null, html(1_000), Map.of())).isNotNull();
        assertThat(service.publish(place(alice), null, "S", null, html(100), Map.of())).isNotNull();
    }

    @Test
    @DisplayName("CA3 — republier une page à sa borne de versions libère la plus ancienne AVANT de compter")
    void quotaCountsThePurge() {
        UUID pageId = service.publish(place(alice), null, "P", null, html(800), Map.of()).page().getId();
        service.publish(place(alice), pageId, "P", null, html(800), Map.of());
        service.publish(place(alice), pageId, "P", null, html(800), Map.of());

        // 2 400 conservés ; une 4e version de 800 en purge une de 800 : 2 400, sous la borne de 2 500.
        assertThat(service.publish(place(alice), pageId, "P", null, html(800), Map.of()).version().getVersion())
                .isEqualTo(4);
        assertThat(versionRepository.sumSizeBytesByUserId(alice)).isEqualTo(2_400);
    }

    @Test
    @DisplayName("titre, description et HTML sont validés avec un message qui dit quoi corriger")
    void validation() {
        assertThatThrownBy(() -> service.publish(place(alice), null, "   ", null, "x", Map.of()))
                .isInstanceOf(PageRejectedException.class).hasMessageContaining("titre");
        assertThatThrownBy(() -> service.publish(place(alice), null, "t".repeat(121), null, "x", Map.of()))
                .isInstanceOf(PageRejectedException.class).hasMessageContaining("120");
        assertThatThrownBy(() -> service.publish(place(alice), null, "T", "d".repeat(301), "x", Map.of()))
                .isInstanceOf(PageRejectedException.class).hasMessageContaining("300");
        assertThatThrownBy(() -> service.publish(place(alice), null, "T", null, "  ", Map.of()))
                .isInstanceOf(PageRejectedException.class).hasMessageContaining("vide");
    }

    @Test
    @DisplayName("pièces jointes : nom plat, extension de la liste, nombre borné")
    void attachments() {
        assertThatThrownBy(() -> service.publish(place(alice), null, "T", null, "x",
                Map.of("../secret.css", new byte[1])))
                .isInstanceOf(PageRejectedException.class).hasMessageContaining("../secret.css");
        assertThatThrownBy(() -> service.publish(place(alice), null, "T", null, "x",
                Map.of("dir/a.css", new byte[1]))).isInstanceOf(PageRejectedException.class);
        assertThatThrownBy(() -> service.publish(place(alice), null, "T", null, "x",
                Map.of("run.exe", new byte[1]))).isInstanceOf(PageRejectedException.class);
        Map<String, byte[]> three = new LinkedHashMap<>();
        three.put("a.css", new byte[1]);
        three.put("b.css", new byte[1]);
        three.put("c.css", new byte[1]);
        assertThatThrownBy(() -> service.publish(place(alice), null, "T", null, "x", three))
                .isInstanceOf(PageRejectedException.class).hasMessageContaining("2 au plus");

        UUID pageId = service.publish(place(alice), null, "T", null, "x",
                Map.of("logo.svg", "<svg/>".getBytes())).page().getId();
        assertThat(service.attachment(alice, pageId, null, "logo.svg")).get()
                .extracting(PageService.PageContent::contentType).isEqualTo("image/svg+xml");
        assertThat(service.attachment(alice, pageId, null, "..")).isEmpty();
        assertThat(service.attachment(bob, pageId, null, "logo.svg")).isEmpty();
    }

    // ------------------------------------------------------------ SF-109-04 : ranger les pages d'un lieu

    @Test
    @DisplayName("SF-109-04 — la liste d'un lieu : ce compte, ce poste, cet espace, le plus récent d'abord")
    void listByPlace() {
        UUID host = UUID.randomUUID();
        UUID first = service.publish(new PagePlace(alice, PageSpace.VIGIE, host, null), null, "Un", null, "1", Map.of())
                .page().getId();
        UUID second = service.publish(new PagePlace(alice, PageSpace.VIGIE, host, null), null, "Deux", null, "2", Map.of())
                .page().getId();
        service.publish(new PagePlace(alice, PageSpace.FORGE, host, null), null, "Forge", null, "f", Map.of());
        service.publish(new PagePlace(alice, PageSpace.VIGIE, UUID.randomUUID(), null), null, "Ailleurs", null, "a", Map.of());
        service.publish(new PagePlace(bob, PageSpace.VIGIE, host, null), null, "Bob", null, "b", Map.of());
        service.rename(alice, first, "Un renommé");

        assertThat(service.list(alice, host, PageSpace.VIGIE)).extracting(Page::getId).containsExactly(first, second);
        assertThat(service.list(bob, host, PageSpace.VIGIE)).extracting(Page::getTitle).containsExactly("Bob");
    }

    @Test
    @DisplayName("SF-109-04 — renommer : titre nettoyé, invalide refusé, page d'autrui introuvable")
    void rename() {
        UUID pageId = service.publish(place(alice), null, "Avant", null, "x", Map.of()).page().getId();

        assertThat(service.rename(alice, pageId, "  Après   tout ").getTitle()).isEqualTo("Après tout");
        assertThatThrownBy(() -> service.rename(alice, pageId, " ")).isInstanceOf(PageRejectedException.class);
        assertThatThrownBy(() -> service.rename(bob, pageId, "Volée")).isInstanceOf(PageNotFoundException.class);
        assertThat(service.require(alice, pageId).getTitle()).isEqualTo("Après tout");
    }

    @Test
    @DisplayName("SF-109-04 — supprimer efface les lignes ET les objets ; celle d'autrui reste")
    void delete() {
        UUID pageId = service.publish(place(alice), null, "P", null, "v1", Map.of("a.css", "b{}".getBytes())).page().getId();
        service.publish(place(alice), pageId, "P", null, "v2", Map.of());

        assertThatThrownBy(() -> service.delete(bob, pageId)).isInstanceOf(PageNotFoundException.class);
        assertThat(storage.listKeys(PageStore.PREFIX + alice + "/" + pageId + "/")).isNotEmpty();

        service.delete(alice, pageId);

        assertThatThrownBy(() -> service.require(alice, pageId)).isInstanceOf(PageNotFoundException.class);
        assertThat(versionRepository.sumSizeBytesByUserId(alice)).isZero();
        assertThat(storage.listKeys(PageStore.PREFIX + alice + "/" + pageId + "/")).isEmpty();
    }

    @Test
    @DisplayName("SF-109-04 — purger un lieu n'efface que ce lieu et ce compte")
    void deletePlace() {
        UUID host = UUID.randomUUID();
        service.publish(new PagePlace(alice, PageSpace.VIGIE, host, null), null, "A1", null, "1", Map.of());
        service.publish(new PagePlace(alice, PageSpace.VIGIE, host, null), null, "A2", null, "2", Map.of());
        UUID forge = service.publish(new PagePlace(alice, PageSpace.FORGE, host, null), null, "F", null, "f", Map.of())
                .page().getId();
        UUID bobs = service.publish(new PagePlace(bob, PageSpace.VIGIE, host, null), null, "B", null, "b", Map.of())
                .page().getId();

        assertThat(service.deletePlace(alice, host, PageSpace.VIGIE)).isEqualTo(2);

        assertThat(service.list(alice, host, PageSpace.VIGIE)).isEmpty();
        assertThat(service.require(alice, forge)).isNotNull();
        assertThat(service.require(bob, bobs)).isNotNull();
    }

    @Test
    @DisplayName("SF-109-04 — l'export ZIP porte la version courante et les pièces jointes du lieu, et rien d'autre")
    void exportPlace() throws Exception {
        UUID host = UUID.randomUUID();
        UUID pageId = service.publish(new PagePlace(alice, PageSpace.FORGE, host, null), null, "Maquette été", null,
                "<h1>v1</h1>", Map.of()).page().getId();
        service.publish(new PagePlace(alice, PageSpace.FORGE, host, null), pageId, "Maquette été", null,
                "<h1>v2</h1>", Map.of("style.css", "h1{}".getBytes()));
        service.publish(new PagePlace(alice, PageSpace.FORGE, host, null), null, "Maquette été", null, "<p>homonyme</p>",
                Map.of());
        service.publish(new PagePlace(bob, PageSpace.FORGE, host, null), null, "Bob", null, "<p>bob</p>", Map.of());

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        service.exportPlace(alice, host, PageSpace.FORGE, out);

        Map<String, String> entries = new java.util.TreeMap<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(out.toByteArray()))) {
            for (java.util.zip.ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        // Deux pages homonymes : deux dossiers distincts, dans l'ordre de modification (la plus récente d'abord).
        assertThat(entries.keySet()).hasSize(3).contains("maquette-ete/index.html", "maquette-ete-2/index.html");
        String cssFolder = entries.keySet().stream().filter(name -> name.endsWith("/style.css")).findFirst()
                .orElseThrow().replace("/style.css", "");
        assertThat(entries.get(cssFolder + "/index.html")).isEqualTo("<h1>v2</h1>");
        assertThat(entries.values()).contains("h1{}", "<p>homonyme</p>").doesNotContain("<p>bob</p>", "<h1>v1</h1>");
    }

    @Test
    @DisplayName("SF-109-04 — purger un compte efface les objets de toutes ses pages")
    void purgeUser() {
        service.publish(place(alice), null, "A", null, "a", Map.of());
        UUID bobs = service.publish(place(bob), null, "B", null, "b", Map.of()).page().getId();

        service.purgeUser(alice);

        assertThat(storage.listKeys(PageStore.PREFIX + alice + "/")).isEmpty();
        assertThat(storage.listKeys(PageStore.PREFIX + bob + "/" + bobs + "/")).isNotEmpty();
    }

    @Test
    @DisplayName("CA6 — la page d'Alice est introuvable pour Bob : lecture, versions, republication")
    void isolation() {
        UUID pageId = service.publish(place(alice), null, "Privée", null, "<p>alice</p>", Map.of()).page().getId();

        assertThatThrownBy(() -> service.require(bob, pageId)).isInstanceOf(PageNotFoundException.class);
        assertThatThrownBy(() -> service.html(bob, pageId, null)).isInstanceOf(PageNotFoundException.class);
        assertThatThrownBy(() -> service.versions(bob, pageId)).isInstanceOf(PageNotFoundException.class);
        assertThatThrownBy(() -> service.publish(place(bob), pageId, "Volée", null, "x", Map.of()))
                .isInstanceOf(PageNotFoundException.class);
        assertThat(service.require(alice, pageId).getCurrentVersion()).isEqualTo(1);
    }
}
