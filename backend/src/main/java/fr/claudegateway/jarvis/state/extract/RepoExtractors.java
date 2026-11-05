package fr.claudegateway.jarvis.state.extract;

import static fr.claudegateway.jarvis.state.JarvisStateVocabulary.MOYENNE;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Extracteurs git et base</b> (F-247 / SF-247-05) : {@code git status}, {@code git branch -vv}, {@code git log},
 * {@code git describe} (motifs stricts de la sortie standard de git) ; version de schéma lue dans les tables de
 * migrations ({@code databasechangelog}, {@code flyway_schema_history}) par {@code psql}. Le dépôt est celui de
 * {@code git -C <chemin>}, sinon {@code {subject}} — remplacé par le nom du sujet par l'exécutant.
 */
final class RepoExtractors {

    private RepoExtractors() {
    }

    static final String SUBJECT = "{subject}";
    private static final Pattern C_PATH = Pattern.compile("\\bgit\\s+-C\\s+(\"[^\"]+\"|\\S+)");

    static String repo(String command) {
        Matcher m = C_PATH.matcher(command);
        if (m.find()) {
            String p = m.group(1).replace("\"", "");
            String[] parts = p.replace('\\', '/').split("/");
            return parts[parts.length - 1].isBlank() && parts.length > 1 ? parts[parts.length - 2]
                    : parts[parts.length - 1];
        }
        return SUBJECT;
    }

    static final class Git implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("^(?:cd\\s+\\S+\\s*&&\\s*)?git\\s+(?:-C\\s+(?:\"[^\"]+\"|\\S+)\\s+)?(status|branch|log|describe)\\b");
        private static final Pattern ON_BRANCH = Pattern.compile("(?m)^On branch (\\S+)$|(?m)^Sur la branche (\\S+)$");
        private static final Pattern AHEAD = Pattern.compile("Your branch is ahead of '([^']+)' by (\\d+) commits?");
        private static final Pattern BEHIND = Pattern.compile("Your branch is behind '([^']+)' by (\\d+) commits?");
        private static final Pattern DIVERGED = Pattern.compile("and have (\\d+) and (\\d+) different commits each");
        private static final Pattern UP_TO_DATE = Pattern.compile("Your branch is up to date with '([^']+)'");
        private static final Pattern VV = Pattern.compile("(?m)^\\*\\s+(\\S+)\\s+([0-9a-f]{7,40})\\s+(?:\\[([^\\]]+)\\]\\s+)?(.*)$");
        private static final Pattern LOG_ONELINE = Pattern.compile("^([0-9a-f]{7,40})\\s+(.+)$");
        private static final Pattern LOG_FULL = Pattern.compile("^commit ([0-9a-f]{40})");
        private static final Pattern DESCRIBE = Pattern.compile("^[\\w.\\-/]+$");

