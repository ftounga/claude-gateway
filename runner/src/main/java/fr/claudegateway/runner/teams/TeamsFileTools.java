package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.ToolOutcome;

/**
 * <b>Les fichiers de Teams, SharePoint et OneDrive</b> (F-108 / SF-108-03) — lister, et rapatrier sur
 * la machine.
 *
 * <h2>Deux chemins, et l'outil dit lequel il a pris</h2>
 *
 * <ol>
 *   <li><b>Le dossier synchronisé</b> ({@link SyncedLibraries}) : si la bibliothèque est déjà sur le
 *       disque, aucun geste — le disque fait foi ({@code route: SYNCED_FOLDER}).</li>
 *   <li><b>Le navigateur</b> : l'onglet relié est amené sur le site ({@link SharePointPage}), l'API
 *       REST documentée est appelée depuis la page, la vue est remise, et c'est Chrome qui télécharge
 *       ({@link ChromeDownloads}) ({@code route: BROWSER}).</li>
 * </ol>
 *
 * <h2>Ce qu'aucun résultat ne tait</h2>
 *
 * <p>Les gestes faits dans l'onglet ({@code gestures}), la vue remise ({@code viewport}), ce qui n'a
 * pas pu être lu ({@code gaps}) — et la <b>provenance</b> : ces adaptateurs sont écrits sur la
 * documentation publique Microsoft, <b>à confirmer sur poste réel</b>. Ce sont des <b>lectures</b> :
 * elles ne demandent aucune confirmation (cadrage §4.4).</p>
 */
final class TeamsFileTools {

    static final long DOCUMENT_WAIT_MS = 60_000L;

    /** Au-delà, un {@code .docx} n'est pas lu en mémoire (F-108 / SF-108-06) : refus nommé. */
    static final long MAX_DOCX_BYTES = 50L * 1024 * 1024;

    /** Ce que ces outils empruntent aux outils Teams : la liaison, le registre, la version. */
    interface Host {
        BrowserLink link();

        TeamsLedger ledger();

        /** Récolte ce que la page a déjà reçu, pour que le registre soit à jour. */
        List<TeamsGap> harvest();

        String adapterVersion();

        String firstUse();
    }

    private final Host host;
    private final BrowserLink.Sleeper sleeper;
    private final TeamsWorkFolder folder;
    private final SyncedLibraries synced;
    private final ChromeDownloads downloads;
    private final java.util.function.Consumer<String> say;

    TeamsFileTools(Host host, BrowserLink.Sleeper sleeper, TeamsWorkFolder folder,
            SyncedLibraries synced, java.util.function.Consumer<String> say) {
        this.host = host;
        this.sleeper = sleeper;
        this.folder = folder;
        this.synced = synced;
        this.downloads = new ChromeDownloads(sleeper);
        this.say = say == null ? line -> { } : say;
    }

    // ------------------------------------------------------------------ teams_list_files

