package fr.claudegateway.atelier.video;

import java.util.List;
import java.util.Map;

import fr.claudegateway.agent.AgentTool;

/**
 * <b>Rendre une vidéo sur le poste</b> (F-215 / SF-215-02) : les deux outils déclarés au modèle sur un terminal
 * servi par un poste dont le runner annonce la capacité {@value #CAPABILITY} (patron F-190 {@code video}).
 *
 * <p>Gateway-First : la gateway ne rend rien — elle relaie. Le poste prépare sa chaîne (Node 22, hyperframes
 * épinglé, Chrome sans écran, ffmpeg) et rend en arrière-plan ; un poste qui interdit les commandes n'annonce
 * pas la capacité.</p>
 *
 * <p>Préfixe stable : définitions <b>fixes</b>, toujours dans le même ordre ; aucune consigne système ajoutée.</p>
 */
public final class VideoRenderToolCatalog {

    /** Capacité annoncée par le runner ; sans elle, ces outils ne sont pas déclarés. */
    public static final String CAPABILITY = "video_render";
    public static final String RENDER = "video_render";
    public static final String STATUS = "video_render_status";

    private VideoRenderToolCatalog() {
    }

    public static boolean isRenderTool(String name) {
        return RENDER.equals(name) || STATUS.equals(name);
    }

    /** Les deux outils, toujours dans le même ordre : ils font partie du préfixe caché du poste. */
    public static List<AgentTool> tools() {
        Map<String, Object> text = Map.of("type", "string");
        return List.of(
                new AgentTool(RENDER,
                        "Rend en MP4, sur le poste, une composition HyperFrames (page HTML animée) : prépare "
                                + "d'abord tout seul la chaîne au premier usage (Node 22, hyperframes 0.8.193, "
                                + "Chrome sans écran, ffmpeg — n'abandonne pas parce qu'ils manquent), puis lance "
                                + "le rendu EN ARRIÈRE-PLAN : l'outil rend la main tout de suite avec un job_id, "
                                + "suis avec video_render_status. verifier_seulement: true prépare la chaîne sans "
                                + "rendre. Sur un poste verrouillé, l'échec dit quoi demander : répète-le, ne "
                                + "cherche pas à contourner la politique du poste.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "dossier", Map.of("type", "string", "description",
                                                "Dossier du projet HyperFrames (contient index.html)."),
                                        "composition", Map.of("type", "string", "description",
                                                "Composition à rendre, relative au dossier (défaut index.html)."),
                                        "sortie", Map.of("type", "string", "description",
                                                "Chemin du mp4 (défaut <dossier>/renders/<nom>.mp4)."),
                                        "qualite", Map.of("type", "string",
                                                "enum", List.of("draft", "looks", "delivery")),
                                        "fps", Map.of("type", "integer", "enum", List.of(24, 25, 30, 60)),
                                        "workers", Map.of("type", "integer", "description", "1 à 8."),
                                        "verifier_seulement", Map.of("type", "boolean")))),
                new AgentTool(STATUS,
                        "Où en est un rendu lancé par video_render : étape, avancement, journal ; à la fin, le "
                                + "chemin et la taille du mp4 (relis des images clés avant de le publier avec "
                                + "video_publier). En échec : la cause et le remède, à répéter tels quels.",
                        Map.of("type", "object",
                                "properties", Map.of("job_id", text),
                                "required", List.of("job_id"))));
    }
}
