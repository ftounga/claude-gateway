package fr.claudegateway.runner;

import java.util.Locale;

/**
 * Système d'exploitation courant, et <b>gestes de configuration du proxy</b> qui lui correspondent
 * (F-38 / SF-38-25).
 *
 * <p>Le runner <b>dit où regarder</b> ; il ne va pas chercher (D2). Lire le registre Windows ou
 * interpréter un fichier PAC reviendrait à exécuter la configuration réseau du poste, avec les
 * décisions de sécurité que cela suppose.</p>
 */
public enum OperatingSystem {

    WINDOWS,
    MACOS,
    LINUX,
    /** Tout le reste : les gestes génériques valent mieux que rien (critère d'acceptation). */
    OTHER;

    /** Système courant, déduit de {@code os.name}. */
    public static OperatingSystem current() {
        return from(System.getProperty("os.name"));
    }

    static OperatingSystem from(String osName) {
        if (osName == null) {
            return OTHER;
        }
        String name = osName.toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return MACOS;
        }
        if (name.contains("nux") || name.contains("nix") || name.contains("aix")) {
            return LINUX;
        }
        return OTHER;
    }

    /**
     * Comment retrouver le proxy du poste, puis le déclarer dans le terminal courant.
     *
     * <p>Jamais vide : un système inconnu reçoit les variables d'environnement, qui sont ce que le
     * runner lit réellement.</p>
     */
    public String proxyInstructions() {
        String nl = System.lineSeparator();
        switch (this) {
            case WINDOWS:
                return "Sur Windows — retrouver le proxy :" + nl
                        + "  netsh winhttp show proxy" + nl
                        + "  reg query \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\"
                        + "Internet Settings\" /v ProxyServer" + nl
                        + "  reg query \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\"
                        + "Internet Settings\" /v AutoConfigURL" + nl
                        + "Puis, dans ce terminal :" + nl
                        + "  set HTTPS_PROXY=http://hote:port        (invite de commandes)" + nl
                        + "  $env:HTTPS_PROXY=\"http://hote:port\"     (PowerShell)" + nl
                        + "  export HTTPS_PROXY=http://hote:port     (Git Bash)" + nl
                        + nl
                        + "Si AutoConfigURL renvoie un fichier .pac, ouvrez-le dans le navigateur :"
                        + nl
                        + "l'adresse du proxy y est ecrite en clair, sur une ligne PROXY hote:port.";
            case MACOS:
                return "Sur macOS — retrouver le proxy :" + nl
                        + "  scutil --proxy" + nl
                        + "Puis, dans ce terminal :" + nl
                        + "  export HTTPS_PROXY=http://hote:port" + nl
                        + "  export HTTP_PROXY=http://hote:port";
            case LINUX:
                return "Sur Linux — voir ce qui est deja declare :" + nl
                        + "  env | grep -i proxy" + nl
                        + "Puis, dans ce terminal :" + nl
                        + "  export HTTPS_PROXY=http://hote:port" + nl
                        + "  export HTTP_PROXY=http://hote:port";
            case OTHER:
            default:
                return "Declarez le proxy dans ce terminal :" + nl
                        + "  HTTPS_PROXY=http://hote:port" + nl
                        + "  HTTP_PROXY=http://hote:port" + nl
                        + "Ce sont les variables que le runner lit, avec NO_PROXY.";
        }
    }
}
