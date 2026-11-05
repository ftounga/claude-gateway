package fr.claudegateway.jarvis.state.extract;

import static fr.claudegateway.jarvis.state.JarvisStateVocabulary.HAUTE;
import static fr.claudegateway.jarvis.state.JarvisStateVocabulary.MOYENNE;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Extracteurs des déploiements</b> (F-247 / SF-247-05) : {@code kubectl}/{@code oc} (deploy, sts, en JSON ou en
 * tableau strict ; {@code rollout status}), {@code helm list|status|history -o json}, {@code argocd app get|list -o
 * json}. Le contexte et l'espace de noms viennent des drapeaux de la commande ({@code --context}, {@code -n}), sinon de
 * la sortie ; jamais devinés.
 */
final class DeployExtractors {

    private DeployExtractors() {
    }

    private static final Pattern CONTEXT = Pattern.compile("--context[ =](\\S+)");
    private static final Pattern NAMESPACE = Pattern.compile("(?:-n|--namespace)[ =](\\S+)");

    static String ctx(String command) {
        Matcher m = CONTEXT.matcher(command);
        return m.find() ? m.group(1) + "/" : "";
    }

    static String ns(String command) {
        Matcher m = NAMESPACE.matcher(command);
        return m.find() ? m.group(1) : null;
    }

    /** {@code kubectl|oc get deploy|sts … -o json} : image, réplicas prêts. */
    static final class KubeJson implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("\\b(kubectl|oc)\\b.*\\bget\\s+(deploy|deployment|deployments|sts|statefulset|statefulsets)\\b.*-o\\s*json|\\b(kubectl|oc)\\b.*\\bget\\s+(deploy|deployment|deployments|sts|statefulset|statefulsets)\\b.*--output[ =]json");

