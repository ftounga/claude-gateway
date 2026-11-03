package fr.claudegateway.jarvis.mail;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;

/**
 * <b>Le texte d'un courrier</b> (F-242) — extrait borné pour le cache (SF-242-02, 2 000 caractères) ou corps borné pour
 * une lecture (SF-242-05, ≈ 8 000), sans jamais télécharger plus que nécessaire : la partie texte est lue en flux et
 * la lecture s'arrête à la borne. Texte brut d'abord ; sinon le HTML est réduit au texte (scripts, styles et images
 * retirés, aucune ressource chargée). Une pièce jointe n'est jamais ouverte (D9 e).
 */
public final class MailText {

    private static final Pattern SCRIPTS = Pattern.compile("(?is)<(script|style|head|title)[^>]*>.*?</\\1\\s*>");
    private static final Pattern COMMENTS = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern BREAKS = Pattern.compile("(?i)<\\s*(br|/p|/div|/li|/tr|/h[1-6])\\b[^>]*>");
    private static final Pattern TAGS = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\u00A0]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n+");

    private MailText() {
    }

    /** Le texte du courrier, borné à {@code max} caractères (texte brut d'abord, sinon HTML réduit). */
    public static String text(Part part, int max) {
        try {
            String plain = find(part, "text/plain", max);
            if (plain != null && !plain.isBlank()) {
                return clip(normalize(plain), max);
            }
            String html = find(part, "text/html", max * 4);
            return html == null ? "" : clip(normalize(htmlToText(html)), max);
        } catch (MessagingException | IOException | RuntimeException e) {
            return "";
        }
    }

    /** Vrai si le courrier porte une invitation ({@code text/calendar} ou un {@code .ics}), sans la télécharger. */
    public static boolean hasCalendar(Part part) {
        try {
            if (part.isMimeType("text/calendar")) {
                return true;
            }
            String name = part.getFileName();
            if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".ics")) {
                return true;
            }
            if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart mp) {
                for (int i = 0; i < mp.getCount(); i++) {
                    if (hasCalendar(mp.getBodyPart(i))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (MessagingException | IOException | RuntimeException e) {
            return false;
        }
    }

    /** HTML → texte : scripts, styles, commentaires et balises retirés ; quelques entités décodées. */
    public static String htmlToText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String s = COMMENTS.matcher(html).replaceAll(" ");
        s = SCRIPTS.matcher(s).replaceAll(" ");
        s = BREAKS.matcher(s).replaceAll("\n");
        s = TAGS.matcher(s).replaceAll(" ");
        return s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String find(Part part, String mime, int max) throws MessagingException, IOException {
        if (isAttachment(part)) {
            return null;
        }
        if (part.isMimeType(mime)) {
            return read(part, max);
        }
        if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart child = mp.getBodyPart(i);
                String found = find(child, mime, max);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isAttachment(Part part) throws MessagingException {
        return Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition());
    }

    /** Lit au plus {@code max} caractères du flux décodé : on n'en télécharge pas davantage. */
    private static String read(Part part, int max) throws MessagingException, IOException {
        Charset charset = charset(part);
        try (InputStream in = part.getInputStream(); Reader reader = new InputStreamReader(in, charset)) {
            char[] buffer = new char[Math.min(max, 8192)];
            StringBuilder out = new StringBuilder();
            int n;
            while (out.length() < max && (n = reader.read(buffer, 0, Math.min(buffer.length, max - out.length()))) > 0) {
                out.append(buffer, 0, n);
            }
            return out.toString();
        }
    }

    private static Charset charset(Part part) {
        try {
            String name = new ContentType(part.getContentType()).getParameter("charset");
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
        } catch (MessagingException | RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String normalize(String text) {
        String s = text.replace("\r\n", "\n").replace('\r', '\n');
        s = SPACES.matcher(s).replaceAll(" ");
        s = BLANK_LINES.matcher(s).replaceAll("\n\n");
        return s.strip();
    }

    static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
