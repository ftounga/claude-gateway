package fr.claudegateway.jarvis.calendar;

/**
 * Ce qui est scellé d'une occurrence (F-243, D13) : le titre, le lieu et une description courte. Ce sont des
 * <b>données non fiables</b> (D12) : elles ne sont jamais interprétées comme une consigne.
 *
 * @param title       titre (≤ 200 caractères)
 * @param location    lieu (≤ 200), vide si absent
 * @param description description courte (≤ 300), vide si absente
 */
public record EventText(String title, String location, String description) {

    static final int MAX_TITLE = 200;
    static final int MAX_LOCATION = 200;
    static final int MAX_DESCRIPTION = 300;

    public EventText {
        title = clip(title, MAX_TITLE);
        location = clip(location, MAX_LOCATION);
        description = clip(description, MAX_DESCRIPTION);
    }

    static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max - 1).strip() + "…";
    }
}
