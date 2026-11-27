package fr.claudegateway.atelier.gallery;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.deposit.AtelierDepositedFile;
import fr.claudegateway.atelier.deposit.AtelierDepositedFileRepository;
import fr.claudegateway.atelier.storage.WorkspaceStorage;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * <b>La galerie d'images du poste</b> (F-278 / SF-278-01) : lit les images par le runner existant
 * ({@code read_file_bytes}, par tranches), en range des aperçus bornés ({@link GalleryImages}) dans le stockage du
 * terminal et rend le bloc {@link GalleryBlock} et le texte au modèle — jamais d'image au modèle.
 *
 * <p><b>Isolation.</b> Aucune table : la clé d'un aperçu est {@code galeries/{user}/{workspace}/{id}.jpg}, toujours
 * <b>reconstruite</b> depuis l'utilisateur authentifié et le terminal relu par {@code requireOwned}. La date d'un dépôt
 * est lue sous {@code (user_id, workspace_id)}.</p>
 */
@Service
public class GalleryService {

    static final String PREFIX = "galeries/";
    /** Tranche de lecture : celle du runner (Base64 dans la trame de 1 Mio). */
    static final int CHUNK_BYTES = 393_216;
    /** Transfert total par appel. */
    static final long MAX_TOTAL_BYTES = 150L * 1024 * 1024;
    /** Marge gardée avant l'échéance du tour : les images suivantes ne sont pas lues. */
    static final long DEADLINE_MARGIN_MS = 15_000;
    static final int MAX_TITLE = 200;
    static final int MAX_CAPTION = 300;
    static final int MAX_DATE = 40;
    /** Écartées listées dans le bloc et au modèle. */
    static final int MAX_SKIPPED_LISTED = 30;

    /** Extensions lues (décodeurs du JDK). */
    static final Set<String> SUPPORTED = Set.of("png", "jpg", "jpeg", "gif", "bmp");
    /** Extensions d'images reconnues mais non décodées : écartées avec leur raison. */
    static final Set<String> UNSUPPORTED_IMAGES = Set.of("webp", "heic", "heif", "svg", "tif", "tiff", "avif", "ico");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RunnerToolGateway runner;
    private final RunnerAuditService audit;
    private final WorkspaceStorage storage;
    private final AtelierDepositedFileRepository deposits;
    private LongSupplier clock = System::currentTimeMillis;

    @Autowired
    public GalleryService(RunnerToolGateway runner, RunnerAuditService audit, WorkspaceStorage storage,
            AtelierDepositedFileRepository deposits) {
        this.runner = runner;
        this.audit = audit;
        this.storage = storage;
        this.deposits = deposits;
    }

    /** Horloge des tests (échéance du tour). */
    void clock(LongSupplier clock) {
        this.clock = clock;
    }

    /** Une image demandée : chemin, légende et date donnés par l'agent (facultatives). */
    public record Request(String path, String caption, String date) {
    }

    /**
     * L'issue : le bloc (absent si aucune image n'a pu être affichée), le texte au modèle, et {@code error} vrai si
     * rien n'est affiché.
     */
    public record Result(GalleryBlock block, String modelText, boolean error) {
    }

    /** Les images d'un dossier, plus récentes d'abord ; vide + message si rien. */
    public record Listing(List<String> paths, String error) {
    }

    /**
     * Les images d'un dossier du poste, <b>plus récentes d'abord</b> (le {@code glob} du runner trie par date de
     * modification), au plus {@code count}.
     */
    public Listing listFolder(UUID userId, RunnerTarget target, String callId, String folder, int count) {
        String dir = folder == null ? "" : folder.strip().replace('\\', '/');
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        if (dir.startsWith("./")) {
            dir = dir.substring(2);
        }
        if (dir.isEmpty()) {
            return new Listing(List.of(), "Donne le « dossier » des images (par ex. .atelier/entrees).");
        }
        ObjectNode input = MAPPER.createObjectNode();
        input.put("pattern", dir + "/**");
        input.put("path", dir);
        RunnerCallResult result = runner.glob(target, callId, input);
        audit.recordCall(userId, target, callId, "glob", shorten(dir + "/**", 200), result);
        if (!result.ok()) {
            return new Listing(List.of(), runnerError(dir, result));
        }
        List<String> images = new ArrayList<>();
        String content = result.content() == null ? "" : result.content();
        for (String line : content.split("\n")) {
            String path = line.strip();
            if (!path.isEmpty() && SUPPORTED.contains(extension(path)) && images.size() < count) {
                images.add(path);
            }
        }
        if (images.isEmpty()) {
            return new Listing(List.of(), "Aucune image (PNG, JPEG, GIF, BMP) trouvée dans « " + dir + " ». Si le "
                    + "dossier est exclu du balayage, liste ses fichiers par bash puis passe-les dans « images ».");
        }
        return new Listing(images, null);
    }

