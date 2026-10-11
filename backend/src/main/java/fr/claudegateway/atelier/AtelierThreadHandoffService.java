package fr.claudegateway.atelier;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.dto.ThreadHandoffResponse;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.live.RemoteTurnSource;

/**
 * <b>La passation du fil</b> (F-117 / SF-117-09) : lire et corriger le résumé que la compaction
 * automatique (SF-117-01, gabarit F-121 / SF-121-09) écrit dans {@code workspaces.chat_thread_summary}
 * et réinjecte en tête du rejeu ({@link AtelierCompactionService#summaryPrefix}).
 *
 * <p><b>Une vue et une correction, aucun tour.</b> Rien n'est demandé au modèle : on montre ce qui
 * existe déjà et on laisse l'utilisateur le rectifier. Le texte corrigé remplace celui réinjecté au tour
 * suivant, et une compaction ultérieure fusionnera à partir de lui (compaction incrémentale inchangée).</p>
 *
 * <p><b>Isolation.</b> Tout passe par {@link WorkspaceService#requireOwned} : un projet d'autrui rend un
 * <b>404 indiscernable</b>, avant toute autre vérification.</p>
 *
 * <p><b>Journal.</b> On trace la correction (projet, poste, longueurs) — <b>jamais le contenu</b>, qui
 * peut porter des extraits de code ou de configuration du client.</p>
 */
@Service
public class AtelierThreadHandoffService {

    private static final Logger log = LoggerFactory.getLogger(AtelierThreadHandoffService.class);

    /**
     * Longueur maximale d'une passation corrigée. En prod, les résumés observés font 10 000 à 17 000
     * caractères ; 40 000 laisse la place d'enrichir sans laisser grossir le préfixe rejoué sans borne.
     */
    public static final int MAX_LENGTH = 40_000;

    private final WorkspaceService workspaceService;
    private final WorkspaceRepository workspaceRepository;
    private final LiveTurnRegistry liveTurns;
    private final RemoteTurnSource remoteTurns;

    public AtelierThreadHandoffService(WorkspaceService workspaceService,
            WorkspaceRepository workspaceRepository, LiveTurnRegistry liveTurns,
            RemoteTurnSource remoteTurns) {
        this.workspaceService = workspaceService;
        this.workspaceRepository = workspaceRepository;
        this.liveTurns = liveTurns;
        this.remoteTurns = remoteTurns;
    }

    /**
     * La passation courante du fil, ou {@code summary = null} s'il n'y en a pas encore.
     *
     * @throws WorkspaceNotFoundException 404 indiscernable si le projet n'appartient pas à l'utilisateur
     */
    @Transactional(readOnly = true)
    public ThreadHandoffResponse read(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        return ThreadHandoffResponse.of(workspace.getChatThreadSummary());
    }

    /**
     * Remplace la passation par le texte corrigé de l'utilisateur.
     *
     * <p>Refusée pendant un tour vivant (ici ou chez un pair) : le tour tient son propre exemplaire du
     * projet et l'enregistre à la fin, ce qui écraserait la correction sans prévenir.</p>
     *
     * @throws WorkspaceNotFoundException      404 si le projet n'appartient pas à l'utilisateur
     * @throws InvalidThreadHandoffException   400 si le texte est vide, trop long ou hors gabarit
     * @throws ThreadHandoffConflictException  409 s'il n'y a rien à corriger ou si un tour tourne
     */
    @Transactional
    public ThreadHandoffResponse correct(UUID userId, UUID workspaceId, String corrected) {
        // Isolation d'abord : un projet d'autrui rend 404 avant toute autre réponse.
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        String text = validate(corrected);
        String previous = workspace.getChatThreadSummary();
        if (previous == null || previous.isBlank()) {
            throw new ThreadHandoffConflictException("no_handoff",
                    "Ce fil n'a pas encore de passation : elle apparaît à la première compaction.");
        }
        if (liveTurns.find(userId, workspaceId).isPresent()
                || remoteTurns.findRemoteTurn(userId, workspaceId).isPresent()) {
            throw new ThreadHandoffConflictException("turn_in_progress",
                    "Un tour est en cours sur ce projet : corrigez la passation une fois qu'il est terminé.");
        }
        workspace.setChatThreadSummary(text);
        workspaceRepository.save(workspace);
        log.info("Passation corrigée (workspace={}, poste={}) : {} -> {} caractères.",
                workspace.getId(), workspace.getHostId(), previous.length(), text.length());
        return ThreadHandoffResponse.of(text);
    }

    /** Le texte nettoyé de ses blancs de bord, ou un refus explicite (400). */
    static String validate(String corrected) {
        if (corrected == null || corrected.isBlank()) {
            throw new InvalidThreadHandoffException("La passation ne peut pas être vide.");
        }
        String text = corrected.strip();
        if (text.length() > MAX_LENGTH) {
            throw new InvalidThreadHandoffException(
                    "La passation est trop longue (" + MAX_LENGTH + " caractères au plus).");
        }
        if (!AtelierCompactionService.looksSectioned(text)) {
            throw new InvalidThreadHandoffException("Gardez les cinq rubriques, dans l'ordre : "
                    + String.join(", ", AtelierCompactionService.SUMMARY_SECTIONS) + ".");
        }
        return text;
    }
}
