package fr.claudegateway.runner.teams;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>L'état d'une capture locale</b> (F-91 / SF-91-01) — écrit sur le disque de la machine, et relu.
 *
 * <h2>Pourquoi écrit, et pas seulement en mémoire</h2>
 *
 * <p>Une capture produit un fichier lourd. Un runner redémarré pendant qu'elle tourne doit pouvoir
 * <b>dire qu'elle a eu lieu</b>, même s'il ne peut plus l'arrêter proprement : un état gardé
 * seulement en mémoire laisserait un fichier de plusieurs centaines de mégaoctets dont plus rien ne
 * parlerait — exactement l'enregistrement orphelin et anonyme que ce volet interdit.</p>
 *
 * <h2>Ce qu'il porte</h2>
 *
 * <p>L'usage, la confirmation qui a été donnée (ou pas), le filigrane <b>tel qu'il a été incrusté</b>
 * et la mention destinée au compte rendu. Ces deux dernières ne sont pas des doublons de la vidéo :
 * ce sont les deux autres endroits où la trace voyage — l'image, le texte, le journal.</p>
 */
public final class CaptureRecord {

    /** Les états d'une capture. Liste close. */
    public enum State {
        /** Elle tourne. */
        EN_COURS("en cours"),
        /** Elle s'est arrêtée proprement, et le fichier porte quelque chose. */
        TERMINEE("terminée"),
        /** Elle s'est arrêtée mal, ou n'a rien produit. */
        ECHOUEE("échouée");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean isOver() {
            return this != EN_COURS;
        }
    }

    private final String id;
    private final CapturePurpose purpose;
    private final boolean participantsInformed;
    private final String video;
    private final String watermark;
    private final String mention;
    private final List<TeamsGap> gaps = Collections.synchronizedList(new ArrayList<>());

    private volatile State state = State.EN_COURS;
    private volatile Instant startedAt = Instant.now();
    private volatile Instant stoppedAt;
    private volatile boolean audio;
    private volatile String devices = "";
    private volatile String subject = "";
    private volatile String failure = "";
    private volatile String remedy = "";
    private volatile long bytes;
    /** Transcription produite sur la machine (SF-91-03), ou {@code ""} tant qu'il n'y en a pas. */
    private volatile String transcript = "";

    public CaptureRecord(String id, CaptureConsent consent, String video, Watermark watermark) {
        this.id = id == null ? "" : id;
        this.purpose = consent == null ? CapturePurpose.SELF_SCREEN : consent.purpose();
        this.participantsInformed = consent != null && consent.participantsInformed();
        this.video = video == null ? "" : video;
        this.watermark = watermark == null ? "" : watermark.line();
        this.mention = watermark == null ? "" : watermark.mention();
    }

    public String id() {
        return id;
    }

    public CapturePurpose purpose() {
        return purpose;
    }

    public boolean participantsInformed() {
        return participantsInformed;
    }

    /** Le fichier produit, <b>sur la machine</b> — il n'en bouge jamais. */
    public String video() {
        return video;
    }

    /** La ligne incrustée dans l'image, telle quelle. */
    public String watermark() {
        return watermark;
    }

    /** La mention à poser <b>en tête du compte rendu</b>. */
    public String mention() {
        return mention;
    }

    public State state() {
        return state;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant stoppedAt() {
        return stoppedAt;
    }

    public boolean audio() {
        return audio;
    }

    public String devices() {
        return devices;
    }

    public String subject() {
        return subject;
    }

    public String failure() {
        return failure;
    }

    public String remedy() {
        return remedy;
    }

    public long bytes() {
        return bytes;
    }

    public String transcript() {
        return transcript;
    }

    public List<TeamsGap> gaps() {
        synchronized (gaps) {
            return List.copyOf(gaps);
        }
    }

    public boolean isOver() {
        return state.isOver();
    }

    /** Durée écoulée, ou durée finale une fois arrêtée. */
    public Duration elapsed(Instant now) {
        Instant end = stoppedAt != null ? stoppedAt : (now == null ? Instant.now() : now);
        return startedAt == null ? Duration.ZERO : Duration.between(startedAt, end);
    }

    // ------------------------------------------------------------------ avancement

    CaptureRecord startedAt(Instant when) {
        this.startedAt = when == null ? Instant.now() : when;
        return this;
    }

    CaptureRecord devices(CaptureDevices resolved) {
        this.audio = resolved != null && resolved.hasAudio();
        this.devices = resolved == null ? "" : resolved.describe();
        return this;
    }

    CaptureRecord subject(String value) {
        this.subject = value == null ? "" : value.strip();
        return this;
    }

    CaptureRecord finished(Instant when, long sizeBytes) {
        this.stoppedAt = when == null ? Instant.now() : when;
        this.bytes = Math.max(0L, sizeBytes);
        this.state = State.TERMINEE;
        return this;
    }

    CaptureRecord failed(Instant when, String why, String how) {
        this.stoppedAt = when == null ? Instant.now() : when;
        this.failure = why == null ? "" : why.strip();
        this.remedy = how == null ? "" : how.strip();
        this.state = State.ECHOUEE;
        return this;
    }

    CaptureRecord transcript(String value) {
        this.transcript = value == null ? "" : value.strip();
        return this;
    }

    CaptureRecord addGap(TeamsGap gap) {
        if (gap != null) {
            gaps.add(gap);
        }
        return this;
    }

    /**
     * La phrase à citer : où en est la capture, ce qu'elle porte, et <b>ce qui n'a pas pu être
     * fait</b>.
     */
    public String describe(Instant now) {
        StringBuilder text = new StringBuilder();
        switch (state) {
            case EN_COURS -> text.append("Capture en cours depuis ").append(clock(elapsed(now)))
                    .append(" — ").append(purpose.label()).append('.');
            case TERMINEE -> text.append("Capture terminée après ").append(clock(elapsed(now)))
                    .append(" : ").append(video).append(" (").append(mib(bytes)).append(").");
            case ECHOUEE -> {
                text.append("Cette capture n'a pas abouti : ").append(failure);
                if (!remedy.isEmpty()) {
                    text.append(' ').append(remedy);
                }
            }
            default -> text.append(state.label());
        }
        if (!devices.isEmpty()) {
            text.append(' ').append(devices);
        }
        text.append(" Filigrane incrusté dans l'image : « ").append(watermark).append(" »");
        text.append(" — la vidéo reste sur cette machine.");
        List<TeamsGap> snapshot = gaps();
        if (!snapshot.isEmpty()) {
            List<String> described = new ArrayList<>();
            snapshot.forEach(gap -> described.add(gap.describe()));
            text.append(" Ce qui n'a pas pu être fait : ").append(String.join(" ; ", described))
                    .append('.');
        }
        return text.toString();
    }

    /** {@code 01:23:45} — une durée se lit, elle ne se calcule pas. */
    public static String clock(Duration duration) {
        long total = Math.max(0L, duration == null ? 0L : duration.getSeconds());
        return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60,
                total % 60);
    }

