package fr.claudegateway.teams;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.billing.TeamsEntitlementService;
import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.RunnerTokenAuthenticator;
import fr.claudegateway.teams.block.TeamsMomentImageService;

/**
 * <b>Ce qui remonte d'un enregistrement de réunion</b> (F-90 / SF-90-03) : les 20 à 60 images
 * retenues, <b>et rien d'autre</b>.
 *
 * <h2>Le producteur que F-89 avait annoncé</h2>
 *
 * <p>{@link TeamsMomentImageService} existait <b>sans producteur</b>, et c'était écrit :
 * <i>« ouvrir dès maintenant un endpoint de dépôt public sans rien pour l'alimenter offrirait une
 * surface pour rien : le dépôt est donc une méthode de service, que F-90 appellera »</i>. C'est cet
 * appel, et c'est la seule route qui l'ouvre.</p>
 *
 * <h2>La vidéo ne remonte jamais</h2>
 *
 * <p>L'enregistrement (des centaines de Mo), l'audio, les fichiers bruts et les images écartées
 * <b>restent sur la machine</b>. <b>La gateway orchestre, elle ne devient pas un entrepôt de vidéos
 * de réunions</b> — et la vidéo est l'artefact le plus indiscret de tous : tout ce qui est passé à
 * l'écran, y compris ce qu'on n'avait pas l'intention de montrer.</p>
 *
 * <h2>Quatre gardes, et l'ordre compte</h2>
 *
 * <ol>
 *   <li><b>Le jeton runner</b>, vérifié <b>ici</b> (décision D9 : aucun filtre HTTP ne sait lire un
 *       jeton runner). Refus en <b>401 générique</b>, et <b>rien n'est jamais posé</b> dans le
 *       {@code SecurityContext} : un jeton runner n'est pas un JWT utilisateur.</li>
 *   <li><b>L'isolation</b> : le {@code userId} vient <b>du jeton</b>, jamais du corps ni de la
 *       query. Le {@code workspaceId} reçu est <b>revérifié possédé</b> par ce compte — inconnu et
 *       « à quelqu'un d'autre » restent <b>indiscernables</b> (404). Et la clé de stockage est
 *       <b>reconstruite</b> à partir de l'utilisateur : l'identifiant d'image n'est jamais un
 *       chemin.</li>
 *   <li><b>Le droit Teams</b> (D5). <b>Produire</b> des captures demande l'option ;
 *       <b>relire</b> n'en demande pas — c'est volontairement asymétrique, et F-89 l'a écrit :
 *       <i>« un compte qui a résilié l'option doit continuer de voir les comptes rendus qu'il a
 *       payés »</i>.</li>
 *   <li><b>Le terminal Teams</b> : on ne dépose pas de captures de réunion dans un projet de
 *       code.</li>
 * </ol>
 *
 * <h2>D2 — elles s'en vont avec le compte rendu</h2>
 *
 * <p>Rien à faire de plus ici : l'effacement des images d'un terminal est déjà branché sur sa
 * suppression (F-89). <b>Aucun cache</b> ne survit au compte rendu.</p>
 */
@RestController
@RequestMapping("/runner/teams")
public class RunnerTeamsMomentController {

    /** En-tête portant le jeton runner. Volontairement pas {@code Authorization} (D9). */
    public static final String TOKEN_HEADER = "X-Runner-Token";

    /** Une capture d'écran ramenée à 1 280 px n'en pèse jamais le dixième. */
    static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    /**
     * Images par terminal Teams. Soixante par compte rendu, dix comptes rendus par terminal : large
     * pour l'usage, net pour la borne. <b>Sans borne, une machine pourrait remplir le stockage d'un
     * compte</b> — et le refus est <b>nommé</b>, jamais silencieux.
     */
    static final int MAX_IMAGES_PER_WORKSPACE = 600;

    private final RunnerTokenAuthenticator authenticator;
    private final WorkspaceService workspaceService;
    private final TeamsMomentImageService images;
    private final TeamsEntitlementService entitlements;

