package fr.claudegateway.governance.control;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.governance.GovernanceControl;
import fr.claudegateway.governance.GovernanceMapDestinations;
import fr.claudegateway.governance.control.FinDeTourMarker.Promotion;

/**
 * <b>Une promotion dit où, et une case non cochée empêche de clore</b> (F-52 / SF-52-02, complété
 * par F-93 / SF-93-01).
 *
 * <p>La promotion sans dette bloquante ne tient pas : « je le noterai » se dit à chaque tour, et rien
 * n'oblige jamais à y revenir. Une case {@code - [ ]} laissée dans le projet est une <b>dette</b>, et
 * tant qu'elle y est, le tour ne se clôt pas.</p>
 *
 * <p><b>Ce qui manquait : la destination.</b> Ce contrôle savait compter ; il ne savait pas dire
 * <i>où</i> promouvoir, et son message envoyait vers la carte du <b>projet</b>. Or un cluster, un
 * VPN, un bastion n'appartiennent pas au projet — ils appartiennent au <b>poste</b>, et doivent lui
 * survivre. F-92 a créé la destination ; ce contrôle la <b>nomme</b>, à partir des fichiers de carte
 * réellement déposés sur ce poste ({@link GovernanceMapDestinations}), et il <b>refuse une promotion
 * qui ne la dit pas</b>. C'est le même contrôle, complété : un second contrôle qui réclamerait la
 * même chose ne rendrait qu'une correction — la première — et l'autre serait muette.</p>
 *
 * <p><b>Trois refus, dans cet ordre, et l'ordre est le message :</b></p>
 *
 * <ol>
 *   <li>une promotion déclarée <b>sans destination</b> — on demande où, avec la forme exacte ;</li>
 *   <li>une destination <b>étrangère à la carte</b> — on nomme les fichiers réels du poste ;</li>
 *   <li>une <b>dette</b> non nulle — on dit quoi en faire, et vers quoi promouvoir.</li>
 * </ol>
 *
 * <p><b>Il compte, il ne police pas la forme.</b> Sans marqueur, ce contrôle <b>passe</b> : demander
 * le marqueur est le travail de {@link JugeFinDeTourControl}, et deux contrôles qui réclament la même
 * chose ne rendraient qu'une correction. Un paquet qui n'active que celui-ci accepte de ne rien
 * compter faute de déclaration ; c'est une composition légitime, pas un oubli.</p>
 *
 * <p><b>Il ne tombe jamais pour une carte qu'il n'a pas su lister.</b> Poste sans machine, rien
 * d'activé, projet effacé entre-temps : la liste des destinations est vide, le message reste vrai
 * sous sa forme générique, et la dette continue d'être comptée. Un contrôle qui prendrait le message
 * d'un utilisateur en otage pour une raison qui ne le regarde pas serait pire que pas de contrôle.</p>
 *
 * <p>Comme tout blocage de fin de tour, celui-ci est <b>borné</b> par F-50 : après un nombre fixe de
 * refus, la main revient au modèle.</p>
 */
@Component
public class PromotionDetteBloquanteControl implements GovernanceControl {

    /** Identifiant cité par les paquets. Immuable : un paquet publié le référence. */
    public static final String ID = "promotion-dette-bloquante";

    private final GovernanceMapDestinations destinations;
    private final PromotionReportee reportees;

