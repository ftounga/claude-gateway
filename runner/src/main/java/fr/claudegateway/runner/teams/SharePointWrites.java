package fr.claudegateway.runner.teams;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Écrire dans une bibliothèque SharePoint / OneDrive</b> par son API REST documentée
 * (F-108 / SF-108-04).
 *
 * <p><b>Forme éprouvée sur documentation, à confirmer sur poste réel.</b> Les appels sont ceux que la
 * documentation publique de l'API REST SharePoint donne : {@code POST /_api/contextinfo} pour le digest
 * de formulaire, {@code POST /_api/web/folders}, {@code …/Files/AddUsingPath(DecodedUrl,Overwrite)},
 * {@code …/MoveTo(newurl,flags)}, {@code …/recycle()}.</p>
 *
 * <p><b>Le digest ne sort jamais de la page</b> : il est obtenu par la fonction d'appel du script
 * ({@link SharePointPage}), posé dans l'en-tête {@code X-RequestDigest} et oublié ; ce qui revient est
 * la projection sur liste blanche. <b>Aucune écriture n'écrase</b> sans le dire : dossier et dépôt
 * refusent un élément existant, {@code MoveTo} est appelé sans écrasement ({@code flags=0}), la
 * suppression va à la <b>corbeille</b> du site. Seul le remplacement de version écrase — et
 * SharePoint en garde l'historique.</p>
 *
 * <p>Ces opérations n'ont rien à décider : elles ne sont appelées qu'après l'autorisation de
 * l'utilisateur, donnée côté gateway pour <b>chaque</b> écriture (SF-108-02).</p>
 */
public final class SharePointWrites {

    static final long WRITE_TIMEOUT_MS = 90_000L;
    static final long UPLOAD_TIMEOUT_MS = 300_000L;
    /** Un dépôt découpé peut être long : on attend davantage (F-108 / SF-108-06). */
    static final long LARGE_UPLOAD_TIMEOUT_MS = 870_000L;
    /** Au-delà, l'API impose un dépôt <b>découpé</b> ({@link #uploadChunked}) — F-108 / SF-108-06. */
    public static final long SIMPLE_UPLOAD_LIMIT_BYTES = 250L * 1024 * 1024;
    /** Le plafond dur d'un dépôt : au-delà, refus nommé avant tout geste (F-108 / SF-108-06). */
    public static final long MAX_UPLOAD_BYTES = 15L * 1024 * 1024 * 1024;
    /** La taille d'une tranche de la session d'envoi découpée : découpée par le navigateur. */
    static final int UPLOAD_CHUNK_BYTES = 10 * 1024 * 1024;

    private static final String FORBIDDEN_CHARS = "/\\:*?\"<>|";

    private SharePointWrites() {
    }

    /** Ce qui se trouve à un emplacement : un fichier, un dossier, rien — ou un manque qui empêche de le savoir. */
    record Existing(boolean file, boolean folder, SharePointFiles.Entry entry, TeamsGap gap) {

        boolean nothing() {
            return !file && !folder && gap == null;
        }

        static Existing none() {
            return new Existing(false, false, null, null);
        }

        static Existing failed(TeamsGap gap) {
            return new Existing(false, false, null, gap);
        }
    }