    ToolOutcome listFiles(JsonNode input) {
        TeamsToolResult result = result(TeamsTools.LIST_FILES);
        List<TeamsGap> gaps = new ArrayList<>();
        String location = TeamsAsk.text(input, "location", "folder", "url");

        if (!location.isEmpty()) {
            SharePointLocation.Parsed parsed = SharePointLocation.parse(location);
            if (!parsed.ok()) {
                return refusedLocation(result, location, parsed.refusal());
            }
            return listAt(result, parsed.location(), gaps, List.of());
        }

        // Sans adresse : il faut le registre (pièces jointes, sites observés), donc la liaison.
        BrowserLink link;
        try {
            link = host.link();
        } catch (RuntimeException e) {
            return unlinked(result, e);
        }
        gaps.addAll(host.harvest());
        Known known = known(link);

        boolean oneDrive = Boolean.TRUE.equals(TeamsAsk.flag(input, "onedrive"));
        String conversationId = TeamsAsk.text(input, "conversation_id", "conversationId");
        String team = TeamsAsk.text(input, "team", "site");
        String channel = TeamsAsk.text(input, "channel");

        if (oneDrive) {
            return listOneDrive(result, known, gaps, TeamsAsk.text(input, "path"));
        }
        if (!conversationId.isEmpty()) {
            List<Candidate> inConversation = known.attachments().stream()
                    .filter(candidate -> conversationId.equals(candidate.conversationId()))
                    .toList();
            if (inConversation.isEmpty()) {
                gaps.add(TeamsGap.of(TeamsGapKind.LOCATION_UNKNOWN, "conversation " + conversationId,
                        "aucun fichier partagé n'a été observé dans cette conversation : ouvrez son "
                                + "onglet Fichiers dans Teams, ou donnez l'adresse du dossier"));
                return knownLocations(result, known, gaps);
            }
            return listAt(result, inConversation.get(0).location(), gaps,
                    inConversation.subList(1, inConversation.size()));
        }
        if (!team.isEmpty() || !channel.isEmpty()) {
            SharePointLocation site = matchSite(known, team.isEmpty() ? channel : team);
            if (site == null) {
                gaps.add(TeamsGap.of(TeamsGapKind.LOCATION_UNKNOWN, team.isEmpty() ? channel : team,
                        "le site SharePoint de cette équipe n'a pas été observé : ouvrez l'onglet "
                                + "Fichiers de l'équipe dans Teams, puis redemandez, ou donnez "
                                + "l'adresse du dossier"));
                return knownLocations(result, known, gaps);
            }
            SharePointLocation target = channel.isEmpty() ? site : site.child(channel);
            return listAt(result, target, gaps, List.of());
        }
        return knownLocations(result, known, gaps);
    }

    /** Liste un emplacement résolu : synchronisé d'abord, sinon par le navigateur. */
    private ToolOutcome listAt(TeamsToolResult result, SharePointLocation location,
            List<TeamsGap> gaps, List<Candidate> others) {
        renderLocation(result, location);
        if (!others.isEmpty()) {
            ArrayNode more = result.array("otherLocations");
            others.forEach(other -> candidate(more, other));
        }
        Path local = synced.resolve(location).orElse(null);
        if (local != null && Files.isDirectory(local)) {
            return listSynced(result, location, local, gaps, List.of(), "");
        }
        BrowserLink link;
        try {
            link = host.link();
        } catch (RuntimeException e) {
            return unlinked(result, e);
        }
        List<PageActions.GestureRecord> journal = new ArrayList<>();
        PageActions actions = actions(link, journal);
        SharePointPage.Visit visit = null;
        SharePointFiles.Listing listing = new SharePointFiles.Listing(List.of(), List.of());
        try {
            visit = new SharePointPage(actions, sleeper).open(location);
            listing = SharePointFiles.list(visit, location);
        } catch (SharePointPage.Refused refused) {
            gaps.add(TeamsGap.of(refused.kind(), location.label(), refused.getMessage()));
        } catch (BrowserLinkException refused) {
            gaps.add(gapOf(refused, location.label()));
        } finally {
            if (visit != null) {
                visit.close();
            }
        }
        gaps.addAll(listing.gaps());
        result.with("route", "BROWSER");
        ArrayNode items = result.array("items");
        listing.entries().forEach(entry -> entry(items, location, entry));
        StringBuilder text = new StringBuilder(describeCount(listing.entries()))
                .append(" dans « ").append(location.label()).append(" », lus par le navigateur.");
        return finish(result, gaps, journal, visit == null ? "" : visit.viewport(), text);
    }