    /** Forme d'avant F-93 / SF-93-04 : un registre de reports propre à ce contrôle. */
    public PromotionDetteBloquanteControl(GovernanceMapDestinations destinations) {
        this(destinations, new PromotionReportee());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PromotionDetteBloquanteControl(GovernanceMapDestinations destinations,
            PromotionReportee reportees) {
        this.destinations = destinations;
        this.reportees = reportees;
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
        return "Refuse de clore un tour tant qu'une promotion ne dit pas dans quel fichier de la "
                + "carte du poste elle a été rangée, ou tant qu'une case « - [ ] » reste non cochée "
                + "dans le projet.";
    }

    @Override
    public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
        AtelierCheckpointVerdict claimed =
                JugeFinDeTourControl.claimIfDue(context, destinations, reportees);
        if (claimed != null) {
            return claimed;
        }
        String reply = context == null ? null : context.replyText();
        Optional<FinDeTourMarker> parsed = FinDeTourMarker.parse(reply);
        if (parsed.isEmpty()) {
            return AtelierCheckpointVerdict.proceed();
        }
        FinDeTourMarker marker = parsed.get();

        List<String> carte = destinations.pathsForProject(context.userId(), context.workspaceId());
        String cited = GovernanceMapDestinations.cite(carte);

        if (context.machineOffline()) {
            // F-93 / SF-93-04 : les trois refus ci-dessous demandent tous d'écrire sur la machine (la
            // carte, STATE.md). Poste hors ligne : on reporte ce qui serait réclamé, on ne bloque pas.
            List<Promotion> unplaced = new java.util.ArrayList<>(marker.withoutDestination());
            List<String> foreign = foreignDestinations(marker, carte);
            marker.promus().stream().filter(promotion -> foreign.contains(promotion.destination()))
                    .forEach(unplaced::add);
            if (unplaced.isEmpty() && marker.dette() == 0) {
                return AtelierCheckpointVerdict.proceed();
            }
            List<String> elements = new java.util.ArrayList<>(marker.promotions());
            unplaced.forEach(promotion -> elements.add(promotion.element()));
            reportees.reporter(context.userId(), context.hostId(), context.workspaceId(), elements,
                    marker.dette());
            return AtelierCheckpointVerdict.deferred(PromotionReportee.NOTICE);
        }

        List<Promotion> mute = marker.withoutDestination();
        if (!mute.isEmpty()) {
            return AtelierCheckpointVerdict.block("tu déclares avoir promu « "
                    + marker.citedPromus(mute) + " » sans dire où. Reprends le marqueur sous la "
                    + "forme « promu=" + mute.get(0).element() + " -> <fichier> », le fichier étant "
                    + "l'un de ceux de la carte du poste : " + cited + " — et trace-le coché dans "
                    + "STATE.md : « - [x] " + mute.get(0).element() + " -> promu dans <fichier> ».");
        }

        List<String> foreign = foreignDestinations(marker, carte);
        if (!foreign.isEmpty()) {
            return AtelierCheckpointVerdict.block("« " + String.join(", ", foreign) + " » "
                    + (foreign.size() > 1 ? "ne sont pas des fichiers" : "n'est pas un fichier")
                    + " de la carte de ce poste. Range l'élément dans l'un de ceux-ci : " + cited
                    + ", puis reprends le marqueur avec cette destination.");
        }

        if (marker.dette() == 0) {
            return AtelierCheckpointVerdict.proceed();
        }
        int dette = marker.dette();
        return AtelierCheckpointVerdict.block("le projet garde " + dette
                + (dette > 1 ? " cases « - [ ] » non cochées" : " case « - [ ] » non cochée")
                + " : pour chacune, promeus l'élément durable dans la carte du poste (" + cited
                + "), coche-la en disant où — « - [x] <élément> -> promu dans <fichier> » —, ou "
                + "retire la ligne devenue sans objet. Conclus ensuite avec « dette=0 ».");
    }

    /**
     * Les destinations citées qui n'appartiennent pas à la carte de ce poste.
     *
     * <p>Quand la carte n'a pas pu être listée, <b>aucune</b> destination n'est étrangère : on ne
     * refuse pas un fichier au nom d'une liste qu'on n'a pas.</p>
     *
     * <p>La comparaison porte sur le <b>nom du fichier</b>, pas sur le chemin écrit : « acces.md »
     * et « ./acces.md » désignent le même fichier, et un refus pour un « ./ » ferait perdre un tour
     * sans rien protéger.</p>
     */
    private static List<String> foreignDestinations(FinDeTourMarker marker, List<String> carte) {
        if (carte.isEmpty()) {
            return List.of();
        }
        return marker.destinations().stream()
                .filter(destination -> carte.stream()
                        .noneMatch(path -> fileName(path).equalsIgnoreCase(fileName(destination))))
                .toList();
    }

    /** Le dernier segment d'un chemin — ce qui identifie un fichier de carte. */
    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path.strip() : path.substring(slash + 1).strip();
    }
}