    /** Pourquoi ce nom est refusé, ou {@code ""} s'il est acceptable. */
    static String invalidName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            return "nom vide";
        }
        if (name.length() > 255) {
            return "nom de plus de 255 caractères";
        }
        if (".".equals(name) || "..".equals(name)) {
            return "« " + name + " » n'est pas un nom";
        }
        for (char c : name.toCharArray()) {
            if (c < 32 || FORBIDDEN_CHARS.indexOf(c) >= 0) {
                return "caractère interdit par SharePoint dans « " + name + " » (/ \\ : * ? \" < > |)";
            }
        }
        if (name.endsWith(".")) {
            return "un nom ne peut pas finir par un point";
        }
        return "";
    }

    // ------------------------------------------------------------------ lectures d'appui

    /** Qu'y a-t-il à cet emplacement ? Un fichier d'abord, puis un dossier. */
    static Existing probe(SharePointPage.Visit visit, SharePointLocation target) {
        SharePointPage.Answer asFile = visit.run("vérifier le fichier", target,
                "return await call('GET', \"/_api/web/GetFileByServerRelativePath(decodedurl='\" + lit("
                        + SharePointPage.literal(target.serverPath()) + ") + \"')?$select=Name,"
                        + "ServerRelativeUrl,Length,TimeLastModified,UniqueId,UIVersionLabel\");",
                WRITE_TIMEOUT_MS);
        if (asFile.ok()) {
            SharePointFiles.Item item = SharePointFiles.parseFile(target, asFile.body());
            return item.ok() ? new Existing(true, false, item.entry(), null)
                    : Existing.failed(item.gaps().get(0));
        }
        if (asFile.status() != 404) {
            return Existing.failed(SharePointFiles.gapOf(asFile, target.label()));
        }
        SharePointPage.Answer asFolder = visit.run("vérifier le dossier", target,
                "return await call('GET', \"/_api/web/GetFolderByServerRelativePath(decodedurl='\" + lit("
                        + SharePointPage.literal(target.serverPath()) + ") + \"')?$select=Name,"
                        + "ServerRelativeUrl,Exists,ItemCount,UniqueId,TimeLastModified\");",
                WRITE_TIMEOUT_MS);
        if (asFolder.status() == 404) {
            return Existing.none();
        }
        if (!asFolder.ok()) {
            return Existing.failed(SharePointFiles.gapOf(asFolder, target.label()));
        }
        JsonNode body = asFolder.body();
        if (body == null || (body.has("Exists") && !body.path("Exists").asBoolean(true))) {
            return Existing.none();
        }
        if (body.path("Name").asText("").isBlank() || body.path("ServerRelativeUrl").asText("").isBlank()) {
            return Existing.failed(TeamsGap.of(TeamsGapKind.SHAPE_MISMATCH, target.label(),
                    "champs absents : Name, ServerRelativeUrl — " + SharePointFiles.PROVENANCE));
        }
        return new Existing(false, true, new SharePointFiles.Entry(true, body.path("Name").asText(),
                body.path("ServerRelativeUrl").asText(), -1, body.path("TimeLastModified").asText(""),
                body.path("UniqueId").asText(""), "", body.path("ItemCount").asInt(-1)), null);
    }

    // ------------------------------------------------------------------ écritures

    /** {@code POST /_api/web/folders} — la forme documentée de la création d'un dossier. */
    static SharePointPage.Answer createFolder(SharePointPage.Visit visit, SharePointLocation folder) {
        return visit.run("créer le dossier", folder,
                "return await call('POST', '/_api/web/folders', { contentType: "
                        + "'application/json;odata=verbose', body: JSON.stringify({ __metadata: "
                        + "{ type: 'SP.Folder' }, ServerRelativeUrl: "
                        + SharePointPage.literal(folder.serverPath()) + " }) });",
                WRITE_TIMEOUT_MS);
    }

    /**
     * Dépose le fichier posé dans le champ {@code inputId} — <b>Chrome</b> lit le disque ; le script
     * donne le fichier tel quel comme corps de la requête, sans le lire.
     */
    static SharePointPage.Answer upload(SharePointPage.Visit visit, SharePointLocation folder,
            String name, String inputId, boolean overwrite) {
        return visit.run(overwrite ? "remplacer la version" : "déposer le fichier", folder,
                "const input = document.getElementById(" + SharePointPage.literal(inputId) + ");"
                        + " const file = input && input.files && input.files[0];"
                        + " if (!file) { return { ok: false, status: 0,"
                        + " error: 'fichier non posé dans la page' }; }"
                        + " try { return await call('POST', \"/_api/web/GetFolderByServerRelativePath("
                        + "decodedurl='\" + lit(" + SharePointPage.literal(folder.serverPath())
                        + ") + \"')/Files/AddUsingPath(DecodedUrl='\" + lit("
                        + SharePointPage.literal(name) + ") + \"',Overwrite=" + overwrite
                        + ")\", { body: file }); } finally { input.remove(); }",
                UPLOAD_TIMEOUT_MS);
    }

    /**
     * <b>Dépôt par session d'envoi découpée</b> (F-108 / SF-108-06) — pour les fichiers au-delà de
     * {@link #SIMPLE_UPLOAD_LIMIT_BYTES}, que {@code AddUsingPath} refuse d'un bloc.
     *
     * <p>Tout tient dans <b>un seul script de page</b> : Chrome lit le disque (le fichier posé dans
     * {@code inputId}), le fichier vide est créé ({@code AddUsingPath}), puis la session
     * {@code StartUpload} / {@code ContinueUpload} / {@code FinishUpload} envoie des tranches de
     * {@link #UPLOAD_CHUNK_BYTES} découpées par {@code File.slice} <b>dans le navigateur</b> — les
     * octets ne franchissent jamais {@code Runtime.evaluate}. Le corps rendu est la File finale,
     * projetée. Un fragment en échec abandonne la session et rend l'erreur Microsoft, jamais un
     * faux succès.</p>
     *
     * <p><b>Forme éprouvée sur documentation, à confirmer sur poste réel.</b></p>
     */
    static SharePointPage.Answer uploadChunked(SharePointPage.Visit visit, SharePointLocation folder,
            String name, String inputId, boolean overwrite) {
        String filePath = folder.serverPath() + "/" + name;
        String fileRes = "\"/_api/web/GetFileByServerRelativePath(decodedurl='\" + lit("
                + SharePointPage.literal(filePath) + ") + \"')\"";
        String body = "const input = document.getElementById(" + SharePointPage.literal(inputId) + ");"
                + " const file = input && input.files && input.files[0];"
                + " if (!file) { return { ok: false, status: 0,"
                + " error: 'fichier non posé dans la page' }; }"
                + " try {"
                + "  const created = await call('POST', \"/_api/web/GetFolderByServerRelativePath("
                + "decodedurl='\" + lit(" + SharePointPage.literal(folder.serverPath())
                + ") + \"')/Files/AddUsingPath(DecodedUrl='\" + lit(" + SharePointPage.literal(name)
                + ") + \"',Overwrite=" + overwrite + ")\", { body: new Blob([]) });"
                + "  if (!created.ok) { return created; }"
                + "  const fileRes = " + fileRes + ";"
                + "  const uploadId = (self.crypto && self.crypto.randomUUID) ? self.crypto.randomUUID()"
                + " : ('cg' + Date.now() + Math.random().toString(16).slice(2));"
                + "  const size = file.size; const chunk = " + UPLOAD_CHUNK_BYTES + ";"
                + "  let offset = 0; let first = true; let last = null;"
                + "  while (offset < size) {"
                + "   const end = Math.min(offset + chunk, size); const isLast = end >= size;"
                + "   const part = file.slice(offset, end); let r;"
                + "   if (first) { r = await call('POST', fileRes + \"/StartUpload(uploadId=guid'\""
                + " + uploadId + \"')\", { body: part }); first = false; }"
                + "   else if (isLast) { r = await call('POST', fileRes + \"/FinishUpload(uploadId=guid'\""
                + " + uploadId + \"',fileOffset=\" + offset + \")\", { body: part }); last = r; }"
                + "   else { r = await call('POST', fileRes + \"/ContinueUpload(uploadId=guid'\""
                + " + uploadId + \"',fileOffset=\" + offset + \")\", { body: part }); }"
                + "   if (!r.ok) { return r; } offset = end;"
                + "  }"
                + "  if (last) { return last; }"
                + "  return await call('GET', fileRes + \"?$select=Name,ServerRelativeUrl,Length,"
                + "UIVersionLabel,UniqueId\");"
                + " } finally { if (input) { input.remove(); } }";
        return visit.run(overwrite ? "remplacer par fragments" : "déposer par fragments", folder, body,
                LARGE_UPLOAD_TIMEOUT_MS);
    }

    /** Le script qui crée le champ de dépôt — un champ à nous, pas un sélecteur de l'interface. */
    static String inputScript(String inputId) {
        return "(() => { /*cg-input*/ const i = document.createElement('input'); i.type = 'file';"
                + " i.id = " + SharePointPage.literal(inputId) + "; i.style.display = 'none';"
                + " (document.body || document.documentElement).appendChild(i); return true; })()";
    }

    /** {@code MoveTo(newurl, flags=0)} : renommer ou déplacer, <b>sans jamais écraser</b>. */
    static SharePointPage.Answer moveTo(SharePointPage.Visit visit, SharePointLocation source,
            SharePointLocation destination, boolean folder) {
        String resource = folder ? "GetFolderByServerRelativePath" : "GetFileByServerRelativePath";
        return visit.run("renommer ou déplacer", source,
                "return await call('POST', \"/_api/web/" + resource + "(decodedurl='\" + lit("
                        + SharePointPage.literal(source.serverPath()) + ") + \"')/MoveTo(newurl='\" + lit("
                        + SharePointPage.literal(destination.serverPath()) + ") + \"'"
                        + (folder ? "" : ",flags=0") + ")\");",
                WRITE_TIMEOUT_MS);
    }

    /**
     * <b>Copier vers un autre dossier — du même site ou d'un autre site</b> (F-108 / SF-108-06) :
     * {@code SP.MoveCopyUtil.CopyFileByPath} (fichier) ou {@code CopyFolderByPath} (dossier), <b>sans
     * jamais écraser</b> ({@code overwrite=false}). L'appel est POSTé au site de la <b>source</b> (où
     * l'onglet se trouve) ; source et destination sont désignées par leur <b>adresse absolue décodée</b>.
     *
     * <p>Une copie vers un <b>autre hôte</b> (OneDrive {@code -my}, autre tenant) ne peut pas être
     * revérifiée depuis l'onglet de la source : l'appelant le dit ({@code verifyAtDestination}). Dans
     * tous les cas, un déplacement inter-site reste <b>copie puis corbeille, deux autorisations</b>.</p>
     *
     * <p><b>Forme éprouvée sur documentation, à confirmer sur poste réel.</b></p>
     */
    static SharePointPage.Answer copyTo(SharePointPage.Visit visit, SharePointLocation source,
            SharePointLocation destination, boolean folder) {
        String method = folder ? "CopyFolderByPath" : "CopyFileByPath";
        String srcAbs = source.origin() + source.serverPath();
        String destAbs = destination.origin() + destination.serverPath();
        return visit.run("copier", source,
                "return await call('POST', '/_api/SP.MoveCopyUtil." + method + "', { contentType: "
                        + "'application/json;odata=verbose', body: JSON.stringify({ srcPath: { "
                        + "__metadata: { type: 'SP.ResourcePath' }, DecodedUrl: "
                        + SharePointPage.literal(srcAbs) + " }, destPath: { __metadata: "
                        + "{ type: 'SP.ResourcePath' }, DecodedUrl: " + SharePointPage.literal(destAbs)
                        + " }, overwrite: false, options: { __metadata: { type: 'SP.MoveCopyOptions' },"
                        + " KeepBoth: false, ResetAuthorAndCreatedOnCopy: false,"
                        + " ShouldBypassSharedLocks: false } }) });",
                UPLOAD_TIMEOUT_MS);
    }

    /** {@code recycle()} : à la corbeille du site, restaurable. */
    static SharePointPage.Answer recycle(SharePointPage.Visit visit, SharePointLocation target,
            boolean folder) {
        String resource = folder ? "GetFolderByServerRelativePath" : "GetFileByServerRelativePath";
        return visit.run("mettre à la corbeille", target,
                "return await call('POST', \"/_api/web/" + resource + "(decodedurl='\" + lit("
                        + SharePointPage.literal(target.serverPath()) + ") + \"')/recycle()\");",
                WRITE_TIMEOUT_MS);
    }

    /** Le manque qui correspond à une écriture refusée par Microsoft 365. */
    static TeamsGap gapOfWrite(SharePointPage.Answer answer, String where) {
        String message = answer.error() == null ? "" : answer.error();
        String lower = message.toLowerCase(Locale.ROOT);
        String detail = "HTTP " + answer.status() + (message.isBlank() ? "" : " : " + message);
        if (lower.contains("already exists") || lower.contains("existe déjà")) {
            return TeamsGap.of(TeamsGapKind.ALREADY_EXISTS, where, detail);
        }
        if (answer.status() == 423 || lower.contains("locked") || lower.contains("checked out")) {
            return TeamsGap.of(TeamsGapKind.WRITE_FAILED, where,
                    detail + " — l'élément est verrouillé ou extrait par quelqu'un");
        }
        if (answer.status() == 401 || answer.status() == 403 || answer.status() == 404
                || answer.status() == 0) {
            return SharePointFiles.gapOf(answer, where);
        }
        return TeamsGap.of(TeamsGapKind.WRITE_FAILED, where, detail);
    }

    /** Vrai si le corps d'une réponse de fichier ou de dossier porte ce qu'on attend. */
    static boolean describesItem(JsonNode body, List<String> fields) {
        if (body == null || !body.isObject()) {
            return false;
        }
        for (String field : fields) {
            if (body.path(field).asText("").isBlank()) {
                return false;
            }
        }
        return true;
    }
}
