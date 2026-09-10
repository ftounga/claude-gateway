package fr.claudegateway.governance.control;

import java.util.Optional;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.governance.GovernanceControl;

/**
 * <b>Le filet sémantique</b> (F-52 / SF-52-02) : rien de durable ne sort d'un tour sans avoir rejoint
 * la carte du projet.
 *
 * <p>C'est le troisième niveau de gouvernance, celui qui n'existait pas. La <b>convention</b> demande,
 * le <b>verrou</b> refuse — et entre les deux manquait ce qui <i>regarde</i> ce qui vient d'être
 * produit. Le principe qu'il défend tient en une phrase : <b>le travail est jetable, le savoir est
 * durable</b>. Les notes d'un sujet mourront avec lui ; ce qu'elles ont fait apparaître de durable
 * doit être promu, sinon il meurt avec elles.</p>
 *
 * <p><b>Best-effort, et jamais une autorité.</b> Le jugement sémantique est rendu par le modèle
 * lui-même, dans le marqueur qu'il pose en terminant ({@link FinDeTourMarker}) : ce contrôle ne lit
 * qu'une forme. Et si le modèle s'entête, F-50 rend la main après un nombre fixe de refus — un juge
 * ne prend jamais le message d'un utilisateur en otage.</p>
 *
 * <p><b>Le repli alerte, il ne laisse pas passer.</b> Marqueur absent ou illisible → la fin du tour
 * est refusée et la forme exacte est rendue au modèle. C'est l'exigence écrite de la feature, et elle
 * est juste : un filet qui se tait quand il ne comprend pas ne protège de rien, il donne l'illusion
 * d'un contrôle.</p>
 */
@Component
public class JugeFinDeTourControl implements GovernanceControl {

    /** Identifiant cité par les paquets. Immuable : un paquet publié le référence. */
    public static final String ID = "juge-fin-de-tour";

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
        return "Refuse de clore un tour tant que ce qu'il a fait apparaître de durable n'a pas "
                + "rejoint la carte du projet ; alerte si le marqueur de fin de tour manque.";
    }

    @Override
    public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
        String reply = context == null ? null : context.replyText();
        Optional<FinDeTourMarker> marker = FinDeTourMarker.parse(reply);
        if (marker.isEmpty()) {
            return AtelierCheckpointVerdict.block(
                    "termine ta réponse par le marqueur de fin de tour, exactement sous cette forme : "
                            + FinDeTourMarker.FORME + " — « promotion » liste ce que ce tour a fait "
                            + "apparaître de durable et qui ne figure pas encore dans la carte du "
                            + "projet (PLAN-ACTION.md), « aucune » s'il n'y a rien ; « dette » compte "
                            + "les cases « - [ ] » qui y restent non cochées.");
        }
        FinDeTourMarker parsed = marker.get();
        if (!parsed.nothingToPromote()) {
            return AtelierCheckpointVerdict.block(
                    "ajoute d'abord à la carte du projet (PLAN-ACTION.md) ce que tu viens de déclarer "
                            + "durable : " + parsed.citedPromotions()
                            + ". Reprends ensuite ta réponse avec « promotion=aucune ».");
        }
        return AtelierCheckpointVerdict.proceed();
    }
}
