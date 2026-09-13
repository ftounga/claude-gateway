package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.ToolOutcome;

/**
 * <b>L'enregistrement d'une réunion Teams, rapatrié sur la machine</b> (F-108 / SF-108-05).
 *
 * <h2>Ce qui a changé depuis F-88, et ce qui n'a pas bougé</h2>
 *
 * <p>F-88 disait « je ne télécharge pas » : l'adresse signée d'un enregistrement est retirée à
 * l'entrée de la liaison (SF-87-02). <b>Cette garde tient toujours</b> : ce n'est pas nous qui
 * téléchargeons, c'est <b>Chrome</b>, vers une adresse de téléchargement construite ici et non signée,
 * que la session du navigateur autorise ({@link ChromeDownloads}). Les octets vont de Microsoft au
 * disque sans passer par la liaison ; la vidéo <b>reste sur la machine</b> (règle F-90).</p>
 *
 * <h2>Traitement lourd, donc asynchrone</h2>
 *
 * <p>Un enregistrement d'une heure pèse des centaines de mégaoctets. L'outil <b>rend la main dès que le
 * téléchargement a démarré</b>, et un fichier-témoin (nom, taille, adresse web — aucun jeton) permet aux
 * rappels de suivre <b>sans aucun geste</b>. La transcription locale, si elle est nécessaire, tourne
 * elle aussi en tâche de fond.</p>
 *
 * <h2>La transcription, de la meilleure source à la dernière</h2>
 *
 * <ol>
 *   <li>celle que <b>Teams</b> a servie ;</li>
 *   <li>un <b>{@code .vtt}</b> de même nom dans le dossier de l'enregistrement, téléchargé par Chrome ;</li>
 *   <li>la <b>transcription locale</b> F-91 de l'audio téléchargé ;</li>
 *   <li>sinon, un manque nommé.</li>
 * </ol>
 *
 * <p><b>Forme éprouvée sur documentation, à confirmer sur poste réel</b> : la forme du message
 * d'enregistrement (adresse {@code .mp4} SharePoint / OneDrive) et l'API REST SharePoint.</p>
 */
final class TeamsRecordingTools {

    /** Source d'une transcription, dans l'ordre de préférence. */
    enum TranscriptSource { TEAMS, VTT_FILE, LOCAL, NONE }

    static final long VTT_WAIT_MS = 30_000L;

    private static final Pattern MP4 = Pattern.compile(
            "https://[a-z0-9.-]+\\.sharepoint\\.com/[^\"'<>\\s?#]+\\.mp4", Pattern.CASE_INSENSITIVE);

    private final TeamsFileTools.Host host;
    private final BrowserLink.Sleeper sleeper;
    private final TeamsWorkFolder folder;
    private final SyncedLibraries synced;
    private final TranscriptionWorker transcription;
    private final ChromeDownloads downloads;
    private final java.util.function.Consumer<String> say;
    private final ObjectMapper mapper = new ObjectMapper();

    TeamsRecordingTools(TeamsFileTools.Host host, BrowserLink.Sleeper sleeper, TeamsWorkFolder folder,
            SyncedLibraries synced, TranscriptionWorker transcription,
            java.util.function.Consumer<String> say) {
        this.host = host;
        this.sleeper = sleeper;
        this.folder = folder;
        this.synced = synced;
        this.transcription = transcription;
        this.downloads = new ChromeDownloads(sleeper);
        this.say = say == null ? line -> { } : say;
    }

    // ------------------------------------------------------------------ teams_meeting_recording

