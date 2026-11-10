package fr.claudegateway.jarvis.fastbrain;

import java.util.ArrayList;
import java.util.List;

import fr.claudegateway.jarvis.universe.JarvisUniverse;

/**
 * <b>Une vue de l'univers, vérifiée avant d'être montrée</b> (F-253 / SF-253-02, cadrage §3 #1 et #4) : l'action
 * {@code univers} de l'outil {@code afficher} est confrontée à l'univers <b>du compte</b> (déjà filtré par
 * {@code user_id}) avant d'être relayée. Le modèle reçoit ce qui va <b>vraiment</b> apparaître (« affiché : 2 clients —
 * CAGIP, Edenred »), ou le refus et sa raison (client inconnu, rien à montrer) — jamais un « ok » de principe. Pur.
 *
 * <p>Mêmes règles que l'écran ({@code resolveShow}, {@code scenes/jarvis-show.ts}) : un client restreint la vue ; une
 * vue vide (aucun chantier bloqué, rien à envoyer) n'est pas relayée.</p>
 */
public final class JarvisUniverseView {

    /** Noms cités au plus dans le résultat rendu au modèle. */
    static final int MAX_NAMES = 8;

    private JarvisUniverseView() {
    }

    /** Le verdict : l'ordre à relayer (client rapproché au nom exact de l'univers) ou {@code null}, et le résultat. */
    public record Check(JarvisShowCommand command, String result) {
    }

    /** Vérifie {@code command} (action {@code univers}) contre {@code universe} ; {@code null} : univers illisible. */
    public static Check check(JarvisShowCommand command, JarvisUniverse universe) {
        if (universe == null) {
            return new Check(command, "affiché : vue " + command.view() + " (univers non relu)");
        }
        List<JarvisUniverse.Client> clients = universe.clients();
        String clientName = null;
        if (command.client() != null) {
            JarvisNameMatch.Result<JarvisUniverse.Client> m = JarvisNameMatch.match(command.client(), clients,
                    JarvisUniverse.Client::name);
            if (m.found() == null) {
                String known = String.join(", ", clients.stream().map(JarvisUniverse.Client::name).toList());
                return new Check(null, m.ambiguous().isEmpty()
                        ? "refusé : client « " + command.client() + " » inconnu ; clients : " + known
                        : "refusé : client « " + command.client() + " » ambigu ; clients : " + known);
            }
            clients = List.of(m.found());
            clientName = m.found().name();
        }
        List<String> names = new ArrayList<>();
        String what;
        int pending = 0;
        switch (command.view()) {
            case "clients" -> {
                clients.forEach(c -> names.add(c.name()));
                what = plural(names.size(), "client");
            }
            case "connectes", "deconnectes" -> {
                boolean on = "connectes".equals(command.view());
                clients.stream().filter(c -> !c.virtual() && c.connected() == on).forEach(c -> names.add(c.name()));
                long hosts = clients.stream().filter(c -> !c.virtual()).count();
                what = plural(names.size(), "poste") + (on ? " connecté" : " déconnecté")
                        + (names.size() > 1 ? "s" : "") + " sur " + hosts;
            }
            case "sujets" -> {
                for (JarvisUniverse.Client c : clients) {
                    c.subjects().stream().filter(s -> !s.hostTerminal())
                            .forEach(s -> names.add(s.name() + " (" + c.name() + ")"));
                }
                what = plural(names.size(), "sujet");
            }
            case "chantiers-bloques", "etapes-en-cours" -> {
                boolean blocked = "chantiers-bloques".equals(command.view());
                for (JarvisUniverse.Client c : clients) {
                    for (JarvisUniverse.Subject s : c.subjects()) {
                        for (JarvisUniverse.Chantier ch : s.chantiers()) {
                            if (blocked ? !ch.waitingOn().isEmpty() : ch.step() != null) {
                                names.add("chantier " + ch.number() + " de " + s.name()
                                        + (blocked ? " (attend " + String.join(", ", ch.waitingOn()) + ")" : ""));
                            }
                        }
                    }
                }
                what = blocked ? plural(names.size(), "chantier") + " bloqué" + (names.size() > 1 ? "s" : "")
                        : plural(names.size(), "étape") + " en cours";
            }
            case "a-envoyer" -> {
                for (JarvisUniverse.Client c : clients) {
                    c.toSend().forEach(r -> names.add(r + " (" + c.name() + ")"));
                }
                // Comme l'écran : la liste peut être tronquée, le compteur de l'univers fait foi sans client nommé.
                pending = clientName == null ? Math.max(names.size(), universe.toSendCount()) : names.size();
                what = plural(pending, "message") + " à envoyer";
            }
            default -> {
                return new Check(null, "refusé : vue inconnue");
            }
        }
        boolean emptyAllowed = "connectes".equals(command.view()) || "deconnectes".equals(command.view());
        if (names.isEmpty() && !emptyAllowed && pending == 0) {
            return new Check(null, "rien à montrer : aucun" + (command.view().equals("etapes-en-cours") ? "e" : "")
                    + " " + emptyNoun(command.view()) + (clientName == null ? "" : " chez " + clientName)
                    + " ; rien n'est affiché");
        }
        JarvisShowCommand relayed = new JarvisShowCommand(command.action(), command.pieces(), null, null, null, null,
                null, null, null, command.reason(), command.view(), clientName);
        String listed = names.isEmpty() ? "" : " — " + String.join(", ", names.subList(0, Math.min(MAX_NAMES,
                names.size()))) + (names.size() > MAX_NAMES ? " et " + (names.size() - MAX_NAMES) + " autres" : "");
        return new Check(relayed, "affiché : " + what + (clientName == null ? "" : " chez " + clientName) + listed);
    }

    private static String emptyNoun(String view) {
        return switch (view) {
            case "clients" -> "client";
            case "sujets" -> "sujet";
            case "chantiers-bloques" -> "chantier bloqué";
            case "etapes-en-cours" -> "étape en cours";
            default -> "message à envoyer";
        };
    }

    private static String plural(int n, String noun) {
        return n + " " + noun + (n > 1 ? "s" : "");
    }
}
