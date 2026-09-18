package fr.claudegateway.governance.control;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.governance.GovernanceControl;

/**
 * <b>Contrôle retiré : la tenue de la carte ne se déclare plus</b> (F-125 / SF-125-06b).
 *
 * <p>Ce contrôle exigeait autrefois un <b>marqueur de fin de tour</b> posé par le modèle, et
 * sanctionnait son absence. Le cadrage de F-125-06 a établi que cette exigence était la <b>cause
 * racine</b> du défaut CAGIP : obligé de déclarer sa comptabilité à chaque tour, le modèle répondait
 * « rien à ranger / ce tour n'était qu'un conseil » là où on attendait une réponse de fond.</p>
 *
 * <p><b>Le suivi de la promotion est désormais un effet de bord serveur.</b> Il ne repose plus sur
 * une déclaration du modèle mais sur les <b>écritures de fichiers réelles</b> : {@code
 * GovernanceMapGrowth} constate le delta de faits d'une carte à la lecture, et {@code
 * JugeIndependantControl} audite les fichiers écrits pour rattraper un durable oublié — un modèle qui
 * oublie de ranger oublierait aussi de le déclarer. La dette, elle, reste comptée et signalée par
 * {@code IntegritePosteControl}, sans jamais renvoyer le modèle au travail.</p>
 *
 * <p><b>Pourquoi le garder dans le paquet.</b> Il reste <b>déclaré</b> — identité de paquet stable,
 * aucune rupture pour un poste déjà activé qui le référence — mais il ne fait plus qu'un {@link
 * AtelierCheckpointVerdict#proceed()} : il ne lit plus rien et ne sanctionne plus rien.</p>
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
        return "Retiré (F-125 / SF-125-06b) : ne sanctionne plus l'absence de marqueur de fin de "
                + "tour. Le suivi de la promotion est un effet de bord serveur (écritures de "
                + "fichiers), porté par le juge indépendant et l'intégrité du poste.";
    }

    @Override
    public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
        // Plus aucune dépendance à un marqueur émis par le modèle (F-125 / SF-125-06b).
        return AtelierCheckpointVerdict.proceed();
    }
}
