package fr.claudegateway.mail;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.email.ClientMailMessage.Attachment;
import fr.claudegateway.pages.Page;
import fr.claudegateway.pages.PageNotFoundException;
import fr.claudegateway.pages.PageService;
import fr.claudegateway.radar.RadarExportService;
import fr.claudegateway.radar.RadarMarkdown;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerTargets;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * <b>Les pièces jointes d'un courriel du client</b> (F-110 / SF-110-03, cadrage §4).
 *
 * <h2>Trois sources, toutes rattachées au poste du terminal</h2>
 *
 * <ul>
 *   <li><b>Un fichier du poste</b>, lu par le runner en binaire, par tranches ({@code read_file_bytes}) — un
 *       runner antérieur n'a que {@code read_file}, qui ne sait rendre que du texte : il suffit pour un fichier
 *       texte de 512 Kio au plus, et un binaire demande la mise à jour du runner. La lecture est tracée dans le
 *       journal du runner, une ligne par fichier.</li>
 *   <li><b>Une page</b> (F-109) du compte <b>au même poste</b> : son HTML courant en pièce jointe, et son lien
 *       privé dans le corps — ou le lien seul ({@code link_only}).</li>
 *   <li><b>L'export Markdown du Radar</b> du client, celui du bouton d'export (SF-99-05).</li>
 * </ul>
 *
 * <h2>Ce qui est refusé</h2>
 *
 * <p>Un fichier <b>nommé comme un conteneur de secrets</b> ({@code .env}, clé privée, magasin de certificats…),
 * une pièce <b>texte</b> où {@link ClientMailSecrets} trouve un secret manifeste, et un total au-delà de
 * {@link #MAX_TOTAL_BYTES} — auquel cas l'agent doit proposer un lien. Un binaire (PDF, docx) n'est pas inspecté
 * au-delà de son nom. Aucun refus ne laisse rien derrière lui : l'outil ne met rien en file.</p>
 */
@Component
public class ClientMailAttachments {

    /** Plafond d'un courriel : pièces et corps rendus (cadrage §4, « 10 Mo au total »). */
    public static final long MAX_TOTAL_BYTES = 10L * 1024 * 1024;
    /** Pièces au plus par courriel. */
    public static final int MAX_ATTACHMENTS = 10;
    static final int MAX_NAME_CHARS = 100;
    /** Tranche lue par appel : ≈ 480 Kio une fois en Base64, sous la borne de 512 Kio du contrat. */
    static final int CHUNK_BYTES = 360 * 1024;
    /** Borne d'une lecture de repli par {@code read_file} (contrat §5). */
    static final int TEXT_FALLBACK_BYTES = 524_288;

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("odt", "application/vnd.oasis.opendocument.text"),
            Map.entry("ods", "application/vnd.oasis.opendocument.spreadsheet"),
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("log", "text/plain; charset=utf-8"),
            Map.entry("csv", "text/csv; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("yaml", "application/yaml"),
            Map.entry("yml", "application/yaml"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("zip", "application/zip"));

    /** Extensions qu'un runner antérieur peut lire par {@code read_file} sans les corrompre. */
    private static final Set<String> TEXT_EXTENSIONS = Set.of("md", "txt", "log", "csv", "json", "xml", "yaml",
            "yml", "html", "htm", "svg", "sql", "sh", "ps1", "properties", "ini", "conf", "java", "ts", "js", "py");

    private static final Set<String> SECRET_FILE_NAMES = Set.of(".env", "id_rsa", "id_dsa", "id_ecdsa",
            "id_ed25519", ".npmrc", ".netrc", ".pgpass", ".git-credentials", "credentials", "kubeconfig");
    private static final Set<String> SECRET_EXTENSIONS = Set.of("pem", "key", "p12", "pfx", "jks", "keystore",
            "kdbx", "ppk");

    private final RunnerToolGateway runner;
    private final RunnerAuditService audit;
    private final PageService pages;
    private final RadarExportService radarExport;
    private final Clock clock;
    private final String frontendUrl;

    public ClientMailAttachments(RunnerToolGateway runner, RunnerAuditService audit, PageService pages,
            RadarExportService radarExport, Clock clock,
            @Value("${app.frontend-url:http://localhost:4200}") String frontendUrl) {
        this.runner = runner;
        this.audit = audit;
        this.pages = pages;
        this.radarExport = radarExport;
        this.clock = clock;
        this.frontendUrl = frontendUrl == null ? "" : frontendUrl.replaceAll("/+$", "");
    }

    /** Un lien privé de page, ajouté au corps. */
    public record PageLink(String title, String url) {
    }

    /**
     * Ce que rend la collecte : des pièces et des liens, ou un refus.
     *
     * @param attachments pièces lues et contrôlées, dans l'ordre
     * @param links       liens privés de pages, dans l'ordre
     * @param refusal     phrase pour le modèle quand la collecte est refusée, sinon {@code null}
     */
    public record Collected(List<Attachment> attachments, List<PageLink> links, String refusal) {

        static Collected refused(String refusal) {
            return new Collected(List.of(), List.of(), refusal);
        }

        public boolean isRefused() {
            return refusal != null;
        }

        /** Octets des pièces. */
        public long bytes() {
            return attachments.stream().mapToLong(a -> a.content().length).sum();
        }
    }

    /**
     * Lit et contrôle les pièces demandées.
     *
     * @param userId     propriétaire du terminal (celui du tour)
     * @param workspace  terminal du tour, déjà vérifié possédé, sur un poste
     * @param callId     identifiant de corrélation de l'appel {@code email_me}
     * @param list       le champ {@code attachments} du modèle ({@code null} toléré)
     * @param bodyBytes  octets déjà pris par les corps rendus, comptés dans le plafond
     */
    public Collected collect(UUID userId, Workspace workspace, String callId, JsonNode list, long bodyBytes) {
        if (list == null || list.isNull() || (list.isArray() && list.isEmpty())) {
            return new Collected(List.of(), List.of(), null);
        }
        if (!list.isArray()) {
            return Collected.refused("« attachments » doit être une liste d'objets : aucun courriel n'a été envoyé.");
        }
        if (list.size() > MAX_ATTACHMENTS) {
            return Collected.refused(MAX_ATTACHMENTS + " pièces jointes au plus par courriel : aucun courriel n'a "
                    + "été envoyé.");
        }
        List<Attachment> attachments = new ArrayList<>();
        List<PageLink> links = new ArrayList<>();
        Set<String> names = new HashSet<>();
        boolean radarTaken = false;
        long total = bodyBytes;
        int index = 0;
        for (JsonNode item : list) {
            index++;
            String label = "pièce n° " + index;
            String path = text(item, "path");
            String pageId = text(item, "page_id");
            boolean radar = item != null && item.path("radar_export").asBoolean(false);
            int sources = (path.isEmpty() ? 0 : 1) + (pageId.isEmpty() ? 0 : 1) + (radar ? 1 : 0);
            if (sources != 1) {
                return Collected.refused("La " + label + " doit porter exactement une source : path (fichier du "
                        + "poste), page_id (page publiée) ou radar_export. Aucun courriel n'a été envoyé.");
            }
            String requestedName = text(item, "name");
            if (!requestedName.isEmpty() && !isValidName(requestedName)) {
                return Collected.refused("Nom de la " + label + " invalide : un nom de fichier, " + MAX_NAME_CHARS
                        + " caractères au plus, sans dossier. Aucun courriel n'a été envoyé.");
            }

            Attachment attachment;
            if (!pageId.isEmpty()) {
                Optional<Page> page = page(userId, workspace, pageId);
                if (page.isEmpty()) {
                    return Collected.refused("La " + label + " nomme une page inconnue pour ce client (page_id). "
                            + "Aucun courriel n'a été envoyé.");
                }
                links.add(new PageLink(page.get().getTitle(), frontendUrl + "/pages/" + page.get().getId()));
                if (item.path("link_only").asBoolean(false)) {
                    continue;
                }
                byte[] html;
                try {
                    html = pages.html(userId, page.get().getId(), null).content();
                } catch (PageNotFoundException e) {
                    return Collected.refused("La page de la " + label + " est illisible : envoie son lien seul "
                            + "(link_only). Aucun courriel n'a été envoyé.");
                }
                String name = requestedName.isEmpty() ? slug(page.get().getTitle(), "page") + ".html" : requestedName;
                attachment = new Attachment(name, contentTypeOf(name), html);
            } else if (radar) {
                if (radarTaken) {
                    return Collected.refused("L'export du Radar n'est joint qu'une fois. Aucun courriel n'a été envoyé.");
                }
                radarTaken = true;
                RadarExportService.Export export;
                try {
                    export = radarExport.export(new RadarScope(userId, workspace.getHostId()), LocalDate.now(clock));
                } catch (RuntimeException e) {
                    return Collected.refused("L'export du Radar de ce client est indisponible. Aucun courriel n'a été "
                            + "envoyé.");
                }
                if (export.entries() == 0) {
                    return Collected.refused("Le Radar de ce client est vide : rien à joindre. Aucun courriel n'a été "
                            + "envoyé.");
                }
                String name = requestedName.isEmpty() ? export.fileName() : requestedName;
                attachment = new Attachment(name, contentTypeOf(name), export.markdown().getBytes(StandardCharsets.UTF_8));
            } else {
                String fileName = baseName(path);
                if (isSecretFileName(fileName)) {
                    return secretFile(fileName);
                }
                Read read = readFromPoste(userId, workspace, callId, index, path, MAX_TOTAL_BYTES - total);
                if (read.refusal() != null) {
                    return Collected.refused(read.refusal());
                }
                String name = requestedName.isEmpty() ? fileName : requestedName;
                if (!isValidName(name)) {
                    return Collected.refused("Le fichier « " + path + " » n'a pas de nom joignable : donne « name ». "
                            + "Aucun courriel n'a été envoyé.");
                }
                attachment = new Attachment(name, contentTypeOf(name), read.content());
            }

            if (isSecretFileName(attachment.name())) {
                return secretFile(attachment.name());
            }
            if (!names.add(attachment.name().toLowerCase(Locale.ROOT))) {
                return Collected.refused("Deux pièces jointes portent le nom « " + attachment.name() + " » : donne "
                        + "« name ». Aucun courriel n'a été envoyé.");
            }
            Optional<String> secret = asText(attachment.content()).flatMap(ClientMailSecrets::find);
            if (secret.isPresent()) {
                return Collected.refused("Courriel refusé : la pièce jointe « " + attachment.name() + " » contient "
                        + "manifestement " + secret.get() + ". Un secret ne voyage jamais par courriel ; ne la joins "
                        + "pas et dis à l'utilisateur pourquoi.");
            }
            total += attachment.content().length;
            if (total > MAX_TOTAL_BYTES) {
                return tooLarge(total);
            }
            attachments.add(attachment);
        }
        return new Collected(List.copyOf(attachments), List.copyOf(links), null);
    }

    // ---------------------------------------------------------------- le poste

    private record Read(byte[] content, String refusal) {
    }

    /** Lit un fichier du poste par tranches ; s'arrête dès que sa taille annoncée dépasse ce qui reste. */
    private Read readFromPoste(UUID userId, Workspace workspace, String callId, int index, String path, long room) {
        RunnerTarget target = RunnerTargets.of(workspace);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Long size = null;
        RunnerCallResult last = null;
        int chunk = 0;
        while (true) {
            String chunkId = callId + "#pj" + index + "." + chunk;
            RunnerCallResult result = runner.readFileBytes(target, chunkId, path, out.size(), CHUNK_BYTES);
            last = result;
            if (!result.ok()) {
                if (chunk == 0 && RunnerErrorCodes.UNSUPPORTED_TOOL.equals(result.errorCode())) {
                    return readTextFallback(userId, target, chunkId, path, room);
                }
                audit.recordCall(userId, target, callId + "#pj" + index, ClientMailTool.NAME, path, result);
                return new Read(null, unreadable(path, result));
            }
            byte[] part;
            try {
                part = Base64.getDecoder().decode(result.content() == null ? "" : result.content());
            } catch (IllegalArgumentException e) {
                audit.recordCall(userId, target, callId + "#pj" + index, ClientMailTool.NAME, path, result);
                return new Read(null, "Fichier illisible sur la machine (" + path + ") : réponse du runner invalide. "
                        + "Aucun courriel n'a été envoyé.");
            }
            if (result.bytes() == null || result.bytes() < 0) {
                audit.recordCall(userId, target, callId + "#pj" + index, ClientMailTool.NAME, path, result);
                return new Read(null, "Fichier illisible sur la machine (" + path + ") : réponse du runner invalide. "
                        + "Aucun courriel n'a été envoyé.");
            }
            long announced = result.bytes();
            if (size == null) {
                size = announced;
                if (size > room) {
                    audit.recordCall(userId, target, callId + "#pj" + index, ClientMailTool.NAME, path, result);
                    return new Read(null, tooLargeSentence(MAX_TOTAL_BYTES - room + size));
                }
            } else if (announced != size) {
                audit.recordCall(userId, target, callId + "#pj" + index, ClientMailTool.NAME, path, result);
                return new Read(null, "Le fichier « " + path + " » a changé pendant la lecture : réessaie quand il "
                        + "ne bouge plus. Aucun courriel n'a été envoyé.");
            }
            out.write(part, 0, part.length);
            if (!result.truncated() || part.length == 0 || out.size() >= size) {
                break;
            }
            chunk++;
        }
        audit.recordCall(userId, target, callId + "#pj" + index, ClientMailTool.NAME, path, last);
        if (size != null && out.size() != size) {
            return new Read(null, "Le fichier « " + path + " » a changé pendant la lecture : réessaie quand il ne "
                    + "bouge plus. Aucun courriel n'a été envoyé.");
        }
        return new Read(out.toByteArray(), null);
    }

    /** Runner antérieur à {@code read_file_bytes} : un fichier texte passe encore par {@code read_file}. */
    private Read readTextFallback(UUID userId, RunnerTarget target, String callId, String path, long room) {
        if (!TEXT_EXTENSIONS.contains(extension(baseName(path)))) {
            return new Read(null, "Le runner de ce poste est trop ancien pour joindre « " + baseName(path) + " » "
                    + "(fichier binaire) : dis à l'utilisateur de le mettre à jour depuis la Forge. Aucun courriel "
                    + "n'a été envoyé.");
        }
        RunnerCallResult result = runner.readFile(target, callId, path);
        audit.recordCall(userId, target, callId, ClientMailTool.NAME, path, result);
        if (!result.ok()) {
            return new Read(null, unreadable(path, result));
        }
        if (result.truncated()) {
            return new Read(null, "Le runner de ce poste est trop ancien pour joindre un fichier de plus de 512 Kio "
                    + "(« " + baseName(path) + " ») : dis à l'utilisateur de le mettre à jour depuis la Forge. Aucun "
                    + "courriel n'a été envoyé.");
        }
        byte[] content = (result.content() == null ? "" : result.content()).getBytes(StandardCharsets.UTF_8);
        if (content.length > room) {
            return new Read(null, tooLargeSentence(MAX_TOTAL_BYTES - room + content.length));
        }
        return new Read(content, null);
    }

    private static String unreadable(String path, RunnerCallResult result) {
        String reason = result.errorMessage() == null || result.errorMessage().isBlank()
                ? "lecture impossible" : result.errorMessage();
        return "Fichier illisible sur la machine (" + path + ") : " + reason + ". Aucun courriel n'a été envoyé.";
    }

    // ---------------------------------------------------------------- pages

    /** La page du compte, si elle est rangée au poste du terminal. */
    private Optional<Page> page(UUID userId, Workspace workspace, String rawId) {
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        try {
            Page page = pages.require(userId, id);
            return workspace.getHostId().equals(page.getHostId()) ? Optional.of(page) : Optional.empty();
        } catch (PageNotFoundException e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- règles

    private static Collected tooLarge(long total) {
        return Collected.refused(tooLargeSentence(total));
    }

    private static String tooLargeSentence(long total) {
        return "Courriel trop lourd : au moins " + megabytes(total) + " Mo pour 10 Mo au plus au total (pièces et "
                + "corps). Aucun courriel n'a été envoyé. Propose un lien à la place : pour une page, "
                + "« link_only » envoie son lien privé sans le fichier ; un document du poste peut être publié en "
                + "page, ou son emplacement indiqué dans le corps.";
    }

    private static Collected secretFile(String name) {
        return Collected.refused("Courriel refusé : « " + name + " » est un fichier de secrets (clés, jetons, mots de "
                + "passe). Un secret ne voyage jamais par courriel ; ne le joins pas et dis à l'utilisateur pourquoi.");
    }

    /** Vrai si le nom est celui d'un conteneur de secrets reconnu. */
    static boolean isSecretFileName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return SECRET_FILE_NAMES.contains(lower) || lower.startsWith(".env.")
                || SECRET_EXTENSIONS.contains(extension(lower));
    }

    /** Vrai si le nom est joignable : non vide, borné, sans dossier ni caractère de contrôle. */
    static boolean isValidName(String name) {
        return name != null && !name.isBlank() && name.length() <= MAX_NAME_CHARS && !name.equals(".")
                && !name.equals("..") && name.chars().noneMatch(c -> c == '/' || c == '\\' || Character.isISOControl(c));
    }

    /** Le type MIME déduit du nom ; {@code application/octet-stream} par défaut. */
    public static String contentTypeOf(String name) {
        return TYPES.getOrDefault(extension(name == null ? "" : name.toLowerCase(Locale.ROOT)),
                "application/octet-stream");
    }

    /** Le texte d'une pièce s'il en est un (UTF-8 valide, sans octet nul) ; vide pour un binaire. */
    static Optional<String> asText(byte[] content) {
        for (byte b : content) {
            if (b == 0) {
                return Optional.empty();
            }
        }
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    static String baseName(String path) {
        String trimmed = path.strip().replaceAll("[/\\\\]+$", "");
        int cut = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return cut < 0 ? trimmed : trimmed.substring(cut + 1);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Un titre en nom de fichier ASCII. */
    static String slug(String title, String fallback) {
        String slug = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? fallback : slug;
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.FRANCE, "%.1f", bytes / (1024.0 * 1024.0));
    }

    private static String text(JsonNode node, String field) {
        return node == null || !node.hasNonNull(field) ? "" : node.path(field).asText("").strip();
    }

    /** La section « Pages » ajoutée au bas du corps Markdown. */
    public static String linksSection(List<PageLink> links) {
        if (links.isEmpty()) {
            return "";
        }
        StringBuilder md = new StringBuilder("\n\n---\n\n**Pages** (liens privés : connexion à claude-gateway "
                + "requise)\n\n");
        for (PageLink link : links) {
            md.append("- ").append(RadarMarkdown.text(link.title())).append(" — <").append(link.url()).append(">\n");
        }
        return md.toString();
    }
}