    /**
     * Lit, réduit et range les images demandées ; {@code refused} = les chemins déjà écartés par la politique de
     * lecture (avec leur raison), repris dans le bloc et le texte au modèle.
     */
    public Result publish(UUID userId, UUID workspaceId, RunnerTarget target, String callId, String title,
            List<Request> requests, List<GalleryBlock.Skipped> refused, long deadline) {
        String cleanTitle = clip(title == null || title.isBlank() ? "Images" : title.strip(), MAX_TITLE);
        List<GalleryBlock.Item> items = new ArrayList<>();
        List<GalleryBlock.Skipped> skipped = new ArrayList<>(refused == null ? List.of() : refused);
        Map<String, String> depositDates = null;
        long transferred = 0;
        for (int i = 0; i < requests.size(); i++) {
            Request request = requests.get(i);
            String path = normalize(request.path());
            String ext = extension(path);
            if (UNSUPPORTED_IMAGES.contains(ext)) {
                skipped.add(new GalleryBlock.Skipped(path, "format non pris en charge (" + ext + ")"));
                continue;
            }
            if (!SUPPORTED.contains(ext)) {
                skipped.add(new GalleryBlock.Skipped(path, "pas une image PNG, JPEG, GIF ou BMP"));
                continue;
            }
            if (transferred >= MAX_TOTAL_BYTES || clock.getAsLong() > deadline - DEADLINE_MARGIN_MS) {
                skipped.add(new GalleryBlock.Skipped(path, "non chargée (limite de la galerie atteinte)"));
                continue;
            }
            Read read = read(userId, target, callId + "-i" + (i + 1), path);
            if (read.offline()) {
                return new Result(null, "Le poste est injoignable : la galerie n'a pas pu être lue. Réessaie quand "
                        + "il est reconnecté.", true);
            }
            if (read.error() != null) {
                skipped.add(new GalleryBlock.Skipped(path, read.error()));
                continue;
            }
            transferred += read.bytes().length;
            Optional<GalleryImages.Preview> preview = GalleryImages.preview(read.bytes());
            if (preview.isEmpty()) {
                skipped.add(new GalleryBlock.Skipped(path, "image illisible"));
                continue;
            }
            UUID id = UUID.randomUUID();
            storage.putFile(prefixOf(userId, workspaceId) + hex(id) + ".jpg", preview.get().jpeg(), "image/jpeg");
            String date = clipOrNull(request.date(), MAX_DATE);
            if (date == null) {
                if (depositDates == null) {
                    depositDates = depositDates(userId, workspaceId);
                }
                date = depositDates.get(normalize(path));
            }
            if (date == null) {
                date = GalleryDates.fromName(fileName(path)).map(Object::toString).orElse(null);
            }
            items.add(new GalleryBlock.Item(hex(id), fileName(path), path, date,
                    clipOrNull(request.caption(), MAX_CAPTION), preview.get().width(), preview.get().height()));
        }
        List<GalleryBlock.Skipped> listed = skipped.size() > MAX_SKIPPED_LISTED
                ? List.copyOf(skipped.subList(0, MAX_SKIPPED_LISTED)) : List.copyOf(skipped);
        if (items.isEmpty()) {
            return new Result(null, "Aucune image n'a pu être affichée pour « " + cleanTitle + " »."
                    + skippedText(skipped, listed), true);
        }
        GalleryBlock block = new GalleryBlock(cleanTitle, List.copyOf(items), listed);
        return new Result(block, modelText(cleanTitle, items, skipped, listed), false);
    }

    /** L'aperçu d'une image de ce compte et de ce terminal, ou vide (inconnue et étrangère indiscernables). */
    public Optional<byte[]> image(UUID userId, UUID workspaceId, String imageId) {
        if (imageId == null || !imageId.matches("[0-9a-f]{32}")) {
            return Optional.empty();
        }
        // La clé est reconstruite : jamais lue d'ailleurs que du compte authentifié.
        return storage.getFile(prefixOf(userId, workspaceId) + imageId + ".jpg");
    }

    /** Racine des aperçus d'un terminal — effacée avec lui ({@code WorkspaceService.delete}). */
    public static String prefixOf(UUID userId, UUID workspaceId) {
        return PREFIX + userId + "/" + workspaceId + "/";
    }

    // ------------------------------------------------------------------ lecture

    private record Read(byte[] bytes, String error, boolean offline) {
    }

