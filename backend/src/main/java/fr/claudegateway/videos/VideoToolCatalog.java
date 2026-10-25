package fr.claudegateway.videos;

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
 * <b>Les outils {@code video_publier} et {@code audio_publier}</b> (F-213 / SF-213-01) — et la garde qui
 * décide s'ils sont donnés.
 *
 * <p>Même garde que les pages (F-109) : le <b>droit de l'espace du terminal</b> (Vigie pour un terminal
 * Teams, Forge sinon ; ouvert d'office à l'ADMIN), et en plus une <b>cible poste</b> — la vidéo est un
 * fichier du poste, rapatrié par tranches. Hors garde : aucun outil, aucune ligne de consigne.</p>
 *
 * <p>Préfixe stable (cache de prompt) : les définitions sont un texte <b>fixe</b> ; aucune consigne n'est
 * ajoutée à la consigne système — la description de l'outil suffit.</p>
 */
@Component
public class VideoToolCatalog {

    /** Publier une vidéo du poste. */
    public static final String VIDEO = "video_publier";
    /** Publier un ou plusieurs sons du poste. */
    public static final String AUDIO = "audio_publier";
    /** Lire les retours à la seconde d'une vidéo (F-214 / SF-214-01), en lecture seule. */
    public static final String RETOURS = "video_retours";

    private final SpaceEntitlementService entitlements;

    @Autowired
    public VideoToolCatalog(SpaceEntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    /** Catalogue <b>vide</b> : les outils ne sont jamais donnés (formes historiques, tests). */
    public static VideoToolCatalog none() {
        return new VideoToolCatalog(null);
    }

    /** Vrai si ce nom d'outil est un des deux outils de publication de médias. */
    public static boolean isVideoTool(String tool) {
        return VIDEO.equals(tool) || AUDIO.equals(tool);
    }

    /**
     * Vrai si les outils sont ouverts pour ce tour : droit de l'espace du terminal, et cible poste.
     *
     * @param userId    propriétaire du terminal (celui du tour)
     * @param workspace terminal du tour, déjà vérifié comme possédé
     */
    public boolean isOpenFor(UUID userId, Workspace workspace) {
        if (entitlements == null || userId == null || workspace == null || !workspace.isRunnerTarget()) {
            return false;
        }
        EntitlementSpace space = workspace.isTeamsTerminal() ? EntitlementSpace.VIGIE : EntitlementSpace.FORGE;
        try {
            return entitlements.isEntitled(userId, space);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Vrai si ce nom d'outil est la lecture des retours à la seconde (F-214). */
    public static boolean isRetoursTool(String tool) {
        return RETOURS.equals(tool);
    }

    /** Les outils à donner à l'agent pour ce tour, ou la liste vide. */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        return isOpenFor(userId, workspace)
                ? List.of(videoDefinition(), audioDefinition(), retoursDefinition())
                : List.of();
    }

    /** F-214 / SF-214-01 : la lecture des retours à la seconde — texte fixe (préfixe stable). */
    static AgentTool retoursDefinition() {
        return new AgentTool(RETOURS,
                "Lit les RETOURS À LA SECONDE que l'utilisateur a posés sur une vidéo publiée : identifiant, "
                        + "version, instant (mm:ss et image à 30 i/s), statut (OUVERT, ENVOYE, TRAITE, REJETE), "
                        + "texte. Regarde chaque instant (video_images au temps donné) avant de corriger, puis "
                        + "republie avec video_publier(remplace, retours_traites). Lecture seule.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "video", Map.of("type", "string", "description", "video_id de la vidéo."),
                                "version", Map.of("type", "integer",
                                        "description", "Seulement les retours posés sur cette version ; toutes sinon.")),
                        "required", List.of("video")));
    }

