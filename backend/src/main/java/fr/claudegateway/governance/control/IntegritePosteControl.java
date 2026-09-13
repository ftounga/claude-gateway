package fr.claudegateway.governance.control;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.governance.GovernanceControl;
import fr.claudegateway.governance.integrite.IntegriteInspection;
import fr.claudegateway.governance.integrite.IntegriteMemo;
import fr.claudegateway.governance.integrite.IntegriteRapport;

/**
 * <b>L'intégrité du poste</b>, branchée en fin de tour (F-95 / SF-95-03).
 *
 * <p>C'est l'équivalent d'{@code infra-doctor} du prompt d'origine, remis à son lecteur : un
 * <b>modèle qui doit corriger</b>. Le rapport de SF-95-02 arrive ici, et seules ses <b>erreurs</b>
 * refusent la fin du tour — les avertissements voyagent dans le même message, sous leur propre
 * intitulé, et ne bloquent rien. C'est l'exigence littérale de la feature : deux niveaux, jamais
 * mélangés.</p>
 *
 * <p><b>Il ne coûte que quand il peut servir.</b> C'est le marqueur {@code .infra-dirty} du prompt,
 * transposé comme F-94 l'a fait : l'inspection ne part <b>que si le tour a écrit</b>, et une
 * mémoire empêche de reposer la même question au passage suivant. Sans ces deux gardes, un tour
 * refusé ferait partir jusqu'à trois inspections — et une inspection, ce sont des allers-retours sur
 * la machine d'un client.</p>
 *
 * <p><b>Où le prompt et le produit divergent, et pourquoi.</b> Le prompt branche son doctor
 * <b>après chaque écriture</b>. Ici, une écriture ne coûte rien à celui qui la fait : le contrôle
 * lirait la machine à chaque {@code write_file}, soit des dizaines d'inspections par tour pour un
 * poste dont l'état n'aura pas bougé entre deux. Le point d'accroche retenu est donc la <b>fin de
 * tour</b> — le moment où le modèle croit avoir fini, et le seul où une correction a encore un sens
 * avant que la réponse parte.</p>
 *
 * <p><b>Il ne prend jamais un message en otage</b> : une inspection qui lève laisse passer (F-50,
 * décision D2 — un contrôle cassé ne condamne pas le projet de quelqu'un), un rapport
 * <b>silencieux</b> laisse passer, et F-50 rend la main après un nombre fixe de refus.</p>
 */
@Component
public class IntegritePosteControl implements GovernanceControl {

    private static final Logger log = LoggerFactory.getLogger(IntegritePosteControl.class);

    /** Identifiant cité par les paquets. Immuable : un paquet publié le référence. */
    public static final String ID = "integrite-du-poste";

    private final IntegriteInspection inspection;
    private final IntegriteMemo memo;

    public IntegritePosteControl(IntegriteInspection inspection, IntegriteMemo memo) {
        this.inspection = inspection;
        this.memo = memo;
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
        return "Inspecte le poste quand le tour a écrit — carte présente et structurée, un "
                + "STATE.md par projet, dette et clôture, projet jamais un dépôt git, note perso "
                + "chez un client, liens morts — et refuse la fin du tour sur les erreurs.";
    }

    @Override
    public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
        if (context == null || context.userId() == null || context.workspaceId() == null) {
            return AtelierCheckpointVerdict.proceed();
        }
        List<String> ecrits = context.writtenPaths();
        if (ecrits.isEmpty()) {
            // Le tour n'a rien écrit : le poste n'a pas pu changer. Aucun appel.
            return AtelierCheckpointVerdict.proceed();
        }
        if (memo.dejaInspecte(context.userId(), context.workspaceId(), ecrits)) {
            return AtelierCheckpointVerdict.proceed();
        }
        IntegriteRapport rapport = inspecte(context);
        memo.retenir(context.userId(), context.workspaceId(), ecrits);
        if (!rapport.bloque()) {
            // Rien à signaler, rien d'inspecté, ou des avertissements seuls : on ne bloque pas.
            // Les avertissements ont leur lecteur — la carte du poste (SF-95-03).
            return AtelierCheckpointVerdict.proceed();
        }
        return AtelierCheckpointVerdict.block(rapport.correction());
    }

    /** Inspecte sans jamais laisser une défaillance casser le tour de quelqu'un. */
    private IntegriteRapport inspecte(AtelierCheckpointContext context) {
        try {
            return inspection.deProjet(context.userId(), context.workspaceId());
        } catch (RuntimeException ex) {
            log.debug("Intégrité du poste ignorée ({})", ex.getClass().getSimpleName());
            return IntegriteRapport.silencieux();
        }
    }
}
