package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.ToolOutcome;

/**
 * <b>Les six écritures dans Microsoft 365</b> (F-108 / SF-108-04) : créer un dossier, déposer,
 * renommer, déplacer, supprimer, remplacer une version.
 *
 * <h2>Ce qui a déjà eu lieu quand on arrive ici</h2>
 *
 * <p>L'utilisateur a <b>autorisé cette écriture-là</b>, au libellé clair, dans le terminal (SF-108-02) —
 * aucune n'est couverte par « tout autoriser ». Cette classe ne redécide rien ; elle refuse seulement,
 * <b>avant tout geste</b>, ce qui ne peut pas réussir (nom invalide, fichier local absent, adresse
 * illisible), et elle <b>vérifie</b> ce qu'elle a fait : un résultat dit « c'est fait » quand le
 * serveur l'a confirmé, « a peut-être eu lieu, vérifiez » quand la réponse ne ressemble pas au modèle —
 * jamais l'inverse.</p>
 *
 * <p><b>Toujours par l'API, jamais par le dossier synchronisé</b> : la version créée, l'identifiant de
 * corbeille, le refus d'écraser sont confirmés côté serveur ; le dossier synchronisé reçoit le
 * changement par OneDrive.</p>
 *
 * <p><b>Forme éprouvée sur documentation, à confirmer sur poste réel.</b></p>
 */
final class TeamsWriteTools {

    private final TeamsFileTools.Host host;
    private final BrowserLink.Sleeper sleeper;
    private final java.util.function.Consumer<String> say;

    TeamsWriteTools(TeamsFileTools.Host host, BrowserLink.Sleeper sleeper,
            java.util.function.Consumer<String> say) {
        this.host = host;
        this.sleeper = sleeper;
        this.say = say == null ? line -> { } : say;
    }

    // ------------------------------------------------------------------ teams_create_folder

    ToolOutcome createFolder(JsonNode input) {
        Context ctx = new Context(TeamsTools.CREATE_FOLDER);
        String name = TeamsAsk.text(input, "name");
        SharePointLocation parent = location(ctx, TeamsAsk.text(input, "location", "parent"), "location");
        if (parent == null || !validName(ctx, name)) {
            return ctx.refused("Je n'ai rien créé.");
        }
        SharePointLocation folder = parent.child(name.strip());
        ctx.item(folder, "folder");
        return ctx.inVisit(parent, visit -> {
            SharePointWrites.Existing existing = SharePointWrites.probe(visit, folder);
            if (existing.gap() != null) {
                ctx.gaps.add(existing.gap());
                return "Je n'ai rien créé : je n'ai pas pu vérifier l'emplacement.";
            }
            if (!existing.nothing()) {
                ctx.gaps.add(TeamsGap.of(TeamsGapKind.ALREADY_EXISTS, folder.label(),
                        existing.folder() ? "ce dossier existe déjà" : "un fichier porte déjà ce nom"));
                return "Je n'ai rien créé : « " + name.strip() + " » existe déjà dans « "
                        + parent.label() + " ».";
            }
            SharePointPage.Answer answer = SharePointWrites.createFolder(visit, folder);
            if (!answer.ok()) {
                ctx.gaps.add(SharePointWrites.gapOfWrite(answer, folder.label()));
                return "Le dossier n'a PAS été créé.";
            }
            if (!SharePointWrites.describesItem(answer.body(), List.of("Name", "ServerRelativeUrl"))) {
                return ctx.unsure(folder, "la création");
            }
            ctx.done(answer.body());
            return "C'est fait : le dossier « " + name.strip() + " » a été créé dans « "
                    + parent.label() + " ».";
        });
    }

    // ------------------------------------------------------------------ teams_upload_file

