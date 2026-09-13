import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;

/**
 * F-111 / SF-111-03 — Signe le runner à la construction de l'image backend.
 *
 * <p>Lancé par {@code backend/Dockerfile} en programme à fichier unique ({@code java SignRunnerUpdate.java})
 * : il n'entre ni dans le runner livré ni dans la gateway.</p>
 *
 * <pre>
 * java SignRunnerUpdate.java sign   &lt;clé-privée.pem&gt; &lt;claude-runner.jar&gt; &lt;clé-publique.pem&gt; &lt;notes.txt|-&gt; &lt;sortie&gt;
 * java SignRunnerUpdate.java unsigned &lt;claude-runner.jar&gt; &lt;notes.txt|-&gt; &lt;sortie&gt;
 * </pre>
 *
 * <p>Produit dans {@code <sortie>} : {@code runner.jar} (copie à l'octet), {@code runner.jar.sha256}
 * (hexadécimal), {@code runner.jar.sig} (Ed25519, Base64 — mode {@code sign} seulement) et
 * {@code runner-manifest.json}. En mode {@code sign}, la signature est <b>vérifiée avec la clé publique
 * embarquée dans le runner</b> : une clé privée qui ne lui correspond pas fait échouer la construction,
 * plutôt que de publier une version qu'aucun runner n'accepterait.</p>
 */
public final class SignRunnerUpdate {

    private SignRunnerUpdate() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 6 && args[0].equals("sign")) {
            run(Path.of(args[2]), Path.of(args[1]), Path.of(args[3]), args[4], Path.of(args[5]));
        } else if (args.length == 4 && args[0].equals("unsigned")) {
            run(Path.of(args[1]), null, null, args[2], Path.of(args[3]));
        } else {
            System.err.println("Usage : sign <clé-privée.pem> <jar> <clé-publique.pem> <notes|-> <sortie>"
                    + " | unsigned <jar> <notes|-> <sortie>");
            System.exit(2);
        }
    }

    static void run(Path jar, Path privateKeyPem, Path publicKeyPem, String notesFile, Path out)
            throws Exception {
        byte[] bytes = Files.readAllBytes(jar);
        Properties build = buildProperties(bytes);
        String version = required(build, "version");
        String stamp = required(build, "stamp");
        String commit = build.getProperty("commit", "inconnu").trim();
        String id = version + "-" + stamp + "-" + commit;
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        Files.createDirectories(out);
        Files.write(out.resolve("runner.jar"), bytes);
        Files.writeString(out.resolve("runner.jar.sha256"), sha256 + "\n", StandardCharsets.US_ASCII);

        boolean signed = privateKeyPem != null;
        if (signed) {
            PrivateKey key = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(pem(Files.readString(privateKeyPem))));
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(bytes);
            byte[] signature = signer.sign();

            PublicKey embedded = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(pem(Files.readString(publicKeyPem))));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(embedded);
            verifier.update(bytes);
            if (!verifier.verify(signature)) {
                throw new IllegalStateException("La clé de signature ne correspond pas à la clé publique "
                        + "embarquée dans le runner (" + publicKeyPem + ") : aucun runner n'accepterait "
                        + "cette version. Construction arrêtée.");
            }
            Files.writeString(out.resolve("runner.jar.sig"),
                    Base64.getEncoder().encodeToString(signature) + "\n", StandardCharsets.US_ASCII);
        }

        LocalDateTime date = LocalDateTime.parse(stamp, DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        StringBuilder json = new StringBuilder("{\n");
        json.append("  \"id\": ").append(quote(id)).append(",\n");
        json.append("  \"version\": ").append(quote(version)).append(",\n");
        json.append("  \"commit\": ").append(quote(commit)).append(",\n");
        json.append("  \"builtAt\": ").append(quote(date.toInstant(ZoneOffset.UTC).toString())).append(",\n");
        json.append("  \"contract\": ").append(Integer.parseInt(required(build, "contract"))).append(",\n");
        json.append("  \"minJava\": ").append(Integer.parseInt(required(build, "java"))).append(",\n");
        json.append("  \"sha256\": ").append(quote(sha256)).append(",\n");
        json.append("  \"size\": ").append(bytes.length).append(",\n");
        json.append("  \"signed\": ").append(signed).append(",\n");
        json.append("  \"signedAt\": ").append(quote(Instant.now().toString())).append(",\n");
        json.append("  \"notes\": [");
        List<String> notes = notes(notesFile);
        for (int i = 0; i < notes.size(); i++) {
            json.append(i == 0 ? "" : ", ").append(quote(notes.get(i)));
        }
        json.append("]\n}\n");
        Files.writeString(out.resolve("runner-manifest.json"), json.toString(), StandardCharsets.UTF_8);

        System.out.println((signed ? "Runner signé : " : "Runner NON signé : ") + id + " (sha256 " + sha256 + ")");
    }

    private static Properties buildProperties(byte[] jar) throws Exception {
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals("runner-build.properties")) {
                    Properties properties = new Properties();
                    properties.load(in);
                    return properties;
                }
            }
        }
        throw new IllegalStateException("runner-build.properties absent du jar : ce n'est pas un runner F-111.");
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank() || value.contains("${")) {
            throw new IllegalStateException("runner-build.properties : « " + key + " » manquant ou non filtré.");
        }
        return value.trim();
    }

    private static List<String> notes(String file) throws Exception {
        List<String> notes = new ArrayList<>();
        if (file == null || file.equals("-") || !Files.isRegularFile(Path.of(file))) {
            return notes;
        }
        for (String line : Files.readAllLines(Path.of(file), StandardCharsets.UTF_8)) {
            String note = line.strip();
            if (!note.isEmpty() && !note.startsWith("#") && notes.size() < 10) {
                notes.add(note.length() > 200 ? note.substring(0, 200) : note);
            }
        }
        return notes;
    }

    private static byte[] pem(String text) {
        String base64 = text.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