    private ToolOutcome listSynced(TeamsToolResult result, SharePointLocation location, Path local,
            List<TeamsGap> gaps, List<PageActions.GestureRecord> journal, String viewport) {
        result.with("route", "SYNCED_FOLDER");
        result.with("localPath", local.toString());
        ArrayNode items = result.array("items");
        List<Path> children;
        try (Stream<Path> stream = Files.list(local)) {
            children = stream.sorted().toList();
        } catch (IOException e) {
            children = List.of();
            gaps.add(TeamsGap.of(TeamsGapKind.BODY_UNAVAILABLE, local.toString(),
                    "dossier synchronisé illisible"));
        }
        int folders = 0;
        int files = 0;
        for (Path child : children) {
            if (folders + files >= SharePointFiles.MAX_ENTRIES) {
                gaps.add(new TeamsGap(TeamsGapKind.CAP_REACHED, location.label(),
                        "seuls les " + SharePointFiles.MAX_ENTRIES + " premiers éléments sont rendus",
                        children.size() - SharePointFiles.MAX_ENTRIES));
                break;
            }
            ObjectNode item = items.addObject();
            boolean folder = Files.isDirectory(child);
            String name = child.getFileName().toString();
            item.put("kind", folder ? "folder" : "file");
            item.put("name", name);
            item.put("localPath", child.toString());
            item.put("webUrl", location.child(name).webUrl());
            try {
                item.put("modified", Files.getLastModifiedTime(child).toInstant().toString());
                if (!folder) {
                    item.put("size", Files.size(child));
                }
            } catch (IOException e) {
                // la date ou la taille manquent : le nom reste exact
            }
            if (folder) {
                folders++;
            } else {
                files++;
            }
        }
        String text = count(folders, "dossier", "dossiers") + " et " + count(files, "fichier",
                "fichiers") + " dans « " + location.label() + " », lus dans le dossier synchronisé "
                + local + (journal.isEmpty() ? " (aucun geste dans le navigateur)." : ".");
        return finish(result, gaps, journal, viewport, new StringBuilder(text));
    }

    /** Le OneDrive de l'utilisateur : hôte observé, adresse personnelle lue depuis la page. */
    private ToolOutcome listOneDrive(TeamsToolResult result, Known known, List<TeamsGap> gaps,
            String path) {
        String myHost = known.oneDriveHost();
        if (myHost.isEmpty()) {
            gaps.add(TeamsGap.of(TeamsGapKind.LOCATION_UNKNOWN, "OneDrive",
                    "l'hôte OneDrive de votre organisation n'a pas été observé : ouvrez OneDrive "
                            + "une fois dans le navigateur relié, ou donnez l'adresse du dossier"));
            return knownLocations(result, known, gaps);
        }
        BrowserLink link = host.link();
        List<PageActions.GestureRecord> journal = new ArrayList<>();
        PageActions actions = actions(link, journal);
        SharePointLocation hostRoot = new SharePointLocation("https://" + myHost, "", "/");
        SharePointPage.Visit visit = null;
        SharePointLocation personal = null;
        SharePointFiles.Listing listing = new SharePointFiles.Listing(List.of(), List.of());
        try {
            visit = new SharePointPage(actions, sleeper).open(hostRoot);
            SharePointPage.Answer me = visit.run("trouver le OneDrive personnel", hostRoot,
                    "return await call('GET', '/_api/SP.UserProfiles.PeopleManager/GetMyProperties"
                            + "?$select=PersonalUrl');", SharePointFiles.READ_TIMEOUT_MS);
            String personalUrl = me.ok() && me.body() != null
                    ? me.body().path("PersonalUrl").asText("") : "";
            if (!me.ok()) {
                gaps.add(SharePointFiles.gapOf(me, "OneDrive"));
            } else if (personalUrl.isBlank()) {
                gaps.add(TeamsGap.of(TeamsGapKind.SHAPE_MISMATCH, "OneDrive",
                        "champ absent : PersonalUrl — " + SharePointFiles.PROVENANCE));
            } else {
                String base = personalUrl.endsWith("/") ? personalUrl : personalUrl + "/";
                SharePointLocation.Parsed parsed = SharePointLocation.parse(base + "Documents"
                        + (path.isBlank() ? "" : "/" + path.strip()));
                if (!parsed.ok() || !parsed.location().sameOrigin(hostRoot.origin())) {
                    gaps.add(TeamsGap.of(TeamsGapKind.SHAPE_MISMATCH, "OneDrive",
                            "adresse personnelle inattendue — " + SharePointFiles.PROVENANCE));
                } else {
                    personal = parsed.location();
                    Path local = synced.resolve(personal).orElse(null);
                    if (local != null && Files.isDirectory(local)) {
                        visit.close();
                        renderLocation(result, personal);
                        return listSynced(result, personal, local, gaps, journal, visit.viewport());
                    }
                    listing = SharePointFiles.list(visit, personal);
                }
            }
        } catch (SharePointPage.Refused refused) {
            gaps.add(TeamsGap.of(refused.kind(), "OneDrive", refused.getMessage()));
        } catch (BrowserLinkException refused) {
            gaps.add(gapOf(refused, "OneDrive"));
        } finally {
            if (visit != null) {
                visit.close();
            }
        }
        gaps.addAll(listing.gaps());
        result.with("route", "BROWSER");
        if (personal != null) {
            renderLocation(result, personal);
        }
        ArrayNode items = result.array("items");
        SharePointLocation where = personal == null ? hostRoot : personal;
        listing.entries().forEach(entry -> entry(items, where, entry));
        StringBuilder text = new StringBuilder(describeCount(listing.entries()))
                .append(" dans « ").append(personal == null ? "OneDrive" : personal.label())
                .append(" », lus par le navigateur.");
        return finish(result, gaps, journal, visit == null ? "" : visit.viewport(), text);
    }