    ToolOutcome recording(JsonNode input, List<TeamsGap> harvested) {
        TeamsLedger book = host.ledger();
        String meetingId = TeamsAsk.text(input, "meeting_id", "meetingId", "id");
        TeamsMeeting meeting = meetingId.isEmpty() ? null : book.meeting(meetingId);
        TeamsToolResult result = new TeamsToolResult(TeamsTools.MEETING_RECORDING,
                host.adapterVersion(), TeamsLinkState.LINKED);
        result.with("meetingId", meetingId);
        result.with("provenance", SharePointFiles.PROVENANCE);
        result.json().put("downloaded", false);
        result.json().put("inProgress", false);
        List<TeamsGap> gaps = new ArrayList<>(harvested);
        List<PageActions.GestureRecord> journal = new ArrayList<>();
        String viewport = "";
        if (meeting != null) {
            TeamsViews.meeting(result.put("meeting"), meeting);
            result.json().put("available", meeting.recorded());
        } else {
            result.json().putNull("available");
        }
        StringBuilder text = new StringBuilder();
        if (meetingId.isEmpty()) {
            gaps.add(TeamsGap.of(TeamsGapKind.MISSING_FIELD, "enregistrement", "meeting_id"));
            return finish(result, gaps, journal, viewport, text.append(
                    "Donnez « meeting_id » : trouvez d'abord la réunion. Rien n'a été téléchargé."));
        }

        Sidecar sidecar = readSidecar(meetingId);
        ChromeDownloads.Outcome outcome = null;
        if (sidecar != null) {
            outcome = sidecar.localPath().isBlank()
                    ? downloads.check(videoDir(meetingId), sidecar.name(), sidecar.expectedBytes(),
                            sidecar.label())
                    : localOutcome(Path.of(sidecar.localPath()));
        }
        boolean wantsDownload = !Boolean.FALSE.equals(TeamsAsk.flag(input, "download"));
        if (outcome == null || outcome.state() == ChromeDownloads.State.NONE
                || outcome.state() == ChromeDownloads.State.BLOCKED) {
            String address = TeamsAsk.text(input, "recording_url", "recordingUrl", "url");
            if (address.isEmpty()) {
                address = observedRecordingUrl(book, meeting);
            }
            if (address.isEmpty()) {
                gaps.add(TeamsGap.of(TeamsGapKind.LOCATION_UNKNOWN, meetingId,
                        "l'adresse de l'enregistrement n'a pas été observée : ouvrez le fil de la "
                                + "réunion dans Teams (le message d'enregistrement), puis redemandez, "
                                + "ou donnez « recording_url »"));
                text.append(meeting == null ? "Je ne connais pas cette réunion."
                        : meeting.recorded() ? "« " + meeting.subject() + " » annonce un "
                                + "enregistrement, mais je ne sais pas encore où il est."
                                : "« " + meeting.subject() + " » n'annonce aucun enregistrement.");
                text.append(" Rien n'a été téléchargé.");
                return finish(result, gaps, journal, viewport, text);
            }
            SharePointLocation.Parsed parsed = SharePointLocation.parse(address);
            if (!parsed.ok()) {
                gaps.add(TeamsGap.of(TeamsGapKind.LOCATION_UNKNOWN, meetingId, parsed.refusal()));
                return finish(result, gaps, journal, viewport, text.append(
                        "Cette adresse d'enregistrement n'est pas lisible (" + parsed.refusal()
                                + "). Rien n'a été téléchargé."));
            }
            SharePointLocation file = parsed.location();
            renderRecording(result, file);
            if (!wantsDownload) {
                return finish(result, gaps, journal, viewport, text.append("L'enregistrement est à « ")
                        .append(file.label()).append(" ». Téléchargement non demandé."));
            }
            Path local = synced.resolve(file).orElse(null);
            if (local != null && Files.isRegularFile(local)) {
                result.with("route", "SYNCED_FOLDER");
                sidecar = new Sidecar(meetingId, file.name(), sizeOf(local), file.webUrl(),
                        file.serverPath(), "", local.toString(), "", file.label());
                writeSidecar(sidecar);
                outcome = localOutcome(local);
            } else {
                result.with("route", "BROWSER");
                Download started = download(file, meetingId, meeting, gaps, journal);
                viewport = started.viewport();
                outcome = started.outcome();
                sidecar = started.sidecar();
            }
        } else {
            result.with("route", sidecar.localPath().isBlank() ? "BROWSER" : "SYNCED_FOLDER");
            renderRecording(result, new SharePointLocation("", "", sidecar.serverPath()),
                    sidecar.webUrl(), sidecar.label());
        }

        if (outcome != null && outcome.gap() != null) {
            gaps.add(outcome.gap());
        }
        if (sidecar != null) {
            result.json().put("expectedBytes", sidecar.expectedBytes());
        }
        if (outcome != null && outcome.done()) {
            result.json().put("downloaded", true);
            result.with("video", outcome.file().toString());
            result.json().put("bytes", outcome.bytes());
            text.append("L'enregistrement est sur la machine : ").append(outcome.file())
                    .append(". Il y reste — rien n'en remonte.");
            Transcript transcript = transcript(meetingId, meeting, sidecar, outcome.file(), true);
            renderTranscript(result, transcript);
            gaps.addAll(transcript.gaps());
            text.append(' ').append(transcript.sentence());
            if (transcript.source() != TranscriptSource.LOCAL || transcript.done()) {
                text.append(" Pour les captures alignées, appelle " + TeamsTools.MEETING_MOMENTS
                        + " avec ce « meeting_id », sans « video » : il prendra ce fichier.");
            }
        } else if (outcome != null && outcome.state() == ChromeDownloads.State.IN_PROGRESS) {
            result.json().put("inProgress", true);
            result.json().put("bytes", outcome.bytes());
            text.append("Chrome télécharge l'enregistrement (").append(outcome.bytes())
                    .append(sidecar == null ? "" : " / " + sidecar.expectedBytes())
                    .append(" octets). C'est long : redemande " + TeamsTools.MEETING_RECORDING
                            + " pour suivre, et NE lance pas les captures avant la fin.");
        } else {
            text.append("L'enregistrement n'a PAS été téléchargé.");
            if (gaps.stream().anyMatch(gap -> gap.kind() == TeamsGapKind.DOWNLOAD_BLOCKED)) {
                text.append(" Le téléchargement semble bloqué par l'organisateur ou par la politique "
                        + "du tenant : demandez à l'organisateur de l'autoriser, ou utilisez "
                        + TeamsTools.CAPTURE_START + " lors de la prochaine réunion.");
            }
        }
        return finish(result, gaps, journal, viewport, text);
    }

