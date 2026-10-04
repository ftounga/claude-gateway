package fr.claudegateway.governance.map.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * <b>Le découpage d'un fichier de carte en sections</b> (F-174 / SF-174-02, D3).
 *
 * <p>Une section commence à un titre {@code ##} et court jusqu'au suivant ; les sous-titres
 * {@code ###} restent dans leur section. Ce qui précède le premier {@code ##} forme une section sans
 * titre. Chaque section porte l'empreinte de son texte : c'est ce qui rend l'extraction incrémentale.</p>
 *
 * <p>Un <b>fait</b> est une ligne porteuse : ni titre, ni citation de gabarit ({@code >}), ni
 * séparateur de tableau, ni bloc de code. Le numéro de ligne est celui du fichier (à partir de 1).</p>
 */
public final class HostMapSectionSplitter {

    /** Longueur gardée d'un fait : une ligne de carte, pas un chapitre. */
    static final int MAX_FACT_CHARS = 2_000;

    /** Une ligne porteuse de fait. */
    public record FactLine(int lineNo, String text) {
    }

    /** Une section, son texte, son empreinte et ses faits. */
    public record Section(int ordinal, String heading, String text, String fingerprint,
            List<FactLine> facts) {
    }

    private HostMapSectionSplitter() {
    }

    public static List<Section> split(String content) {
        List<Section> sections = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return sections;
        }
        String[] lines = content.split("\n", -1);
        String heading = null;
        StringBuilder text = new StringBuilder();
        List<FactLine> facts = new ArrayList<>();
        boolean inCode = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (!inCode && trimmed.startsWith("## ") || !inCode && trimmed.equals("##")) {
                add(sections, heading, text, facts);
                heading = trimmed.substring(2).strip();
                text = new StringBuilder();
                facts = new ArrayList<>();
                text.append(line).append('\n');
                continue;
            }
            text.append(line).append('\n');
            if (trimmed.startsWith("```")) {
                inCode = !inCode;
                continue;
            }
            if (inCode || !isFact(trimmed)) {
                continue;
            }
            facts.add(new FactLine(i + 1, trimmed.length() > MAX_FACT_CHARS
                    ? trimmed.substring(0, MAX_FACT_CHARS) + "…"
                    : trimmed));
        }
        add(sections, heading, text, facts);
        return sections;
    }

    private static boolean isFact(String trimmed) {
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(">")) {
            return false;
        }
        if (trimmed.startsWith("|") && trimmed.replaceAll("[|\\-:\\s]", "").isEmpty()) {
            return false; // Séparateur de tableau.
        }
        if (trimmed.equals("---") || trimmed.equals("***")) {
            return false;
        }
        // Une ligne de puce vide (« - ») n'est pas un fait.
        return !trimmed.replaceAll("^[-*+]\\s*", "").isBlank();
    }

    private static void add(List<Section> sections, String heading, StringBuilder text,
            List<FactLine> facts) {
        if (heading == null && text.toString().isBlank()) {
            return; // Pas de préambule.
        }
        String body = text.toString();
        sections.add(new Section(sections.size(), heading, body, fingerprint(body), List.copyOf(facts)));
    }

    static String fingerprint(String text) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