    /** Les emplacements connus, sans aucun geste. */
    private ToolOutcome knownLocations(TeamsToolResult result, Known known, List<TeamsGap> gaps) {
        ArrayNode locations = result.array("locations");
        known.attachments().forEach(candidate -> candidate(locations, candidate));
        known.sites().forEach(site -> candidate(locations,
                new Candidate(site, "site observé dans Teams", "")));
        ArrayNode roots = result.array("syncedRoots");
        synced.roots().forEach(root -> roots.add(root.toString()));
        result.array("items");
        StringBuilder text = new StringBuilder();
        int total = known.attachments().size() + known.sites().size();
        if (total == 0) {
            text.append("Je ne connais encore aucun emplacement de fichiers : aucun fichier partagé "
                    + "ni site SharePoint n'a été observé. Donnez l'adresse d'un dossier (location), "
                    + "ou ouvrez l'onglet Fichiers d'une équipe dans Teams puis redemandez.");
        } else {
            text.append(count(total, "emplacement connu", "emplacements connus")).append(" : ")
                    .append("redemandez avec « location » pour en lister un. Aucun geste n'a été fait.");
        }
        return finish(result, gaps, List.of(), "", text);
    }

    // ------------------------------------------------------------------ teams_read_file

    ToolOutcome readFile(JsonNode input) {
        TeamsToolResult result = result(TeamsTools.READ_FILE);
        List<TeamsGap> gaps = new ArrayList<>();
        String address = TeamsAsk.text(input, "file", "url", "location");
        result.json().put("downloaded", false);
        if (address.isEmpty()) {
            gaps.add(TeamsGap.of(TeamsGapKind.MISSING_FIELD, "lecture de fichier", "file"));
            return finish(result, gaps, List.of(), "", new StringBuilder(
                    "Donnez l'adresse web du fichier (« file ») : celle de la pièce jointe ou de la "
                            + "bibliothèque. Rien n'a été lu."));
        }
        SharePointLocation.Parsed parsed = SharePointLocation.parse(address);
        if (!parsed.ok()) {
            return refusedLocation(result, address, parsed.refusal());
        }
        SharePointLocation file = parsed.location();
        renderLocation(result, file);
        Path local = synced.resolve(file).orElse(null);
        if (local != null && Files.isRegularFile(local)) {
            result.with("route", "SYNCED_FOLDER");
            result.json().put("downloaded", true);
            result.with("localPath", local.toString());
            result.json().put("bytes", sizeOf(local));
            return finish(result, gaps, List.of(), "", new StringBuilder("« ").append(file.name())
                    .append(" » est déjà sur la machine, dans le dossier synchronisé : ").append(local)
                    .append(". Lisez-le avec les outils du poste (aucun geste dans le navigateur)."));
        }
        BrowserLink link;
        try {
            link = host.link();
        } catch (RuntimeException e) {
            return unlinked(result, e);
        }
        result.with("route", "BROWSER");
        List<PageActions.GestureRecord> journal = new ArrayList<>();
        PageActions actions = actions(link, journal);
        SharePointPage.Visit visit = null;
        ChromeDownloads.Outcome outcome = null;
        SharePointFiles.Entry entry = null;
        try {
            visit = new SharePointPage(actions, sleeper).open(file);
            SharePointFiles.Item item = SharePointFiles.file(visit, file);
            gaps.addAll(item.gaps());
            if (item.ok()) {
                entry = item.entry();
                Path dir = folder.downloadDir(entry.id().isBlank() ? file.name()
                        : entry.id() + "-v" + entry.version());
                outcome = downloads.check(dir, entry.name(), entry.size(), file.label());
                if (outcome.state() == ChromeDownloads.State.NONE
                        || outcome.state() == ChromeDownloads.State.BLOCKED) {
                    outcome = downloads.fetch(actions, file, dir, entry.name(), entry.size(),
                            DOCUMENT_WAIT_MS);
                }
            }
        } catch (SharePointPage.Refused refused) {
            gaps.add(TeamsGap.of(refused.kind(), file.label(), refused.getMessage()));
        } catch (BrowserLinkException refused) {
            gaps.add(gapOf(refused, file.label()));
        } finally {
            if (visit != null) {
                visit.close();
            }
        }
        StringBuilder text = new StringBuilder();
        if (entry != null) {
            result.with("version", entry.version());
            result.json().put("expectedBytes", entry.size());
        }
        if (outcome != null && outcome.done()) {
            result.json().put("downloaded", true);
            result.with("localPath", outcome.file().toString());
            result.json().put("bytes", outcome.bytes());
            text.append("« ").append(file.name()).append(" » a été téléchargé par Chrome sur la "
                    + "machine : ").append(outcome.file()).append(". Lisez-le avec les outils du poste.");
        } else if (outcome != null && outcome.state() == ChromeDownloads.State.IN_PROGRESS) {
            result.json().put("inProgress", true);
            result.json().put("bytes", outcome.bytes());
            text.append("Chrome télécharge encore « ").append(file.name()).append(" » (")
                    .append(outcome.bytes()).append(" octets reçus) : redemandez dans un instant, "
                            + "je reprendrai sans le relancer.");
        } else {
            if (outcome != null && outcome.gap() != null) {
                gaps.add(outcome.gap());
            }
            text.append("« ").append(file.name()).append(" » n'a PAS été rapatrié.");
        }
        return finish(result, gaps, journal, visit == null ? "" : visit.viewport(), text);
    }

