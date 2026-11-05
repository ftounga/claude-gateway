package fr.claudegateway.jarvis.state.extract;

import java.util.List;
import java.util.regex.Pattern;

/**
 * <b>Le filtre « aucun secret »</b> (F-247 / SF-247-05 ; F-248 D14, corpus « aucun secret » à 100 %) : toute valeur
 * qui ressemble à un secret est rejetée avant le registre, quel que soit l'extracteur. On préfère perdre un fait que
 * garder un secret.
 *
 * <p>SF-247-14 : appliqué aussi <b>à l'enregistrement</b>, par le registre lui-même, sur la valeur et sur la clé de
 * toute observation, quelle que soit sa source (tour, constat rédigé, relevé, veille).</p>
 */
public final class SecretFilter {

    private static final List<Pattern> SECRETS = List.of(
            Pattern.compile("\\bgl(pat|dt|rt|cbt|ptt|oas)-[\\w\\-]{10,}"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}"),
            Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b"),
            Pattern.compile("\\beyJ[\\w\\-]{10,}\\.[\\w\\-]{10,}\\.[\\w\\-]{5,}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9+/=._\\-]{12,}"),
            Pattern.compile("\\bxox[abpors]-[\\w\\-]{10,}"),
            Pattern.compile("\\bsk-(ant-)?[A-Za-z0-9_\\-]{20,}"),
            Pattern.compile("(?i)(password|passwd|secret|token|api[_-]?key)\\s*[=:]\\s*\\S{6,}"),
            Pattern.compile("://[^/\\s:@]+:[^/\\s@]{3,}@"));

    private SecretFilter() {
    }

    public static boolean looksSecret(String value) {
        if (value == null) {
            return false;
        }
        return SECRETS.stream().anyMatch(p -> p.matcher(value).find());
    }
}
