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
        Map<OperatingSystem, String> advice) {

    /** Les genres d'archive que le runner sait ouvrir. Liste close. */
    public enum Archive {
        /** {@code java.util.zip}, dans le JDK : Windows et macOS. */
        ZIP,
        /** Délégué au {@code tar} du système, présent partout où cette forme est distribuée. */
        TAR_XZ
    }

    public LocalTool {
        executables = executables == null ? List.of() : List.copyOf(executables);
        downloads = downloads == null ? Map.of() : Map.copyOf(downloads);
        archives = archives == null ? Map.of() : Map.copyOf(archives);
        advice = advice == null ? Map.of() : Map.copyOf(advice);
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
}
