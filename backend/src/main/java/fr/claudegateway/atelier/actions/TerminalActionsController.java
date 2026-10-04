package fr.claudegateway.atelier.actions;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les actions ouvertes du compte, tous projets confondus</b> (F-154 / SF-154-03) — ce que la
 * section « Ailleurs » du menu montre.
 *
 * <p>Racine distincte de {@code /workspaces/{id}/actions} : les deux formes ont le même nombre de
 * segments, et {@code /workspaces/actions/…} serait lu comme un identifiant de projet nommé
 * « actions ».</p>
 *
 * <p><b>Isolation.</b> Cette lecture traverse les projets — c'est son objet — mais jamais les
 * comptes : elle filtre sur le {@code user_id} du {@link CurrentUser}, et aucun identifiant n'est
 * accepté du client.</p>
 */
@RestController
@RequestMapping("/terminal-actions")
public class TerminalActionsController {

    private final TerminalActionQueryService queries;
    private final TerminalActionReviewService reviews;
    private final CurrentUser currentUser;

    public TerminalActionsController(TerminalActionQueryService queries, TerminalActionReviewService reviews,
                                     CurrentUser currentUser) {
        this.queries = queries;
        this.reviews = reviews;
        this.currentUser = currentUser;
    }

    /**
     * Les actions ouvertes du compte, les plus anciennes d'abord.
     *
     * @param exclude projet à retirer — celui du terminal courant, déjà listé au-dessus
     */
    @GetMapping
    public List<TerminalActionElsewhereResponse> open(
            @RequestParam(required = false) String exclude) {
        return queries.openElsewhere(currentUser.requireId(), exclude);
    }

    /** Les attentes héritées « à vérifier », avec l'état proposé (F-175 / SF-175-07). */
    @GetMapping("/review")
    public List<TerminalActionReviewService.ReviewItem> review() {
        return reviews.pending(currentUser.requireId());
    }

    /** Les décisions de l'utilisateur sur la reprise — en bloc ou une par une (F-175 / SF-175-07). */
    @org.springframework.web.bind.annotation.PostMapping("/review")
    public TerminalActionReviewService.ReviewResult applyReview(
            @org.springframework.web.bind.annotation.RequestBody ReviewRequest request) {
        return reviews.apply(currentUser.requireId(), request == null ? List.of() : request.decisions());
    }

    /** Le corps de {@code POST /terminal-actions/review}. */
    public record ReviewRequest(List<TerminalActionReviewService.Decision> decisions) {
    }

    /** Les compteurs par poste et par terminal (F-175 / SF-175-06) — rail de la Forge, mosaïque. */
    @GetMapping("/summary")
    public TerminalActionSummaryResponse summary() {
        return queries.summary(currentUser.requireId());
    }
}
