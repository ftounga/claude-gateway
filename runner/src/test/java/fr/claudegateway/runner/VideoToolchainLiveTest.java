package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.teams.LocalToolchain;
import fr.claudegateway.runner.update.UpdateVerifier;
import fr.claudegateway.runner.video.render.RenderTools;
import fr.claudegateway.runner.video.render.VideoToolchainBundle;

/**
 * <b>La preuve réelle</b> (F-215 / SF-215-06) : aucun processus simulé, aucun téléchargement simulé. Le montage de
 * production ({@link ToolStack#render}) prend la chaîne vidéo à un portail local qui imite la gateway
 * ({@code tools/video-toolchain/serve-local.py}), puis {@code video_render_check} et {@code video_render} tournent
 * pour de vrai et produisent un MP4.
 *
 * <p>Désactivé dans la suite : il exige un paquet réellement fabriqué, un portail local et un Chrome. Lancé par
 * {@code tools/video-toolchain/prove-offline.sh}, dans un espace réseau isolé (boucle locale seule), proxy mort,
 * sans Node 22 dans le PATH. Seule différence avec la production : la clé qui vérifie le manifeste est une clé
 * d'essai, la clé privée F-111 ne sortant pas de Secrets Manager.</p>
 */
@EnabledIfEnvironmentVariable(named = "VIDEO_TOOLCHAIN_LIVE_PORTAL", matches = ".+")
class VideoToolchainLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COMPOSITION = """
            <!doctype html>
            <html lang="fr">
            <head>
            <meta charset="utf-8">
            <style>
              html, body { margin: 0; background: #0b1f3a; }
              #root { position: relative; width: 1280px; height: 720px; overflow: hidden; background: #0b1f3a;
                      font-family: sans-serif; }
              #t { position: absolute; left: 160px; top: 300px; color: #f7f1e3; font-size: 64px; opacity: 0; }
              #b { position: absolute; left: 160px; top: 420px; width: 0; height: 12px; background: #ff7a1a; }
            </style>
            </head>
            <body>
            <div id="root" data-composition-id="root" data-width="1280" data-height="720" data-fps="30" data-duration="3">
              <div class="clip" id="s1" data-start="0" data-duration="3">
                <div id="t">Chaîne vidéo servie par le portail</div>
                <div id="b"></div>
              </div>
            </div>
            <script src="briques/gsap.min.js"></script>
            <script>
              const tl = gsap.timeline({ paused: true });
              tl.to("#t", { opacity: 1, duration: 1 }, 0).to("#b", { width: 960, duration: 2.5 }, 0.3);
              window.__timelines = window.__timelines || {};
              window.__timelines["root"] = tl;
            </script>
            </body>
            </html>
            """;

    @Test
    void videoRenderCheckThenARealRenderOfflineFromThePortal() throws Exception {
        String portal = System.getenv("VIDEO_TOOLCHAIN_LIVE_PORTAL");
        Path root = Path.of(System.getenv("VIDEO_TOOLCHAIN_LIVE_ROOT"));
        PublicKey key = UpdateVerifier.readPublicKey(
                Files.readString(Path.of(System.getenv("VIDEO_TOOLCHAIN_LIVE_PUBKEY"))));
        String token = System.getenv("VIDEO_TOOLCHAIN_LIVE_TOKEN");

        Path pathNode = LocalToolchain.lookupOnPath("node");
        say("Node du PATH : " + (pathNode == null ? "aucun" : pathNode + " (" + version(pathNode) + ")"));
        assertTrue(pathNode == null || !version(pathNode).startsWith("v22"), "pas de Node 22 dans le PATH");
        say("HTTPS_PROXY=" + System.getenv("HTTPS_PROXY") + " HTTP_PROXY=" + System.getenv("HTTP_PROXY")
                + " NO_PROXY=" + System.getenv("NO_PROXY"));

        // Le client réseau du runner, construit comme RunnerMain le construit (proxy d'entreprise compris).
        RunnerHttp.use(RunnerMain.buildHttpClient(ProxyResolver.fromEnv(System.getenv()), TrustStores.resolve(true)));
        RunnerConfig config = RunnerConfig.resolve(new String[] {"--gateway", portal, "--root", root.toString()},
                Map.of());
        RenderTools tools = ToolStack.render(config, new UpdateVerifier(key)::signatureValid, () -> token);
        PathResolver paths = new PathResolver(root);
        Path demo = Files.createDirectories(root.resolve("demo"));
        Files.writeString(demo.resolve("index.html"), COMPOSITION, StandardCharsets.UTF_8);

        // 1. video_render_check — au premier usage, la chaîne arrive du portail : on relance comme l'agent.
        ObjectNode check = MAPPER.createObjectNode().put("dossier", "demo").put("instants", "1,2.5");
        ToolOutcome checked = null;
        long deadline = System.currentTimeMillis() + 600_000L;
        while (System.currentTimeMillis() < deadline) {
            checked = tools.execute(RenderTools.CHECK, check, ToolContext.none(), paths);
            say("video_render_check → " + (checked.ok() ? checked.content() : "ERREUR " + checked.errorMessage()));
            if (!checked.ok() || !checked.content().contains("Relance video_render_check")) {
                break;
            }
            Thread.sleep(5_000L);
        }
        assertTrue(checked != null && checked.ok(), checked == null ? "" : checked.errorMessage());
        assertTrue(checked.content().contains("LINT : OK"), checked.content());
        assertTrue(checked.content().contains("IMAGES (2)"), checked.content());
        Path installed = root.resolve(".claude-runner").toAbsolutePath();
        say("chaîne en place : " + Files.walk(installed, 4)
                .filter(p -> p.getFileName().toString().startsWith("video-toolchain-")).toList());

        // 2. video_render — rendu détaché, suivi par video_render_status.
        ToolOutcome started = tools.execute(RenderTools.RENDER,
                MAPPER.createObjectNode().put("dossier", "demo").put("qualite", "draft"), ToolContext.none(), paths);
        assertTrue(started.ok(), started.errorMessage());
        say("video_render → " + started.content());
        String id = started.content().lines().filter(l -> l.startsWith("job_id : ")).findFirst().orElseThrow()
                .substring("job_id : ".length()).strip();
        ToolOutcome status = null;
        deadline = System.currentTimeMillis() + 600_000L;
        while (System.currentTimeMillis() < deadline) {
            status = tools.execute(RenderTools.STATUS, MAPPER.createObjectNode().put("job_id", id), ToolContext.none(),
                    paths);
            if (status.content().contains("étape : Terminé") || status.content().contains("étape : Échec")) {
                break;
            }
            Thread.sleep(3_000L);
        }
        say("video_render_status → " + status.content());
        assertTrue(status.content().contains("étape : Terminé"), status.content());
        Path mp4 = demo.resolve("renders/demo.mp4");
        assertTrue(Files.size(mp4) > 1_000, "MP4 produit");

        Path ffprobe = Files.walk(installed, 6).filter(p -> p.getFileName().toString().equals("ffprobe")).findFirst()
                .orElseThrow();
        Process probe = new ProcessBuilder(List.of(ffprobe.toString(), "-v", "error", "-show_entries",
                "format=duration:stream=codec_name,width,height", "-of", "default=noprint_wrappers=1", mp4.toString()))
                .redirectErrorStream(true).start();
        String probed = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, probe.waitFor());
        say("MP4 : " + mp4 + " (" + Files.size(mp4) + " octets)\n" + probed.strip());
        assertTrue(probed.contains("codec_name=h264"), probed);
        assertFalse(VideoToolchainBundle.VERSION.isBlank());
    }

    private static String version(Path node) {
        try {
            Process p = new ProcessBuilder(node.toString(), "--version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            p.waitFor();
            return out;
        } catch (Exception e) {
            return "?";
        }
    }

    private static void say(String line) {
        System.out.println("[preuve] " + line);
        System.out.flush();
    }
}