    // ------------------------------------------------------------------ teams_read_docx

    /**
     * <b>Lit le texte d'un {@code .docx} déjà sur la machine</b> (F-108 / SF-108-06) — la transcription
     * Word rapatriée par {@code teams_read_file} ou {@code teams_meeting_recording}.
     *
     * <p>Une <b>lecture locale</b> : aucun réseau, aucun geste, aucune confirmation. Elle <b>relaie le
     * texte</b>, elle n'analyse rien (Provider-First). Un fichier qui n'est pas un {@code .docx}
     * lisible produit un <b>manque nommé</b>, jamais un contenu inventé.</p>
     */
    ToolOutcome readDocx(JsonNode input) {
        TeamsToolResult result = result(TeamsTools.READ_DOCX);
        List<TeamsGap> gaps = new ArrayList<>();
        String raw = TeamsAsk.text(input, "file", "path", "local_path");
        result.json().put("read", false);
        if (raw.isEmpty()) {
            gaps.add(TeamsGap.of(TeamsGapKind.MISSING_FIELD, "lecture d'un .docx", "file"));
            return finish(result, gaps, List.of(), "", new StringBuilder("Donnez le chemin ABSOLU du "
                    + ".docx sur la machine (« file »). Rien n'a été lu."));
        }
        Path path;
        try {
            path = Path.of(raw.strip());
        } catch (RuntimeException e) {
            gaps.add(TeamsGap.of(TeamsGapKind.NOT_FOUND, shorten(raw), "chemin local illisible"));
            return finish(result, gaps, List.of(), "", new StringBuilder("Chemin illisible : rien n'a "
                    + "été lu."));
        }
        if (!path.isAbsolute()) {
            gaps.add(TeamsGap.of(TeamsGapKind.NOT_FOUND, raw,
                    "donne le chemin ABSOLU du fichier sur la machine"));
            return finish(result, gaps, List.of(), "", new StringBuilder("Donnez un chemin ABSOLU : "
                    + "rien n'a été lu."));
        }
        Path normalized = path.normalize();
        if (!Files.isRegularFile(normalized) || !Files.isReadable(normalized)) {
            gaps.add(TeamsGap.of(TeamsGapKind.NOT_FOUND, normalized.toString(),
                    "aucun fichier lisible à ce chemin sur la machine"));
            return finish(result, gaps, List.of(), "", new StringBuilder("Aucun fichier lisible à ce "
                    + "chemin : rien n'a été lu."));
        }
        long size = sizeOf(normalized);
        result.with("localPath", normalized.toString());
        result.json().put("bytes", size);
        if (size > MAX_DOCX_BYTES) {
            gaps.add(TeamsGap.of(TeamsGapKind.BODY_UNAVAILABLE, normalized.toString(),
                    "fichier de plus de 50 Mo : trop gros pour être lu comme un .docx"));
            return finish(result, gaps, List.of(), "", new StringBuilder("« ")
                    .append(normalized.getFileName()).append(" » est trop gros pour être lu ici."));
        }
        try {
            DocxText.Extracted extracted = DocxText.read(normalized);
            result.json().put("read", true);
            result.json().put("paragraphs", extracted.paragraphs());
            result.json().put("truncated", extracted.truncated());
            result.with("documentText", extracted.text());
            StringBuilder text = new StringBuilder("Texte de « ").append(normalized.getFileName())
                    .append(" » (").append(extracted.paragraphs()).append(" paragraphe")
                    .append(extracted.paragraphs() > 1 ? "s" : "");
            if (extracted.truncated()) {
                text.append(", tronqué à ").append(DocxText.MAX_TEXT_CHARS).append(" caractères");
            }
            text.append(") :").append(System.lineSeparator()).append(extracted.text());
            return finish(result, gaps, List.of(), "", text);
        } catch (DocxText.NotADocx e) {
            gaps.add(TeamsGap.of(TeamsGapKind.BODY_UNAVAILABLE, normalized.toString(), e.getMessage()));
            return finish(result, gaps, List.of(), "", new StringBuilder("« ")
                    .append(normalized.getFileName()).append(" » n'a pas pu être lu comme un .docx."));
        }
    }