        @Override
        public String name() {
            return "git";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command.strip()).find() && !command.contains("--porcelain=v2");
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Matcher c = CMD.matcher(command.strip());
            if (!c.find()) {
                return out;
            }
            String repo = repo(command);
            switch (c.group(1)) {
                case "status" -> status(repo, body, out);
                case "branch" -> {
                    if (command.contains("-vv")) {
                        Matcher m = VV.matcher(body);
                        if (m.find()) {
                            String key = "GIT:" + repo + "@" + m.group(1);
                            Json.put(out, "GIT_BRANCH", key, "last_commit", m.group(2).substring(0, 7) + " "
                                    + m.group(4).strip(), MOYENNE);
                            if (m.group(3) != null) {
                                Json.put(out, "GIT_BRANCH", key, "ahead_behind", m.group(3), MOYENNE);
                            }
                        }
                    }
                }
                case "log" -> {
                    String first = body.split("\\R", 2)[0].strip();
                    Matcher full = LOG_FULL.matcher(first);
                    Matcher one = LOG_ONELINE.matcher(first);
                    // HEAD seulement : aucune référence, aucun filtre (sinon le premier commit n'est pas celui de HEAD).
                    String args = command.strip().replaceFirst("^.*?\\blog\\b", "").strip();
                    if (!args.isEmpty() && !args.matches("((-n\\s*\\d+|-\\d+|--oneline|--no-decorate|--decorate|"
                            + "--max-count=\\d+)\\s*)+")) {
                        return out;
                    }
                    String key = "GIT:" + repo + "@HEAD";
                    if (command.contains("--oneline") && one.matches()) {
                        Json.put(out, "GIT_BRANCH", key, "last_commit", one.group(1).substring(0, 7) + " "
                                + one.group(2), MOYENNE);
                    } else if (full.find()) {
                        String[] lines = body.split("\\R");
                        String subject = null;
                        for (int i = 1; i < lines.length && i < 8; i++) {
                            if (lines[i].startsWith("    ") && !lines[i].isBlank()) {
                                subject = lines[i].strip();
                                break;
                            }
                        }
                        Json.put(out, "GIT_BRANCH", key, "last_commit", full.group(1).substring(0, 7)
                                + (subject == null ? "" : " " + subject), MOYENNE);
                    }
                }
                case "describe" -> {
                    String tag = body.strip();
                    if (!tag.contains("\n") && DESCRIBE.matcher(tag).matches()) {
                        Json.put(out, "GIT_BRANCH", "GIT:" + repo + "@HEAD", "tag", tag, MOYENNE);
                    }
                }
                default -> {
                }
            }
            return out;
        }

        private static void status(String repo, String body, List<Extracted> out) {
            Matcher b = ON_BRANCH.matcher(body);
            if (!b.find()) {
                return;
            }
            String branch = b.group(1) != null ? b.group(1) : b.group(2);
            String key = "GIT:" + repo + "@" + branch;
            Matcher a = AHEAD.matcher(body);
            Matcher be = BEHIND.matcher(body);
            Matcher d = DIVERGED.matcher(body);
            Matcher u = UP_TO_DATE.matcher(body);
            String ab = a.find() ? "en avance de " + a.group(2) + " sur " + a.group(1)
                    : be.find() ? "en retard de " + be.group(2) + " sur " + be.group(1)
                            : d.find() ? "divergée (" + d.group(1) + " / " + d.group(2) + ")"
                                    : u.find() ? "à jour avec " + u.group(1) : null;
            Json.put(out, "GIT_BRANCH", key, "ahead_behind", ab, MOYENNE);
            boolean clean = body.contains("nothing to commit, working tree clean");
            boolean dirty = body.contains("Changes not staged for commit") || body.contains("Changes to be committed")
                    || body.contains("Untracked files");
            Json.put(out, "GIT_BRANCH", key, "state", clean ? "propre" : dirty ? "modifications en cours" : null,
                    MOYENNE);
        }
    }

    /** Version de schéma : la dernière ligne d'une table de migrations lue par {@code psql}. */
    static final class SchemaVersion implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("\\bpsql\\b.*\\b(databasechangelog|flyway_schema_history)\\b",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern DB = Pattern.compile("(?:-d|--dbname)[ =](\\S+)|postgres(?:ql)?://[^/\\s]+/([\\w\\-]+)");
        private static final Pattern SEPARATOR = Pattern.compile("^-+(\\+-+)*$");
        private static final Pattern ROWS = Pattern.compile("^\\(\\d+ rows?\\)$|^\\(\\d+ lignes?\\)$");

        @Override
        public String name() {
            return "schema-version";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find() && command.toLowerCase().contains("order by")
                    && command.toLowerCase().contains("desc") && command.toLowerCase().contains("limit 1");
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            String[] lines = body.split("\\R");
            if (lines.length < 4 || !SEPARATOR.matcher(lines[1].strip()).matches()
                    || !ROWS.matcher(lines[lines.length - 1].strip()).matches()) {
                return out;
            }
            Matcher d = DB.matcher(command);
            String db = d.find() ? (d.group(1) != null ? d.group(1) : d.group(2)) : "{subject}";
            String table = command.toLowerCase().contains("flyway_schema_history") ? "flyway_schema_history"
                    : "databasechangelog";
            String first = lines[2].split("\\|")[0].strip();
            Json.put(out, "DB_SCHEMA", "DB:" + db + "/" + table, "schema_version", first.isEmpty() ? null : first,
                    MOYENNE);
            return out;
        }
    }
}