    public RunnerTeamsMomentController(RunnerTokenAuthenticator authenticator,
            WorkspaceService workspaceService, TeamsMomentImageService images,
            TeamsEntitlementService entitlements) {
        this.authenticator = authenticator;
        this.workspaceService = workspaceService;
        this.images = images;
        this.entitlements = entitlements;
    }

    /**
     * Dépose <b>une</b> image de moment et rend son identifiant.
     *
     * <p>Une image par appel, délibérément : un lot rendrait le refus partiel ambigu, et c'est
     * précisément ce que ce volet interdit — le runner compte lui-même ce qui n'est pas passé, et le
     * dit à côté de son résultat.</p>
     */
    @PostMapping(value = "/moments", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> deposit(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestParam("workspaceId") UUID workspaceId,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestBody byte[] content) {
        Optional<RunnerIdentity> identity = authenticator.authenticate(trim(token));
        if (identity.isEmpty()) {
            // 401 générique : on ne dit pas si le jeton est inconnu, expiré ou révoqué.
            return refuse(HttpStatus.UNAUTHORIZED, "Jeton runner refusé.");
        }
        UUID userId = identity.get().userId();

        if (!entitlements.isEntitled(userId)) {
            // D5 : produire des captures demande l'option. Relire n'en demande pas (F-89).
            return refuse(HttpStatus.FORBIDDEN,
                    "Ce compte n'a pas l'option Teams : les captures ne peuvent pas remonter.");
        }

        Workspace workspace;
        try {
            // L'isolation : le workspace doit appartenir AU COMPTE DU JETON. Inconnu et « à
            // quelqu'un d'autre » sont indiscernables — on ne dit pas à un compte qu'un terminal
            // existe ailleurs.
            workspace = workspaceService.requireOwned(userId, workspaceId);
        } catch (WorkspaceNotFoundException e) {
            return refuse(HttpStatus.NOT_FOUND, "Terminal introuvable.");
        }
        if (!workspace.isTeamsTerminal()) {
            return refuse(HttpStatus.BAD_REQUEST,
                    "Ce workspace n'est pas un terminal Teams : on n'y dépose pas de captures de "
                            + "réunion.");
        }
        if (content == null || content.length == 0) {
            return refuse(HttpStatus.BAD_REQUEST, "Image vide.");
        }
        if (content.length > MAX_IMAGE_BYTES) {
            return refuse(HttpStatus.PAYLOAD_TOO_LARGE, "Image trop lourde : "
                    + content.length + " octets pour un maximum de " + MAX_IMAGE_BYTES + ".");
        }
        if (images.list(userId, workspace.getId()).size() >= MAX_IMAGES_PER_WORKSPACE) {
            return refuse(HttpStatus.CONFLICT, "Ce terminal porte déjà "
                    + MAX_IMAGES_PER_WORKSPACE + " images de moments : supprimez un compte rendu "
                    + "avant d'en produire un autre.");
        }
        try {
            String imageId = images.store(userId, workspace.getId(), baseType(contentType), content);
            return ResponseEntity.ok(Map.of("imageId", imageId));
        } catch (IllegalArgumentException e) {
            // La liste close de F-89 : png, jpeg, webp. Un type inconnu n'a rien à faire dans un
            // compte rendu — et le dire vaut mieux que de le stocker « au cas où ».
            return refuse(HttpStatus.BAD_REQUEST,
                    "Type d'image non accepté : " + baseType(contentType) + ".");
        }
    }

    /** Le type sans ses paramètres : {@code image/jpeg; charset=…} devient {@code image/jpeg}. */
    private static String baseType(String contentType) {
        String value = contentType == null ? "" : contentType.strip();
        int separator = value.indexOf(';');
        return separator < 0 ? value : value.substring(0, separator).strip();
    }

    private static ResponseEntity<Map<String, String>> refuse(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    private static String trim(String token) {
        return token == null ? "" : token.strip();
    }
}
