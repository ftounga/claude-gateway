package fr.claudegateway.runner.teams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>La police du filigrane</b> (F-91 / SF-91-01), cherchée sur le poste — et <b>jamais
 * téléchargée</b>.
 *
 * <h2>Pourquoi elle a sa classe, alors que c'est une liste de chemins</h2>
 *
 * <p>Parce que c'est <b>elle</b> qui décide si une capture a lieu. Sans police, {@code drawtext} ne
 * dessine rien ; sans {@code drawtext}, le filigrane n'existe pas ; sans filigrane, l'enregistrement
 * est anonyme — et <b>un enregistrement anonyme, on ne le fait pas</b>. Le chemin qui mène à ce refus
 * mérite d'être lisible d'un seul endroit.</p>
 *
 * <h2>Pourquoi on ne la télécharge pas, alors que D3 le permettrait</h2>
 *
 * <p>D3 nomme trois choses téléchargées au premier usage : le pilotage du navigateur,
 * {@code ffmpeg}, et le modèle de transcription. Pas une police. Et rapatrier une police pose une
 * question de licence de redistribution que personne n'a envie d'avoir à expliquer, pour gagner un
 * cas qui n'arrive presque jamais : un poste de travail <b>a</b> des polices — c'est même à peu près
 * la seule chose dont on soit sûr. Quand il n'en a aucune de celles connues ici, on le <b>dit</b>,
 * avec le remède.</p>
 *
 * <h2>Provenance des chemins, écrite et non maquillée</h2>
 *
 * <p>Ces emplacements viennent des conventions documentées de chaque système (fontconfig sous Linux,
 * {@code /System/Library/Fonts} sous macOS, {@code %WINDIR%\Fonts} sous Windows). Ils n'ont pas été
 * relevés sur un parc de postes de test — nous n'en avons pas. Un poste qui n'en a aucun reçoit un
 * refus qui nomme ce qui manque, ce qui est exactement ce qu'il faut pour que le premier
 * branchement corrige cette liste.</p>
 */
public final class WatermarkFont {

    /** Les emplacements connus, du plus probable au moins, par système. */
    static final List<String> LINUX_CANDIDATES = List.of(
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/truetype/freefont/FreeSans.ttf",
            "/usr/share/fonts/truetype/ubuntu/Ubuntu-R.ttf",
            "/usr/share/fonts/noto/NotoSans-Regular.ttf");

    static final List<String> MACOS_CANDIDATES = List.of(
            "/System/Library/Fonts/Helvetica.ttc",
            "/System/Library/Fonts/HelveticaNeue.ttc",
            "/System/Library/Fonts/Supplemental/Arial.ttf",
            "/Library/Fonts/Arial.ttf",
            "/System/Library/Fonts/SFNS.ttf");

    static final List<String> WINDOWS_CANDIDATES = List.of(
            "C:/Windows/Fonts/arial.ttf",
            "C:/Windows/Fonts/segoeui.ttf",
            "C:/Windows/Fonts/tahoma.ttf",
            "C:/Windows/Fonts/calibri.ttf",
            "C:/Windows/Fonts/verdana.ttf");

    private final OperatingSystem os;
    private final Predicate<Path> exists;

    public WatermarkFont(OperatingSystem os) {
        this(os, path -> Files.isRegularFile(path) && Files.isReadable(path));
    }

    WatermarkFont(OperatingSystem os, Predicate<Path> exists) {
        this.os = os == null ? OperatingSystem.OTHER : os;
        this.exists = exists;
    }

    /**
     * La première police utilisable, ou {@code null}.
     *
     * <p>{@code null} n'est pas un cas dégradé qu'on absorbe : c'est le déclencheur du refus de
     * capturer. Voir {@link LocalCapture}.</p>
     */
    public Path find() {
        for (String candidate : candidates()) {
            Path path = Path.of(candidate);
            if (exists.test(path)) {
                return path;
            }
        }
        return null;
    }

    /** Ce qu'il faut faire quand il n'y en a aucune. Jamais un refus nu. */
    public String remedy() {
        return switch (os) {
            case LINUX -> "Installez une police : « sudo apt install fonts-dejavu-core » (ou "
                    + "« sudo dnf install dejavu-sans-fonts »), puis redemandez.";
            case MACOS -> "Ce Mac ne présente aucune des polices système attendues. Ouvrez le Livre "
                    + "des polices et vérifiez qu'Helvetica ou Arial est activée, puis redemandez.";
            case WINDOWS -> "Vérifiez que le dossier C:\\Windows\\Fonts contient arial.ttf ou "
                    + "segoeui.ttf, puis redemandez.";
            default -> "Installez une police TrueType sur ce poste, puis redemandez.";
        };
    }

    List<String> candidates() {
        return switch (os) {
            case LINUX -> LINUX_CANDIDATES;
            case MACOS -> MACOS_CANDIDATES;
            case WINDOWS -> WINDOWS_CANDIDATES;
            default -> List.of();
        };
    }
}