        @Override
        public String name() {
            return "kube-json";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find();
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Json.parse(body).ifPresent(root -> {
                for (JsonNode d : Json.items(root, "items")) {
                    String kind = Json.text(d, "kind");
                    String name = Json.text(d, "metadata", "name");
                    String namespace = Json.text(d, "metadata", "namespace");
                    if (name == null || namespace == null || !("Deployment".equals(kind)
                            || "StatefulSet".equals(kind))) {
                        continue;
                    }
                    String key = "K8S:" + ctx(command) + namespace + "/" + kind.toLowerCase() + "/" + name;
                    JsonNode containers = d.path("spec").path("template").path("spec").path("containers");
                    List<String> images = new ArrayList<>();
                    containers.forEach(c -> {
                        String img = Json.text(c, "image");
                        if (img != null) {
                            images.add(img);
                        }
                    });
                    Json.put(out, "DEPLOYMENT", key, "image", images.isEmpty() ? null : String.join(", ", images),
                            HAUTE);
                    String desired = Json.text(d, "spec", "replicas");
                    String ready = Json.text(d, "status", "readyReplicas");
                    if (desired != null) {
                        Json.put(out, "DEPLOYMENT", key, "replicas", (ready == null ? "0" : ready) + "/" + desired,
                                HAUTE);
                    }
                    String version = Json.text(d, "metadata", "labels", "app.kubernetes.io/version");
                    Json.put(out, "DEPLOYMENT", key, "version", version, HAUTE);
                }
            });
            return out;
        }
    }

    /**
     * {@code kubectl|oc get deploy} en tableau : en-tête exact {@code NAME READY UP-TO-DATE AVAILABLE AGE} (avec
     * {@code CONTAINERS IMAGES} en {@code -o wide}). Confiance moyenne.
     */
    static final class KubeTable implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("\\b(kubectl|oc)\\b.*\\bget\\s+(deploy|deployment|deployments)\\b");
        private static final Pattern HEADER = Pattern.compile("^NAME\\s+READY\\s+UP-TO-DATE\\s+AVAILABLE\\s+AGE(\\s+CONTAINERS\\s+IMAGES(\\s+SELECTOR)?)?\\s*$");

        @Override
        public String name() {
            return "kube-table";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find() && !command.contains("json") && !command.contains("yaml")
                    && !command.contains("-A") && !command.contains("--all-namespaces");
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            String[] lines = body.split("\\R");
            if (lines.length < 2) {
                return out;
            }
            Matcher h = HEADER.matcher(lines[0].strip());
            if (!h.matches()) {
                return out;
            }
            boolean wide = h.group(1) != null;
            String namespace = ns(command) == null ? "default" : ns(command);
            for (int i = 1; i < lines.length && i <= 200; i++) {
                String[] cols = lines[i].strip().split("\\s+");
                if (cols.length < 5 || !cols[1].matches("\\d+/\\d+")) {
                    continue;
                }
                String key = "K8S:" + ctx(command) + namespace + "/deployment/" + cols[0];
                Json.put(out, "DEPLOYMENT", key, "replicas", cols[1], MOYENNE);
                if (wide && cols.length >= 7) {
                    Json.put(out, "DEPLOYMENT", key, "image", cols[6], MOYENNE);
                }
            }
            return out;
        }
    }

    /** {@code kubectl rollout status deployment/x} : « successfully rolled out ». */
    static final class KubeRollout implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("\\b(kubectl|oc)\\b.*\\brollout\\s+status\\s+(deployment|deploy|statefulset|sts)/([\\w.\\-]+)");
        private static final Pattern DONE = Pattern.compile("(deployment|statefulset|daemon set) \"([\\w.\\-]+)\" successfully rolled out");

        @Override
        public String name() {
            return "kube-rollout";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find();
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Matcher c = CMD.matcher(command);
            Matcher d = DONE.matcher(body);
            if (c.find() && d.find() && c.group(3).equals(d.group(2))) {
                String kind = c.group(2).startsWith("s") ? "statefulset" : "deployment";
                String namespace = ns(command) == null ? "default" : ns(command);
                Json.put(out, "DEPLOYMENT", "K8S:" + ctx(command) + namespace + "/" + kind + "/" + c.group(3),
                        "status", "déployé (rollout terminé)", MOYENNE);
            }
            return out;
        }
    }

    /** {@code helm list|status|history -o json}. */
    static final class Helm implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("\\bhelm\\s+(list|ls|status|history|hist)\\b.*(-o|--output)[ =]?json");
        private static final Pattern RELEASE = Pattern.compile("\\bhelm\\s+(history|hist)\\s+([\\w.\\-]+)");

        @Override
        public String name() {
            return "helm";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find();
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Matcher hist = RELEASE.matcher(command);
            boolean history = hist.find();
            Json.parse(body).ifPresent(root -> {
                if (history) {
                    JsonNode last = null;
                    for (JsonNode r : Json.items(root, null)) {
                        if (last == null || r.path("revision").asInt() > last.path("revision").asInt()) {
                            last = r;
                        }
                    }
                    String namespace = ns(command);
                    if (last != null && namespace != null) {
                        String key = "HELM:" + ctx(command) + namespace + "/" + hist.group(2);
                        Json.put(out, "RELEASE", key, "status", Json.text(last, "status"), HAUTE);
                        Json.put(out, "RELEASE", key, "revision", Json.text(last, "revision"), HAUTE);
                        Json.put(out, "RELEASE", key, "chart", Json.text(last, "chart"), HAUTE);
                        Json.put(out, "RELEASE", key, "version", Json.text(last, "app_version"), HAUTE);
                    }
                    return;
                }
                for (JsonNode r : Json.items(root, null)) {
                    String name = Json.text(r, "name");
                    String namespace = Json.text(r, "namespace");
                    if (name == null || namespace == null) {
                        continue;
                    }
                    String key = "HELM:" + ctx(command) + namespace + "/" + name;
                    String status = Json.text(r, "status");
                    Json.put(out, "RELEASE", key, "status", status != null ? status : Json.text(r, "info", "status"),
                            HAUTE);
                    String revision = Json.text(r, "revision");
                    Json.put(out, "RELEASE", key, "revision", revision != null ? revision : Json.text(r, "version"),
                            HAUTE);
                    String chart = Json.text(r, "chart");
                    if (chart == null && Json.text(r, "chart", "metadata", "name") != null) {
                        chart = Json.text(r, "chart", "metadata", "name") + "-"
                                + Json.text(r, "chart", "metadata", "version");
                    }
                    Json.put(out, "RELEASE", key, "chart", chart, HAUTE);
                    String app = Json.text(r, "app_version");
                    Json.put(out, "RELEASE", key, "version", app != null ? app
                            : Json.text(r, "chart", "metadata", "appVersion"), HAUTE);
                }
            });
            return out;
        }
    }

    /** {@code argocd app get|list -o json} : synchro, santé, révision. */
    static final class Argo implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("\\bargocd\\s+app\\s+(get|list)\\b.*(-o|--output)[ =]?json");

        @Override
        public String name() {
            return "argocd";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find();
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Json.parse(body).ifPresent(root -> {
                for (JsonNode a : Json.items(root, "items")) {
                    String name = Json.text(a, "metadata", "name");
                    if (name == null || !a.has("status")) {
                        continue;
                    }
                    String key = "ARGO:" + name;
                    Json.put(out, "DEPLOYMENT", key, "sync", Json.text(a, "status", "sync", "status"), HAUTE);
                    Json.put(out, "DEPLOYMENT", key, "health", Json.text(a, "status", "health", "status"), HAUTE);
                    String rev = Json.text(a, "status", "sync", "revision");
                    Json.put(out, "DEPLOYMENT", key, "revision", rev == null ? null
                            : rev.substring(0, Math.min(12, rev.length())), HAUTE);
                    JsonNode images = a.path("status").path("summary").path("images");
                    if (images.isArray() && images.size() > 0) {
                        List<String> list = new ArrayList<>();
                        images.forEach(i -> list.add(i.asText()));
                        Json.put(out, "DEPLOYMENT", key, "image", String.join(", ", list), HAUTE);
                    }
                }
            });
            return out;
        }
    }
}
