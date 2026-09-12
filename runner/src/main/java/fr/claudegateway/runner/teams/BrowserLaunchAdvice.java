package fr.claudegateway.runner.teams;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>Comment lancer le navigateur</b> pour que la liaison devienne possible (F-87 / SF-87-02).
 *
 * <p>Ce fichier est la leçon de F-80 mise en pratique : le 2026-09-12, un message disait qu'il
 * fallait un magasin de confiance sans dire comment en fabriquer un, et il s'est révélé
 * inutilisable. Ici, le remède vient donc avec <b>la ligne de commande exacte</b>, pour le système
 * courant, prête à être collée.</p>
 *
 * <p><b>Deux choses que cette page dit et qu'on découvrirait sinon à ses dépens.</b></p>
 * <ol>
 *   <li><b>Un dossier de profil dédié est nécessaire</b> : le navigateur refuse d'ouvrir son port de
 *       débogage sur le profil par défaut. Sans {@code --user-data-dir}, la commande semble
 *       fonctionner et la liaison ne se fait jamais.</li>
 *   <li><b>Il faut s'y connecter une fois.</b> Ce profil est neuf : Teams y demande l'identification
 *       la première fois, puis la session y reste. Sans cette phrase, on croit à une panne.</li>
 * </ol>
 *
 * <p>Le runner <b>ne lance pas</b> le navigateur lui-même : un navigateur lancé par nous ne
 * porterait pas la session de l'utilisateur, et automatiser son identification transformerait un
 * outil de lecture en autre chose.</p>
 */
public final class BrowserLaunchAdvice {

    /** Adresse ouverte dans la fenêtre reliée. */
    public static final String TEAMS_URL = "https://teams.microsoft.com";

    private BrowserLaunchAdvice() {
    }

    /** Le mode d'emploi complet pour le système courant. */
    public static String forCurrentSystem(int port) {
        return forSystem(OperatingSystem.current(), port);
    }

    /**
     * Le mode d'emploi pour un système donné. Jamais vide : un système inconnu reçoit la forme
     * générique, qui reste applicable.
     */
    public static String forSystem(OperatingSystem system, int port) {
        String nl = System.lineSeparator();
        StringBuilder advice = new StringBuilder();
        advice.append("Le navigateur n'est pas joignable sur 127.0.0.1:").append(port).append('.')
                .append(nl)
                .append("Pour relier Teams, lancez votre navigateur avec le port de débogage — ")
                .append("la commande complète, à coller telle quelle :").append(nl).append(nl)
                .append(commandsFor(system, port)).append(nl)
                .append("Pourquoi un dossier de profil séparé : le navigateur refuse d'ouvrir son ")
                .append("port de débogage sur le profil par défaut.").append(nl)
                .append("Connectez-vous à Teams UNE FOIS dans cette fenêtre : la session y reste, ")
                .append("et le runner s'y rattachera ensuite tout seul.").append(nl)
                .append("Rien de votre session ne remonte : ni cookie, ni jeton. Le runner ")
                .append("OBSERVE ce que cette fenêtre reçoit, il ne s'authentifie jamais à votre ")
                .append("place.");
        return advice.toString();
    }

    private static String commandsFor(OperatingSystem system, int port) {
        String nl = System.lineSeparator();
        switch (system) {
            case WINDOWS:
                return "  Chrome :" + nl
                        + "    \"%ProgramFiles%\\Google\\Chrome\\Application\\chrome.exe\""
                        + " --remote-debugging-port=" + port
                        + " --user-data-dir=\"%LOCALAPPDATA%\\ClaudeGateway\\chrome-teams\" "
                        + TEAMS_URL + nl
                        + "  Edge :" + nl
                        + "    \"%ProgramFiles(x86)%\\Microsoft\\Edge\\Application\\msedge.exe\""
                        + " --remote-debugging-port=" + port
                        + " --user-data-dir=\"%LOCALAPPDATA%\\ClaudeGateway\\edge-teams\" "
                        + TEAMS_URL + nl;
            case MACOS:
                return "  Chrome :" + nl
                        + "    \"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome\""
                        + " --remote-debugging-port=" + port
                        + " --user-data-dir=\"$HOME/Library/Application Support/ClaudeGateway/"
                        + "chrome-teams\" " + TEAMS_URL + nl
                        + "  Edge :" + nl
                        + "    \"/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge\""
                        + " --remote-debugging-port=" + port
                        + " --user-data-dir=\"$HOME/Library/Application Support/ClaudeGateway/"
                        + "edge-teams\" " + TEAMS_URL + nl;
            case LINUX:
                return "  Chrome :" + nl
                        + "    google-chrome --remote-debugging-port=" + port
                        + " --user-data-dir=\"$HOME/.config/claude-gateway/chrome-teams\" "
                        + TEAMS_URL + nl
                        + "  Edge :" + nl
                        + "    microsoft-edge --remote-debugging-port=" + port
                        + " --user-data-dir=\"$HOME/.config/claude-gateway/edge-teams\" "
                        + TEAMS_URL + nl;
            case OTHER:
            default:
                return "  Chrome ou Edge :" + nl
                        + "    <navigateur> --remote-debugging-port=" + port
                        + " --user-data-dir=<dossier de profil dédié> " + TEAMS_URL + nl;
        }
    }

    /** Le navigateur répond, mais aucun onglet Teams n'y est ouvert. */
    public static String openTeamsTab() {
        return "Le navigateur est bien relié, mais aucun onglet Teams n'y est ouvert."
                + System.lineSeparator()
                + "Ouvrez " + TEAMS_URL + " dans CETTE fenêtre — celle qui a été lancée avec le "
                + "port de débogage — puis relancez la demande.";
    }

    /** L'onglet Teams existe, mais la session n'est pas ouverte. */
    public static String signIn() {
        return "L'onglet Teams est ouvert, mais la session ne l'est pas."
                + System.lineSeparator()
                + "Connectez-vous à Teams dans cette fenêtre : c'est à faire une seule fois, la "
                + "session y reste ensuite. Le runner ne s'identifie jamais à votre place.";
    }
}
