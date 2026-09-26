package fr.claudegateway.runner.door;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.runner.RunnerLiveness;

/**
 * <b>La porte d'entrée du runner</b> (F-161 / SF-161-01) : refuser un tour qui a besoin du poste
 * <b>avant</b> d'envoyer quoi que ce soit au fournisseur.
 *
 * <p><b>Pourquoi elle existe.</b> Sur la session mesurée du 25/09, <b>8 tours « Non concluant »,
 * ≈ 9 $, 11 % de la facture</b> ont été dépensés pour <i>découvrir</i> que la machine ne répondait
 * pas : le contexte part, le modèle raisonne, appelle {@code bash}, et l'échec n'apparaît qu'alors.</p>
 *
 * <p><b>Rien n'est créé ici.</b> Les deux savoirs existaient et étaient consultés trop tard — la
 * vivacité par {@link RunnerLiveness} (battement partagé par la base) et les capacités déclarées par
 * le runner à chaque connexion. Une capacité dormante, au sens de F-156.</p>
 *
 * <p><b>Et elle ne bloque jamais sur une ignorance</b> : capacités jamais déclarées, poste inconnu
 * — on laisse passer. Une porte qui ferme faute de savoir est pire que pas de porte.</p>
 */
@Component
public class RunnerDoor {

    private final RunnerLiveness liveness;

    /**
     * Le <b>ping conditionnel</b> (F-161 / SF-161-04), branché par mutateur pour ne pas ajouter un
     * second constructeur à ce composant. {@code null} (formes historiques, tests) ⇒ la porte juge
     * exactement comme avant SF-161-04.
     */
    private fr.claudegateway.runner.ping.RunnerPing ping;

    public RunnerDoor(RunnerLiveness liveness) {
        this.liveness = liveness;
    }

    /** Branche le ping conditionnel (F-161 / SF-161-04). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPing(fr.claudegateway.runner.ping.RunnerPing ping) {
        this.ping = ping;
    }

    /**
     * Le poste peut-il porter ce tour ?
     *
     * @param userId       propriétaire, déjà vérifié
     * @param hostId       poste du projet ; {@code null} → aucune porte
     * @param hostName     nom lisible du poste, pour le message
     * @param declared     capacités déclarées par le runner, telles que
     *                     {@code RunnerHostService.declaredCapabilities} les rend déjà analysées ;
     *                     vide → <b>on ne sait pas</b>, donc on laisse passer
     * @param required     capacités que la panoplie du tour exigerait
     */
    public RunnerDoorVerdict check(UUID userId, UUID hostId, String hostName, Set<String> declared,
                                   Set<String> required) {
        return check(userId, hostId, hostName, declared, required, null);
    }

    /**
     * La même porte, avec la <b>cible</b> du tour — ce qu'il faut pour <b>sonder</b> le poste
     * (F-161 / SF-161-04) plutôt que de se fier à ce qu'il déclare.
     *
     * <p>La sonde vient <b>en dernier</b>, et ce n'est pas un détail : sonder un poste dont le
     * battement est déjà périmé ferait attendre le tour pour apprendre ce que la lecture du
     * battement disait gratuitement.</p>
     *
     * @param target poste et projet du tour, construits <b>après</b> le contrôle d'appartenance ;
     *               {@code null} ⇒ aucune sonde, la porte juge comme avant SF-161-04
     */
    public RunnerDoorVerdict check(UUID userId, UUID hostId, String hostName, Set<String> declared,
                                   Set<String> required,
                                   fr.claudegateway.runner.channel.RunnerTarget target) {
        if (hostId == null) {
            return RunnerDoorVerdict.opened(); // projet hébergé : la porte ne le concerne pas
        }
        // Une SEULE lecture du battement : la décision et le message qui l'explique viennent de la
        // même vérité. Deux lectures pourraient dire « hors ligne (dernier signe il y a 0 min) ».
        OffsetDateTime lastSeenAt = liveness.lastSeenAt(userId, hostId);
        if (!liveness.isFresh(lastSeenAt)) {
            return RunnerDoorVerdict.closed(RunnerDoorVerdict.OFFLINE,
                    "Le runner du poste « " + name(hostName) + " » ne répond plus (dernier signe "
                            + sinceLabel(lastSeenAt) + "). Relance-le, puis renvoie ta demande — "
                            + "rien n'a été dépensé.");
        }

        Set<String> known = declared == null ? Set.of() : declared;
        if (known.isEmpty() || required == null || required.isEmpty()) {
            // On ne sait pas ce que le runner sait faire : on ne ferme pas là-dessus. La sonde, en
            // revanche, reste due — elle ne demande rien à ce que le poste DÉCLARE.
            return probe(target, hostName);
        }
        for (String capability : required) {
            if (!known.contains(capability)) {
                return RunnerDoorVerdict.closed(RunnerDoorVerdict.MISSING_CAPABILITY,
                        missing(capability, name(hostName)));
            }
        }
        return probe(target, hostName);
    }

    /**
     * La <b>dernière</b> vérification, et la seule qui fasse travailler la machine (F-161 /
     * SF-161-04) : le poste exécute-t-il <i>vraiment</i> ? Elle est <b>conditionnelle</b> — sans
     * ping branché, sans cible, ou quand l'exécution vient d'être prouvée, elle ne coûte rien.
     */
    private RunnerDoorVerdict probe(fr.claudegateway.runner.channel.RunnerTarget target,
                                    String hostName) {
        if (ping == null || target == null) {
            return RunnerDoorVerdict.opened();
        }
        fr.claudegateway.runner.ping.RunnerPingVerdict sonde = ping.probe(target, hostName);
        return sonde.mute()
                ? RunnerDoorVerdict.closed(RunnerDoorVerdict.UNRESPONSIVE, sonde.reason())
                : RunnerDoorVerdict.opened();
    }

    /** Le message dit <b>quoi faire</b>, pas seulement ce qui ne va pas. */
    private static String missing(String capability, String hostName) {
        if ("bash".equals(capability)) {
            return "Le runner du poste « " + hostName + " » tourne SANS bash : il ne pourra rien "
                    + "exécuter. Relance-le sans l'option --no-bash, puis renvoie ta demande — rien "
                    + "n'a été dépensé.";
        }
        return "Le runner du poste « " + hostName + " » ne déclare pas la capacité « " + capability
                + " », dont ce tour aurait besoin. Relance-le à jour, puis renvoie ta demande — rien "
                + "n'a été dépensé.";
    }

    /** Depuis quand le poste n'a plus donné signe — pour que le message soit concret. */
    public String sinceLabel(OffsetDateTime lastSeenAt) {
        if (lastSeenAt == null) {
            return "jamais vu";
        }
        long minutes = Duration.between(lastSeenAt, OffsetDateTime.now()).toMinutes();
        if (minutes < 1) {
            return "il y a moins d'une minute";
        }
        return minutes < 60 ? "il y a " + minutes + " min" : "il y a " + (minutes / 60) + " h";
    }

    private static String name(String hostName) {
        return hostName == null || hostName.isBlank() ? "ce poste" : hostName;
    }
}
