package fr.claudegateway.jarvis.state.extract;

import static fr.claudegateway.jarvis.state.JarvisStateVocabulary.HAUTE;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Extracteurs des revues de code</b> (F-247 / SF-247-04) : MR GitLab ({@code glab mr view|list -F json},
 * {@code /merge_requests}) et PR GitHub ({@code gh pr view|list --json}, API {@code /pulls}). La clé est tirée de la
 * sortie elle-même ({@code references.full}, {@code web_url}, {@code url}), jamais devinée ; l'URL n'est gardée que si
 * la sortie la porte (F-248 D15).
 */
final class ReviewExtractors {

    private ReviewExtractors() {
    }

    /** {@code https://hôte/groupe/projet/-/merge_requests/42}. */
    static final Pattern GITLAB_MR_URL = Pattern.compile("^https?://[^/]+/(.+?)/-/merge_requests/(\\d+)");
    /** {@code https://github.com/org/repo/pull/17}. */
    static final Pattern GITHUB_PR_URL = Pattern.compile("^https?://[^/]+/([^/]+/[^/]+)/pull/(\\d+)");

    static final class GitLabMr implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("\\bglab\\s+mr\\s+(view|list|show)\\b.*(-F|--output)[ =]?json|/merge_requests");

        @Override
        public String name() {
            return "gitlab-mr";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find();
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Json.parse(body).ifPresent(root -> {
                for (JsonNode mr : Json.items(root, null)) {
                    String key = key(mr);
                    if (key == null || Json.text(mr, "state") == null) {
                        continue;
                    }
                    Json.put(out, "MR", key, "status", Json.text(mr, "state"), HAUTE);
                    Json.put(out, "MR", key, "title", Json.text(mr, "title"), HAUTE);
                    Json.put(out, "MR", key, "target", Json.text(mr, "target_branch"), HAUTE);
                    Json.put(out, "MR", key, "branch", Json.text(mr, "source_branch"), HAUTE);
                    Json.put(out, "MR", key, "conflicts", Json.text(mr, "has_conflicts"), HAUTE);
                    String merge = Json.text(mr, "detailed_merge_status");
                    Json.put(out, "MR", key, "approvals", merge != null ? merge : Json.text(mr, "merge_status"),
                            HAUTE);
                    String pipeline = Json.text(mr, "head_pipeline", "status");
                    Json.put(out, "MR", key, "pipeline", pipeline != null ? pipeline : Json.text(mr, "pipeline",
                            "status"), HAUTE);
                    Json.put(out, "MR", key, "draft", Json.text(mr, "draft"), HAUTE);
                    Json.put(out, "MR", key, "author", Json.text(mr, "author", "username"), HAUTE);
                    Json.put(out, "MR", key, "updated_at", Json.text(mr, "updated_at"), HAUTE);
                    String url = Json.text(mr, "web_url");
                    if (url != null && GITLAB_MR_URL.matcher(url).find()) {
                        Json.put(out, "MR", key, "url", url, HAUTE);
                    }
                }
            });
            return out;
        }

        static String key(JsonNode mr) {
            String full = Json.text(mr, "references", "full");
            if (full != null && full.matches("[\\w.\\-/]+![0-9]+")) {
                return "GITLAB:" + full;
            }
            String url = Json.text(mr, "web_url");
            if (url != null) {
                Matcher m = GITLAB_MR_URL.matcher(url);
                if (m.find()) {
                    return "GITLAB:" + m.group(1) + "!" + m.group(2);
                }
            }
            return null;
        }
    }

    static final class GitHubPr implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("\\bgh\\s+pr\\s+(view|list|status)\\b.*--json|api\\.github\\.com/repos/.+/pulls|\\bgh\\s+api\\s+.*repos/.+/pulls");

        @Override
        public String name() {
            return "github-pr";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find();
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Json.parse(body).ifPresent(root -> {
                for (JsonNode pr : Json.items(root, null)) {
                    String url = Json.text(pr, "url");
                    String html = Json.text(pr, "html_url");
                    String web = html != null ? html : url;
                    if (web == null) {
                        continue;
                    }
                    Matcher m = GITHUB_PR_URL.matcher(web);
                    if (!m.find()) {
                        continue;
                    }
                    String key = "GITHUB:" + m.group(1) + "#" + m.group(2);
                    String state = Json.text(pr, "state");
                    if (state == null) {
                        continue;
                    }
                    String merged = Json.text(pr, "merged_at");
                    Json.put(out, "MR", key, "status", merged != null ? "MERGED" : state, HAUTE);
                    Json.put(out, "MR", key, "title", Json.text(pr, "title"), HAUTE);
                    Json.put(out, "MR", key, "approvals", Json.text(pr, "reviewDecision"), HAUTE);
                    String mergeable = Json.text(pr, "mergeable");
                    Json.put(out, "MR", key, "conflicts", mergeable == null ? null
                            : "CONFLICTING".equalsIgnoreCase(mergeable) ? "true"
                                    : "MERGEABLE".equalsIgnoreCase(mergeable) || "true".equals(mergeable) ? "false"
                                            : null, HAUTE);
                    String draft = Json.text(pr, "isDraft");
                    Json.put(out, "MR", key, "draft", draft != null ? draft : Json.text(pr, "draft"), HAUTE);
                    String head = Json.text(pr, "headRefName");
                    Json.put(out, "MR", key, "branch", head != null ? head : Json.text(pr, "head", "ref"), HAUTE);
                    String base = Json.text(pr, "baseRefName");
                    Json.put(out, "MR", key, "target", base != null ? base : Json.text(pr, "base", "ref"), HAUTE);
                    String updated = Json.text(pr, "updatedAt");
                    Json.put(out, "MR", key, "updated_at", updated != null ? updated : Json.text(pr, "updated_at"),
                            HAUTE);
                    Json.put(out, "MR", key, "url", web, HAUTE);
                }
            });
            return out;
        }
    }
}