    ToolOutcome upload(JsonNode input) {
        Context ctx = new Context(TeamsTools.UPLOAD_FILE);
        Path local = localFile(ctx, TeamsAsk.text(input, "file", "local_path"));
        SharePointLocation parent = location(ctx, TeamsAsk.text(input, "location", "destination"),
                "location");
        String name = TeamsAsk.text(input, "name");
        if (name.isBlank() && local != null) {
            name = local.getFileName().toString();
        }
        if (local == null || parent == null || !validName(ctx, name)) {
            return ctx.refused("Je n'ai rien déposé.");
        }
        String remoteName = name.strip();
        SharePointLocation target = parent.child(remoteName);
        ctx.item(target, "file");
        ctx.result.with("localFile", local.toString());
        long size = sizeOf(local);
        return ctx.inVisit(parent, visit -> {
            SharePointWrites.Existing existing = SharePointWrites.probe(visit, target);
            if (existing.gap() != null) {
                ctx.gaps.add(existing.gap());
                return "Je n'ai rien déposé : je n'ai pas pu vérifier l'emplacement.";
            }
            if (!existing.nothing()) {
                ctx.gaps.add(TeamsGap.of(TeamsGapKind.ALREADY_EXISTS, target.label(),
                        "un élément porte déjà ce nom — pour une nouvelle version, utilise "
                                + TeamsTools.REPLACE_VERSION));
                return "Je n'ai rien déposé : « " + remoteName + " » existe déjà dans « "
                        + parent.label() + " ».";
            }
            SharePointPage.Answer answer = dropAndUpload(visit, parent, remoteName, local, false);
            if (!answer.ok()) {
                ctx.gaps.add(SharePointWrites.gapOfWrite(answer, target.label()));
                return "Le fichier n'a PAS été déposé.";
            }
            if (!SharePointWrites.describesItem(answer.body(), List.of("Name", "ServerRelativeUrl"))
                    || SharePointFiles.size(answer.body().get("Length")) != size) {
                return ctx.unsure(target, "le dépôt");
            }
            ctx.done(answer.body());
            return "C'est fait : « " + remoteName + " » (" + size + " octets) a été déposé dans « "
                    + parent.label() + " ».";
        });
    }

    // ------------------------------------------------------------------ teams_replace_version

    ToolOutcome replaceVersion(JsonNode input) {
        Context ctx = new Context(TeamsTools.REPLACE_VERSION);
        Path local = localFile(ctx, TeamsAsk.text(input, "file", "local_path"));
        SharePointLocation target = location(ctx, TeamsAsk.text(input, "target", "url"), "target");
        if (local == null || target == null) {
            return ctx.refused("Je n'ai rien remplacé.");
        }
        ctx.item(target, "file");
        ctx.result.with("localFile", local.toString());
        long size = sizeOf(local);
        return ctx.inVisit(target, visit -> {
            SharePointWrites.Existing existing = SharePointWrites.probe(visit, target);
            if (existing.gap() != null) {
                ctx.gaps.add(existing.gap());
                return "Je n'ai rien remplacé : je n'ai pas pu lire le fichier distant.";
            }
            if (!existing.file()) {
                ctx.gaps.add(TeamsGap.of(TeamsGapKind.NOT_FOUND, target.label(), existing.folder()
                        ? "c'est un dossier, pas un fichier"
                        : "ce fichier n'existe pas : c'est un dépôt, pas un remplacement — utilise "
                                + TeamsTools.UPLOAD_FILE));
                return "Je n'ai rien remplacé.";
            }
            String previous = existing.entry().version();
            ctx.result.with("previousVersion", previous);
            SharePointPage.Answer answer = dropAndUpload(visit, target.parent(), target.name(), local,
                    true);
            if (!answer.ok()) {
                ctx.gaps.add(SharePointWrites.gapOfWrite(answer, target.label()));
                return "La version n'a PAS été remplacée.";
            }
            if (!SharePointWrites.describesItem(answer.body(), List.of("Name", "ServerRelativeUrl"))
                    || SharePointFiles.size(answer.body().get("Length")) != size) {
                return ctx.unsure(target, "le remplacement");
            }
            ctx.done(answer.body());
            String now = answer.body().path("UIVersionLabel").asText("");
            return "C'est fait : « " + target.name() + " » a une nouvelle version"
                    + (now.isBlank() ? "" : " (" + now + ")") + ", tirée de " + local + "."
                    + (previous.isBlank() ? "" : " La version " + previous + " reste dans l'historique "
                            + "des versions de SharePoint : elle est restaurable.");
        });
    }