    static AgentTool videoDefinition() {
        Map<String, Object> chapter = Map.of("type", "object",
                "properties", Map.of(
                        "t", Map.of("type", "number", "description", "Début du chapitre, en secondes."),
                        "titre", Map.of("type", "string", "description", "Titre du chapitre.")),
                "required", List.of("t", "titre"));
        Map<String, Object> resolved = Map.of("type", "object",
                "properties", Map.of(
                        "id", Map.of("type", "string", "description", "Identifiant du retour (video_retours)."),
                        "statut", Map.of("type", "string", "enum", List.of("traite", "rejete")),
                        "motif", Map.of("type", "string", "description", "Pourquoi, surtout pour un rejet (300 caractères au plus).")),
                "required", List.of("id", "statut"));
        Map<String, Object> source = Map.of("type", "object",
                "properties", Map.of(
                        "affirmation", Map.of("type", "string", "description", "Ce que la vidéo affirme (300 caractères au plus)."),
                        "source", Map.of("type", "string",
                                "description", "D'où cela vient : fait de carte, page, fichier:ligne, compte rendu."),
                        "a_confirmer", Map.of("type", "boolean", "description", "true si ce n'est pas vérifié.")),
                "required", List.of("affirmation"));
        return new AgentTool(VIDEO,
                "Publie une VIDÉO de la machine (.mp4 ou .webm, 300 Mo au plus) que l'utilisateur regarde dans "
                        + "l'application : dans le fil, puis dans la galerie Vidéos du sujet (privée, à lui seul). "
                        + "chapitres : instants cliquables sous le lecteur. serie + episode : range la vidéo dans "
                        + "une série (tutoriel en plusieurs vidéos, sous un même titre). remplace : publie une "
                        + "nouvelle version d'une vidéo déjà publiée (son video_id). sources : la fiche des sources, "
                        + "affichée sous le lecteur. voix : fixe la voix de toute la série. L'utilisateur confirme "
                        + "d'un clic.",
                Map.of("type", "object",
                        "properties", Map.ofEntries(
                                Map.entry("path", Map.of("type", "string", "description", "Chemin du fichier vidéo sur la machine.")),
                                Map.entry("titre", Map.of("type", "string", "description", "Titre court et distinctif (120 caractères au plus).")),
                                Map.entry("description", Map.of("type", "string", "description", "Une phrase (300 caractères au plus).")),
                                Map.entry("chapitres", Map.of("type", "array", "items", chapter,
                                        "description", "Chapitres dans l'ordre (60 au plus).")),
                                Map.entry("serie", Map.of("type", "string",
                                        "description", "Titre (ou identifiant) de la série ; créée si elle n'existe pas dans ce sujet.")),
                                Map.entry("episode", Map.of("type", "integer", "description", "Rang dans la série ; le suivant si absent.")),
                                Map.entry("remplace", Map.of("type", "string", "description", "video_id d'une vidéo déjà publiée : nouvelle version.")),
                                Map.entry("couverture", Map.of("type", "string",
                                        "description", "Image de couverture sur la machine (png, jpg, webp ; 2 Mo au plus).")),
                                Map.entry("duree_s", Map.of("type", "integer", "description", "Durée en secondes, si connue.")),
                                Map.entry("retours_traites", Map.of("type", "array", "items", resolved,
                                        "description", "Avec remplace : pour chaque retour à la seconde corrigé "
                                                + "(traite) ou écarté (rejete + motif).")),
                                Map.entry("sources", Map.of("type", "array", "items", source,
                                        "description", "Fiche des sources : chaque affirmation technique et sa source "
                                                + "(80 au plus) ; le non-vérifié en a_confirmer.")),
                                Map.entry("voix", Map.of("type", "string",
                                        "description", "Avec serie : la voix de la narration, fixée pour toute la série.")),
                                Map.entry("consignes_voix", Map.of("type", "string",
                                        "description", "Avec serie : les consignes de ton de la série."))),
                        "required", List.of("path", "titre")));
    }

    static AgentTool audioDefinition() {
        Map<String, Object> track = Map.of("type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "Chemin du fichier son sur la machine."),
                        "libelle", Map.of("type", "string", "description", "Libellé court (« Voix 1 — grave, posée »).")),
                "required", List.of("path", "libelle"));
        return new AgentTool(AUDIO,
                "Publie un ou plusieurs SONS de la machine (.mp3, .wav, .m4a ; 20 Mo au plus chacun) que "
                        + "l'utilisateur écoute dans le fil, en lecteurs empilés — par exemple des auditions de voix "
                        + "à comparer. Pas de galerie. L'utilisateur confirme d'un clic.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "titre", Map.of("type", "string", "description", "Titre du lot (« Auditions de voix »)."),
                                "pistes", Map.of("type", "array", "items", track, "minItems", 1, "maxItems", 6,
                                        "description", "Les sons, 1 à 6.")),
                        "required", List.of("pistes")));
    }
}
