package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>La transcription, sur la machine</b> (F-91 / SF-91-03).
 *
 * <h2>Pourquoi ici, et pas ailleurs</h2>
 *
 * <p>Une capture locale n'a <b>pas</b> de transcription — Teams n'en produit que pour ses propres
 * enregistrements. Il faut donc la produire. Et il faut la produire <b>ici</b> : envoyer l'audio
 * d'une réunion à un service <b>annulerait le bénéfice de garder la vidéo en local</b>. Ce qu'on
 * protège en ne remontant pas les images, on le donnerait en remontant les voix.</p>
 *
 * <p>Le seul trafic de cette classe est le <b>rapatriement du modèle</b>, une fois, depuis une
 * adresse <b>en dur</b> dans {@link LocalTool}. Ni l'audio, ni la vidéo, ni le fichier intermédiaire
 * ne sortent de la machine.</p>
 *
 * <h2>L'origine du temps est la seule du volet qui ne soit pas une hypothèse</h2>
 *
 * <p>F-90 refuse d'aligner des images sans connaître l'instant où commence la vidéo, et c'est la
 * bonne règle. Ici, on le <b>connaît exactement</b> : c'est l'instant où <b>nous</b> avons démarré la
 * capture, et nous l'avons écrit. Les répliques sont donc datées en absolu sans rien deviner — ce
 * qui rend les moments d'une capture locale plus sûrs que ceux d'un enregistrement Teams.</p>
 *
 * <h2>Ce qu'elle ne prétend pas faire</h2>
 *
 * <p><b>Dire qui parle.</b> Le moteur local ne sépare pas les voix, et attribuer les paroles serait
 * une invention — « une attribution fausse est pire qu'une absence d'attribution ». Les répliques
 * sortent sans locuteur, et le manque est <b>nommé</b>.</p>
 */
public final class LocalTranscription {

    /** Une heure de réunion sur un portable ordinaire : quelques minutes, au pire davantage. */
    static final long TRANSCRIPTION_TIMEOUT_MS = 3_600_000L;
    /** Préfixe des fichiers produits par le moteur. */
    static final String OUTPUT_BASE = "transcript";
    /** Le fichier lisible, celui qui porte la mention en première ligne. */
    static final String READABLE = "transcript.txt";

    private final LocalToolchain toolchain;
    private final ProcessRunner processes;

    public LocalTranscription(LocalToolchain toolchain, ProcessRunner processes) {
        this.toolchain = toolchain;
        this.processes = processes;
    }

    /**
     * Transcrit un fichier audio.
     *
     * @param audio     l'audio extrait de la capture, <b>sur la machine</b>
     * @param into      le dossier de la capture
     * @param startedAt l'instant du début de la capture — <b>connu</b>, pas deviné
     * @param mention   la mention à écrire en <b>première ligne</b> du fichier lisible
     * @throws ToolchainUnavailableException    quand le moteur ou le modèle manquent
     * @throws TranscriptionFailedException     quand le moteur échoue, ou rend un format inconnu
     */
    public Result transcribe(Path audio, Path into, Instant startedAt, String mention) {
        Path engine = toolchain.require(LocalTool.transcriber());
        Path model = toolchain.require(LocalTool.transcriptionModel());
        Path output = into.resolve(OUTPUT_BASE);
        try {
            ProcessRunner.ProcessResult result = processes.run(
                    command(engine, model, audio, output), into, TRANSCRIPTION_TIMEOUT_MS);
            if (!result.succeeded()) {
                throw new TranscriptionFailedException(
                        result.timedOut()
                                ? "La transcription a dépassé le délai et a été arrêtée."
                                : "Le moteur de transcription n'a pas abouti sur cet enregistrement.",
                        result.tail());
            }
            Path csv = into.resolve(OUTPUT_BASE + ".csv");
            if (!Files.isRegularFile(csv)) {
                throw new TranscriptionFailedException(
                        "Le moteur de transcription n'a pas écrit le fichier attendu.",
                        "Il a peut-être changé de format de sortie. Je préfère refuser plutôt que "
                                + "de rendre une transcription partielle : un compte rendu "
                                + "plausible et faux est pire qu'un compte rendu qui manque. "
                                + result.tail(3));
            }
            Result parsed = read(Files.readAllLines(csv, StandardCharsets.UTF_8), startedAt);
            write(into.resolve(READABLE), mention, parsed.cues());
            return parsed;
        } catch (IOException e) {
            throw new TranscriptionFailedException(
                    "La transcription n'a pas pu être lue sur cette machine.",
                    e.getMessage() == null ? "" : e.getMessage());
        }
    }