    // ------------------------------------------------------------------ emplacements connus

    /** Un emplacement proposé, avec d'où on le tient. */
    record Candidate(SharePointLocation location, String source, String conversationId) {
    }

    /** Ce que le registre et l'observation apprennent des emplacements de fichiers. */
    record Known(List<Candidate> attachments, List<SharePointLocation> sites, List<String> hosts) {

        String oneDriveHost() {
            for (String host : hosts) {
                if (host.endsWith("-my.sharepoint.com")) {
                    return host;
                }
            }
            for (String host : hosts) {
                if (host.endsWith(".sharepoint.com")) {
                    return host.substring(0, host.length() - ".sharepoint.com".length())
                            + "-my.sharepoint.com";
                }
            }
            return "";
        }
    }

    private Known known(BrowserLink link) {
        Map<String, Candidate> attachments = new LinkedHashMap<>();
        List<String> hosts = new ArrayList<>();
        List<TeamsMessage> messages = new ArrayList<>(host.ledger().messagesOf("", null));
        java.util.Collections.reverse(messages); // les plus récents d'abord
        for (TeamsMessage message : messages) {
            for (TeamsAttachmentRef attachment : message.attachments()) {
                SharePointLocation.Parsed parsed = SharePointLocation.parse(attachment.sourceUrl());
                if (!parsed.ok()) {
                    continue;
                }
                addHost(hosts, parsed.location().origin());
                SharePointLocation parent = parsed.location().parent();
                String key = message.conversationId() + "|" + parent.serverPath();
                TeamsConversation conversation = host.ledger().conversation(message.conversationId());
                attachments.putIfAbsent(key, new Candidate(parent,
                        "fichiers partagés dans « " + (conversation == null ? message.conversationId()
                                : conversation.label()) + " »", message.conversationId()));
            }
        }
        Map<String, SharePointLocation> sites = new LinkedHashMap<>();
        for (String path : link.observer().observedFilePaths()) {
            addHost(hosts, "https://" + MicrosoftDomains.hostOf(path));
            SharePointLocation site = siteOf(path);
            if (site != null) {
                sites.putIfAbsent(site.serverPath(), site);
            }
        }
        return new Known(List.copyOf(attachments.values()), List.copyOf(sites.values()), hosts);
    }

