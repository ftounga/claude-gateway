package fr.claudegateway.governance.control;

import java.util.Optional;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.governance.GovernanceControl;

/**
 * <b>Une case non cochée empêche de clore</b> (F-52 / SF-52-02).
 *
 * <p>La promotion sans dette bloquante ne tient pas : « je le noterai » se dit à chaque tour, et rien
 * n'oblige jamais à y revenir. Une case {@code - [ ]} laissée dans la carte du projet est une
 * <b>dette</b>, et tant qu'elle y est, le tour ne se clôt pas.</p>
 *
 * <p><b>Il compte, il ne police pas la forme.</b> Sans marqueur, ce contrôle <b>passe</b> : demander
 * le marqueur est le travail de {@link JugeFinDeTourControl}, et deux contrôles qui réclament la même
 * chose ne rendraient qu'une correction — la première. Un paquet qui n'active que celui-ci accepte de
 * ne rien compter faute de déclaration ; c'est une composition légitime, pas un oubli.</p>
 *
 * <p>Comme tout blocage de fin de tour, celui-ci est <b>borné</b> par F-50 : après un nombre fixe de
 * refus, la main revient au modèle.</p>
 */
@Component
public class PromotionDetteBloquanteControl implements GovernanceControl {

    /** Identifiant cité par les paquets. Immuable : un paquet publié le référence. */
    public static final String ID = "promotion-dette-bloquante";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AtelierCheckpointKind kind() {
        return AtelierCheckpointKind.END_OF_TURN;
    }

    @Override
    public String description() {
        return "Refuse de clore un tour tant que la carte du projet garde une case « - [ ] » "
                + "non cochée, d'après le marqueur de fin de tour.";
    }

    @Override
    public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
        String reply = context == null ? null : context.replyText();
        Optional<FinDeTourMarker> marker = FinDeTourMarker.parse(reply);
        if (marker.isEmpty() || marker.get().dette() == 0) {
            return AtelierCheckpointVerdict.proceed();
        }
        int dette = marker.get().dette();
        return AtelierCheckpointVerdict.block("la carte du projet (PLAN-ACTION.md) garde " + dette
                + (dette > 1 ? " cases « - [ ] » non cochées" : " case « - [ ] » non cochée")
                + " : traite-les, ou retire les lignes devenues sans objet, puis conclus avec "
                + "« dette=0 ».");
    }
}