    /**
     * La ligne de commande, <b>argument par argument</b>.
     *
     * <p>Le format demandé est le <b>CSV</b> plutôt que le texte brut, et pour une raison unique :
     * il porte les <b>horodatages</b>. Sans eux, aucune réplique ne peut être posée à côté de
     * l'image qui était à l'écran pendant qu'elle se disait — c'est-à-dire que F-90 ne sert plus à
     * rien.</p>
     */
    static List<String> command(Path engine, Path model, Path audio, Path outputBase) {
        return List.of(
                engine.toAbsolutePath().toString(),
                "-m", model.toAbsolutePath().toString(),
                "-f", audio.toAbsolutePath().toString(),
                // Langue détectée : un consultant passe d'une langue à l'autre, et imposer le
                // français ferait transcrire un appel en anglais en charabia français.
                "-l", "auto",
                "-ocsv",
                "-of", outputBase.toAbsolutePath().toString());
    }

    /**
     * <b>Lit la sortie du moteur</b>, et refuse quand elle n'est pas celle qu'on attend.
     *
     * <p><b>La nuance qui décide de tout</b> : un <b>format entier</b> qu'on ne reconnaît plus veut
     * dire qu'on ne sait plus lire — on refuse, plutôt que de rendre la moitié. Une <b>ligne
     * isolée</b> illisible est un trou qu'on peut nommer : elle est écartée, comptée, et dite.</p>
     *
     * <p><b>Provenance, écrite et non maquillée</b> : le format {@code start,end,text} en
     * millisecondes est celui documenté par le moteur. Il n'a été observé sur <b>aucune</b> sortie
     * réelle — le CI n'a ni le binaire ni le modèle.</p>
     */
    static Result read(List<String> lines, Instant startedAt) {
        if (lines == null || lines.isEmpty()) {
            throw new TranscriptionFailedException(
                    "Le fichier de transcription est vide : je ne sais pas si rien n'a été dit ou "
                            + "si le moteur a échoué, et je ne veux pas faire croire à l'un ou à "
                            + "l'autre.",
                    "Relancez la transcription. Si cela se reproduit, l'enregistrement ne porte "
                            + "peut-être aucun son.");
        }
        List<TeamsTranscriptCue> cues = new ArrayList<>();
        List<TeamsGap> gaps = new ArrayList<>();
        int unreadable = 0;
        int examined = 0;
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            if (isHeader(line)) {
                continue;
            }
            examined++;
            TeamsTranscriptCue cue = cueOf(line, startedAt);
            if (cue == null) {
                unreadable++;
            } else if (cue.isReadable()) {
                cues.add(cue);
            }
        }
        if (examined == 0) {
            throw new TranscriptionFailedException(
                    "Le moteur de transcription a rendu un format que je ne reconnais pas.",
                    "Je refuse plutôt que de rendre la moitié des répliques : un compte rendu "
                            + "plausible et faux est pire qu'un compte rendu qui manque.");
        }
        if (unreadable > 0) {
            gaps.add(new TeamsGap(TeamsGapKind.UNRECOGNIZED_PAYLOAD, "transcription locale",
                    "répliques dont l'horodatage n'a pas pu être lu : elles sont écartées — une "
                            + "parole mal datée serait posée à côté de la mauvaise image",
                    unreadable));
        }
        if (cues.isEmpty()) {
            // Zéro ET un manque nommé : « aucune parole reconnue » n'est PAS « personne n'a parlé ».
            gaps.add(new TeamsGap(TeamsGapKind.NOTHING_OBSERVED, "transcription locale",
                    "aucune parole n'a été reconnue dans cet enregistrement — ce qui ne veut pas "
                            + "dire que personne n'a parlé : le son était peut-être trop faible", 1));
        }
        // Le moteur local ne sépare pas les voix : c'est dit, jamais deviné.
        gaps.add(new TeamsGap(TeamsGapKind.MISSING_FIELD, "transcription locale",
                "le moteur qui tourne sur cette machine ne dit pas QUI parle : les répliques sont "
                        + "rendues sans locuteur — une attribution fausse serait pire qu'une "
                        + "absence d'attribution", 1));
        return new Result(List.copyOf(cues), List.copyOf(gaps), examined, unreadable);
    }

    /** {@code 0,2400,"Bonjour à tous."} — une réplique, ou {@code null} si la ligne est illisible. */
    private static TeamsTranscriptCue cueOf(String line, Instant startedAt) {
        int firstComma = line.indexOf(',');
        int secondComma = firstComma < 0 ? -1 : line.indexOf(',', firstComma + 1);
        if (firstComma <= 0 || secondComma < 0) {
            return null;
        }
        long startMs;
        long endMs;
        try {
            startMs = Long.parseLong(line.substring(0, firstComma).strip());
            endMs = Long.parseLong(line.substring(firstComma + 1, secondComma).strip());
        } catch (NumberFormatException e) {
            return null;
        }
        String text = unquote(line.substring(secondComma + 1));
        if (text.isEmpty()) {
            // Une réplique sans texte n'est pas un trou : le moteur en produit sur les silences.
            return new TeamsTranscriptCue(startedAt.plusMillis(startMs), 0L, "", "", "");
        }
        return new TeamsTranscriptCue(startedAt.plusMillis(startMs),
                Math.max(0L, endMs - startMs), "", "", text);
    }

    /** La première ligne du CSV, quand le moteur en écrit une. */
    private static boolean isHeader(String line) {
        String lower = line.strip().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("start,end") || lower.startsWith("\"start\"");
    }

    /** Le texte CSV, guillemets et doublements retirés. */
    static String unquote(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.length() >= 2 && value.charAt(0) == '"' && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.replace("\"\"", "\"").strip();
    }

    /**
     * <b>Le fichier lisible, et la mention en PREMIÈRE ligne.</b>
     *
     * <p>C'est le troisième endroit où la trace voyage — l'image porte le filigrane, le journal
     * d'audit porte la ligne, ce fichier porte la mention. Quelqu'un qui ouvre la transcription six
     * mois plus tard doit savoir <b>d'où elle vient</b> avant de lire la première réplique.</p>
     */
    private static void write(Path file, String mention, List<TeamsTranscriptCue> cues)
            throws IOException {
        StringBuilder text = new StringBuilder();
        if (mention != null && !mention.isBlank()) {
            text.append(mention.strip()).append(System.lineSeparator())
                    .append(System.lineSeparator());
        }
        for (TeamsTranscriptCue cue : cues) {
            text.append('[').append(cue.at()).append("] ").append(cue.text())
                    .append(System.lineSeparator());
        }
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Ce que la transcription a produit — <b>et ce qu'elle n'a pas pu faire</b>.
     *
     * @param cues       les répliques, datées en absolu
     * @param gaps       ce qui n'a pas pu être fait, toujours non vide (le locuteur y est)
     * @param examined   lignes examinées
     * @param unreadable lignes écartées
     */
    public record Result(List<TeamsTranscriptCue> cues, List<TeamsGap> gaps, int examined,
            int unreadable) {

        public Result {
            cues = cues == null ? List.of() : List.copyOf(cues);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }
    }

    /** La transcription n'a pas abouti. Porte une phrase et, quand le moteur a parlé, ses lignes. */
    public static class TranscriptionFailedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String detail;

        public TranscriptionFailedException(String message, String detail) {
            super(message);
            this.detail = detail == null ? "" : detail.strip();
        }

        public String detail() {
            return detail;
        }
    }
}