    /** Une image lue entière par tranches, bornée à {@link GalleryImages#MAX_SOURCE_BYTES}. */
    private Read read(UUID userId, RunnerTarget target, String callId, String path) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RunnerCallResult last = null;
        Long size = null;
        int chunk = 0;
        while (true) {
            RunnerCallResult result = runner.readFileBytes(target, callId + "." + chunk, path, out.size(),
                    CHUNK_BYTES);
            last = result;
            if (!result.ok()) {
                audit.recordCall(userId, target, callId, "read_file", shorten(path, 200), result);
                if (RunnerErrorCodes.RUNNER_UNAVAILABLE.equals(result.errorCode())) {
                    return new Read(null, null, true);
                }
                if ("too_large".equals(result.errorCode())) {
                    return new Read(null, "trop lourde (10 Mo au plus)", false);
                }
                return new Read(null, result.errorMessage() == null || result.errorMessage().isBlank()
                        ? "lecture impossible" : shorten(result.errorMessage(), 160), false);
            }
            byte[] part = decode(result.content());
            if (part == null || result.bytes() == null || result.bytes() < 0) {
                audit.recordCall(userId, target, callId, "read_file", shorten(path, 200), result);
                return new Read(null, "réponse du poste invalide", false);
            }
            if (size == null) {
                size = result.bytes();
                if (size > GalleryImages.MAX_SOURCE_BYTES) {
                    audit.recordCall(userId, target, callId, "read_file", shorten(path, 200), result);
                    return new Read(null, "trop lourde (10 Mo au plus)", false);
                }
            } else if (result.bytes().longValue() != size) {
                audit.recordCall(userId, target, callId, "read_file", shorten(path, 200), result);
                return new Read(null, "modifiée pendant la lecture", false);
            }
            out.write(part, 0, part.length);
            if (!result.truncated() || part.length == 0 || out.size() >= size) {
                break;
            }
            chunk++;
        }
        audit.recordCall(userId, target, callId, "read_file", shorten(path, 200), last);
        if (out.size() != size) {
            return new Read(null, "modifiée pendant la lecture", false);
        }
        return new Read(out.toByteArray(), null, false);
    }

    /** Chemin normalisé → date du dépôt le plus récent à ce chemin (isolation {@code user_id} + terminal). */
    private Map<String, String> depositDates(UUID userId, UUID workspaceId) {
        Map<String, String> dates = new HashMap<>();
        for (AtelierDepositedFile deposit : deposits.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId,
                workspaceId)) {
            if (deposit.getPath() != null && deposit.getCreatedAt() != null) {
                dates.put(normalize(deposit.getPath()), deposit.getCreatedAt().toString());
            }
        }
        return dates;
    }

    // ------------------------------------------------------------------ texte au modèle

    String modelText(String title, List<GalleryBlock.Item> items, List<GalleryBlock.Skipped> skipped,
            List<GalleryBlock.Skipped> listed) {
        StringBuilder text = new StringBuilder("Galerie « ").append(title).append(" » affichée dans le fil : ")
                .append(items.size()).append(items.size() > 1 ? " images" : " image")
                .append(" (l'utilisateur les fait défiler ; ne les décris pas) : ");
        List<String> names = items.stream().map(GalleryBlock.Item::name).toList();
        text.append(shorten(String.join(", ", names), 2_000)).append('.');
        text.append(skippedText(skipped, listed));
        return text.toString();
    }

    private static String skippedText(List<GalleryBlock.Skipped> skipped, List<GalleryBlock.Skipped> listed) {
        if (skipped.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder(" Écartées (").append(skipped.size()).append(") : ");
        List<String> parts = listed.stream().map(s -> fileName(s.path()) + " — " + s.reason()).toList();
        text.append(shorten(String.join(" ; ", parts), 2_000));
        if (skipped.size() > listed.size()) {
            text.append(" ; …");
        }
        return text.append('.').toString();
    }

    // ------------------------------------------------------------------ outils

    static String extension(String path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String fileName(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash < 0 ? p : p.substring(slash + 1);
    }

    private static String normalize(String path) {
        String p = path == null ? "" : path.strip().replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }

    private static String runnerError(String path, RunnerCallResult result) {
        if (RunnerErrorCodes.RUNNER_UNAVAILABLE.equals(result.errorCode())) {
            return "Le poste est injoignable : la galerie n'a pas pu être lue. Réessaie quand il est reconnecté.";
        }
        String reason = result.errorMessage() == null || result.errorMessage().isBlank()
                ? "lecture impossible" : result.errorMessage();
        return "Dossier illisible sur le poste (" + path + ") : " + shorten(reason, 200);
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64 == null ? "" : base64);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String hex(UUID id) {
        return id.toString().replace("-", "");
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static String clipOrNull(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return clip(value.strip(), max);
    }

    private static String shorten(String value, int max) {
        return value == null ? "" : clip(value, max);
    }
}
