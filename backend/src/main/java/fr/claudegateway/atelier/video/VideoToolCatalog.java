package fr.claudegateway.atelier.video;

import java.util.List;
import java.util.Map;

import fr.claudegateway.agent.AgentTool;

/**
 * <b>Regarder une vidéo</b> (F-190 / SF-190-01) : les deux outils déclarés au modèle sur un terminal
 * servi par un poste dont le runner annonce la capacité {@value #CAPABILITY}.
 *
 * <p>Provider-First : l'API du modèle ne prend pas de vidéo, mais elle <b>voit</b> des images. Le
 * poste extrait celles qui portent le sens ; l'agent les ouvre par {@code read_file}
 * (F-121 / SF-121-15). La gateway ne fait que relayer — elle ne voit ni la vidéo, ni les images
 * qu'on n'ouvre pas (décision D1 du cadrage F-190).</p>
 */
public final class VideoToolCatalog {

    /** Capacité annoncée par le runner ; sans elle, ces outils ne sont pas déclarés. */
    public static final String CAPABILITY = "video";
    public static final String PREFIX = "video_";
    public static final String IMAGES = "video_images";
    public static final String STATUS = "video_images_status";

    private VideoToolCatalog() {
    }

    public static boolean isVideoTool(String name) {
        return IMAGES.equals(name) || STATUS.equals(name);
    }

    /** Les deux outils, toujours dans le même ordre : ils font partie du préfixe caché du poste. */
    public static List<AgentTool> tools() {
        Map<String, Object> text = Map.of("type", "string");
        return List.of(
                new AgentTool(IMAGES,
                        "Extrait sur le poste les images d'une VIDÉO (mp4, mov, webm, mkv…) pour que "
                                + "tu puisses la REGARDER : tu ne vois pas une vidéo, mais tu vois une "
                                + "image ouverte par read_file. Mode « plans » (défaut) : une image à "
                                + "chaque changement d'écran, 60 au plus, réparties sur toute la durée. "
                                + "Mode « intervalle » (zoom) : une image toutes les every_seconds "
                                + "secondes entre from et to (secondes ou HH:MM:SS, 60 images au plus) "
                                + "— pour un passage où l'écran défile sans changer de plan. C'est LONG "
                                + "(plusieurs minutes pour une heure de vidéo) : l'outil rend la main "
                                + "tout de suite avec un job_id, suis avec video_images_status. ffmpeg "
                                + "est rapatrié tout seul au premier usage : n'abandonne pas parce "
                                + "qu'il manque sur la machine. Donne « transcript » si une "
                                + "transcription existe (sinon « transcribe ») : chaque image vient "
                                + "alors avec ce qui se disait. La vidéo ne quitte jamais le poste.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "path", Map.of("type", "string", "description",
                                                "Chemin de la vidéo sur le poste (un dépôt arrive "
                                                        + "dans .atelier/entrees/)."),
                                        "mode", Map.of("type", "string",
                                                "enum", List.of("plans", "intervalle")),
                                        "from", text,
                                        "to", text,
                                        "every_seconds", Map.of("type", "integer"),
                                        // SF-190-03 : le son à côté de l'image.
                                        "transcript", Map.of("type", "string", "description",
                                                "Transcription sur le poste (.vtt, .srt, .docx "
                                                        + "Teams, .txt horodaté) : chaque image "
                                                        + "reçoit ce qui se disait pendant qu'elle "
                                                        + "était à l'écran."),
                                        "transcribe", Map.of("type", "boolean", "description",
                                                "true pour transcrire la vidéo sur le poste "
                                                        + "quand tu n'as pas de transcription "
                                                        + "(plus long ; sans locuteurs)."),
                                        "restart", Map.of("type", "boolean", "description",
                                                "true pour refaire une extraction déjà faite ; "
                                                        + "sinon la même demande rend le résultat "
                                                        + "existant.")),
                                "required", List.of("path"))),
                new AgentTool(STATUS,
                        "Où en est une extraction d'images lancée par video_images et, quand elle est "
                                + "TERMINÉE, les PLANCHES CONTACT (16 vignettes horodatées par image) "
                                + "puis, case par case, l'instant HH:MM:SS et l'image en pleine taille. "
                                + "Ouvre d'abord les planches avec read_file pour survoler la vidéo, "
                                + "puis seulement les images en pleine taille dont tu as besoin pour "
                                + "lire un détail. Tant qu'elle n'est pas terminée, ne conclus RIEN sur "
                                + "le contenu visuel. Si elle échoue, répète à l'utilisateur l'échec et "
                                + "le remède tels quels.",
                        Map.of("type", "object",
                                "properties", Map.of("job_id", text),
                                "required", List.of("job_id"))));
    }
}
