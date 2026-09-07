package fr.claudegateway.atelier;

import java.util.Locale;
import java.util.Optional;

/**
 * Genre d'interpréteur déclaré par le runner dans sa trame {@code ready} (F-38 / SF-38-27).
 *
 * <p>En cible {@code RUNNER}, {@code list_files} et {@code search_files} ne sont pas déclarés au
 * modèle (SF-39-05) : {@code bash} est son <b>seul</b> moyen d'explorer. La consigne système lui
 * dicte donc une syntaxe — et jusqu'ici elle dictait {@code ls}, {@code find} et {@code grep -n} y
 * compris sur un poste où l'interpréteur était {@code cmd.exe}, qui n'en connaît aucun. Chaque
 * exploration y revenait en erreur.</p>
 *
 * <p><b>Liste blanche stricte</b> (décision D6) : la valeur vient d'un client. Elle n'est jamais
 * affichée telle quelle ni concaténée dans une commande — elle ne sert qu'à choisir entre trois
 * textes écrits en dur ici. Une valeur inconnue n'est pas enregistrée, et la consigne garde son
 * texte POSIX.</p>
 */
public enum RunnerShell {

    /** Shell POSIX — Unix, ou Git Bash sous Windows. Le cas nominal. */
    POSIX("posix",
            "Explore avec bash (ls, find, grep -n) — c'est le bon outil pour lister, chercher et "
                    + "vérifier."),

    /** PowerShell — le poste Windows sans bash. */
    POWERSHELL("powershell",
            "L'interpréteur de cette machine est PowerShell, pas un shell Unix : explore avec "
                    + "Get-ChildItem -Recurse et Select-String -Path … -Pattern … (et non ls, find "
                    + "ou grep, qui n'y sont pas). Les chemins s'y écrivent avec des antislashs."),

    /** {@code cmd.exe} — le repli, quand la machine n'a ni bash ni PowerShell. */
    CMD("cmd",
            "L'interpréteur de cette machine est cmd.exe : il n'y a ni ls, ni grep, ni find au sens "
                    + "Unix. Liste avec dir /s /b et cherche avec findstr /s /n /c:\"motif\" *.*. "
                    + "Les chemins s'y écrivent avec des antislashs.");

    private final String declared;
    private final String explorationGuidance;

    RunnerShell(String declared, String explorationGuidance) {
        this.declared = declared;
        this.explorationGuidance = explorationGuidance;
    }

    /** Valeur telle qu'elle circule dans la trame {@code ready} et telle qu'elle est stockée. */
    public String declared() {
        return declared;
    }

    /** Phrase d'exploration insérée dans la consigne système, propre à cet interpréteur. */
    public String explorationGuidance() {
        return explorationGuidance;
    }

    /**
     * Genre correspondant à une valeur déclarée, s'il est reconnu.
     *
     * @return vide si la valeur est nulle, vide ou hors liste blanche — jamais d'exception
     */
    public static Optional<RunnerShell> fromDeclared(String declared) {
        if (declared == null || declared.isBlank()) {
            return Optional.empty();
        }
        String normalized = declared.trim().toLowerCase(Locale.ROOT);
        for (RunnerShell shell : values()) {
            if (shell.declared.equals(normalized)) {
                return Optional.of(shell);
            }
        }
        return Optional.empty();
    }

    /**
     * Genre à utiliser pour construire une consigne : celui qui est déclaré, {@link #POSIX} sinon.
     *
     * <p>Le repli est POSIX et non {@code cmd.exe} : un projet sans déclaration est soit sans
     * runner, soit servi par un runner antérieur à SF-38-27 — et dans ce dernier cas le seul poste
     * qui exécutait quoi que ce soit d'utile était un poste Unix.</p>
     */
    public static RunnerShell resolve(String declared) {
        return fromDeclared(declared).orElse(POSIX);
    }
}