    // ------------------------------------------------------------------ teams_rename, teams_move

    ToolOutcome rename(JsonNode input) {
        Context ctx = new Context(TeamsTools.RENAME);
        SharePointLocation source = location(ctx, TeamsAsk.text(input, "target", "url"), "target");
        String name = TeamsAsk.text(input, "name", "new_name");
        if (source == null || !validName(ctx, name)) {
            return ctx.refused("Je n'ai rien renommé.");
        }
        return relocate(ctx, source, source.parent().child(name.strip()), "renommé");
    }

    ToolOutcome move(JsonNode input) {
        Context ctx = new Context(TeamsTools.MOVE);
        SharePointLocation source = location(ctx, TeamsAsk.text(input, "target", "url"), "target");
        SharePointLocation destination = location(ctx, TeamsAsk.text(input, "destination", "location"),
                "destination");
        if (source == null || destination == null) {
            return ctx.refused("Je n'ai rien déplacé.");
        }
        if (!destination.origin().equals(source.origin())
                || !destination.sitePath().equalsIgnoreCase(source.sitePath())) {
            ctx.gaps.add(TeamsGap.of(TeamsGapKind.WRITE_FAILED, destination.label(),
                    "déplacement entre sites : utilise " + TeamsTools.COPY + " (copier vers l'autre "
                            + "site), vérifie à destination, puis " + TeamsTools.DELETE
                            + " (corbeille du site) — deux autorisations"));
            return ctx.refused("Je n'ai rien déplacé.");
        }
        return relocate(ctx, source, destination.child(source.name()), "déplacé");
    }

    private ToolOutcome relocate(Context ctx, SharePointLocation source, SharePointLocation target,
            String verb) {
        ctx.item(target, "");
        ctx.result.put("source").put("label", source.label());
        if (target.serverPath().equals(source.serverPath())) {
            return ctx.refused("Rien à faire : l'élément est déjà à cet emplacement.");
        }
        return ctx.inVisit(source, visit -> {
            SharePointWrites.Existing what = SharePointWrites.probe(visit, source);
            if (what.gap() != null) {
                ctx.gaps.add(what.gap());
                return "Rien n'a été " + verb + " : je n'ai pas pu lire l'élément.";
            }
            if (what.nothing()) {
                ctx.gaps.add(TeamsGap.of(TeamsGapKind.NOT_FOUND, source.label(),
                        "aucun fichier ni dossier à cet emplacement"));
                return "Rien n'a été " + verb + ".";
            }
            if (!target.parent().serverPath().equals(source.parent().serverPath())) {
                SharePointWrites.Existing folder = SharePointWrites.probe(visit, target.parent());
                if (!folder.folder()) {
                    ctx.gaps.add(folder.gap() != null ? folder.gap()
                            : TeamsGap.of(TeamsGapKind.NOT_FOUND, target.parent().label(),
                                    "le dossier de destination n'existe pas"));
                    return "Rien n'a été " + verb + ".";
                }
            }
            SharePointWrites.Existing clash = SharePointWrites.probe(visit, target);
            if (clash.gap() != null) {
                ctx.gaps.add(clash.gap());
                return "Rien n'a été " + verb + " : je n'ai pas pu vérifier la destination.";
            }
            if (!clash.nothing()) {
                ctx.gaps.add(TeamsGap.of(TeamsGapKind.ALREADY_EXISTS, target.label(),
                        "un élément porte déjà ce nom à destination : rien n'est écrasé"));
                return "Rien n'a été " + verb + ".";
            }
            ctx.result.with("kind", what.folder() ? "folder" : "file");
            SharePointPage.Answer answer = SharePointWrites.moveTo(visit, source, target, what.folder());
            if (!answer.ok()) {
                ctx.gaps.add(SharePointWrites.gapOfWrite(answer, source.label()));
                return "Rien n'a été " + verb + ".";
            }
            SharePointWrites.Existing after = SharePointWrites.probe(visit, target);
            if (after.nothing() || after.gap() != null) {
                return ctx.unsure(target, "l'opération");
            }
            ctx.done(null);
            return "C'est fait : « " + source.name() + " » a été " + verb + " — il est maintenant « "
                    + target.label() + " ».";
        });
    }