    /** Le site d'un chemin observé ({@code https://h/sites/X/_api/…}), bibliothèque par défaut. */
    static SharePointLocation siteOf(String observed) {
        String host = MicrosoftDomains.hostOf(observed);
        if (!host.endsWith(".sharepoint.com") || host.endsWith("-my.sharepoint.com")) {
            return null;
        }
        int start = observed.indexOf(host) + host.length();
        String[] parts = observed.substring(start).split("/");
        // parts[0] est vide (le chemin commence par « / »)
        if (parts.length < 3) {
            return null;
        }
        String prefix = parts[1].toLowerCase(Locale.ROOT);
        if (!"sites".equals(prefix) && !"teams".equals(prefix)) {
            return null;
        }
        String name = java.net.URLDecoder.decode(parts[2].replace("+", "%2B"),
                java.nio.charset.StandardCharsets.UTF_8);
        return new SharePointLocation("https://" + host, "/" + parts[1] + "/" + name,
                "/" + parts[1] + "/" + name + "/Shared Documents");
    }

    private static SharePointLocation matchSite(Known known, String wanted) {
        String needle = TeamsTools.fold(wanted).replaceAll("[^a-z0-9]", "");
        if (needle.isEmpty()) {
            return null;
        }
        for (SharePointLocation site : known.sites()) {
            String name = TeamsTools.fold(site.sitePath().substring(site.sitePath().lastIndexOf('/') + 1))
                    .replaceAll("[^a-z0-9]", "");
            if (name.equals(needle) || name.contains(needle)) {
                return site;
            }
        }
        return null;
    }

    private static void addHost(List<String> hosts, String origin) {
        String host = MicrosoftDomains.hostOf(origin);
        if (!host.isEmpty() && !hosts.contains(host)) {
            hosts.add(host);
        }
    }

    // ------------------------------------------------------------------ rendu

    private TeamsToolResult result(String tool) {
        TeamsToolResult result = new TeamsToolResult(tool, host.adapterVersion(),
                TeamsLinkState.LINKED);
        result.with("provenance", SharePointFiles.PROVENANCE);
        return result;
    }

    private PageActions actions(BrowserLink link, List<PageActions.GestureRecord> journal) {
        return new PageActions(link, sleeper, record -> {
            journal.add(record);
            say.accept("Geste Microsoft 365 : " + record.action() + " — " + record.domain() + " — "
                    + record.result());
        });
    }

    private ToolOutcome finish(TeamsToolResult result, List<TeamsGap> gaps,
            List<PageActions.GestureRecord> journal, String viewport, StringBuilder text) {
        ArrayNode gestures = result.array("gestures");
        journal.forEach(record -> gesture(gestures, record));
        if (!gaps.isEmpty()) {
            List<String> described = new ArrayList<>();
            gaps.forEach(gap -> described.add(gap.describe()));
            text.append(" Ce qui n'a pas pu être fait : ").append(String.join(" ; ", described))
                    .append('.');
        }
        if (!viewport.isBlank()) {
            text.append(' ').append(viewport);
        }
        text.append(" (Adaptateur fichiers : ").append(SharePointFiles.PROVENANCE).append(".)");
        result.window(null)
                .gaps(gaps)
                .health(TeamsHealth.full(0))
                .viewport(viewport)
                .with("firstUse", host.firstUse())
                .text(text.toString());
        return ToolOutcome.ok(result.render());
    }

