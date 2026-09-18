package fr.claudegateway.governance.control;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.governance.GovernanceControl;

/**
 * <b>Contrôle retiré : la promotion ne se déclare plus, elle se constate</b> (F-125 / SF-125-06b).
 *
 * <p>Ce contrôle lisait le <b>marqueur de fin de tour</b> pour refuser une promotion « sans dire
 * où » (SF-125-04 avait déjà cessé de bloquer sur la seule dette). SF-125-06b supprime toute
 * dépendance à ce marqueur : le suivi de la promotion et de la dette est un <b>effet de bord
 * serveur</b>, à partir des écritures de fichiers réelles.</p>
 *
 * <ul>
 *   <li>La <b>promotion</b> est constatée par {@code GovernanceMapGrowth} (delta de faits d'une carte
 *       à la lecture) et rattrapée par {@code JugeIndependantControl} (audit des fichiers écrits) —
 *       jamais par une déclaration du modèle.</li>
 *   <li>La <b>dette</b> (cases {@code - [ ]} des fichiers du projet) est comptée et signalée par
 *       {@code IntegritePosteControl} : {@code DETTE_EN_COURS} (avertissement, non bloquant) et
 *       {@code DETTE_A_LA_CLOTURE} (erreur à la clôture) — hors de la réponse à l'utilisateur.</li>
 * </ul>
 *
 * <p>Il reste <b>déclaré</b> dans le paquet (identité stable, aucune rupture pour un poste activé)
 * mais rend désormais {@link AtelierCheckpointVerdict#proceed()} : la carte se tient en silence.</p>
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
        return "Retiré (F-125 / SF-125-06b) : ne s'appuie plus sur un marqueur émis par le modèle. "
                + "Le suivi promotion/dette se fait côté serveur (écritures de fichiers), porté par le "
                + "juge indépendant, la croissance de carte et l'intégrité du poste.";
    }

    @Override
    public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
        // Plus aucune dépendance à un marqueur émis par le modèle (F-125 / SF-125-06b) : le suivi de
        // la promotion et de la dette est un effet de bord serveur.
        return AtelierCheckpointVerdict.proceed();
    }
}