    // ------------------------------------------------------------------ teams_delete

    ToolOutcome delete(JsonNode input) {
        Context ctx = new Context(TeamsTools.DELETE);
        SharePointLocation target = location(ctx, TeamsAsk.text(input, "target", "url"), "target");
        if (target == null) {
            return ctx.refused("Je n'ai rien supprimé.");
        }
        ctx.item(target, "");
        return ctx.inVisit(target, visit -> {
            SharePointWrites.Existing what = SharePointWrites.probe(visit, target);
            if (what.gap() != null) {
                ctx.gaps.add(what.gap());
                return "Je n'ai rien supprimé : je n'ai pas pu lire l'élément.";
            }
            if (what.nothing()) {
                ctx.gaps.add(TeamsGap.of(TeamsGapKind.NOT_FOUND, target.label(),
                        "aucun fichier ni dossier à cet emplacement"));
                return "Je n'ai rien supprimé.";
            }
            ctx.result.with("kind", what.folder() ? "folder" : "file");
            SharePointPage.Answer answer = SharePointWrites.recycle(visit, target, what.folder());
            if (!answer.ok()) {
                ctx.gaps.add(SharePointWrites.gapOfWrite(answer, target.label()));
                return "Rien n'a été supprimé.";
            }
            String recycled = answer.body() == null ? "" : answer.body().path("value").asText("");
            if (recycled.isBlank()) {
                return ctx.unsure(target, "la mise à la corbeille");
            }
            ctx.done(null);
            ctx.result.with("recycleBinItemId", recycled);
            return "C'est fait : « " + target.name() + " » a été placé dans la CORBEILLE du site — "
                    + "il reste restaurable depuis la corbeille.";
        });
    }

    // ------------------------------------------------------------------ teams_copy