    private ToolOutcome refusedLocation(TeamsToolResult result, String asked, String why) {
        result.array("items");
        List<TeamsGap> gaps = List.of(TeamsGap.of(TeamsGapKind.LOCATION_UNKNOWN,
                shorten(asked), why));
        return finish(result, new ArrayList<>(gaps), List.of(), "", new StringBuilder(
                "Je n'ai rien fait : cette adresse n'est pas un emplacement SharePoint ou OneDrive "
                        + "que je sais lire (" + why + ")."));
    }

    private ToolOutcome unlinked(TeamsToolResult result, RuntimeException e) {
        TeamsToolResult unlinked = new TeamsToolResult(result.json().path("tool").asText(),
                host.adapterVersion(), TeamsLinkState.BROWSER_NOT_DETECTED);
        String remedy = e instanceof BrowserLinkException ? e.getMessage() : "";
        unlinked.with("remedy", remedy)
                .with("provenance", SharePointFiles.PROVENANCE)
                .window(null)
                .gaps(List.of(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, "liaison Teams",
                        "la liaison au navigateur n'est pas établie")))
                .health(TeamsHealth.full(0))
                .with("firstUse", host.firstUse())
                .text("Le navigateur du poste n'est pas relié : rien n'a été lu."
                        + (remedy == null || remedy.isBlank() ? "" : System.lineSeparator() + remedy));
        return ToolOutcome.ok(unlinked.render());
    }

    private static void renderLocation(TeamsToolResult result, SharePointLocation location) {
        ObjectNode node = result.put("location");
        node.put("label", location.label());
        node.put("webUrl", location.webUrl());
        node.put("serverRelativeUrl", location.serverPath());
    }

    private static void candidate(ArrayNode into, Candidate candidate) {
        ObjectNode node = into.addObject();
        node.put("label", candidate.location().label());
        node.put("webUrl", candidate.location().webUrl());
        node.put("source", candidate.source());
        if (!candidate.conversationId().isBlank()) {
            node.put("conversationId", candidate.conversationId());
        }
    }

    private static void entry(ArrayNode into, SharePointLocation where, SharePointFiles.Entry entry) {
        ObjectNode node = into.addObject();
        node.put("kind", entry.folder() ? "folder" : "file");
        node.put("name", entry.name());
        node.put("serverRelativeUrl", entry.serverPath());
        node.put("webUrl", new SharePointLocation(where.origin(), where.sitePath(),
                entry.serverPath()).webUrl());
        if (!entry.folder()) {
            node.put("size", entry.size());
        }
        if (!entry.modified().isBlank()) {
            node.put("modified", entry.modified());
        }
        if (!entry.id().isBlank()) {
            node.put("id", entry.id());
        }
        if (!entry.version().isBlank()) {
            node.put("version", entry.version());
        }
        if (entry.itemCount() >= 0) {
            node.put("itemCount", entry.itemCount());
        }
    }

    private static void gesture(ArrayNode into, PageActions.GestureRecord record) {
        ObjectNode node = into.addObject();
        node.put("action", record.action());
        node.put("domain", record.domain());
        node.put("target", record.target());
        node.put("result", record.result());
    }

    static TeamsGap gapOf(BrowserLinkException refused, String where) {
        return switch (refused.code()) {
            case BrowserLinkException.SIGN_IN_REFUSED -> TeamsGap.of(TeamsGapKind.SIGNED_OUT, where,
                    "l'onglet est sur une page d'identification : rouvrez Teams et reconnectez-vous, "
                            + "puis redemandez — le runner ne se connecte jamais");
            case BrowserLinkException.DOMAIN_REFUSED -> TeamsGap.of(TeamsGapKind.LOCATION_UNKNOWN,
                    where, refused.getMessage());
            default -> TeamsGap.of(TeamsGapKind.BODY_UNAVAILABLE, where, refused.getMessage());
        };
    }

    private static String describeCount(List<SharePointFiles.Entry> entries) {
        long folders = entries.stream().filter(SharePointFiles.Entry::folder).count();
        long files = entries.size() - folders;
        return count((int) folders, "dossier", "dossiers") + " et " + count((int) files, "fichier",
                "fichiers");
    }

    private static String count(int howMany, String singular, String plural) {
        return howMany + " " + (howMany > 1 ? plural : singular);
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }

    private static String shorten(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120) + "…";
    }
}
