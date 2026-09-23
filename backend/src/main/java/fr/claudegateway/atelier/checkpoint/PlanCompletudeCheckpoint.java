package fr.claudegateway.atelier.checkpoint;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.AtelierPlan;

/**
 * Porte de complétude générique (F-121 / SF-121-05) : en <b>fin de tour</b>, si le modèle rend la
 * main alors que le plan qu'il s'est donné (F-39 / SF-39-13, outil {@code set_plan}) porte encore
 * des étapes {@code pending} ou {@code active}, ce contrôle <b>refuse la clôture</b> et réinjecte une
 * consigne de reprise.
 *
 * <p><b>Déterministe, zéro appel modèle.</b> Le contrôle ne fait qu'une comparaison d'états : il lit
 * le plan déjà tenu en mémoire pendant le tour, remis dans le contexte de fin de tour, et n'invoque
 * jamais le fournisseur. C'est le patron F-50 (un bean par point d'accroche, rapide, synchrone, sans
 * effet de bord) appliqué à la parité Claude Code : ne plus se dire « fini » avec un plan à moitié
 * coché — le ressenti « il abandonne en route ».</p>
 *
 * <p><b>Ne se déclenche que si un plan existe.</b> Un tour sans {@code set_plan} — une simple réponse,
 * une question — porte un plan vide et passe sans bruit. La neutralisation fine du crochet en mode
 * Réponse/Plan relève de F-121-17 ; ici, l'absence de plan suffit à écarter ces tours.</p>
 *
 * <p><b>Anti-boucle.</b> Aucun garde nouveau : la boucle borne déjà les refus de fin de tour à
 * {@code MAX_END_OF_TURN_BLOCKS} (F-50 / SF-50-02). Après ce plafond, la main est rendue même si le
 * plan reste inachevé.</p>
 *
 * <p><b>Coupe-circuit.</b> {@code app.atelier.plan-completeness-gate} (défaut {@code true}) permet de
 * le désactiver ; à {@code false}, il laisse toujours passer — comportement d'avant SF-121-05.</p>
 *
 * <p>Contrairement aux contrôles de gouvernance (délégués à l'activation par paquet, F-51), cette
 * porte est un comportement de la <b>boucle maison</b> elle-même : bean toujours enregistré,
 * découvert par {@link AtelierCheckpointRunner}.</p>
 */
@Component
public class PlanCompletudeCheckpoint implements AtelierCheckpoint {

    /** Au-delà, la correction cite « … et N autres » : on nomme sans transcrire tout le plan. */
    static final int MAX_STEPS_CITED = 5;

    private final boolean enabled;

    public PlanCompletudeCheckpoint(
            @Value("${app.atelier.plan-completeness-gate:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public AtelierCheckpointKind kind() {
        return AtelierCheckpointKind.END_OF_TURN;
    }

    @Override
    public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
        if (!enabled || context == null) {
            return AtelierCheckpointVerdict.proceed();
        }
        AtelierPlan plan = context.plan();
        if (plan == null || plan.isEmpty()) {
            // Ne se déclenche que si un plan existe.
            return AtelierCheckpointVerdict.proceed();
        }
        List<String> unfinished = unfinishedTitles(plan);
        if (unfinished.isEmpty()) {
            // Toutes les étapes sont terminées : rien à reprendre.
            return AtelierCheckpointVerdict.proceed();
        }
        return AtelierCheckpointVerdict.block(correction(unfinished));
    }

    /** Titres des étapes encore {@code pending}/{@code active}, dans l'ordre du plan. */
    private static List<String> unfinishedTitles(AtelierPlan plan) {
        List<String> titles = new ArrayList<>();
        for (AtelierPlan.Step step : plan.steps()) {
            if (step.status() != AtelierPlan.Status.DONE) {
                titles.add(step.title());
            }
        }
        return titles;
    }

    /** Le message porte le geste (règle F-50) : ce qui reste, puis quoi faire. */
    private static String correction(List<String> unfinished) {
        StringBuilder sb = new StringBuilder(
                "ton plan porte encore des étapes non terminées : ");
        int cited = Math.min(unfinished.size(), MAX_STEPS_CITED);
        for (int i = 0; i < cited; i++) {
            if (i > 0) {
                sb.append(" ; ");
            }
            sb.append("« ").append(unfinished.get(i)).append(" »");
        }
        int remaining = unfinished.size() - cited;
        if (remaining > 0) {
            sb.append(" et ").append(remaining).append(" autre(s)");
        }
        sb.append(". Termine-les, ou mets le plan à jour (set_plan) pour refléter l'état réel, "
                + "avant de conclure.");
        return sb.toString();
    }
}
