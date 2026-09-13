package fr.claudegateway.runner.teams;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>L'état d'un travail de moments</b> (F-90 / SF-90-03) — ce que l'agent dit quand on lui demande
 * où il en est, et ce qui permet de <b>reprendre</b>.
 *
 * <h2>Pourquoi un état écrit sur le disque</h2>
 *
 * <p>Télécharger {@code ffmpeg}, décoder une heure de vidéo, dédoublonner, remonter soixante
 * images : des <b>minutes</b>. C'est un traitement lourd, donc asynchrone — la règle de
 * {@code CLAUDE.md}, sur le modèle d'{@code OcrPollingWorker}. Un état gardé <b>seulement</b> en
 * mémoire disparaîtrait au premier redémarrage du runner, et l'utilisateur repaierait tout le
 * travail. Il est donc écrit, et relu.</p>
 *
 * <h2>Ce qu'il porte, et ce qu'il ne porte pas</h2>
 *
 * <p>Il porte des <b>identifiants d'image</b> remontés, jamais des octets : la vidéo, l'audio et les
 * images écartées <b>restent sur la machine</b>. Et il porte, à côté des moments, <b>ce qui n'a pas
 * pu être fait</b> — un travail qui ne rendrait que ses réussites produirait un compte rendu
 * plausible et faux.</p>
 */
public final class MomentsJob {

    /** Les étapes, dans l'ordre. Toutes sont <b>dites</b> dans le fil pendant qu'elles durent. */
    public enum Phase {
        OUTILLAGE("je m'assure d'avoir ffmpeg sur cette machine"),
        EXTRACTION("j'extrais les images aux changements de plan"),
        TRI("je dédoublonne : je ne garde que ce qui apprend quelque chose"),
        ALIGNEMENT("je pose chaque image à côté de la phrase prononcée pendant qu'elle était affichée"),
        REMONTEE("je fais remonter les images retenues — la vidéo, elle, reste ici"),
        TERMINE("terminé"),
        ECHOUE("échoué");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        /** Ce que l'agent écrit dans le fil pendant cette étape. */
        public String label() {
            return label;
        }

        public boolean isOver() {
            return this == TERMINE || this == ECHOUE;
        }
    }

    private final String id;
    private final String video;
    private final List<Moment> moments = Collections.synchronizedList(new ArrayList<>());
    private final List<TeamsGap> gaps = Collections.synchronizedList(new ArrayList<>());

    private volatile Phase phase = Phase.OUTILLAGE;
    private volatile Instant startedAt = Instant.now();
    private volatile Instant finishedAt;
    private volatile int extracted;
    private volatile int kept;
    private volatile int uploaded;
    private volatile int uploadRefused;
    private volatile String failure = "";
    private volatile String remedy = "";
    private volatile String timeline = "";
    private volatile String subject = "";

    public MomentsJob(String id, String video) {
        this.id = id == null ? "" : id;
        this.video = video == null ? "" : video;
    }

    public String id() {
        return id;
    }

    public String video() {
        return video;
    }

    public Phase phase() {
        return phase;
    }

    public List<Moment> moments() {
        synchronized (moments) {
            return List.copyOf(moments);
        }
    }

    public List<TeamsGap> gaps() {
        synchronized (gaps) {
            return List.copyOf(gaps);
        }
    }

    public int extracted() {
        return extracted;
    }

    public int kept() {
        return kept;
    }

    public int uploaded() {
        return uploaded;
    }

    public int uploadRefused() {
        return uploadRefused;
    }

    public String failure() {
        return failure;
    }

    public String remedy() {
        return remedy;
    }

    public String timeline() {
        return timeline;
    }

