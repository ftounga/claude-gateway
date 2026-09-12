package fr.claudegateway.governance.control;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.governance.GovernanceControl;
import fr.claudegateway.governance.GovernanceMapDestinations;

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
 * <p><b>Il envoie à la bonne carte</b> (F-93 / SF-93-01). Jusqu'ici son refus disait « ajoute-le à
 * la carte du projet ». C'était la mauvaise destination pour la moitié de ce qui compte : un cluster,
 * un VPN, un bastion, un contact, une convention appartiennent au <b>poste</b>, pas au projet, et
 * doivent lui survivre. Le refus nomme donc les deux destinations <b>et le critère qui les
 * sépare</b> — sans quoi le modèle choisit au hasard, et la moitié du savoir meurt avec le dossier.</p>
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

    /** Ce qui départage les deux cartes, dit en une ligne au modèle qui doit choisir. */
    private static final String CRITERE =
            "un élément d'infrastructure — cluster, VPN, serveur, stockage, bastion, réseau, "
                    + "endpoint, contact, convention — va dans la carte du POSTE ; une décision "
                    + "propre à ce projet va dans PLAN-ACTION.md";

    private final GovernanceMapDestinations destinations;

    public JugeFinDeTourControl(GovernanceMapDestinations destinations) {
        this.destinations = destinations;
    }

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
                + "rejoint la carte du poste ou celle du projet ; alerte si le marqueur de fin de "
                + "tour manque.";
    }

    @Override
    public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
        String reply = context == null ? null : context.replyText();
        Optional<FinDeTourMarker> marker = FinDeTourMarker.parse(reply);
        if (marker.isEmpty()) {
            return AtelierCheckpointVerdict.block(
                    "termine ta réponse par le marqueur de fin de tour, exactement sous cette forme : "
                            + FinDeTourMarker.FORME + " — « promotion » liste ce que ce tour a fait "
                            + "apparaître de durable et qui ne figure encore dans aucune carte, "
                            + "« aucune » s'il n'y a rien ; « promu » dit ce que tu as rangé ET OÙ "
                            + "(« promu=cluster atlas -> plateformes.md ») ; « dette » compte les "
                            + "cases « - [ ] » qui restent non cochées dans le projet.");
        }
        FinDeTourMarker parsed = marker.get();
        if (!parsed.nothingToPromote()) {
            List<String> carte =
                    destinations.pathsForProject(context.userId(), context.workspaceId());
            return AtelierCheckpointVerdict.block("range d'abord ce que tu viens de déclarer "
                    + "durable : " + parsed.citedPromotions() + ". Où : " + CRITERE
                    + " — la carte du poste, ce sont ces fichiers : "
                    + GovernanceMapDestinations.cite(carte) + ". Trace ensuite chaque élément coché "
                    + "dans STATE.md (« - [x] <élément> -> promu dans <fichier> ») et reprends ta "
                    + "réponse avec « promotion=aucune ; promu=<élément> -> <fichier> ».");
        }
        return AtelierCheckpointVerdict.proceed();
    }
}
