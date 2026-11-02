package fr.claudegateway.jarvis.portrait;

import java.util.Locale;

/**
 * <b>Les sept sections fixes du portrait</b> (F-241 / SF-241-01, D1). Chacune est un texte libre, daté. Deux sont
 * <b>sensibles par défaut</b> (l'entourage, ce qui compte en ce moment) : elles guident le tact de Jarvis.
 */
public enum PortraitSection {
    QUI("Qui vous êtes", false),
    ENTOURAGE("Votre entourage", true),
    MOMENT("Ce qui compte en ce moment", true),
    GOUTS("Vos goûts", false),
    HUMOUR("L'humour qui vous plaît", false),
    INTERDITS("À ne pas aborder", false),
    DATES("Dates qui comptent", false);

    private final String title;
    private final boolean sensitive;

    PortraitSection(String title, boolean sensitive) {
        this.title = title;
        this.sensitive = sensitive;
    }

    public String title() {
        return title;
    }

    public boolean sensitive() {
        return sensitive;
    }

    /** Lecture stricte d'une clé (insensible à la casse) ; inconnue ⇒ {@code null}. */
    public static PortraitSection parse(String key) {
        if (key == null) {
            return null;
        }
        try {
            return valueOf(key.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
