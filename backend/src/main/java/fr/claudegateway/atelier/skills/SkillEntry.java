package fr.claudegateway.atelier.skills;

/**
 * Un skill invocable par {@code /nom} (F-177 / SF-177-03).
 *
 * @param name        le nom à taper après {@code /} (minuscules, chiffres, tirets, soulignés)
 * @param path        son fichier, relatif à la racine de son origine
 * @param description sa description (en-tête {@code description:} ou première ligne), ou vide
 * @param origin      {@code SUJET} (dossier du projet) ou {@code POSTE} (racine du poste)
 */
public record SkillEntry(String name, String path, String description, String origin) {

    public static final String SUJET = "SUJET";
    public static final String POSTE = "POSTE";
}