    /** Le premier passage : métadonnées, {@code .vtt} voisin, puis démarrage du téléchargement. */
    private Download download(SharePointLocation file, String meetingId, TeamsMeeting meeting,
            List<TeamsGap> gaps, List<PageActions.GestureRecord> journal) {
        BrowserLink link = host.link();
        PageActions actions = new PageActions(link, sleeper, record -> {
            journal.add(record);
            say.accept("Geste Microsoft 365 : " + record.action() + " — " + record.domain() + " — "
                    + record.result());
        });
        SharePointPage.Visit visit = null;
        ChromeDownloads.Outcome outcome = null;
        Sidecar sidecar = null;
        try {
            visit = new SharePointPage(actions, sleeper).open(file);
            SharePointFiles.Item item = SharePointFiles.file(visit, file);
            gaps.addAll(item.gaps());
            if (item.ok()) {
                SharePointFiles.Entry entry = item.entry();
                String vttPath = fetchVtt(visit, actions, file, meetingId, gaps);
                sidecar = new Sidecar(meetingId, entry.name(), entry.size(), file.webUrl(),
                        file.serverPath(), entry.version(), "", vttPath, file.label());
                outcome = downloads.fetch(actions, file, videoDir(meetingId), entry.name(),
                        entry.size(), 0L);
                if (outcome.state() != ChromeDownloads.State.BLOCKED) {
                    writeSidecar(sidecar);
                }
            }
        } catch (SharePointPage.Refused refused) {
            gaps.add(TeamsGap.of(refused.kind(), file.label(), refused.getMessage()));
        } catch (BrowserLinkException refused) {
            gaps.add(TeamsFileTools.gapOf(refused, file.label()));
        } finally {
            if (visit != null) {
                visit.close();
            }
        }
        return new Download(outcome, sidecar, visit == null ? "" : visit.viewport());
    }

