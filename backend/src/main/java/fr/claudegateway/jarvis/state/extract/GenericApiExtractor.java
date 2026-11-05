package fr.claudegateway.jarvis.state.extract;

import static fr.claudegateway.jarvis.state.JarvisStateVocabulary.MOYENNE;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Les API génériques</b> (F-247 / SF-247-05) : {@code curl} / {@code Invoke-RestMethod} en lecture dont la réponse
 * JSON <b>objet</b> porte au premier niveau un champ d'état reconnu ({@code status}, {@code state}, {@code version}).
 * Famille {@code OTHER}, confiance {@code MOYENNE}, clé {@code API:<hôte><chemin>} sans requête. Ne s'applique que si
 * aucun extracteur spécialisé n'a couvert la sortie.
 */
final class GenericApiExtractor implements StateExtractor {

    private static final Pattern URL = Pattern.compile("https?://([\\w.\\-]+(?::\\d+)?)(/[^\\s?\"'#]*)?");
    private static final Pattern CMD = Pattern.compile("^(curl|invoke-restmethod|irm|invoke-webrequest)\\b",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "api";
    }

    @Override
    public boolean accepts(String command) {
        return CMD.matcher(command.strip()).find() && !StateExtractors.writes(command);
    }

    @Override
    public List<Extracted> extract(String command, String body) {
        List<Extracted> out = new ArrayList<>();
        Matcher u = URL.matcher(command);
        if (!u.find()) {
            return out;
        }
        String path = u.group(2) == null ? "/" : u.group(2);
        if (path.length() > 200) {
            return out;
        }
        String key = "API:" + u.group(1) + path;
        Json.parse(body).ifPresent(root -> {
            if (!root.isObject()) {
                return;
            }
            String status = Json.text(root, "status");
            String state = Json.text(root, "state");
            Json.put(out, "OTHER", key, "status", status != null && status.length() <= 60 ? status
                    : state != null && state.length() <= 60 ? state : null, MOYENNE);
            String version = Json.text(root, "version");
            Json.put(out, "OTHER", key, "version", version != null && version.length() <= 60 ? version : null,
                    MOYENNE);
        });
        return out;
    }
}
