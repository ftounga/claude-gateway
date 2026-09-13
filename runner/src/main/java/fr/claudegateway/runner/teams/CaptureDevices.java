package fr.claudegateway.runner.teams;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>Par où ce poste capture</b> (F-91 / SF-91-01) : l'écran, et le son.
 *
 * <h2>Le son n'est pas un supplément</h2>
 *
 * <p>Toute la suite en dépend : <b>une capture locale n'a pas de transcription</b> — Teams n'en
 * produit que pour ses propres enregistrements —, il faudra donc la produire à partir de l'audio
 * (SF-91-03). Une capture muette est une capture dont on ne pourra rien tirer. Le son est donc pris
 * par défaut, et son absence se <b>dit</b>.</p>
 *
 * <h2>Windows demande le nom de son périphérique, et on ne l'invente pas</h2>
 *
 * <p>Sous Linux, {@code pulse} a un périphérique {@code default} ; sous macOS, {@code avfoundation}
 * numérote les entrées. Sous Windows, {@code dshow} exige un <b>nom exact</b>, qui dépend de la
 * carte son du poste. Le deviner produirait un {@code ffmpeg} qui meurt à la seconde avec un message
 * que personne ne lirait. On demande donc le nom — et on dit <b>la commande qui le donne</b>.</p>
 *
 * @param screen l'entrée vidéo, au format attendu par le module de capture du système
 * @param audio  l'entrée audio, ou {@code ""} pour une capture muette assumée
 */
public record CaptureDevices(String screen, String audio) {

    /** L'affichage X par défaut, quand la variable d'environnement ne dit rien. */
    static final String DEFAULT_DISPLAY = ":0.0";

    public CaptureDevices {
        screen = screen == null ? "" : screen.strip();
        audio = audio == null ? "" : audio.strip();
    }

    public boolean hasAudio() {
        return !audio.isEmpty();
    }

    /**
     * Les entrées de ce poste.
     *
     * @param os            le système observé
     * @param wantAudio     vrai pour capturer le son (défaut du produit)
     * @param screenAsked   entrée vidéo imposée dans la demande, ou {@code ""}
     * @param audioAsked    entrée audio imposée dans la demande, ou {@code ""}
     * @param display       contenu de {@code DISPLAY}, ou {@code ""} — passé pour être éprouvable
     * @throws CaptureRefusedException quand le son est demandé sans qu'on sache par où le prendre
     */
    public static CaptureDevices resolve(OperatingSystem os, boolean wantAudio, String screenAsked,
            String audioAsked, String display) {
        String screen = blankTo(screenAsked, defaultScreen(os, display));
        if (!wantAudio) {
            return new CaptureDevices(screen, "");
        }
        String audio = blankTo(audioAsked, defaultAudio(os));
        if (audio.isEmpty()) {
            throw new CaptureRefusedException(CaptureRefusedException.NO_DEVICE,
                    "Je ne sais pas par où prendre le son sur ce poste, et une capture muette ne "
                            + "donnerait aucune transcription : je préfère ne pas démarrer.",
                    "Listez vos entrées audio avec « ffmpeg -list_devices true -f dshow -i dummy », "
                            + "puis redemandez avec « audio_device »: « <le nom exact, guillemets "
                            + "compris s'il en a> ». Ou demandez explicitement une capture sans son "
                            + "avec « audio »: false — il n'y aura alors pas de transcription, et je "
                            + "le dirai.");
        }
        return new CaptureDevices(screen, audio);
    }

    private static String defaultScreen(OperatingSystem os, String display) {
        return switch (os) {
            case LINUX -> blankTo(display, DEFAULT_DISPLAY);
            case WINDOWS -> "desktop";
            // avfoundation numérote les écrans à la suite des caméras : « 1 » est l'écran principal
            // sur la grande majorité des Mac. Surchargeable, et dit dans l'état de la capture.
            case MACOS -> "1";
            default -> "";
        };
    }

    private static String defaultAudio(OperatingSystem os) {
        return switch (os) {
            case LINUX -> "default";
            case MACOS -> "0";
            // Windows : AUCUN défaut. Voir la note de classe.
            default -> "";
        };
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /** L'état des entrées, en une phrase — il voyage avec la capture. */
    public String describe() {
        return hasAudio()
                ? "Écran « " + screen + " », son « " + audio + " »."
                : "Écran « " + screen + " », SANS SON — il n'y aura donc aucune transcription.";
    }
}