    static String mib(long bytes) {
        return String.format(Locale.FRENCH, "%.1f Mo", bytes / (1024.0 * 1024.0));
    }

    // ------------------------------------------------------------------ persistance

    ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id);
        node.put("purpose", purpose.name());
        node.put("participantsInformed", participantsInformed);
        node.put("video", video);
        node.put("watermark", watermark);
        node.put("mention", mention);
        node.put("state", state.name());
        node.put("startedAt", startedAt == null ? null : startedAt.toString());
        node.put("stoppedAt", stoppedAt == null ? null : stoppedAt.toString());
        node.put("audio", audio);
        node.put("devices", devices);
        node.put("subject", subject);
        node.put("failure", failure);
        node.put("remedy", remedy);
        node.put("bytes", bytes);
        node.put("transcript", transcript);
        ArrayNode holes = node.putArray("gaps");
        for (TeamsGap gap : gaps()) {
            ObjectNode entry = holes.addObject();
            entry.put("kind", gap.kind().name());
            entry.put("where", gap.where());
            entry.put("detail", gap.detail());
            entry.put("count", gap.count());
        }
        return node;
    }

    /**
     * Un enregistrement relu. Le filigrane et la mention sont repris <b>tels qu'ils ont été écrits
     * au démarrage</b>, jamais recalculés : recalculer donnerait une heure différente de celle qui
     * est dans l'image, donc une trace qui ne correspondrait plus à l'artefact.
     */
    static CaptureRecord fromJson(JsonNode node) {
        CaptureRecord record = new CaptureRecord(node.path("id").asText(""),
                purposeOf(node.path("purpose").asText("")),
                node.path("participantsInformed").asBoolean(false),
                node.path("video").asText(""),
                node.path("watermark").asText(""),
                node.path("mention").asText(""));
        record.state = stateOf(node.path("state").asText(""));
        record.startedAt = instant(node.path("startedAt").asText(""));
        record.stoppedAt = instant(node.path("stoppedAt").asText(""));
        record.audio = node.path("audio").asBoolean(false);
        record.devices = node.path("devices").asText("");
        record.subject = node.path("subject").asText("");
        record.failure = node.path("failure").asText("");
        record.remedy = node.path("remedy").asText("");
        record.bytes = node.path("bytes").asLong(0L);
        record.transcript = node.path("transcript").asText("");
        for (JsonNode gap : node.path("gaps")) {
            TeamsGapKind kind = gapKindOf(gap.path("kind").asText(""));
            if (kind != null) {
                record.gaps.add(new TeamsGap(kind, gap.path("where").asText(""),
                        gap.path("detail").asText(""), gap.path("count").asInt(1)));
            }
        }
        return record;
    }

    private CaptureRecord(String id, CapturePurpose purpose, boolean participantsInformed,
            String video, String watermark, String mention) {
        this.id = id;
        this.purpose = purpose;
        this.participantsInformed = participantsInformed;
        this.video = video;
        this.watermark = watermark == null ? "" : watermark;
        this.mention = mention == null ? "" : mention;
    }

    /**
     * Un usage relu mais inconnu vaut « réunion » : c'est le plus contraignant des deux. Se replier
     * sur le moins exigeant transformerait une relecture ratée en permission.
     */
    private static CapturePurpose purposeOf(String raw) {
        try {
            return CapturePurpose.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return CapturePurpose.MEETING_WITH_OTHERS;
        }
    }

    /**
     * Un état relu mais inconnu vaut {@link State#ECHOUEE}, jamais « en cours » : une capture dont on
     * ne sait plus où elle en est ne doit pas se faire passer pour vivante.
     */
    private static State stateOf(String raw) {
        try {
            return State.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return State.ECHOUEE;
        }
    }

    private static TeamsGapKind gapKindOf(String raw) {
        try {
            return TeamsGapKind.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private static Instant instant(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : Instant.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