    /**
     * <b>Copier vers un autre dossier — du même site ou d'un autre site</b> (F-108 / SF-108-06).
     *
     * <p>Sur le <b>même site</b>, la copie est <b>revérifiée</b> comme un déplacement l'est. Sur un
     * <b>autre site</b>, elle ne peut pas l'être depuis l'onglet de la source : le résultat le dit
     * ({@code verifyAtDestination}), {@code done} reste faux, et l'original n'est jamais supprimé ici.
     * Le déplacement inter-site reste donc <b>copie puis corbeille, deux autorisations</b> — l'original
     * ne part à la corbeille (restaurable) que sur une autorisation séparée de {@code teams_delete}.</p>
     */
    ToolOutcome copy(JsonNode input) {
        Context ctx = new Context(TeamsTools.COPY);
        SharePointLocation source = location(ctx, TeamsAsk.text(input, "target", "url", "source"),
                "target");
        SharePointLocation destFolder = location(ctx, TeamsAsk.text(input, "destination", "location"),
                "destination");
        String name = TeamsAsk.text(input, "name");
        if (source == null || destFolder == null || (!name.isBlank() && !validName(ctx, name))) {
            return ctx.refused("Je n'ai rien copié.");
        }
        SharePointLocation target = destFolder.child(name.isBlank() ? source.name() : name.strip());
        ctx.item(target, "");
        ctx.result.put("source").put("label", source.label());
        // Vérifiable = même hôte que la source : l'onglet posé sur la source peut relire la
        // destination. Un autre hôte (OneDrive -my, autre tenant) ne l'est pas depuis cet onglet.
        boolean verifiable = target.origin().equals(source.origin());
        if (verifiable && target.serverPath().equals(source.serverPath())) {
            return ctx.refused("Rien à faire : la copie viserait l'élément lui-même.");
        }
        return ctx.inVisit(source, visit -> {
            SharePointWrites.Existing what = SharePointWrites.probe(visit, source);
            if (what.gap() != null) {
                ctx.gaps.add(what.gap());
                return "Je n'ai rien copié : je n'ai pas pu lire la source.";
            }
            if (what.nothing()) {
                ctx.gaps.add(TeamsGap.of(TeamsGapKind.NOT_FOUND, source.label(),
                        "aucun fichier ni dossier à cet emplacement"));
                return "Je n'ai rien copié.";
            }
            ctx.result.with("kind", what.folder() ? "folder" : "file");
            if (verifiable) {
                SharePointWrites.Existing clash = SharePointWrites.probe(visit, target);
                if (clash.gap() != null) {
                    ctx.gaps.add(clash.gap());
                    return "Rien n'a été copié : je n'ai pas pu vérifier la destination.";
                }
                if (!clash.nothing()) {
                    ctx.gaps.add(TeamsGap.of(TeamsGapKind.ALREADY_EXISTS, target.label(),
                            "un élément porte déjà ce nom à destination : rien n'est écrasé"));
                    return "Rien n'a été copié.";
                }
            }
            SharePointPage.Answer answer = SharePointWrites.copyTo(visit, source, target, what.folder());
            if (!answer.ok()) {
                ctx.gaps.add(SharePointWrites.gapOfWrite(answer, target.label()));
                return "Rien n'a été copié.";
            }
            if (verifiable) {
                SharePointWrites.Existing after = SharePointWrites.probe(visit, target);
                if (after.nothing() || after.gap() != null) {
                    return ctx.unsure(target, "la copie");
                }
                ctx.done(null);
                return "C'est fait : « " + source.name() + " » a été copié — la copie est « "
                        + target.label() + " ».";
            }
            // Autre site : non vérifiable depuis cet onglet. On ne dit JAMAIS « c'est fait ».
            ctx.result.json().put("verifyAtDestination", true);
            return "« " + source.name() + " » a été copié selon Microsoft 365 (réponse OK) vers « "
                    + target.label() + " », sur un AUTRE site : je ne peux pas le vérifier depuis cet "
                    + "onglet. VÉRIFIE à destination AVANT toute suppression de l'original — la "
                    + "suppression (" + TeamsTools.DELETE + ") va à la corbeille du site, restaurable.";
        });
    }

    // ------------------------------------------------------------------ plomberie

    /**
     * Crée le champ de dépôt, y pose le fichier local, puis envoie le dépôt — <b>simple</b> jusqu'à
     * {@link SharePointWrites#SIMPLE_UPLOAD_LIMIT_BYTES}, <b>découpé</b> au-delà (F-108 / SF-108-06).
     * Dans les deux cas, Chrome lit le disque ; les octets ne passent jamais par la liaison.
     */
    private SharePointPage.Answer dropAndUpload(SharePointPage.Visit visit, SharePointLocation folder,
            String name, Path local, boolean overwrite) {
        String inputId = "cg-drop-" + UUID.randomUUID().toString().replace("-", "");
        JsonNode created = visit.actions().runScript("préparer le champ de dépôt",
                SharePointWrites.inputScript(inputId));
        if (created == null || !created.asBoolean(false)) {
            return SharePointPage.Answer.failed(0, "le champ de dépôt n'a pas pu être créé dans la page");
        }
        if (!visit.actions().setFileInputFiles("#" + inputId, List.of(local.toString()))) {
            return SharePointPage.Answer.failed(0, "le fichier n'a pas pu être posé dans la page");
        }
        return sizeOf(local) > SharePointWrites.SIMPLE_UPLOAD_LIMIT_BYTES
                ? SharePointWrites.uploadChunked(visit, folder, name, inputId, overwrite)
                : SharePointWrites.upload(visit, folder, name, inputId, overwrite);
    }

