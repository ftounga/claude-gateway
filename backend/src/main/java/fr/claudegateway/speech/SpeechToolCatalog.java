package fr.claudegateway.speech;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;

/**
 * <b>L'outil {@code voix_off}</b> (F-215 / SF-215-01) — et la garde qui décide s'il est donné.
 *
 * <p>Même garde que {@code video_publier} (F-213) : droit de l'espace du terminal (Vigie pour un terminal
 * Teams, Forge sinon ; ADMIN d'office) et cible poste ; en plus, la voix off doit être <b>allumée</b>
 * (coupe-circuit {@code APP_ATELIER_SPEECH_ENABLED}) et <b>configurée</b> (clé présente).</p>
 *
 * <p>Préfixe stable (cache de prompt) : la définition est un texte <b>fixe</b> — elle ne nomme ni la
 * préférence de l'utilisateur ni rien du tour ; aucune ligne n'est ajoutée à la consigne système.</p>
 */
@Component
public class SpeechToolCatalog {

    /** Le nom de l'outil. */
    public static final String TOOL = "voix_off";

    private final SpaceEntitlementService entitlements;
    private final SpeechProperties properties;

    @Autowired
    public SpeechToolCatalog(SpaceEntitlementService entitlements, SpeechProperties properties) {
        this.entitlements = entitlements;
        this.properties = properties;
    }

    /** Catalogue <b>vide</b> : l'outil n'est jamais donné (formes historiques, tests). */
    public static SpeechToolCatalog none() {
        return new SpeechToolCatalog(null, null);
    }

    public static boolean isSpeechTool(String tool) {
        return TOOL.equals(tool);
    }

    /** Vrai si l'outil est ouvert pour ce tour. */
    public boolean isOpenFor(UUID userId, Workspace workspace) {
        if (entitlements == null || properties == null || !properties.isActive()
                || userId == null || workspace == null || !workspace.isRunnerTarget()) {
            return false;
        }
        EntitlementSpace space = workspace.isTeamsTerminal() ? EntitlementSpace.VIGIE : EntitlementSpace.FORGE;
        try {
            return entitlements.isEntitled(userId, space);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** L'outil à donner à l'agent pour ce tour, ou la liste vide. */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        return isOpenFor(userId, workspace) ? List.of(definition(properties.voices())) : List.of();
    }

    static AgentTool definition(List<String> voices) {
        Map<String, Object> voice = Map.of("type", "string", "enum", voices);
        return new AgentTool(TOOL,
                "Produit la VOIX OFF d'une vidéo : lit le texte avec une voix de synthèse, dépose le mp3 dans le "
                        + "projet et rend l'HORODATAGE DE CHAQUE MOT (début–fin en secondes, aussi dans "
                        + "<fichier>.mots.json) pour caler les animations au mot. Sans « voix », la voix par défaut "
                        + "de l'utilisateur (sinon onyx, ton de formateur posé). Adapte voix et consignes à la "
                        + "demande (« voix féminine », « plus dynamique », « la voix 3 »). auditions: true lit UNE "
                        + "phrase avec 3 à 4 voix et les publie à l'écoute ; propose ensuite le choix par une "
                        + "question. Refaire une vidéo avec une autre voix : nouvelle voix_off, animations "
                        + "recalées, puis video_publier avec remplace. L'utilisateur confirme d'un clic ; payant.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "texte", Map.of("type", "string", "description",
                                        "Le texte à lire (4 000 caractères au plus ; 300 pour des auditions)."),
                                "voix", voice,
                                "consignes", Map.of("type", "string", "description",
                                        "Consignes de ton (1 000 caractères au plus), ex. « plus dynamique »."),
                                "fichier", Map.of("type", "string", "description",
                                        "Chemin relatif du mp3 dans le projet (ex. cours/audio/chapitre-1.mp3)."),
                                "auditions", Map.of("type", "boolean", "description",
                                        "true : la même phrase lue par plusieurs voix, publiées à l'écoute."),
                                "voix_auditions", Map.of("type", "array", "items", voice, "minItems", 2,
                                        "maxItems", 4, "description", "Les voix des auditions (défaut : 4 voix).")),
                        "required", List.of("texte")));
    }
}