    public String subject() {
        return subject;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public boolean isOver() {
        return phase.isOver();
    }

    // ------------------------------------------------------------------ avancement

    MomentsJob phase(Phase next) {
        this.phase = next;
        if (next.isOver()) {
            this.finishedAt = Instant.now();
        }
        return this;
    }

    MomentsJob counts(int extractedCount, int keptCount) {
        this.extracted = extractedCount;
        this.kept = keptCount;
        return this;
    }

    MomentsJob uploadedOne() {
        this.uploaded++;
        return this;
    }

    MomentsJob uploadRefusedOne() {
        this.uploadRefused++;
        return this;
    }

    MomentsJob subject(String value) {
        this.subject = value == null ? "" : value.strip();
        return this;
    }

    MomentsJob timeline(String value) {
        this.timeline = value == null ? "" : value.strip();
        return this;
    }

    MomentsJob addMoment(Moment moment) {
        moments.add(moment);
        return this;
    }

    MomentsJob addGaps(List<TeamsGap> more) {
        if (more != null) {
            gaps.addAll(more);
        }
        return this;
    }

    MomentsJob addGap(TeamsGap gap) {
        if (gap != null) {
            gaps.add(gap);
        }
        return this;
    }

    /** L'échec, <b>avec son remède</b> : « échoué » tout seul n'aide personne. */
    MomentsJob failed(String why, String how) {
        this.failure = why == null ? "" : why.strip();
        this.remedy = how == null ? "" : how.strip();
        return phase(Phase.ECHOUE);
    }

    /**
     * La phrase à citer : où en est le travail, ce qu'il a produit, et <b>ce qu'il n'a pas pu
     * faire</b>.
     */
    public String describe() {
        StringBuilder text = new StringBuilder();
        switch (phase) {
            case TERMINE -> text.append(moments.size())
                    .append(moments.size() > 1 ? " moments alignés" : " moment aligné")
                    .append(" sur ").append(kept)
                    .append(kept > 1 ? " images retenues" : " image retenue")
                    .append(" (").append(extracted).append(" changements de plan détectés).");
            case ECHOUE -> {
                text.append("Ce travail n'a pas abouti : ").append(failure);
                if (!remedy.isEmpty()) {
                    text.append(' ').append(remedy);
                }
            }
            default -> text.append("En cours — ").append(phase.label()).append('.');
        }
        if (uploadRefused > 0) {
            text.append(' ').append(uploadRefused)
                    .append(uploadRefused > 1 ? " images n'ont pas pu remonter" : " image n'a pas "
                            + "pu remonter")
                    .append(" : leurs moments sont rendus sans capture.");
        }
        List<TeamsGap> snapshot = gaps();
        if (!snapshot.isEmpty()) {
            List<String> described = new ArrayList<>();
            snapshot.forEach(gap -> described.add(gap.describe()));
            text.append(" Ce qui n'a pas pu être fait : ").append(String.join(" ; ", described))
                    .append('.');
        }
        if (!timeline.isEmpty()) {
            text.append(' ').append(timeline);
        }
        return text.toString();
    }

    // ------------------------------------------------------------------ persistance

    /**
     * Un moment tel qu'il survit au redémarrage : l'image y est un <b>identifiant remonté</b>, pas
     * un chemin local — c'est ce qui sera posé dans le bloc moment de F-89.
     */
    public record Moment(String at, double offsetSeconds, String quote, String speaker,
            String imageId, int otherCues) {

        public Moment {
            at = at == null ? "" : at;
            quote = quote == null ? "" : quote;
            speaker = speaker == null ? "" : speaker;
            imageId = imageId == null ? "" : imageId;
        }
    }

    ObjectNode toJson(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id);
        node.put("video", video);
        node.put("phase", phase.name());
        node.put("startedAt", startedAt == null ? null : startedAt.toString());
        node.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
        node.put("extracted", extracted);
        node.put("kept", kept);
        node.put("uploaded", uploaded);
        node.put("uploadRefused", uploadRefused);
        node.put("failure", failure);
        node.put("remedy", remedy);
        node.put("timeline", timeline);
        node.put("subject", subject);
        ArrayNode items = node.putArray("moments");
        for (Moment moment : moments()) {
            ObjectNode entry = items.addObject();
            entry.put("at", moment.at());
            entry.put("offsetSeconds", moment.offsetSeconds());
            entry.put("quote", moment.quote());
            entry.put("speaker", moment.speaker());
            entry.put("imageId", moment.imageId());
            entry.put("otherCues", moment.otherCues());
        }
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

    static MomentsJob fromJson(JsonNode node) {
        MomentsJob job = new MomentsJob(node.path("id").asText(""), node.path("video").asText(""));
        job.phase = phaseOf(node.path("phase").asText(""));
        job.startedAt = instant(node.path("startedAt").asText(""));
        job.finishedAt = instant(node.path("finishedAt").asText(""));
        job.extracted = node.path("extracted").asInt();
        job.kept = node.path("kept").asInt();
        job.uploaded = node.path("uploaded").asInt();
        job.uploadRefused = node.path("uploadRefused").asInt();
        job.failure = node.path("failure").asText("");
        job.remedy = node.path("remedy").asText("");
        job.timeline = node.path("timeline").asText("");
        job.subject = node.path("subject").asText("");
        for (JsonNode moment : node.path("moments")) {
            job.moments.add(new Moment(moment.path("at").asText(""),
                    moment.path("offsetSeconds").asDouble(), moment.path("quote").asText(""),
                    moment.path("speaker").asText(""), moment.path("imageId").asText(""),
                    moment.path("otherCues").asInt()));
        }
        for (JsonNode gap : node.path("gaps")) {
            TeamsGapKind kind = gapKindOf(gap.path("kind").asText(""));
            if (kind != null) {
                job.gaps.add(new TeamsGap(kind, gap.path("where").asText(""),
                        gap.path("detail").asText(""), gap.path("count").asInt(1)));
            }
        }
        return job;
    }

    /**
     * Une étape relue mais inconnue vaut {@link Phase#ECHOUE}, jamais « en cours » : un travail dont
     * on ne sait plus où il en est ne doit pas se faire passer pour vivant.
     */
    private static Phase phaseOf(String raw) {
        try {
            return Phase.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Phase.ECHOUE;
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
