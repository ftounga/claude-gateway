package fr.claudegateway.runner.teams;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>La ligne de commande de la capture</b> (F-91 / SF-91-01), assemblée <b>argument par
 * argument</b>.
 *
 * <h2>Jamais une ligne de shell</h2>
 *
 * <p>C'est la leçon de SF-38-23, et elle vaut ici plus qu'ailleurs : le filigrane porte un <b>nom
 * d'utilisateur</b>, et un nom d'utilisateur peut contenir n'importe quoi. Concaténé dans un shell,
 * il deviendrait une commande. Chaque argument est donc un élément de liste, et le graphe de filtres
 * est échappé par {@link Watermark#escape(String)}.</p>
 *
 * <h2>Le filigrane n'est pas optionnel dans cette classe</h2>
 *
 * <p>{@link #build} <b>exige</b> un filtre. Une signature qui accepterait {@code null} laisserait un
 * jour quelqu'un passer {@code null} « juste pour déboguer », et produirait un enregistrement
 * anonyme. Le type dit la règle.</p>
 *
 * <h2>Provenance des entrées, écrite et non maquillée</h2>
 *
 * <p>{@code x11grab} + {@code pulse}, {@code gdigrab} + {@code dshow}, {@code avfoundation} : ce
 * sont les modules de capture <b>documentés</b> d'{@code ffmpeg} pour chaque système. Ils n'ont pas
 * été éprouvés sur un poste de test — le CI n'a ni écran ni son. Ce qui est éprouvé ici est
 * l'<b>assemblage</b> : que le filigrane y soit toujours, que rien ne passe par un shell, que les
 * systèmes inconnus soient refusés plutôt que devinés.</p>
 */
public final class ScreenCaptureCommand {

    /** Images par seconde. Une réunion n'est pas un film : 15 suffisent et divisent le poids. */
    static final int FRAMERATE = 15;
    /** Qualité vidéo : lisible, et raisonnable pour une heure de réunion. */
    static final String CRF = "28";
    /** Débit audio : la parole, pas la musique. */
    static final String AUDIO_BITRATE = "128k";

    private ScreenCaptureCommand() {
    }

    /**
     * La commande complète.
     *
     * @param ffmpeg    le binaire résolu (D3)
     * @param os        le système observé
     * @param devices   les entrées de ce poste
     * @param watermark le filtre de filigrane — <b>obligatoire</b>
     * @param output    le fichier produit, <b>dans le dossier de travail du volet</b>
     * @throws CaptureRefusedException pour un système dont on ne connaît pas le mode de capture
     */
    public static List<String> build(Path ffmpeg, OperatingSystem os, CaptureDevices devices,
            String watermark, Path output) {
        if (watermark == null || watermark.isBlank()) {
            throw new IllegalArgumentException(
                    "aucune capture ne s'assemble sans filigrane : c'est la règle, pas un défaut");
        }
        List<String> command = new ArrayList<>();
        command.add(ffmpeg.toAbsolutePath().toString());
        command.add("-hide_banner");
        // PAS de -nostdin, contrairement à F-90 : c'est par l'entrée standard qu'on demandera
        // l'arrêt propre (« q »), et un conteneur fermé proprement est la différence entre un
        // fichier lisible et 400 Mo que personne n'ouvre.
        command.add("-y");
        addVideoInput(command, os, devices);
        addAudioInput(command, os, devices);
        command.add("-vf");
        command.add(watermark);
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");
        command.add("-crf");
        command.add(CRF);
        command.add("-pix_fmt");
        command.add("yuv420p");
        if (devices.hasAudio()) {
            command.add("-c:a");
            command.add("aac");
            command.add("-b:a");
            command.add(AUDIO_BITRATE);
        }
        // L'index en tête : un fichier tronqué par une coupure de courant reste lisible jusqu'où il
        // va, au lieu de n'être lisible nulle part.
        command.add("-movflags");
        command.add("+faststart");
        command.add(output.toAbsolutePath().toString());
        return List.copyOf(command);
    }

    private static void addVideoInput(List<String> command, OperatingSystem os,
            CaptureDevices devices) {
        switch (os) {
            case LINUX -> {
                command.add("-f");
                command.add("x11grab");
                command.add("-framerate");
                command.add(String.valueOf(FRAMERATE));
                command.add("-i");
                command.add(devices.screen());
            }
            case WINDOWS -> {
                command.add("-f");
                command.add("gdigrab");
                command.add("-framerate");
                command.add(String.valueOf(FRAMERATE));
                command.add("-i");
                command.add(devices.screen());
            }
            case MACOS -> {
                // avfoundation prend l'écran ET le son dans UNE seule entrée « écran:son » : deux
                // entrées séparées ne sont pas acceptées par ce module.
                command.add("-f");
                command.add("avfoundation");
                command.add("-framerate");
                command.add(String.valueOf(FRAMERATE));
                command.add("-i");
                command.add(devices.hasAudio()
                        ? devices.screen() + ":" + devices.audio() : devices.screen() + ":none");
            }
            default -> throw new CaptureRefusedException(CaptureRefusedException.NO_DEVICE,
                    "Je ne sais pas capturer l'écran de ce système.",
                    "La capture locale connaît Windows, macOS et Linux. Sur ce système, "
                            + "enregistrez avec l'outil de votre poste, puis donnez-moi le chemin "
                            + "du fichier : le reste du traitement, lui, fonctionne.");
        }
    }

    private static void addAudioInput(List<String> command, OperatingSystem os,
            CaptureDevices devices) {
        if (!devices.hasAudio() || os == OperatingSystem.MACOS) {
            return; // macOS : déjà dans l'entrée unique ci-dessus.
        }
        switch (os) {
            case LINUX -> {
                command.add("-f");
                command.add("pulse");
                command.add("-i");
                command.add(devices.audio());
            }
            case WINDOWS -> {
                command.add("-f");
                command.add("dshow");
                command.add("-i");
                command.add("audio=" + devices.audio());
            }
            default -> {
                // Inaccessible : l'entrée vidéo a déjà refusé les systèmes inconnus.
            }
        }
    }
}
