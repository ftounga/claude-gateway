package fr.claudegateway.atelier.proof;

import java.util.List;
import java.util.Map;

import fr.claudegateway.agent.AgentTool;

/**
 * <b>Les captures de preuve</b> (F-212 / SF-212-03) : l'outil {@value #TOOL} déclaré au modèle sur un
 * terminal servi par un poste ; le runner l'exécute par {@value #RUNNER_TOOL} (capacité
 * {@value #CAPABILITY}).
 *
 * <p><b>Coût (exigence PO)</b> : définition courte et FIXE (préfixe caché) ; les images ne reviennent
 * jamais au modèle — il reçoit un texte borné ({@link ProofCaptureService#modelText}).</p>
 */
public final class ProofToolCatalog {

    public static final String TOOL = "capturer_preuves";
    /** Outil du runner (contrat). */
    public static final String RUNNER_TOOL = "proof_run";
    /** Capacité annoncée par un runner qui sait exécuter une session de preuve. */
    public static final String CAPABILITY = "proof";
    public static final int MAX_COMMANDS = 8;

    /** Message rendu quand le poste n'annonce pas la capacité : un runner antérieur à F-212. */
    public static final String UPDATE_RUNNER_MESSAGE = "Ce poste ne sait pas encore produire de captures "
            + "de preuve : mettez à jour le poste (Forge › Postes › « Mettre à jour »), puis réessayez. "
            + "En attendant, prouve par bash et cite les sorties.";

    /** Message rendu hors poste (terminal hébergé). */
    public static final String HOSTED_MESSAGE = "Les captures de preuve exigent un poste connecté : ce "
            + "terminal est hébergé. Prouve par bash et cite les sorties.";

    private static final AgentTool DEFINITION = new AgentTool(TOOL,
            "Prouve un résultat en images : rejoue tes commandes de vérification à la suite, dans un "
                    + "même shell du poste, et montre le terminal dans le fil (sortie réelle). Utilise-le "
                    + "quand l'utilisateur demande une preuve, ou pour un point annoncé validé si des "
                    + "preuves ont été demandées dans ce sujet ; une fois par point, pas à chaque étape. "
                    + "Ne lance pas ces commandes en bash juste avant. Chaque commande passe par la même "
                    + "autorisation que bash. Rend les identifiants des captures (à citer dans la preuve "
                    + "de l'étape, ou à joindre à un message par preuves=).",
            Map.of("type", "object",
                    "properties", Map.of(
                            "titre", Map.of("type", "string"),
                            "commandes", Map.of("type", "array", "maxItems", MAX_COMMANDS,
                                    "items", Map.of("type", "string")),
                            "etape", Map.of("type", "string", "description",
                                    "Étape du plan prouvée (facultatif).")),
                    "required", List.of("titre", "commandes")));

    private ProofToolCatalog() {
    }

    public static boolean isProofTool(String name) {
        return TOOL.equals(name);
    }

    /** La définition, identique d'un tour à l'autre. */
    public static AgentTool tool() {
        return DEFINITION;
    }
}
