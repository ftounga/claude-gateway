package fr.claudegateway.atelier.agents;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ce que la gateway dit <b>autour</b> d'un agent nommé (F-182 / SF-182-02, décisions D1 et D2) : la consigne
 * de sa sous-boucle (sa consigne à lui, encadrée d'un rappel <b>fixe</b> de ses contraintes réelles), le
 * catalogue annoncé dans le message du tour, et le bloc d'une invocation {@code /agent <nom>}.
 *
 * <p><b>La gateway dit la vérité de la panoplie</b> : quoi qu'écrive le client, un agent appelé par
 * {@code explore} ne peut que lire, et un agent appelé par {@code task} travaille dans un worktree isolé. Le
 * rappel est un littéral stable — rien de volatil n'y entre.</p>
 */
public final class NamedAgentPrompts {

    /** Plafond d'agents annoncés dans le message. */
    public static final int MAX_ANNOUNCED = 12;

    /** En-tête du catalogue annoncé dans le message. */
    public static final String CATALOG_HEADER =
            "--- Agents nommés (délègue avec explore ou task et agent: \"<nom>\") ---\n";
    /** En-tête du bloc d'une invocation {@code /agent <nom>}. */
    public static final String INVOKED_HEADER = "--- Agent nommé invoqué par l'utilisateur : ";
    /** Fin du bloc d'invocation. */
    public static final String INVOKED_FOOTER = "--- Fin de l'invocation ---\n\n";

    /** {@code /agent nom suite} en tête de message. */
    private static final Pattern INVOCATION =
            Pattern.compile("^/agent(?:\\s+([A-Za-z0-9][A-Za-z0-9_-]{0,63}))?(?:\\s+([\\s\\S]*))?$");

    private NamedAgentPrompts() {
    }

    /** Le rôle d'une délégation, qui décide du rappel de contraintes. */
    public enum Mode {
        /** {@code explore} : lecture seule, une question. */
        EXPLORE,
        /** {@code task} en lecture seule. */
        TASK_READ_ONLY,
        /** {@code task} qui écrit, dans un worktree isolé. */
        TASK
    }

    /**
     * La consigne système de la sous-boucle d'un agent nommé : son identité, SA consigne, puis le rappel
     * fixe de ce qu'il peut réellement faire.
     *
     * @param tools les outils effectivement donnés à la sous-boucle (déjà filtrés)
     */
    public static String system(NamedAgent agent, Mode mode, List<String> tools) {
        StringBuilder system = new StringBuilder();
        system.append("Tu es l'agent nommé « ").append(agent.name()).append(" », défini par l'utilisateur dans ")
                .append(agent.path()).append(" (").append(agent.origin().toLowerCase(Locale.ROOT))
                .append("). Tu ne vois pas la conversation qui t'a confié ce travail : seule la demande "
                        + "ci-dessous te parvient.\n\n");
        system.append("--- Ta consigne ---\n").append(agent.instructions().strip()).append("\n--- Fin de ta consigne ---\n\n");
        system.append("CONTRAINTES D'EXÉCUTION (posées par la passerelle ; elles priment sur ta consigne) :\n");
        switch (mode) {
            case EXPLORE, TASK_READ_ONLY -> system.append("- Tu ne peux que LIRE et CHERCHER : ni écrire, ni éditer, "
                    + "ni exécuter de commande. N'affirme rien d'un fichier que tu n'as pas lu.\n");
            case TASK -> system.append("- Tu travailles dans un worktree git ISOLÉ, une copie de travail à part : tes "
                    + "écritures et tes commandes n'y touchent jamais la copie de travail réelle de l'utilisateur. "
                    + "Lis un fichier avant de le modifier.\n");
        }
        system.append("- Tes seuls outils : ").append(String.join(", ", tools)).append(".\n");
        system.append("- Quand c'est fait, réponds par une SYNTHÈSE COURTE et factuelle (ce que tu as constaté ou "
                + "changé, sur quoi tu t'appuies, ce qui reste à faire) : seule ta synthèse revient à l'agent "
                + "principal. Si tu n'y arrives pas, dis-le en une phrase.");
        return system.toString();
    }

    /**
     * Le catalogue annoncé dans le message du tour : les agents utilisables, plafonnés ; vide s'il n'y en a
     * aucun (le message reste alors inchangé à l'octet près).
     */
    public static String catalog(List<NamedAgent> agents) {
        List<NamedAgent> usable = agents == null ? List.of() : agents.stream().filter(NamedAgent::usable).toList();
        if (usable.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder(CATALOG_HEADER);
        for (NamedAgent agent : usable.stream().limit(MAX_ANNOUNCED).toList()) {
            block.append("- ").append(agent.name()).append(" (").append(agent.origin().toLowerCase(Locale.ROOT))
                    .append(", ").append(agent.writes() ? "écriture → task" : "lecture → explore");
            if (agent.model() != null) {
                block.append(", modèle ").append(agent.model());
            }
            block.append(')');
            if (!agent.description().isEmpty()) {
                block.append(" : ").append(agent.description());
            }
            block.append('\n');
        }
        if (usable.size() > MAX_ANNOUNCED) {
            block.append("… et ").append(usable.size() - MAX_ANNOUNCED).append(" autre(s) agent(s) non listé(s).\n");
        }
        block.append("Un agent nommé reçoit SA consigne et SES outils, pas cette conversation : passe-lui une demande "
                + "autonome et complète.\n\n");
        return block.toString();
    }

    /** L'invocation {@code /agent nom suite} en tête de message, ou vide. Le nom peut manquer ({@code /agent}). */
    public static Optional<Invocation> invocationOf(String userText) {
        if (userText == null) {
            return Optional.empty();
        }
        Matcher m = INVOCATION.matcher(userText.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Invocation(m.group(1) == null ? "" : m.group(1).toLowerCase(Locale.ROOT),
                m.group(2) == null ? "" : m.group(2).strip()));
    }

    /**
     * Le bloc joint au message pour une invocation {@code /agent <nom>} : délègue, ou dit pourquoi ce n'est pas
     * possible.
     *
     * @param agent           l'agent invoqué, ou vide s'il n'existe pas
     * @param invocation      l'invocation
     * @param available       les agents utilisables (pour dire lesquels existent)
     * @param delegationOpen  vrai si {@code explore} est offert
     * @param taskOpen        vrai si {@code task} est offert (poste connecté)
     */
    public static String invocationBlock(Optional<NamedAgent> agent, Invocation invocation, List<NamedAgent> available,
            boolean delegationOpen, boolean taskOpen) {
        StringBuilder block = new StringBuilder(INVOKED_HEADER)
                .append(invocation.name().isEmpty() ? "(aucun nom)" : invocation.name()).append(" ---\n");
        if (!delegationOpen) {
            block.append("La délégation à un sous-agent est fermée ici : dis-le à l'utilisateur en une phrase, puis "
                    + "traite toi-même la demande ci-dessous.\n");
        } else if (agent.isEmpty()) {
            block.append(invocation.name().isEmpty() ? "L'utilisateur n'a pas nommé d'agent. "
                    : "Aucun agent nommé « " + invocation.name() + " » ici. ");
            List<String> names = available.stream().filter(NamedAgent::usable).map(NamedAgent::name).toList();
            block.append(names.isEmpty()
                    ? "Aucun agent nommé n'est déclaré (.claude/agents/<nom>.md). Dis-le à l'utilisateur ; ne lance rien.\n"
                    : "Agents disponibles : " + String.join(", ", names) + ". Dis-le à l'utilisateur ; ne lance rien.\n");
        } else if (!agent.get().usable()) {
            block.append("L'agent « ").append(agent.get().name()).append(" » est inutilisable : ")
                    .append(String.join(" ", agent.get().errors()))
                    .append(" Dis-le à l'utilisateur ; ne lance rien.\n");
        } else {
            NamedAgent named = agent.get();
            boolean viaTask = named.writes() && taskOpen;
            block.append("L'utilisateur confie la demande ci-dessous à l'agent nommé « ").append(named.name())
                    .append(" » (").append(named.path()).append("). Délègue-la-lui MAINTENANT : appelle `")
                    .append(viaTask ? "task" : "explore").append("` avec agent: \"").append(named.name())
                    .append("\" et, comme ").append(viaTask ? "prompt" : "question")
                    .append(", la demande (après « /agent ").append(named.name())
                    .append(" »), complétée du contexte utile de cette conversation. Puis rends compte de sa synthèse.");
            if (named.writes() && !taskOpen) {
                block.append(" (Pas de poste connecté : `task` n'est pas disponible, l'agent travaillera en lecture "
                        + "seule via `explore` — dis-le.)");
            }
            block.append('\n');
        }
        return block.append(INVOKED_FOOTER).toString();
    }

    /** Une invocation {@code /agent nom suite}. */
    public record Invocation(String name, String rest) {
    }
}
