package fr.claudegateway.runner.teams;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>Chaque échec de mise en service est nommé</b> (F-122 / SF-122-04) — jamais un silence.
 *
 * <p>SF-122-01 rend des <b>états bruts</b> ({@link ManagedChrome.State}) ; cette classe les habille en
 * <b>diagnostics actionnables</b>, sur la règle écrite pour F-80/F-87 : le message porte le remède
 * <b>et</b> le moyen de l'appliquer. Trois cas d'entreprise, chacun nommé avec sa marche à suivre :</p>
 * <ul>
 *   <li><b>Chrome absent</b> — installer Chrome, ou déclarer son chemin ({@link ChromePaths#PATH_ENV}).</li>
 *   <li><b>Remote-debugging bloqué par une policy</b> — Chrome démarre mais son port ne s'ouvre pas :
 *       la cause la plus fréquente est une GPO/plist d'entreprise ; repli documenté (Edge via la
 *       surcharge de chemin), sinon la DSI.</li>
 *   <li><b>Proxy NTLM</b> — la JVM ne porte pas l'authentification NTLM du proxy ; on renvoie au relais
 *       local {@code px} (F-59) et aux instructions proxy du système (F-38/F-45).</li>
 * </ul>
 */
public final class VigieServiceDiagnostic {

    private VigieServiceDiagnostic() {
    }

    /** La nature de l'échec de mise en service. */
    public enum Fault {
        /** Rien à signaler. */
        NONE,
        /** Aucun navigateur Chrome/Chromium trouvé sur le poste. */
        CHROME_NOT_FOUND,
        /** Chrome démarre mais son port de débogage ne s'ouvre pas (policy d'entreprise probable). */
        REMOTE_DEBUG_BLOCKED,
        /** Un proxy d'entreprise NTLM avale la liaison. */
        PROXY_NTLM
    }

    /** Un diagnostic nommé : sa nature, un titre, un message portant la marche à suivre. */
    public record Diagnosis(Fault fault, String title, String message) {

        /** Vrai si un défaut est diagnostiqué. */
        public boolean isFault() {
            return fault != Fault.NONE;
        }

        static Diagnosis none() {
            return new Diagnosis(Fault.NONE, "", "");
        }
    }

    /** Le diagnostic correspondant à un état du Chrome managé. */
    public static Diagnosis fromChromeState(ManagedChrome.State state, OperatingSystem system) {
        return switch (state) {
            case REACHABLE, LAUNCHED -> Diagnosis.none();
            case NO_BROWSER -> chromeNotFound(system);
            case UNREACHABLE -> remoteDebugBlocked(system);
        };
    }

    private static Diagnosis chromeNotFound(OperatingSystem system) {
        String nl = System.lineSeparator();
        String message = "Aucun navigateur Chrome ou Chromium n'a été trouvé sur ce poste." + nl
                + "Marche à suivre :" + nl
                + "  1. Installez Google Chrome (ou Chromium)." + nl
                + "  2. Ou, si Chrome est déjà installé ailleurs, déclarez son chemin :" + nl
                + "     " + declareEnv(system, ChromePaths.PATH_ENV, exampleChromePath(system)) + nl
                + "Le runner relancera alors le Chrome managé tout seul.";
        return new Diagnosis(Fault.CHROME_NOT_FOUND, "Chrome introuvable", message);
    }

    private static Diagnosis remoteDebugBlocked(OperatingSystem system) {
        String nl = System.lineSeparator();
        String message = "Chrome a démarré, mais son port de débogage local ne répond pas." + nl
                + "La cause la plus fréquente : une policy d'entreprise (GPO sous Windows, "
                + "profil de configuration sous macOS) qui désactive le remote-debugging de Chrome." + nl
                + "Marche à suivre :" + nl
                + "  1. Vérifiez auprès de la DSI si le remote-debugging de Chrome est autorisé." + nl
                + "  2. Repli : essayez Microsoft Edge (Chromium) en déclarant son chemin — " + nl
                + "     " + declareEnv(system, ChromePaths.PATH_ENV, exampleEdgePath(system)) + nl
                + "  3. Si la policy le bloque aussi, la mise en service Teams n'est pas possible sur "
                + "ce poste sans un assouplissement de la DSI.";
        return new Diagnosis(Fault.REMOTE_DEBUG_BLOCKED,
                "Débogage Chrome bloqué (policy d'entreprise ?)", message);
    }

    /** Le diagnostic d'un proxy d'entreprise NTLM (F-59), avec les instructions du système. */
    public static Diagnosis proxyNtlm(OperatingSystem system) {
        String nl = System.lineSeparator();
        String message = "Un proxy d'entreprise exige une authentification NTLM, que la JVM ne porte "
                + "pas nativement : la liaison est avalée par le proxy." + nl
                + "Marche à suivre :" + nl
                + "  1. Utilisez le relais local px (F-59), qui porte l'authentification NTLM à la "
                + "place de la JVM, puis déclarez-le comme proxy :" + nl
                + "     " + system.declareProxy("http://127.0.0.1:3128") + nl
                + "  2. Pour retrouver le proxy du poste :" + nl
                + system.proxyInstructions();
        return new Diagnosis(Fault.PROXY_NTLM, "Proxy d'entreprise (NTLM)", message);
    }

    /** Comment déclarer une variable d'environnement dans le terminal du système courant. */
    private static String declareEnv(OperatingSystem system, String name, String value) {
        return switch (system) {
            case WINDOWS -> "$env:" + name + "=\"" + value + "\"     (PowerShell)";
            case MACOS, LINUX, OTHER -> "export " + name + "=\"" + value + "\"";
        };
    }

    private static String exampleChromePath(OperatingSystem system) {
        return switch (system) {
            case WINDOWS -> "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
            case MACOS -> "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
            case LINUX, OTHER -> "/usr/bin/google-chrome";
        };
    }

    private static String exampleEdgePath(OperatingSystem system) {
        return switch (system) {
            case WINDOWS -> "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
            case MACOS -> "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge";
            case LINUX, OTHER -> "/usr/bin/microsoft-edge";
        };
    }
}