    /**
     * Un {@code .vtt} de même nom dans le dossier de l'enregistrement : téléchargé par Chrome et gardé
     * à côté. Rend son chemin local, ou {@code ""}. Son absence n'est pas un manque : c'est l'une des
     * sources possibles, pas la seule.
     */
    private String fetchVtt(SharePointPage.Visit visit, PageActions actions, SharePointLocation video,
            String meetingId, List<TeamsGap> gaps) {
        SharePointFiles.Listing siblings = SharePointFiles.list(visit, video.parent());
        if (!siblings.ok()) {
            return "";
        }
        String base = video.name().toLowerCase(Locale.ROOT);
        base = base.endsWith(".mp4") ? base.substring(0, base.length() - 4) : base;
        for (SharePointFiles.Entry entry : siblings.entries()) {
            String name = entry.name().toLowerCase(Locale.ROOT);
            if (!entry.folder() && name.equals(base + ".vtt")
                    && entry.size() <= VttTranscript.MAX_BYTES) {
                SharePointLocation vtt = new SharePointLocation(video.origin(), video.sitePath(),
                        entry.serverPath());
                ChromeDownloads.Outcome got = downloads.fetch(actions, vtt, transcriptDir(meetingId),
                        entry.name(), entry.size(), VTT_WAIT_MS);
                if (got.done()) {
                    return got.file().toString();
                }
                if (got.gap() != null) {
                    gaps.add(got.gap());
                }
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ transcription

    /** La meilleure transcription disponible pour cette réunion. */
    private Transcript transcript(String meetingId, TeamsMeeting meeting, Sidecar sidecar, Path video,
            boolean startLocal) {
        List<TeamsTranscriptCue> teams = host.ledger().transcriptOf(meetingId);
        if (!teams.isEmpty()) {
            return new Transcript(TranscriptSource.TEAMS, teams, "", true, null, List.of(),
                    teams.size() + " répliques servies par Teams.");
        }
        Instant origin = meeting == null ? null : meeting.startedAt();
        if (sidecar != null && !sidecar.transcriptFile().isBlank()
                && Files.isRegularFile(Path.of(sidecar.transcriptFile()))) {
            VttTranscript.Reading reading = readVtt(Path.of(sidecar.transcriptFile()), origin);
            List<TeamsGap> gaps = new ArrayList<>();
            if (origin == null) {
                gaps.add(TeamsGap.of(TeamsGapKind.MISSING_FIELD, "transcription " + meetingId,
                        "début de réunion inconnu : les répliques du .vtt ne peuvent pas être datées"));
            }
            if (reading.skipped() > 0) {
                gaps.add(new TeamsGap(TeamsGapKind.MISSING_FIELD, "transcription .vtt",
                        "répliques illisibles écartées", reading.skipped()));
            }
            return new Transcript(TranscriptSource.VTT_FILE, reading.cues(), sidecar.transcriptFile(),
                    true, null, gaps, reading.cues().size() + " répliques lues dans la transcription "
                            + ".vtt téléchargée à côté de l'enregistrement.");
        }
        if (transcription == null) {
            return new Transcript(TranscriptSource.NONE, List.of(), "", true, null,
                    List.of(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, "transcription " + meetingId,
                            "ni Teams ni un fichier .vtt ne l'ont fournie, et la transcription locale "
                                    + "n'est pas montée sur ce poste")),
                    "Aucune transcription : ouvrez la transcription dans Teams puis redemandez.");
        }
        String jobId = jobId(meetingId);
        TranscriptionJob job = transcription.find(jobId).orElse(null);
        if (job == null && startLocal) {
            if (origin == null) {
                return new Transcript(TranscriptSource.NONE, List.of(), "", true, null,
                        List.of(TeamsGap.of(TeamsGapKind.MISSING_FIELD, "transcription " + meetingId,
                                "début de réunion inconnu : une transcription locale ne pourrait pas "
                                        + "être datée")),
                        "Aucune transcription datable : ouvrez la réunion dans Teams puis redemandez.");
            }
            try {
                job = transcription.startOrResumeFile(jobId, video,
                        folder.workDir("transcription-" + jobId), origin,
                        "Transcription locale de l'enregistrement Teams « "
                                + (meeting.subject().isEmpty() ? meetingId : meeting.subject())
                                + " », faite sur cette machine.", say);
            } catch (IOException e) {
                return new Transcript(TranscriptSource.NONE, List.of(), "", true, null,
                        List.of(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, "transcription",
                                "dossier de travail non inscriptible")),
                        "La transcription locale n'a pas pu démarrer.");
            }
        }
        if (job == null) {
            return new Transcript(TranscriptSource.NONE, List.of(), "", true, null, List.of(),
                    "Aucune transcription disponible.");
        }
        boolean succeeded = job.phase() == TranscriptionJob.Phase.TERMINE;
        return new Transcript(TranscriptSource.LOCAL, succeeded ? job.cues() : List.of(), job.file(),
                job.isOver(), job, job.gaps(), job.isOver() ? job.describe()
                        : "Je transcris l'enregistrement SUR CETTE MACHINE (" + job.phase().label()
                                + ") : c'est long, redemande pour suivre.");
    }

    /**
     * L'état d'une réunion pour la chaîne F-90 : la vidéo téléchargée, et les répliques de la
     * meilleure source. {@code ready} faux porte la phrase qui dit pourquoi.
     */
    Readiness readiness(String meetingId, TeamsMeeting meeting) {
        Sidecar sidecar = readSidecar(meetingId);
        if (sidecar == null) {
            return Readiness.notReady("Aucun enregistrement n'a été téléchargé pour cette réunion : "
                    + "appelle d'abord " + TeamsTools.MEETING_RECORDING + ".");
        }
        ChromeDownloads.Outcome outcome = sidecar.localPath().isBlank()
                ? downloads.check(videoDir(meetingId), sidecar.name(), sidecar.expectedBytes(),
                        sidecar.label())
                : localOutcome(Path.of(sidecar.localPath()));
        if (!outcome.done()) {
            return Readiness.notReady(outcome.state() == ChromeDownloads.State.IN_PROGRESS
                    ? "Le téléchargement de l'enregistrement n'est pas terminé : je n'extrais pas "
                            + "d'images d'un fichier incomplet."
                    : "L'enregistrement téléchargé est introuvable ou écarté : rappelle "
                            + TeamsTools.MEETING_RECORDING + ".");
        }
        Transcript transcript = transcript(meetingId, meeting, sidecar, outcome.file(), false);
        if (transcript.source() == TranscriptSource.LOCAL && !transcript.done()) {
            return Readiness.notReady("La transcription locale de l'enregistrement tourne encore : "
                    + "attends qu'elle soit terminée pour aligner les captures.");
        }
        Instant origin = meeting == null ? null : meeting.startedAt();
        List<TeamsTranscriptCue> cues = transcript.source() == TranscriptSource.TEAMS
                ? List.of() : transcript.cues();
        return new Readiness(true, outcome.file().toString(), cues, origin, "");
    }

    static String jobId(String meetingId) {
        return "rec-" + TeamsWorkFolder.safe(meetingId);
    }

    // ------------------------------------------------------------------ l'adresse observée

    /** L'adresse {@code .mp4} SharePoint / OneDrive observée dans le fil de la réunion, ou {@code ""}. */
    static String observedRecordingUrl(TeamsLedger book, TeamsMeeting meeting) {
        if (meeting == null || meeting.conversationId().isEmpty()) {
            return "";
        }
        List<TeamsMessage> messages = new ArrayList<>(book.messagesOf(meeting.conversationId(), null));
        java.util.Collections.reverse(messages);
        for (TeamsMessage message : messages) {
            for (TeamsAttachmentRef attachment : message.attachments()) {
                String url = ObservedResponse.withoutQuery(attachment.sourceUrl());
                if (url.toLowerCase(Locale.ROOT).endsWith(".mp4") && MP4.matcher(url).matches()) {
                    return url;
                }
            }
            Matcher found = MP4.matcher(message.html().replace("&amp;", "&"));
            if (found.find()) {
                return found.group();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ fichier-témoin

    /**
     * Ce qu'on garde d'un téléchargement pour le suivre sans geste : nom, taille, adresse web, chemins
     * locaux. <b>Aucun jeton, aucun cookie, aucune adresse signée</b> — il n'y en a jamais eu entre nos
     * mains.
     */
    record Sidecar(String meetingId, String name, long expectedBytes, String webUrl, String serverPath,
            String version, String localPath, String transcriptFile, String label) {
    }

    private Path sidecarPath(String meetingId) {
        return folder.downloadsDir().resolve(jobId(meetingId) + ".json");
    }

    Path videoDir(String meetingId) {
        return folder.downloadDir(jobId(meetingId));
    }

    private Path transcriptDir(String meetingId) {
        return folder.downloadDir(jobId(meetingId) + "-transcript");
    }

    private Sidecar readSidecar(String meetingId) {
        Path path = sidecarPath(meetingId);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(path.toFile());
            return new Sidecar(node.path("meetingId").asText(""), node.path("name").asText(""),
                    node.path("expectedBytes").asLong(-1), node.path("webUrl").asText(""),
                    node.path("serverRelativeUrl").asText(""), node.path("version").asText(""),
                    node.path("localPath").asText(""), node.path("transcriptFile").asText(""),
                    node.path("label").asText(""));
        } catch (IOException e) {
            return null;
        }
    }

    private void writeSidecar(Sidecar sidecar) {
        ObjectNode node = mapper.createObjectNode();
        node.put("meetingId", sidecar.meetingId());
        node.put("name", sidecar.name());
        node.put("expectedBytes", sidecar.expectedBytes());
        node.put("webUrl", sidecar.webUrl());
        node.put("serverRelativeUrl", sidecar.serverPath());
        node.put("version", sidecar.version());
        node.put("localPath", sidecar.localPath());
        node.put("transcriptFile", sidecar.transcriptFile());
        node.put("label", sidecar.label());
        try {
            Files.createDirectories(folder.downloadsDir());
            Files.writeString(sidecarPath(sidecar.meetingId()), node.toPrettyString(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            say.accept("Le fichier-témoin du téléchargement n'a pas pu être écrit : le suivi "
                    + "demandera un nouveau passage par le navigateur.");
        }
    }

    // ------------------------------------------------------------------ rendu

    private ToolOutcome finish(TeamsToolResult result, List<TeamsGap> gaps,
            List<PageActions.GestureRecord> journal, String viewport, StringBuilder text) {
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
        text.append(" (Adaptateur enregistrements : ").append(SharePointFiles.PROVENANCE).append(".)");
        result.window(null)
                .gaps(gaps)
                .health(host.ledger().health())
                .viewport(viewport)
                .with("firstUse", host.firstUse())
                .text(text.toString());
        return ToolOutcome.ok(result.render());
    }

    private static void renderRecording(TeamsToolResult result, SharePointLocation file) {
        renderRecording(result, file, file.webUrl(), file.label());
    }

    private static void renderRecording(TeamsToolResult result, SharePointLocation file, String webUrl,
            String label) {
        ObjectNode node = result.put("recording");
        node.put("label", label);
        node.put("webUrl", webUrl);
        node.put("serverRelativeUrl", file.serverPath());
    }

    private static void renderTranscript(TeamsToolResult result, Transcript transcript) {
        ObjectNode node = result.put("transcript");
        node.put("source", transcript.source().name());
        node.put("cues", transcript.cues().size());
        node.put("done", transcript.done());
        if (!transcript.file().isBlank()) {
            node.put("file", transcript.file());
        }
        if (transcript.job() != null) {
            node.put("phase", transcript.job().phase().name());
            if (!transcript.job().failure().isBlank()) {
                node.put("failure", transcript.job().failure());
            }
        }
    }

    private static ChromeDownloads.Outcome localOutcome(Path local) {
        return Files.isRegularFile(local)
                ? new ChromeDownloads.Outcome(ChromeDownloads.State.DONE, local, sizeOf(local), null)
                : new ChromeDownloads.Outcome(ChromeDownloads.State.NONE, null, 0L, null);
    }

    private static VttTranscript.Reading readVtt(Path file, Instant origin) {
        try {
            if (Files.size(file) > VttTranscript.MAX_BYTES) {
                return new VttTranscript.Reading(List.of(), 1);
            }
            return VttTranscript.parse(Files.readString(file, StandardCharsets.UTF_8), origin);
        } catch (IOException | RuntimeException e) {
            return new VttTranscript.Reading(List.of(), 1);
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }

    /** Le résultat du premier passage. */
    private record Download(ChromeDownloads.Outcome outcome, Sidecar sidecar, String viewport) {
    }

    /** Une transcription : sa source, ses répliques, et où elle en est. */
    private record Transcript(TranscriptSource source, List<TeamsTranscriptCue> cues, String file,
            boolean done, TranscriptionJob job, List<TeamsGap> gaps, String sentence) {
    }

    /** Ce que la chaîne F-90 peut prendre d'un enregistrement téléchargé. */
    record Readiness(boolean ready, String video, List<TeamsTranscriptCue> cues, Instant origin,
            String sentence) {

        static Readiness notReady(String sentence) {
            return new Readiness(false, "", List.of(), null, sentence);
        }
    }
}
