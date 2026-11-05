package fr.claudegateway.atelier.veille;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;

/**
 * <b>L'agent propose une veille Jarvis</b> (F-230 / SF-230-01, D1/D2) — et la garde qui décide où l'outil est
 * donné : le terminal d'un <b>sujet sur poste</b>, à son propriétaire (même garde que les routines).
 *
 * <p>L'outil <b>n'active rien</b> : il range une proposition que le PO teste puis active d'une carte. La définition
 * est un <b>littéral stable</b> (cache F-134) : aucune donnée volatile n'y entre. Elle porte aussi la consigne de
 * SF-230-04 — proposer une veille en une phrase à l'ouverture d'un chantier — pour ne toucher le préfixe qu'une
 * fois.</p>
 */
@Component
public class JarvisWatchToolCatalog {

    /** Nom de l'outil. */
    public static final String PROPOSE = "veille_poser";

    private static final AgentTool PROPOSE_TOOL = new AgentTool(PROPOSE,
            "Pose une VEILLE JARVIS pour ce sujet, à la demande du PO seulement (« mets-moi un suivi Jarvis sur ce "
                    + "chantier, via les tickets Jira de tel tableau »). Une veille lance, aux heures données par le "
                    + "PO, du lundi au vendredi, une SONDE en lecture seule sur ce poste, sans IA ; seul un "
                    + "changement est ensuite résumé au PO. AVANT d'appeler cet outil : 1) écris la sonde dans le "
                    + "dossier du sujet (ex. .atelier/veilles/<nom>.sh ou .py) — appel REST en GET ou GraphQL sans "
                    + "mutation, jeton lu dans un fichier ou une variable du poste, JAMAIS écrit en clair — qui "
                    + "écrit sur la sortie standard UNIQUEMENT une liste JSON [{\"id\",\"titre\",\"statut\","
                    + "\"assigne\",\"mis_a_jour\",\"dernier_commentaire\"}] (500 éléments au plus, rien d'autre ; "
                    + "erreurs sur stderr) ; 2) lance-la une fois et dis au PO ce que tu vois (« 12 tickets : 5 en "
                    + "cours, 2 bloqués… »). La veille N'EST PAS active : le PO la teste et l'active d'une carte. "
                    + "Pour réparer une veille en panne, rappelle l'outil avec le MÊME nom. À l'ouverture d'un "
                    + "chantier sur un client suivi par une plateforme de tickets, tu peux PROPOSER une veille en "
                    + "une phrase ; ne la pose qu'après l'accord du PO. RELEVÉ D'ÉTAT (usage « etat ») : une sonde "
                    + "de relevé ne fait que lire l'état (tickets, MR, pipelines, déploiements…) pour que Jarvis le "
                    + "sache poste éteint ; elle ne prévient jamais et n'utilise aucune IA ; heures facultatives "
                    + "(toutes les heures de 8 h à 20 h par défaut) ; seuls les verbes de lecture sont admis (get, "
                    + "list, view, describe, status, show, log, output, history).",
            Map.of("type", "object",
                    "properties", Map.of(
                            "nom", Map.of("type", "string", "description", "Nom court (80 caractères au plus)."),
                            "plateforme", Map.of("type", "string", "description", "Jira, Monday, ClickUp…"),
                            "perimetre", Map.of("type", "string",
                                    "description", "Ce que la veille regarde et ignore, en clair (500 caractères au "
                                            + "plus)."),
                            "commande", Map.of("type", "string",
                                    "description", "La ligne lancée sur le poste depuis le dossier du sujet (ex. "
                                            + "« bash .atelier/veilles/agenor.sh »)."),
                            "script", Map.of("type", "string",
                                    "description", "Chemin relatif du script de sonde appelé par la commande, s'il y "
                                            + "en a un : il est relu et épinglé."),
                            "heures", Map.of("type", "array", "items", Map.of("type", "string"),
                                    "description", "Heures de passage HH:mm (1 à 6), heure du poste, données par le "
                                            + "PO. Jours : lundi→vendredi, imposés."),
                            "chantier", Map.of("type", "integer",
                                    "description", "Numéro du chantier suivi, s'il y en a un."),
                            "usage", Map.of("type", "string", "enum", List.of("alerte", "etat"),
                                    "description", "alerte (défaut) : une veille qui prévient ; etat : un relevé "
                                            + "d'état silencieux pour le registre de Jarvis.")),
                    "required", List.of("nom", "plateforme", "perimetre", "commande")));

    /** Vrai si ce nom est l'outil de ce catalogue. */
    public static boolean isWatchTool(String name) {
        return PROPOSE.equals(name);
    }

    /** Vrai si l'outil est ouvert dans ce terminal : sujet sur poste, terminal de son propriétaire. */
    public boolean isOpenFor(UUID userId, Workspace workspace) {
        return userId != null && workspace != null && userId.equals(workspace.getUserId())
                && JarvisWatchService.isWatchCapable(workspace);
    }

    /** Les outils à déclarer dans ce terminal (vide ailleurs). */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        return isOpenFor(userId, workspace) ? List.of(PROPOSE_TOOL) : List.of();
    }
}
