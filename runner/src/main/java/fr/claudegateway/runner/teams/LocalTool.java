package fr.claudegateway.runner.teams;

import java.util.List;
import java.util.Map;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>La description d'un outil local</b> téléchargé au premier usage (F-90 / SF-90-01, décision
 * <b>D3</b>).
 *
 * <p>Cette classe ne télécharge rien et ne lance rien : elle <b>décrit</b>. Ce qui la rend
 * réutilisable est exactement ce que D3 énumère — « le pilotage du navigateur, {@code ffmpeg} et le
 * <b>modèle de transcription</b> sont téléchargés au premier usage, jamais embarqués ». Le modèle de
 * transcription est le travail de <b>F-91</b> ; il se décrira ici, à côté d'{@link #ffmpeg()}, sans
 * réécrire une ligne de {@link LocalToolchain}.</p>
 *
 * <h2>Ce que le produit ne prétend pas vérifier</h2>
 *
 * <p>Aucune empreinte n'est épinglée. Les distributions de {@code ffmpeg} sont <b>roulantes</b> : la
 * même adresse sert une version différente chaque mois, et une empreinte figée ferait échouer le
 * téléchargement à la première mise à jour amont — c'est-à-dire transformer une commodité en panne.
 * Ce qui <b>est</b> vérifié : le transport (HTTPS), et surtout que le binaire obtenu
 * <b>s'identifie lui-même</b> en répondant à {@code -version}. C'est une vérification d'usage, pas
 * de provenance, et c'est écrit tel quel plutôt que maquillé en « archive vérifiée ». La voie sûre
 * reste offerte et recommandée en premier : {@link #installAdvice(OperatingSystem)} donne la
 * commande du gestionnaire de paquets du système, qui, lui, signe ce qu'il distribue.</p>
 *
 * @param name        nom de l'outil, tel qu'il est dit à l'utilisateur
 * @param executables noms d'exécutable à chercher dans le {@code PATH}, du plus probable au moins
 * @param purpose     à quoi il sert, en une phrase — dite avant de le télécharger
 * @param downloads   adresse de l'archive par système ; un système absent n'est jamais téléchargé
 * @param archives    genre d'archive par système
 * @param advice      commande d'installation recommandée, par système
 */
public record LocalTool(String name, List<String> executables, String purpose,
        Map<OperatingSystem, String> downloads, Map<OperatingSystem, Archive> archives,
        Map<OperatingSystem, String> advice, Kind kind) {

    /** Les genres d'archive que le runner sait ouvrir. Liste close. */
    public enum Archive {
        /** {@code java.util.zip}, dans le JDK : Windows et macOS. */
        ZIP,
        /** Délégué au {@code tar} du système, présent partout où cette forme est distribuée. */
        TAR_XZ,
        /**
         * <b>Pas une archive</b> : le fichier téléchargé <b>est</b> l'artefact (F-91 / SF-91-03).
         * C'est le cas du modèle de transcription — quelques centaines de mégaoctets de poids, rien
         * à décompresser.
         */
        PLAIN
    }

    /**
     * <b>Ce qu'on fait de ce qu'on a rapatrié</b> (F-91 / SF-91-03).
     *
     * <p>Jusqu'ici, tout outil local était un <b>binaire</b> : on le cherchait dans le {@code PATH},
     * on le rendait exécutable, et on lui demandait de <b>s'identifier</b> avant de s'en servir. Le
     * modèle de transcription n'est rien de tout cela : c'est un <b>fichier de données</b>. Le
     * chercher dans le {@code PATH} n'aurait pas de sens, et lui demander {@code -version} le ferait
     * refuser à coup sûr.</p>
     */
    public enum Kind {
        /** Un binaire : cherché dans le {@code PATH}, rendu exécutable, et vérifié. */
        EXECUTABLE,
        /** Un fichier de données : ni cherché dans le {@code PATH}, ni lancé, ni vérifié. */
        DATA
    }

    public LocalTool {
        executables = executables == null ? List.of() : List.copyOf(executables);
        downloads = downloads == null ? Map.of() : Map.copyOf(downloads);
        archives = archives == null ? Map.of() : Map.copyOf(archives);
        advice = advice == null ? Map.of() : Map.copyOf(advice);
        kind = kind == null ? Kind.EXECUTABLE : kind;
    }

    /** La forme historique : un binaire. */
    public LocalTool(String name, List<String> executables, String purpose,
            Map<OperatingSystem, String> downloads, Map<OperatingSystem, Archive> archives,
            Map<OperatingSystem, String> advice) {
        this(name, executables, purpose, downloads, archives, advice, Kind.EXECUTABLE);
    }

    /** Vrai pour un fichier de données : ni PATH, ni bit d'exécution, ni {@code -version}. */
    public boolean isData() {
        return kind == Kind.DATA;
    }

    /** Adresse de téléchargement pour ce système, ou {@code ""} s'il n'y en a pas. */
    public String downloadFor(OperatingSystem os) {
        return downloads.getOrDefault(os, "");
    }

    /** Genre d'archive pour ce système, ou {@code null}. */
    public Archive archiveFor(OperatingSystem os) {
        return archives.get(os);
    }

    /** La commande d'installation à conseiller sur ce système. Jamais vide. */
    public String installAdvice(OperatingSystem os) {
        return advice.getOrDefault(os,
                "installez « " + name + " » par le gestionnaire de paquets de votre système, puis "
                        + "relancez le runner");
    }

    /**
     * <b>{@code ffmpeg}</b> : le seul outil décrit aujourd'hui.
     *
     * <p>Les adresses sont <b>en dur ici</b>, jamais reçues d'un appel d'outil — c'est le premier
     * des trois garde-fous : un paramètre venu du modèle ne peut pas faire télécharger n'importe
     * quoi sur la machine de l'utilisateur.</p>
     */
    public static LocalTool ffmpeg() {
        return new LocalTool("ffmpeg", List.of("ffmpeg"),
                "capturer l'écran et le son de ce poste, y incruster le filigrane, et extraire d'un "
                        + "enregistrement les images des changements de plan",
                Map.of(
                        OperatingSystem.WINDOWS,
                        "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip",
                        OperatingSystem.MACOS,
                        "https://evermeet.cx/ffmpeg/getrelease/ffmpeg/zip",
                        OperatingSystem.LINUX,
                        "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz"),
                Map.of(
                        OperatingSystem.WINDOWS, Archive.ZIP,
                        OperatingSystem.MACOS, Archive.ZIP,
                        OperatingSystem.LINUX, Archive.TAR_XZ),
                Map.of(
                        OperatingSystem.WINDOWS, "winget install Gyan.FFmpeg",
                        OperatingSystem.MACOS, "brew install ffmpeg",
                        OperatingSystem.LINUX,
                        "sudo apt install ffmpeg   (ou : sudo dnf install ffmpeg)"));
    }

    /**
     * <b>Le moteur de transcription</b> (F-91 / SF-91-03) — celui qui tourne <b>sur la machine</b>.
     *
     * <h2>Pourquoi celui-ci, et pas un service</h2>
     *
     * <p>Envoyer l'audio d'une réunion à un service <b>annulerait le bénéfice de garder la vidéo en
     * local</b> : ce qu'on protège en ne remontant pas les images, on le donnerait en remontant les
     * voix. Le moteur tourne donc ici, hors ligne, et <b>seul le texte</b> remonte.</p>
     *
     * <h2>Le téléchargement n'existe que là où l'amont en publie un</h2>
     *
     * <p>Windows a une archive officielle ; ailleurs, le projet se distribue par les gestionnaires
     * de paquets. Quand il n'y a pas d'adresse, {@link LocalToolchain} <b>refuse en donnant la
     * commande d'installation</b> plutôt que d'inventer une URL — une adresse fausse ferait
     * télécharger n'importe quoi sur la machine de quelqu'un.</p>
     *
     * <p><b>Provenance, écrite et non maquillée</b> : ces adresses et ces commandes viennent des
     * pages officielles du projet. Elles n'ont <b>pas</b> été exercées ici — le CI n'a ni le binaire
     * ni de quoi le faire tourner.</p>
     */
    public static LocalTool transcriber() {
        return new LocalTool("whisper-cli",
                // Le binaire a changé de nom en cours de route : « main » est l'ancien, « whisper-cli »
                // le nouveau. Chercher les deux évite de refuser un poste où il est déjà installé.
                List.of("whisper-cli", "whisper-cpp", "main"),
                "transcrire, SUR CETTE MACHINE, l'audio d'un enregistrement local — rien n'en sort",
                Map.of(OperatingSystem.WINDOWS,
                        "https://github.com/ggml-org/whisper.cpp/releases/latest/download/"
                                + "whisper-bin-x64.zip"),
                Map.of(OperatingSystem.WINDOWS, Archive.ZIP),
                Map.of(
                        OperatingSystem.WINDOWS, "winget install whisper-cpp",
                        OperatingSystem.MACOS, "brew install whisper-cpp",
                        OperatingSystem.LINUX,
                        "sudo apt install whisper.cpp   (ou compilez-le depuis "
                                + "github.com/ggml-org/whisper.cpp)"));
    }

    /**
     * <b>Le modèle de transcription</b> (F-91 / SF-91-03), téléchargé <b>une fois</b> (D3).
     *
     * <h2>Un fichier de données, pas un binaire</h2>
     *
     * <p>D'où {@link Kind#DATA} : il n'est pas cherché dans le {@code PATH}, il n'est pas rendu
     * exécutable, et on ne lui demande pas de s'identifier. Le chemin de rapatriement, lui, est
     * <b>exactement le même</b> que celui d'{@code ffmpeg} — mêmes annonces, mêmes refus, même
     * absence de copie à moitié écrite.</p>
     *
     * <h2>Pourquoi « base » et pas « large »</h2>
     *
     * <p>Le poids se paie deux fois : au téléchargement (une minute, ou un quart d'heure) et à chaque
     * transcription (le temps de calcul croît avec la taille). « base » tient dans 150 Mo et
     * transcrit une heure de réunion en quelques minutes sur un portable ordinaire ; « large »
     * demanderait 3 Go et un processeur graphique. Pour un compte rendu de réunion, la différence de
     * qualité ne vaut pas la différence d'attente.</p>
     *
     * <p><b>Provenance</b> : dépôt officiel des modèles du projet. Adresse <b>en dur</b>, jamais reçue
     * d'un appel d'outil — le premier des trois garde-fous de SF-90-01, inchangé.</p>
     */
    public static LocalTool transcriptionModel() {
        String url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin";
        return new LocalTool("modele-de-transcription", List.of("ggml-base.bin"),
                "reconnaître la parole SUR CETTE MACHINE, sans rien envoyer nulle part",
                Map.of(
                        OperatingSystem.WINDOWS, url,
                        OperatingSystem.MACOS, url,
                        OperatingSystem.LINUX, url,
                        OperatingSystem.OTHER, url),
                Map.of(
                        OperatingSystem.WINDOWS, Archive.PLAIN,
                        OperatingSystem.MACOS, Archive.PLAIN,
                        OperatingSystem.LINUX, Archive.PLAIN,
                        OperatingSystem.OTHER, Archive.PLAIN),
                Map.of(),
                Kind.DATA);
    }
}
