package fr.claudegateway.jarvis.notices;

/**
 * <b>Les phrases des annonces</b> (F-249) — composées par la passerelle, sans IA : ce qui s'affiche (court, chiffré) et
 * ce que Jarvis dit (nombres en lettres). Les noms de sujet sont bornés à 60 caractères.
 */
public final class NoticeTexts {

    private static final String[] WORDS = {"Aucun", "Un", "Deux", "Trois", "Quatre", "Cinq", "Six", "Sept", "Huit",
            "Neuf", "Dix"};
    static final int NAME_MAX = 60;

    private NoticeTexts() {
    }

    /** « Deux » ; au-delà de dix : « 12 ». */
    public static String word(int n) {
        return n >= 0 && n < WORDS.length ? WORDS[n] : String.valueOf(n);
    }

    /** Un nom de sujet ou de client, borné. */
    public static String name(String s) {
        String v = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        return v.length() <= NAME_MAX ? v : v.substring(0, NAME_MAX - 1) + "…";
    }

    /** « 2 mails sur « Alarme Fortec » (EDENRED) » / « Deux nouveaux mails sur « Alarme Fortec », chez EDENRED. » */
    public static JarvisNoticeService.Texts mailsOnSubject(int count, String subject, String client) {
        String s = name(subject);
        String c = name(client);
        String display = count + (count == 1 ? " mail" : " mails") + " sur « " + s + " »"
                + (c.isEmpty() ? "" : " (" + c + ")");
        String speech = word(count) + (count == 1 ? " nouveau mail sur « " : " nouveaux mails sur « ") + s + " »"
                + (c.isEmpty() ? "" : ", chez " + c) + ".";
        return new JarvisNoticeService.Texts(display, speech);
    }
}
