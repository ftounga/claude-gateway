package fr.claudegateway.jarvis.state.extract;

import static fr.claudegateway.jarvis.state.JarvisStateVocabulary.HAUTE;
import static fr.claudegateway.jarvis.state.JarvisStateVocabulary.MOYENNE;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Les identités du poste, en lecture seule</b> (amendement F-248 D14 de F-247, porté ici avec le lot 2) : famille
 * {@code ACCESS}, limitée à <b>compte, type, validité, état</b>. Formes : {@code aws sts get-caller-identity},
 * {@code az account show}, {@code kubectl config current-context}, {@code glab auth status}, {@code gh auth status}.
 * <b>Jamais de secret</b> : ni jeton, ni identifiant de clé, ni ligne « Token » ; le filtre global
 * ({@link SecretFilter}) s'applique en plus.
 */
final class AccessExtractors {

    private AccessExtractors() {
    }

    static final class AwsIdentity implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("\\baws\\s+sts\\s+get-caller-identity\\b");
        private static final Pattern ARN = Pattern.compile("^arn:aws[\\w\\-]*:(iam|sts)::(\\d{12}):(user|assumed-role|role|root|federated-user)(/.*)?$");

        @Override
        public String name() {
            return "aws-identity";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find();
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Json.parse(body).ifPresent(root -> {
                String arn = Json.text(root, "Arn");
                String account = Json.text(root, "Account");
                if (arn == null || account == null) {
                    return;
                }
                Matcher m = ARN.matcher(arn);
                if (!m.matches() || !m.group(2).equals(account)) {
                    return;
                }
                String key = "AWS:" + account;
                Json.put(out, "ACCESS", key, "account", arn, HAUTE);
                Json.put(out, "ACCESS", key, "identity_type", m.group(3), HAUTE);
                Json.put(out, "ACCESS", key, "validity", "valide", HAUTE);
            });
            return out;
        }
    }

    static final class AzAccount implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("\\baz\\s+account\\s+show\\b");

        @Override
        public String name() {
            return "az-account";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find() && !command.contains("--query");
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Json.parse(body).ifPresent(root -> {
                String id = Json.text(root, "id");
                if (id == null || !id.matches("[0-9a-f\\-]{36}")) {
                    return;
                }
                String key = "AZ:" + id;
                Json.put(out, "ACCESS", key, "account", Json.text(root, "user", "name"), HAUTE);
                Json.put(out, "ACCESS", key, "identity_type", Json.text(root, "user", "type"), HAUTE);
                Json.put(out, "ACCESS", key, "state", Json.text(root, "state"), HAUTE);
                Json.put(out, "ACCESS", key, "title", Json.text(root, "name"), HAUTE);
            });
            return out;
        }
    }

    static final class KubeContext implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("^(kubectl|oc)\\s+config\\s+current-context\\s*$");
        private static final Pattern NAME = Pattern.compile("^[\\w.@:/\\-]{1,200}$");

        @Override
        public String name() {
            return "kube-context";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command.strip()).matches();
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            String ctx = body.strip();
            if (!ctx.contains("\n") && NAME.matcher(ctx).matches()) {
                Json.put(out, "ACCESS", "K8S:current-context", "account", ctx, MOYENNE);
            }
            return out;
        }
    }

    /**
     * {@code glab auth status} / {@code gh auth status} : « Logged in to <hôte> as <compte> » (glab, ancien gh) ou
     * « Logged in to <hôte> account <compte> » (gh récent). Les lignes de jeton ne sont jamais lues.
     */
    static final class CliAuth implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("^(glab|gh)\\s+auth\\s+status\\b");
        private static final Pattern LOGGED = Pattern.compile("Logged in to ([\\w.\\-]+(?::\\d+)?) (?:as|account) ([\\w.\\-]+)");
        private static final Pattern FAILED = Pattern.compile("(?i)(not logged in|token is invalid|failed to log in|authentication failed)");

        @Override
        public String name() {
            return "cli-auth";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command.strip()).find();
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            String forge = command.strip().startsWith("glab") ? "GITLAB:" : "GITHUB:";
            for (String line : body.split("\\R")) {
                if (line.toLowerCase().contains("token")) {
                    continue;
                }
                Matcher m = LOGGED.matcher(line);
                if (m.find()) {
                    String key = forge + m.group(1);
                    Json.put(out, "ACCESS", key, "account", m.group(2), MOYENNE);
                    Json.put(out, "ACCESS", key, "state", FAILED.matcher(body).find() ? "en défaut" : "connecté",
                            MOYENNE);
                }
            }
            return out.size() > 20 ? out.subList(0, 20) : out;
        }
    }
}
