package fr.claudegateway.atelier.actions;

import java.text.Normalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;

/**
 * <b>La reprise des natures</b> (F-180 / SF-180-02, décision D1) : les attentes ouvertes nées sans
 * nature sont présentées avec une nature et un « qui » <b>devinés</b> par heuristique ; l'utilisateur
 * valide, une par une ou en bloc. <b>Rien n'est écrit sans sa validation</b>.
 *
 * <p>L'heuristique ne sert qu'à <b>proposer</b> : elle lit des mots de l'énoncé (« accès », « valider »,
 * « incident »…) et la présence d'une personne. Une erreur ne coûte qu'un clic de correction.</p>
 *
 * <p><b>Isolation</b> : lecture et écriture sous {@code user_id} ; un identifiant inconnu ou d'autrui
 * est ignoré, compté, jamais une erreur.</p>
 */
@Service
public class TerminalActionQualifier {

    static final int MAX_DECISIONS = 200;

    static final List<String> ACCES_WORDS = List.of("acces", "compte", "droit", "jeton", "token",
            "habilitation", "vpn", "proxy", "identifiant", "login", "mot de passe", "permission",
            "ouverture de flux", "flux", "licence", "cle ", "certificat", "sso");
    static final List<String> VALIDATION_WORDS = List.of("valid", "accord", "approb", "approuv", "signer",
            "signature", "arbitr", " go ", "feu vert", "decision");
    static final List<String> INCIDENT_WORDS = List.of("incident", "panne", "bug", "erreur", "ticket",
            "plantage", "crash", "indisponib");
    static final List<String> TRANSMISSION_WORDS = List.of("envoyer", "transmettre", "transferer",
            "partager", "fichier", "document", "export", "livrable", "recevoir");

    /** Les gestes qui sont à l'utilisateur lui-même : personne d'autre à attendre. */
    static final List<String> MOI_WORDS = List.of("sso", "aws sso", "se connecter", "reconnecter",
            "redemarrer", "relancer le runner", "runner", "reappairer", "installer", "mettre a jour",
            "lancer chrome", "ouvrir teams", "se reconnecter");
    static final List<String> TIERS_WORDS = List.of("support", "editeur", "fournisseur", "microsoft",
            "atlassian", "aws support", "prestataire", "hebergeur");

    private final TerminalActionRepository repository;
    private final WorkspaceRepository workspaces;
    private final Clock clock;

    public TerminalActionQualifier(TerminalActionRepository repository, WorkspaceRepository workspaces, Clock clock) {
        this.repository = repository;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    /** La nature devinée d'après l'énoncé. */
    static TerminalActionCategorie suggestCategorie(String description) {
        String t = " " + fold(description) + " ";
        if (containsAny(t, INCIDENT_WORDS)) {
            return TerminalActionCategorie.INCIDENT;
        }
        if (containsAny(t, ACCES_WORDS)) {
            return TerminalActionCategorie.ACCES;
        }
        if (containsAny(t, VALIDATION_WORDS)) {
            return TerminalActionCategorie.VALIDATION;
        }
        if (containsAny(t, TRANSMISSION_WORDS)) {
            return TerminalActionCategorie.TRANSMISSION;
        }
        return TerminalActionCategorie.INFO;
    }

    /** « Qui doit agir » deviné : un geste à soi, un tiers nommé, sinon le client. */
    static TerminalActionQui suggestQui(TerminalAction action) {
        String t = " " + fold(action.getDescription()) + " " + fold(action.getPerson()) + " ";
        if (containsAny(t, TIERS_WORDS)) {
            return TerminalActionQui.TIERS;
        }
        boolean nobody = action.getPerson() == null && action.getRequestedTo() == null;
        if (containsAny(t, MOI_WORDS) && nobody) {
            return TerminalActionQui.MOI;
        }
        return TerminalActionQui.CLIENT;
    }

    /** Les attentes ouvertes du compte sans nature, avec la proposition. */
    @Transactional(readOnly = true)
    public List<QualifyItem> pending(UUID userId) {
        Map<UUID, String> names = new HashMap<>();
        return repository.findByUserIdAndStatusInOrderByCreatedAtAsc(userId, TerminalActionStatus.OPEN_STATES)
                .stream()
                .filter(a -> a.getCategorie() == null || a.getQui() == null)
                .map(a -> new QualifyItem(
                        TerminalActionResponse.from(a, names.computeIfAbsent(a.getWorkspaceId(),
                                id -> workspaces.findByIdAndUserId(id, userId).map(Workspace::getName)
                                        .orElse("Projet supprimé"))),
                        a.getCategorie() != null ? a.getCategorie() : suggestCategorie(a.getDescription()),
                        a.getQui() != null ? a.getQui() : suggestQui(a)))
                .toList();
    }

    /** Applique les natures validées par l'utilisateur. */
    @Transactional
    public TerminalActionReviewService.ReviewResult apply(UUID userId, List<Decision> decisions) {
        List<Decision> list = decisions == null ? List.of() : decisions;
        if (list.size() > MAX_DECISIONS) {
            throw new InvalidTerminalActionException("Au plus " + MAX_DECISIONS + " décisions par envoi.");
        }
        int applied = 0;
        int ignored = 0;
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (Decision d : list) {
            if (d == null || d.id() == null || (d.categorie() == null && d.qui() == null)) {
                ignored++;
                continue;
            }
            var found = repository.findByIdAndUserId(d.id(), userId);
            if (found.isEmpty()) {
                ignored++;
                continue;
            }
            TerminalAction action = found.get();
            if (d.categorie() != null) {
                action.setCategorie(d.categorie());
            }
            if (d.qui() != null) {
                action.setQui(d.qui());
            }
            action.setUpdatedAt(now);
            repository.save(action);
            applied++;
        }
        return new TerminalActionReviewService.ReviewResult(applied, ignored);
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    static String fold(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[’']", " ")
                .replaceAll("\\s+", " ");
    }

    /** Une attente à qualifier et la proposition. */
    public record QualifyItem(TerminalActionResponse action, TerminalActionCategorie suggestedCategorie,
                              TerminalActionQui suggestedQui) {
    }

    /** La nature retenue par l'utilisateur pour une attente. */
    public record Decision(UUID id, TerminalActionCategorie categorie, TerminalActionQui qui) {
    }
}
