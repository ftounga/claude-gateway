package fr.claudegateway.atelier.skills;

/**
 * Un skill invocable par {@code /nom} (F-177 / SF-177-03).
 *
 * @param name        le nom à taper après {@code /} (minuscules, chiffres, tirets, soulignés)
 * @param path        son fichier, relatif à la racine de son origine
 * @param description sa description (en-tête {@code description:} ou première ligne), ou vide
 * @param origin      {@code SUJET} (dossier du projet) ou {@code POSTE} (racine du poste)
 * @param launch      ce que son en-tête déclare pour être lancé d'un clic (F-275 / SF-275-01), calculé sur le
 *                    contenu complet ; {@link SkillLaunch#NONE} si rien n'est déclaré ou si le contenu n'a pas été lu
 */
public record SkillEntry(String name, String path, String description, String origin, SkillLaunch launch) {

    public static final String SUJET = "SUJET";
    public static final String POSTE = "POSTE";

    public SkillEntry {
        launch = launch == null ? SkillLaunch.NONE : launch;
    }

    /** Un skill sans déclaration de lancement (formes antérieures à F-275). */
    public SkillEntry(String name, String path, String description, String origin) {
        this(name, path, description, origin, SkillLaunch.NONE);
    }
}
