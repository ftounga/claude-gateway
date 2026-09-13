package fr.claudegateway.runner.teams;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Lire une bibliothèque SharePoint / OneDrive</b> par son API REST documentée (F-108 / SF-108-03).
 *
 * <p><b>Forme éprouvée sur documentation, à confirmer sur poste réel.</b> La forme lue ici est celle
 * que la documentation publique de l'API REST SharePoint donne pour
 * {@code GetFolderByServerRelativePath(decodedurl='…')/Folders}, {@code …/Files} et
 * {@code GetFileByServerRelativePath(decodedurl='…')} en {@code odata=nometadata} :
 * {@code {"value":[{"Name":…,"ServerRelativeUrl":…,"Length":…,"TimeLastModified":…}]}}. Aucun tenant
 * réel n'a servi à l'écrire ; les réponses modèles de {@code src/test/resources/teams/sharepoint-*}
 * en sont tirées.</p>
 *
 * <p><b>Jamais à moitié faux</b> — la règle de l'adaptateur Teams, appliquée aux fichiers : si une
 * réponse réelle ne correspond pas au modèle (enveloppe absente, un élément sans nom, sans chemin ou
 * sans taille), <b>rien</b> n'est rendu et le manque nomme les champs absents. Une liste de fichiers
 * à laquelle il manque silencieusement des lignes se lirait « voilà tout le dossier ».</p>
 */
public final class SharePointFiles {

    /** Mention portée par chaque résultat qui passe par ces adaptateurs. */
    public static final String PROVENANCE =
            "forme éprouvée sur documentation, à confirmer sur poste réel";

    /** Éléments rendus au plus par dossier : au-delà, le plafond est dit. */
    public static final int MAX_ENTRIES = 500;

    static final long READ_TIMEOUT_MS = 60_000L;

    private static final List<String> FOLDER_FIELDS = List.of("Name", "ServerRelativeUrl");
    private static final List<String> FILE_FIELDS =
            List.of("Name", "ServerRelativeUrl", "Length", "TimeLastModified");

    private SharePointFiles() {
    }

    /** Un dossier ou un fichier, tel que la bibliothèque le décrit. */
    public record Entry(boolean folder, String name, String serverPath, long size, String modified,
            String id, String version, int itemCount) {
    }

    /** Une liste, et ce qui n'a pas pu être lu à côté. {@code entries} est vide dès qu'un manque bloque. */
    public record Listing(List<Entry> entries, List<TeamsGap> gaps) {

        public boolean ok() {
            return gaps.stream().noneMatch(gap -> gap.kind() != TeamsGapKind.CAP_REACHED);
        }
    }

    /** Un seul élément, ou le manque qui l'empêche. */
    public record Item(Entry entry, List<TeamsGap> gaps) {

        public boolean ok() {
            return entry != null && gaps.isEmpty();
        }
    }

    // ------------------------------------------------------------------ opérations

    /** Liste un dossier : ses sous-dossiers, puis ses fichiers. */
    public static Listing list(SharePointPage.Visit visit, SharePointLocation folder) {
        String path = SharePointPage.literal(folder.serverPath());
        SharePointPage.Answer folders = visit.run("lister les dossiers", folder,
                "return await call('GET', \"/_api/web/GetFolderByServerRelativePath(decodedurl='\" + lit("
                        + path + ") + \"')/Folders?$select=Name,ServerRelativeUrl,ItemCount,"
                        + "TimeLastModified,UniqueId\");", READ_TIMEOUT_MS);
        if (!folders.ok()) {
            return new Listing(List.of(), List.of(gapOf(folders, folder.label())));
        }
        SharePointPage.Answer files = visit.run("lister les fichiers", folder,
                "return await call('GET', \"/_api/web/GetFolderByServerRelativePath(decodedurl='\" + lit("
                        + path + ") + \"')/Files?$select=Name,ServerRelativeUrl,Length,"
                        + "TimeLastModified,UniqueId,UIVersionLabel\");", READ_TIMEOUT_MS);
        if (!files.ok()) {
            return new Listing(List.of(), List.of(gapOf(files, folder.label())));
        }
        return parseListing(folder, folders.body(), files.body());
    }

    /** Les métadonnées d'un fichier. */
    public static Item file(SharePointPage.Visit visit, SharePointLocation file) {
        SharePointPage.Answer answer = visit.run("lire les métadonnées du fichier", file,
                "return await call('GET', \"/_api/web/GetFileByServerRelativePath(decodedurl='\" + lit("
                        + SharePointPage.literal(file.serverPath()) + ") + \"')?$select=Name,"
                        + "ServerRelativeUrl,Length,TimeLastModified,UniqueId,UIVersionLabel\");",
                READ_TIMEOUT_MS);
        if (!answer.ok()) {
            return new Item(null, List.of(gapOf(answer, file.label())));
        }
        return parseFile(file, answer.body());
    }

    // ------------------------------------------------------------------ lecture des réponses