    private SharePointLocation location(Context ctx, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            ctx.gaps.add(TeamsGap.of(TeamsGapKind.MISSING_FIELD, ctx.tool, field));
            return null;
        }
        SharePointLocation.Parsed parsed = SharePointLocation.parse(raw);
        if (!parsed.ok()) {
            ctx.gaps.add(TeamsGap.of(TeamsGapKind.LOCATION_UNKNOWN, field, parsed.refusal()));
            return null;
        }
        return parsed.location();
    }

    private static boolean validName(Context ctx, String name) {
        String why = SharePointWrites.invalidName(name);
        if (!why.isEmpty()) {
            ctx.gaps.add(TeamsGap.of(TeamsGapKind.INVALID_NAME, "name", why));
            return false;
        }
        return true;
    }

    private static Path localFile(Context ctx, String raw) {
        if (raw == null || raw.isBlank()) {
            ctx.gaps.add(TeamsGap.of(TeamsGapKind.MISSING_FIELD, ctx.tool, "file"));
            return null;
        }
        Path path;
        try {
            path = Path.of(raw.strip());
        } catch (RuntimeException e) {
            ctx.gaps.add(TeamsGap.of(TeamsGapKind.NOT_FOUND, raw, "chemin local illisible"));
            return null;
        }
        if (!path.isAbsolute()) {
            ctx.gaps.add(TeamsGap.of(TeamsGapKind.NOT_FOUND, raw,
                    "donne le chemin ABSOLU du fichier sur la machine"));
            return null;
        }
        Path normalized = path.normalize();
        if (!Files.isRegularFile(normalized) || !Files.isReadable(normalized)) {
            ctx.gaps.add(TeamsGap.of(TeamsGapKind.NOT_FOUND, normalized.toString(),
                    "aucun fichier lisible à ce chemin sur la machine"));
            return null;
        }
        if (sizeOf(normalized) > SharePointWrites.MAX_UPLOAD_BYTES) {
            ctx.gaps.add(TeamsGap.of(TeamsGapKind.WRITE_FAILED, normalized.toString(),
                    "fichier de plus de 15 Gio : trop gros pour un dépôt, même découpé"));
            return null;
        }
        return normalized;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }

    /** Le travail d'une écriture, pendant que l'onglet est sur le site : il rend la phrase. */
    @FunctionalInterface
    private interface Work {
        String run(SharePointPage.Visit visit);
    }

    /** L'état d'un appel : le résultat en construction, ses manques, ses gestes. */
    private final class Context {

        final String tool;
        final TeamsToolResult result;
        final List<TeamsGap> gaps = new ArrayList<>();
        final List<PageActions.GestureRecord> journal = new ArrayList<>();

        Context(String tool) {
            this.tool = tool;
            this.result = new TeamsToolResult(tool, host.adapterVersion(), TeamsLinkState.LINKED);
            result.with("provenance", SharePointFiles.PROVENANCE);
            result.json().put("done", false);
        }

        void item(SharePointLocation location, String kind) {
            ObjectNode node = result.put("item");
            node.put("label", location.label());
            node.put("webUrl", location.webUrl());
            node.put("serverRelativeUrl", location.serverPath());
            if (!kind.isBlank()) {
                result.with("kind", kind);
            }
        }

        void done(JsonNode body) {
            result.json().put("done", true);
            if (body != null) {
                ObjectNode item = (ObjectNode) result.json().path("item");
                if (!body.path("UIVersionLabel").asText("").isBlank()) {
                    item.put("version", body.path("UIVersionLabel").asText());
                }
                if (!body.path("UniqueId").asText("").isBlank()) {
                    item.put("id", body.path("UniqueId").asText());
                }
                long length = SharePointFiles.size(body.get("Length"));
                if (length >= 0) {
                    item.put("size", length);
                }
            }
        }

        /** Une réponse de succès qui ne ressemble pas au modèle : on ne dit JAMAIS « c'est fait ». */
        String unsure(SharePointLocation where, String what) {
            result.json().put("unsure", true);
            gaps.add(TeamsGap.of(TeamsGapKind.SHAPE_MISMATCH, where.label(),
                    "Microsoft 365 a répondu OK à " + what + " mais la réponse ou la vérification ne "
                            + "correspond pas au modèle — " + SharePointFiles.PROVENANCE));
            return "Je ne peux pas confirmer " + what + " : elle a PEUT-ÊTRE eu lieu. Vérifie « "
                    + where.label() + " » avant toute autre écriture.";
        }

        ToolOutcome refused(String sentence) {
            return finish(sentence, "");
        }

        ToolOutcome inVisit(SharePointLocation where, Work work) {
            BrowserLink link;
            try {
                link = host.link();
            } catch (RuntimeException e) {
                gaps.add(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, "liaison Teams",
                        "la liaison au navigateur n'est pas établie : "
                                + (e.getMessage() == null ? "" : e.getMessage())));
                return finish("Le navigateur du poste n'est pas relié : rien n'a été écrit.", "");
            }
            PageActions actions = new PageActions(link, sleeper, record -> {
                journal.add(record);
                say.accept("Geste Microsoft 365 : " + record.action() + " — " + record.domain() + " — "
                        + record.result());
            });
            SharePointPage.Visit visit = null;
            String sentence;
            try {
                visit = new SharePointPage(actions, sleeper).open(where);
                sentence = work.run(visit);
            } catch (SharePointPage.Refused refusedVisit) {
                gaps.add(TeamsGap.of(refusedVisit.kind(), where.label(), refusedVisit.getMessage()));
                sentence = "Rien n'a été écrit.";
            } catch (BrowserLinkException refusedGesture) {
                gaps.add(TeamsFileTools.gapOf(refusedGesture, where.label()));
                sentence = "Rien n'a été écrit, ou l'écriture a été interrompue : vérifiez « "
                        + where.label() + " ».";
            } finally {
                if (visit != null) {
                    visit.close();
                }
            }
            return finish(sentence, visit == null ? "" : visit.viewport());
        }

        private ToolOutcome finish(String sentence, String viewport) {
            StringBuilder text = new StringBuilder(sentence);
            ArrayNode gestures = result.array("gestures");
            journal.forEach(record -> {
                ObjectNode node = gestures.addObject();
                node.put("action", record.action());
                node.put("domain", record.domain());
                node.put("target", record.target());
                node.put("result", record.result());
            });
            if (!gaps.isEmpty()) {
                List<String> described = new ArrayList<>();
                gaps.forEach(gap -> described.add(gap.describe()));
                text.append(" Ce qui n'a pas pu être fait : ").append(String.join(" ; ", described))
                        .append('.');
            }
            if (!viewport.isBlank()) {
                text.append(' ').append(viewport);
            }
            text.append(" (Adaptateur écritures : ").append(SharePointFiles.PROVENANCE).append(".)");
            result.window(null)
                    .gaps(gaps)
                    .health(TeamsHealth.full(0))
                    .viewport(viewport)
                    .with("firstUse", host.firstUse())
                    .text(text.toString());
            return ToolOutcome.ok(result.render());
        }
    }
}
