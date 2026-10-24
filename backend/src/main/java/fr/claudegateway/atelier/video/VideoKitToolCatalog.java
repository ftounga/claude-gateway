package fr.claudegateway.atelier.video;

import java.util.Map;

import fr.claudegateway.agent.AgentTool;

/**
 * <b>Le savoir-faire « cours vidéo »</b> (F-215 / SF-215-03) : l'outil {@value #TOOL}, qui dépose sur le poste le
 * guide de méthode, les briques graphiques et les icônes officielles, et la <b>seule</b> ligne de doctrine qui dit
 * que le guide existe.
 *
 * <p><b>Préfixe stable</b> : outil et ligne sont des littéraux <b>fixes</b>, déclarés avec les outils de rendu,
 * seulement quand le poste annonce la capacité {@link VideoRenderToolCatalog#CAPABILITY} (propriété stable du
 * poste). Le guide lui-même — volumineux — n'entre jamais dans la consigne système : il est lu à la demande.</p>
 */
public final class VideoKitToolCatalog {

    public static final String TOOL = "video_briques";

    /** Où le guide se pose dans le sujet (même chemin que le dépôt de gouvernance, genre GUIDE). */
    public static final String GUIDE_PATH = fr.claudegateway.governance.GovernancePackageSeeder.GUIDE_COURS_VIDEO_PATH;

    /** La ligne de doctrine : FIXE et courte (préfixe caché). */
    public static final String DOCTRINE = "Vidéo ou cours vidéo demandé : appelle d'abord " + TOOL + " (il dépose le "
            + "guide " + GUIDE_PATH + ", les briques et les icônes), LIS le guide en entier et suis-le ; relecture "
            + "video_render_check + images regardées obligatoire avant video_render et video_publier.";

    private VideoKitToolCatalog() {
    }

    public static boolean isKitTool(String name) {
        return TOOL.equals(name);
    }

    /** L'outil, toujours identique : il fait partie du préfixe caché du poste. */
    public static AgentTool tool() {
        return new AgentTool(TOOL,
                "Prépare le dossier d'un cours vidéo sur le poste : dépose le guide de méthode (" + GUIDE_PATH
                        + "), les briques graphiques (briques/charte.css, briques/briques.js, briques/LISEZMOI.md) et "
                        + "les icônes officielles demandées (AWS, Azure, GCP, Kubernetes, on-prem) dans icons/. "
                        + "chercher seul : rend les noms d'icônes exacts, sans rien déposer. Gratuit.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "dossier", Map.of("type", "string", "description",
                                        "Dossier de la composition, relatif au sujet (ex. video/cours-eks)."),
                                "icones", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "Noms d'icônes (40 au plus), ex. "
                                                + "aws/network/elb-application-load-balancer, k8s/compute/pod."),
                                "chercher", Map.of("type", "string", "description",
                                        "Mots-clés (en anglais) pour trouver des noms d'icônes, ex. load balancer."))));
    }
}
