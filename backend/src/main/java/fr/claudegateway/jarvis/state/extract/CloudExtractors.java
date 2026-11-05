package fr.claudegateway.jarvis.state.extract;

import static fr.claudegateway.jarvis.state.JarvisStateVocabulary.HAUTE;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Extracteurs du cloud et de l'infra</b> (F-247 / SF-247-05) : {@code aws} (ECS, Lambda, CloudFormation, RDS en
 * lecture), {@code az} (toute ressource {@code show} dont l'{@code id} est un chemin {@code /subscriptions/…}),
 * {@code terraform output -json} (jamais les sorties sensibles) et {@code terraform state list} (nombre de
 * ressources). Jamais {@code plan} ni {@code apply}.
 */
final class CloudExtractors {

    private CloudExtractors() {
    }

    static final class Aws implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("\\baws\\s+(ecs\\s+describe-services|lambda\\s+get-function(-configuration)?|cloudformation\\s+describe-stacks|rds\\s+describe-db-instances)\\b");

        @Override
        public String name() {
            return "aws";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find() && !command.contains("--output text")
                    && !command.contains("--output table");
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Json.parse(body).ifPresent(root -> {
                for (JsonNode s : Json.items(root, "services")) {
                    String arn = Json.text(s, "serviceArn");
                    if (arn == null || !arn.startsWith("arn:")) {
                        continue;
                    }
                    String key = "AWS:" + arn;
                    Json.put(out, "CLOUD_RESOURCE", key, "status", Json.text(s, "status"), HAUTE);
                    String running = Json.text(s, "runningCount");
                    String desired = Json.text(s, "desiredCount");
                    if (running != null && desired != null) {
                        Json.put(out, "CLOUD_RESOURCE", key, "replicas", running + "/" + desired, HAUTE);
                    }
                    String td = Json.text(s, "taskDefinition");
                    Json.put(out, "CLOUD_RESOURCE", key, "version", td == null ? null
                            : td.substring(td.lastIndexOf('/') + 1), HAUTE);
                    JsonNode deployments = s.path("deployments");
                    if (deployments.isArray() && deployments.size() > 0) {
                        Json.put(out, "CLOUD_RESOURCE", key, "state", Json.text(deployments.get(0), "rolloutState"),
                                HAUTE);
                    }
                }
                JsonNode conf = root.has("Configuration") ? root.get("Configuration") : root;
                String fn = Json.text(conf, "FunctionArn");
                if (fn != null && fn.startsWith("arn:")) {
                    String key = "AWS:" + fn;
                    Json.put(out, "CLOUD_RESOURCE", key, "version", Json.text(conf, "Version"), HAUTE);
                    Json.put(out, "CLOUD_RESOURCE", key, "state", Json.text(conf, "State"), HAUTE);
                    Json.put(out, "CLOUD_RESOURCE", key, "status", Json.text(conf, "LastUpdateStatus"), HAUTE);
                    Json.put(out, "CLOUD_RESOURCE", key, "updated_at", Json.text(conf, "LastModified"), HAUTE);
                }
                for (JsonNode st : Json.items(root, "Stacks")) {
                    String id = Json.text(st, "StackId");
                    if (id == null || !id.startsWith("arn:") || !root.has("Stacks")) {
                        continue;
                    }
                    String key = "AWS:" + id;
                    Json.put(out, "CLOUD_RESOURCE", key, "status", Json.text(st, "StackStatus"), HAUTE);
                    String updated = Json.text(st, "LastUpdatedTime");
                    Json.put(out, "CLOUD_RESOURCE", key, "updated_at", updated != null ? updated
                            : Json.text(st, "CreationTime"), HAUTE);
                }
                for (JsonNode db : Json.items(root, "DBInstances")) {
                    String arn = Json.text(db, "DBInstanceArn");
                    if (arn == null || !arn.startsWith("arn:") || !root.has("DBInstances")) {
                        continue;
                    }
                    String key = "AWS:" + arn;
                    Json.put(out, "CLOUD_RESOURCE", key, "status", Json.text(db, "DBInstanceStatus"), HAUTE);
                    Json.put(out, "CLOUD_RESOURCE", key, "version", Json.text(db, "EngineVersion"), HAUTE);
                }
            });
            return out;
        }
    }

    static final class Az implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("\\baz\\s+[\\w\\-]+(\\s+[\\w\\-]+)?\\s+(show|list)\\b");

        @Override
        public String name() {
            return "az";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find() && !command.matches(".*\\baz\\s+account\\b.*")
                    && !command.contains("-o table") && !command.contains("--output table")
                    && !command.contains("-o tsv") && !command.contains("--output tsv");
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Json.parse(body).ifPresent(root -> {
                for (JsonNode r : Json.items(root, null)) {
                    String id = Json.text(r, "id");
                    if (id == null || !id.startsWith("/subscriptions/")) {
                        continue;
                    }
                    String key = "AZ:" + id;
                    String state = Json.text(r, "state");
                    Json.put(out, "CLOUD_RESOURCE", key, "state", state != null ? state
                            : Json.text(r, "powerState", "code"), HAUTE);
                    String prov = Json.text(r, "provisioningState");
                    Json.put(out, "CLOUD_RESOURCE", key, "status", prov != null ? prov
                            : Json.text(r, "properties", "provisioningState"), HAUTE);
                    Json.put(out, "CLOUD_RESOURCE", key, "version", Json.text(r, "kubernetesVersion"), HAUTE);
                    String ts = Json.text(r, "properties", "timestamp");
                    Json.put(out, "CLOUD_RESOURCE", key, "updated_at", ts != null ? ts : Json.text(r, "lastModifiedTimeUtc"),
                            HAUTE);
                }
            });
            return out;
        }
    }

    /** {@code terraform output -json} (sorties non sensibles) et {@code terraform state list}. */
    static final class Terraform implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("\\bterraform\\b.*\\b(output\\b.*-json|state\\s+list)\\b");
        private static final Pattern CHDIR = Pattern.compile("-chdir=(\\S+)");
        private static final Pattern RESOURCE = Pattern.compile("^(module\\.[\\w\\-]+\\.)*(data\\.)?[a-z0-9_]+\\.[\\w\\-\\[\\]\"]+$");

        @Override
        public String name() {
            return "terraform";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find();
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Matcher c = CHDIR.matcher(command);
            String space = "TF:" + (c.find() ? c.group(1) : "{subject}");
            if (command.contains("state list")) {
                String[] lines = body.split("\\R");
                int n = 0;
                for (String l : lines) {
                    if (!RESOURCE.matcher(l.strip()).matches()) {
                        return out;
                    }
                    n++;
                }
                if (n > 0) {
                    Json.put(out, "INFRA_STATE", space, "resources", n + " ressources",
                            fr.claudegateway.jarvis.state.JarvisStateVocabulary.MOYENNE);
                }
                return out;
            }
            Json.parse(body).ifPresent(root -> {
                if (!root.isObject()) {
                    return;
                }
                root.fields().forEachRemaining(e -> {
                    JsonNode o = e.getValue();
                    if (!o.isObject() || !o.has("value") || o.path("sensitive").asBoolean(true)) {
                        return;
                    }
                    JsonNode v = o.get("value");
                    String value = v.isValueNode() ? v.asText() : v.toString();
                    if (out.size() < 30) {
                        Json.put(out, "INFRA_STATE", space + "/output." + e.getKey(), "value", value, HAUTE);
                    }
                });
            });
            return out;
        }
    }
}
