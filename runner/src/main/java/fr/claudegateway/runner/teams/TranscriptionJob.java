package fr.claudegateway.runner.teams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <b>L'état d'une transcription locale</b> (F-91 / SF-91-03) — ce que l'agent dit quand on lui
 * demande où il en est.
 *
 * <p>Il vit en mémoire, et <b>pas</b> sur le disque, contrairement à {@link CaptureRecord} : ce qui
 * doit survivre à un redémarrage, c'est l'existence de l'<b>enregistrement</b> — un fichier lourd
 * dont plus rien ne parlerait serait le vrai danger. Une transcription perdue, elle, se
 * <b>recommence</b> : elle ne coûte que du temps de calcul, et le fichier lisible qu'elle a écrit
 * porte déjà sa mention.</p>
 */
public final class TranscriptionJob {

    /** Les étapes, dans l'ordre. Toutes sont <b>dites</b> dans le fil pendant qu'elles durent. */
    public enum Phase {
        AUDIO("j'extrais le son de l'enregistrement — il ne quitte pas cette machine"),
        MODELE("je m'assure d'avoir le modèle de transcription sur cette machine"),
        TRANSCRIPTION("je transcris, ici, sans rien envoyer nulle part"),
        TERMINE("terminé"),
        ECHOUE("échoué");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean isOver() {
            return this == TERMINE || this == ECHOUE;
        }
    }

    private final String id;
    private final List<TeamsTranscriptCue> cues = Collections.synchronizedList(new ArrayList<>());
    private final List<TeamsGap> gaps = Collections.synchronizedList(new ArrayList<>());

    private volatile Phase phase = Phase.AUDIO;
    private volatile String failure = "";
    private volatile String remedy = "";
    private volatile String file = "";

    public TranscriptionJob(String id) {
        this.id = id == null ? "" : id;
    }

    public String id() {
        return id;
    }

    public Phase phase() {
        return phase;
    }

    public boolean isOver() {
        return phase.isOver();
    }

    public List<TeamsTranscriptCue> cues() {
        synchronized (cues) {
            return List.copyOf(cues);
        }
    }

    public List<TeamsGap> gaps() {
        synchronized (gaps) {
            return List.copyOf(gaps);
        }
    }

    public String failure() {
        return failure;
    }

    public String remedy() {
        return remedy;
    }

    /** Le fichier lisible <b>sur la machine</b> — il n'en bouge pas. */
    public String file() {
        return file;
    }

    // ------------------------------------------------------------------ avancement

    TranscriptionJob phase(Phase next) {
        this.phase = next;
        return this;
    }

    TranscriptionJob cues(List<TeamsTranscriptCue> more) {
        if (more != null) {
            cues.addAll(more);
        }
        return this;
    }

    TranscriptionJob addGaps(List<TeamsGap> more) {
        if (more != null) {
            gaps.addAll(more);
        }
        return this;
    }

    TranscriptionJob addGap(TeamsGap gap) {
        if (gap != null) {
            gaps.add(gap);
        }
        return this;
    }

    TranscriptionJob file(String value) {
        this.file = value == null ? "" : value;
        return this;
    }

    /** L'échec, <b>avec son remède</b> : « échoué » tout seul n'aide personne. */
    TranscriptionJob failed(String why, String how) {
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
        int count = cues().size();
        switch (phase) {
            case TERMINE -> text.append(count)
                    .append(count > 1 ? " répliques transcrites" : " réplique transcrite")
                    .append(" sur cette machine. Ni la vidéo ni l'audio n'en sont sortis.");
            case ECHOUE -> {
                text.append("La transcription n'a pas abouti : ").append(failure);
                if (!remedy.isEmpty()) {
                    text.append(' ').append(remedy);
                }
            }
            default -> text.append("En cours — ").append(phase.label()).append('.');
        }
        List<TeamsGap> snapshot = gaps();
        if (!snapshot.isEmpty()) {
            List<String> described = new ArrayList<>();
            snapshot.forEach(gap -> described.add(gap.describe()));
            text.append(" Ce qui n'a pas pu être fait : ").append(String.join(" ; ", described))
                    .append('.');
        }
        return text.toString();
    }
}