    /** Lit les deux réponses d'une liste. Pur : c'est ce que les réponses modèles éprouvent. */
    static Listing parseListing(SharePointLocation where, JsonNode foldersBody, JsonNode filesBody) {
        JsonNode folders = foldersBody == null ? null : foldersBody.get("value");
        JsonNode files = filesBody == null ? null : filesBody.get("value");
        if (folders == null || !folders.isArray() || files == null || !files.isArray()) {
            return new Listing(List.of(), List.of(TeamsGap.of(TeamsGapKind.SHAPE_MISMATCH,
                    where.label(), "enveloppe « value » absente — " + PROVENANCE)));
        }
        List<String> missing = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        boolean libraryRoot = where.underSite().size() == 1;
        for (JsonNode node : folders) {
            List<String> absent = absent(node, FOLDER_FIELDS);
            if (!absent.isEmpty()) {
                addAll(missing, absent);
                continue;
            }
            String serverPath = node.path("ServerRelativeUrl").asText();
            // Le dossier technique « Forms » d'une bibliothèque n'est pas un dossier de l'utilisateur.
            if (libraryRoot && "Forms".equals(node.path("Name").asText())) {
                continue;
            }
            entries.add(new Entry(true, node.path("Name").asText(), serverPath, -1,
                    node.path("TimeLastModified").asText(""), node.path("UniqueId").asText(""), "",
                    node.path("ItemCount").asInt(-1)));
        }
        for (JsonNode node : files) {
            List<String> absent = absent(node, FILE_FIELDS);
            long size = size(node.get("Length"));
            if (absent.isEmpty() && size < 0) {
                absent = List.of("Length");
            }
            if (!absent.isEmpty()) {
                addAll(missing, absent);
                continue;
            }
            entries.add(fileEntry(node, size));
        }
        if (!missing.isEmpty()) {
            return new Listing(List.of(), List.of(TeamsGap.of(TeamsGapKind.SHAPE_MISMATCH,
                    where.label(), "champs absents : " + String.join(", ", missing)
                            + " — rien n'est rendu plutôt qu'une liste incomplète (" + PROVENANCE
                            + ")")));
        }
        List<TeamsGap> gaps = new ArrayList<>();
        if (entries.size() > MAX_ENTRIES) {
            gaps.add(new TeamsGap(TeamsGapKind.CAP_REACHED, where.label(),
                    "seuls les " + MAX_ENTRIES + " premiers éléments sont rendus",
                    entries.size() - MAX_ENTRIES));
            entries = entries.subList(0, MAX_ENTRIES);
        }
        return new Listing(List.copyOf(entries), gaps);
    }

    /** Lit la réponse d'un fichier. */
    static Item parseFile(SharePointLocation where, JsonNode body) {
        if (body == null || !body.isObject()) {
            return new Item(null, List.of(TeamsGap.of(TeamsGapKind.SHAPE_MISMATCH, where.label(),
                    "réponse vide — " + PROVENANCE)));
        }
        List<String> absent = new ArrayList<>(absent(body, FILE_FIELDS));
        long size = size(body.get("Length"));
        if (absent.isEmpty() && size < 0) {
            absent.add("Length");
        }
        if (!absent.isEmpty()) {
            return new Item(null, List.of(TeamsGap.of(TeamsGapKind.SHAPE_MISMATCH, where.label(),
                    "champs absents : " + String.join(", ", absent) + " — " + PROVENANCE)));
        }
        return new Item(fileEntry(body, size), List.of());
    }

    /** Le manque qui correspond à une réponse en échec. */
    static TeamsGap gapOf(SharePointPage.Answer answer, String where) {
        String detail = answer.error() == null || answer.error().isBlank()
                ? "HTTP " + answer.status() : "HTTP " + answer.status() + " : " + answer.error();
        if (answer.status() == 401 || answer.status() == 403) {
            return TeamsGap.of(TeamsGapKind.ACCESS_DENIED, where, detail);
        }
        if (answer.status() == 404) {
            return TeamsGap.of(TeamsGapKind.NOT_FOUND, where, detail);
        }
        if (answer.status() == 0) {
            return TeamsGap.of(TeamsGapKind.BODY_UNAVAILABLE, where,
                    answer.error() == null || answer.error().isBlank()
                            ? "appel impossible depuis la page" : answer.error());
        }
        return TeamsGap.of(TeamsGapKind.BODY_UNAVAILABLE, where, detail);
    }

    private static Entry fileEntry(JsonNode node, long size) {
        return new Entry(false, node.path("Name").asText(), node.path("ServerRelativeUrl").asText(),
                size, node.path("TimeLastModified").asText(""), node.path("UniqueId").asText(""),
                node.path("UIVersionLabel").asText(""), -1);
    }

    private static List<String> absent(JsonNode node, List<String> fields) {
        List<String> absent = new ArrayList<>();
        for (String field : fields) {
            JsonNode value = node == null ? null : node.get(field);
            if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
                absent.add(field);
            }
        }
        return absent;
    }

    /** {@code Length} est une chaîne dans la documentation ({@code "18432"}) ; un nombre est accepté. */
    static long size(JsonNode node) {
        if (node == null || node.isNull()) {
            return -1;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        try {
            return Long.parseLong(node.asText("").strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void addAll(List<String> into, List<String> more) {
        more.forEach(value -> {
            if (!into.contains(value)) {
                into.add(value);
            }
        });
    }
}
