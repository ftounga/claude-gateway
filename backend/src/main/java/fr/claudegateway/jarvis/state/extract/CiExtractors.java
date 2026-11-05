package fr.claudegateway.jarvis.state.extract;

import static fr.claudegateway.jarvis.state.JarvisStateVocabulary.HAUTE;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Extracteurs de la CI</b> (F-247 / SF-247-04) : pipelines et jobs GitLab (API {@code /pipelines},
 * {@code /jobs}), exécutions GitHub ({@code gh run list|view --json}). Clé
 * {@code PIPELINE:<projet>@<branche>} ; un pipeline plus ancien de la même branche ne remplace jamais le plus récent
 * (on garde, par clé, le premier listé : les API listent du plus récent au plus ancien).
 */
final class CiExtractors {

    private CiExtractors() {
    }

    static final Pattern GITLAB_PIPELINE_URL = Pattern.compile("^https?://[^/]+/(.+?)/-/pipelines/(\\d+)");
    static final Pattern GITLAB_JOB_URL = Pattern.compile("^https?://[^/]+/(.+?)/-/jobs/(\\d+)");
    static final Pattern GITHUB_RUN_URL = Pattern.compile("^https?://[^/]+/([^/]+/[^/]+)/actions/runs/(\\d+)");

    static final class GitLabPipelines implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("/pipelines(\\b|/|\\?)|\\bglab\\s+ci\\s+(list|view|get)\\b.*(-F|--output)[ =]?json");

        @Override
        public String name() {
            return "gitlab-ci";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find() && !command.contains("/jobs");
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Map<String, Boolean> seen = new LinkedHashMap<>();
            Json.parse(body).ifPresent(root -> {
                for (JsonNode p : Json.items(root, null)) {
                    String url = Json.text(p, "web_url");
                    String ref = Json.text(p, "ref");
                    String status = Json.text(p, "status");
                    if (url == null || ref == null || status == null) {
                        continue;
                    }
                    Matcher m = GITLAB_PIPELINE_URL.matcher(url);
                    if (!m.find()) {
                        continue;
                    }
                    String key = "PIPELINE:" + m.group(1) + "@" + ref;
                    if (seen.putIfAbsent(key, Boolean.TRUE) != null) {
                        continue;
                    }
                    Json.put(out, "PIPELINE", key, "result", status, HAUTE);
                    String sha = Json.text(p, "sha");
                    Json.put(out, "PIPELINE", key, "commit", sha == null ? null : sha.substring(0, Math.min(12,
                            sha.length())), HAUTE);
                    Json.put(out, "PIPELINE", key, "updated_at", Json.text(p, "updated_at"), HAUTE);
                    Json.put(out, "PIPELINE", key, "url", url, HAUTE);
                }
            });
            return out;
        }
    }

    /** Jobs GitLab : l'étape en échec du pipeline (le premier job {@code failed} listé, par branche). */
    static final class GitLabJobs implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("/(pipelines/\\d+/)?jobs(\\b|\\?)");

        @Override
        public String name() {
            return "gitlab-jobs";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find() && (command.contains("/api/v4/") || command.contains("glab api"));
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Map<String, Boolean> seen = new LinkedHashMap<>();
            Json.parse(body).ifPresent(root -> {
                for (JsonNode j : Json.items(root, null)) {
                    String url = Json.text(j, "web_url");
                    String ref = Json.text(j, "ref");
                    if (!"failed".equals(Json.text(j, "status")) || url == null || ref == null) {
                        continue;
                    }
                    Matcher m = GITLAB_JOB_URL.matcher(url);
                    if (!m.find()) {
                        continue;
                    }
                    String key = "PIPELINE:" + m.group(1) + "@" + ref;
                    if (seen.putIfAbsent(key, Boolean.TRUE) != null) {
                        continue;
                    }
                    String stage = Json.text(j, "stage");
                    String name = Json.text(j, "name");
                    Json.put(out, "PIPELINE", key, "failed_step", stage == null ? name : stage + " / " + name, HAUTE);
                }
            });
            return out;
        }
    }

    static final class GitHubRuns implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("\\bgh\\s+run\\s+(list|view)\\b.*--json|/actions/runs");

        @Override
        public String name() {
            return "github-runs";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find();
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Map<String, Boolean> seen = new LinkedHashMap<>();
            Json.parse(body).ifPresent(root -> {
                for (JsonNode r : Json.items(root, "workflow_runs")) {
                    String url = Json.text(r, "url");
                    String html = Json.text(r, "html_url");
                    String web = html != null ? html : url;
                    String branch = Json.text(r, "headBranch");
                    branch = branch != null ? branch : Json.text(r, "head_branch");
                    if (web == null || branch == null) {
                        continue;
                    }
                    Matcher m = GITHUB_RUN_URL.matcher(web);
                    if (!m.find()) {
                        continue;
                    }
                    String workflow = Json.text(r, "workflowName");
                    workflow = workflow != null ? workflow : Json.text(r, "name");
                    String key = "PIPELINE:" + m.group(1) + "@" + branch + (workflow == null ? "" : " " + workflow);
                    if (seen.putIfAbsent(key, Boolean.TRUE) != null) {
                        continue;
                    }
                    String conclusion = Json.text(r, "conclusion");
                    String status = Json.text(r, "status");
                    String result = conclusion != null ? conclusion : status;
                    if (result == null) {
                        continue;
                    }
                    Json.put(out, "PIPELINE", key, "result", result, HAUTE);
                    String sha = Json.text(r, "headSha");
                    sha = sha != null ? sha : Json.text(r, "head_sha");
                    Json.put(out, "PIPELINE", key, "commit", sha == null ? null : sha.substring(0, Math.min(12,
                            sha.length())), HAUTE);
                    Json.put(out, "PIPELINE", key, "url", web, HAUTE);
                }
            });
            return out;
        }
    }
}
