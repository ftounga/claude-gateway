package fr.claudegateway.governance.map;

import java.util.List;

/**
 * <b>Le sommaire de ce qu'on sait d'un client</b>, tel qu'il rejoint la consigne système
 * (F-136 / SF-136-02).
 *
 * <p><b>Ce qu'il ne contient pas, et pourquoi.</b> Ni nombre de faits, ni date de dernier apport.
 * Ce bloc vit dans le <b>préfixe stable</b> du prompt : tout octet qui y change invalide le cache de
 * tout ce qui suit — le défaut exact que F-134 vient de corriger (part relue 16-23 % → 99 %). Or ces
 * deux valeurs bougent à <b>chaque</b> tour. Les écrire ici reconstruirait le cache à chaque
 * demande, pour un gain d'information bien moindre que son coût. Elles restent à l'écran.</p>
 *
 * <p>Ne restent donc que les <b>titres</b> — fichiers et sections — qui ne changent que lorsque la
 * carte change de <b>structure</b> : quelques fois par semaine.</p>
 */
public final class HostMapOutline {

    /** Borne du bloc. Au-delà, la coupe se dit plutôt que de tronquer en silence. */
    static final int MAX_CHARS = 4_000;

    /** Sections listées par fichier, au plus : un fichier bavard ne mange pas la place des autres. */
    static final int MAX_SECTIONS_PER_FILE = 12;

    static final String HEADER = """
            --- Ce que tu sais déjà de ce client ---
            Ces fichiers sont à la racine du poste et portent ce qui a été appris de son \
            infrastructure au fil des missions. Avant d'explorer la machine, regarde cette liste : \
            si la réponse s'y trouve, ouvre le fichier concerné plutôt que de partir en exploration.
            """;

    static final String TRUNCATION_NOTICE = "… (sommaire tronqué)\n";

    private HostMapOutline() {
    }

    /**
     * Compose le bloc, ou rend {@code null} s'il n'y a rien à dire.
     *
     * <p><b>Déterministe</b> : deux appels sur la même carte rendent le même texte, à l'octet. C'est
     * ce qui permet au cache de tenir d'un tour à l'autre, et un test le fige.</p>
     */
    public static String of(List<HostMapFile> files) {
        if (files == null || files.isEmpty()) {
            return null;
        }
        StringBuilder block = new StringBuilder(HEADER);
        boolean any = false;
        for (HostMapFile file : files) {
            if (file.getPath() == null || file.getPath().isBlank()) {
                continue;
            }
            any = true;
            block.append("\n").append(file.getPath());
            String title = file.getTitle();
            if (title != null && !title.isBlank() && !title.equals(file.getPath())) {
                block.append(" — ").append(title.strip());
            }
            block.append('\n');
            List<String> sections = sectionsOf(file);
            for (String section : sections.stream().limit(MAX_SECTIONS_PER_FILE).toList()) {
                block.append("  · ").append(section).append('\n');
            }
            if (sections.size() > MAX_SECTIONS_PER_FILE) {
                block.append("  · … et ").append(sections.size() - MAX_SECTIONS_PER_FILE)
                        .append(" autre(s) section(s)\n");
            }
        }
        if (!any) {
            return null;
        }
        block.append("--- fin ---\n\n");
        String result = block.toString();
        return result.length() <= MAX_CHARS
                ? result
                : result.substring(0, MAX_CHARS) + TRUNCATION_NOTICE;
    }

    private static List<String> sectionsOf(HostMapFile file) {
        String sections = file.getSections();
        if (sections == null || sections.isBlank()) {
            return List.of();
        }
        return sections.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }
}
