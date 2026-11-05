package fr.claudegateway.jarvis.state.extract;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Une sortie {@code bash} relue</b> (F-247 / SF-247-04) depuis la transcription du tour : la forme rendue au modèle
 * est {@code "$ <commande>\n<sortie>\n[code de sortie: N]"}, bornée à sa fin par le relevé (« … (début tronqué) »).
 *
 * <p>Une sortie <b>tronquée</b> (d'un côté ou de l'autre), sans code de sortie lisible ou en échec n'est <b>pas
 * exploitable</b> : en cas de doute, rien n'est écrit (cadrage §4.2, point 3).</p>
 */
public record BashOutput(String command, String body, Integer exitCode, boolean truncated) {

    private static final Pattern EXIT = Pattern.compile("\\n?\\[code de sortie: (-?\\d+|inconnu)\\]\\s*$");
    static final String HEAD_CUT = "… (début tronqué)";
    static final String TAIL_CUT = "… (sortie tronquée)";

    /** Relit une sortie ; {@code command} est celle du bloc (repli si la sortie a perdu sa première ligne). */
    public static BashOutput parse(String blockCommand, String output) {
        if (output == null) {
            return new BashOutput(blockCommand, "", null, false);
        }
        String text = output;
        boolean truncated = text.startsWith(HEAD_CUT) || text.contains(TAIL_CUT);
        Integer exit = null;
        Matcher m = EXIT.matcher(text);
        if (m.find()) {
            exit = "inconnu".equals(m.group(1)) ? null : Integer.valueOf(m.group(1));
            text = text.substring(0, m.start());
        }
        String command = blockCommand;
        if (blockCommand != null && blockCommand.contains("\n") && text.startsWith("$ " + blockCommand)) {
            // SF-247-17 — commande sur plusieurs lignes (script en ligne, heredoc) : son écho entier n'est pas la sortie.
            text = text.substring(2 + blockCommand.length());
            text = text.startsWith("\n") ? text.substring(1) : text;
        } else if (text.startsWith("$ ")) {
            int nl = text.indexOf('\n');
            command = nl < 0 ? text.substring(2) : text.substring(2, nl);
            text = nl < 0 ? "" : text.substring(nl + 1);
        }
        return new BashOutput(command == null ? "" : command.strip(), text.strip(), exit, truncated);
    }

    /** Exploitable : code 0 lu, rien de tronqué, une sortie non vide. */
    public boolean usable() {
        return exitCode != null && exitCode == 0 && !truncated && !body.isBlank();
    }
}
