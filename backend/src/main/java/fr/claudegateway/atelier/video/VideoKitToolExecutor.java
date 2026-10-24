package fr.claudegateway.atelier.video;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.ProjectFileDeposit;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.diagrams.DiagramRejectedException;
import fr.claudegateway.diagrams.DiagramRendererUnavailableException;
import fr.claudegateway.governance.GovernancePackageSeeder;

/**
 * <b>Exécute {@code video_briques}</b> (F-215 / SF-215-03) : dépose dans le sujet du poste le guide « cours vidéo »,
 * les briques graphiques et les icônes officielles demandées — ou cherche des noms d'icônes.
 *
 * <p><b>Le guide est la même ressource que celle du paquet {@code savoir-durable}</b> (genre GUIDE) : la gouvernance
 * étant un choix de l'utilisateur (opt-in), un poste qui ne l'a pas activée n'aurait sinon jamais la méthode. Contenu
 * identique ⇒ le dépôt de gouvernance le reconnaît comme intact.</p>
 *
 * <p><b>Isolation</b> : le {@link Workspace} est celui du tour, déjà vérifié possédé ; le modèle ne donne qu'un
 * dossier relatif, nettoyé ici (segments sûrs, jamais {@code ..} ni racine). Toute erreur est un résultat d'outil :
 * le tour continue.</p>
 */
@Component
public class VideoKitToolExecutor {

    static final int MAX_ICONS = 40;
    static final int MAX_DIR_CHARS = 200;
    static final int MAX_QUERY_CHARS = 60;
    static final String BRIQUES_ROOT = "video/briques/";
    /** Les briques déposées, dans cet ordre, sous {@code <dossier>/briques/}. */
    static final List<String> BRIQUES = List.of("charte.css", "briques.js", "LISEZMOI.md");

    private final ProjectFileDeposit deposit;
    private final VideoIconSource icons;

    public VideoKitToolExecutor(ProjectFileDeposit deposit, VideoIconSource icons) {
        this.deposit = deposit;
        this.icons = icons;
    }

    /**
     * @param userId    propriétaire du terminal (celui du tour)
     * @param workspace terminal du tour, déjà vérifié comme possédé
     */
    public Outcome execute(UUID userId, Workspace workspace, String callId, JsonNode input) {
        if (workspace == null || !workspace.isRunnerTarget()) {
            return Outcome.error("Les briques vidéo se déposent sur un poste connecté : ce sujet n'en a pas.");
        }
        String query = text(input, "chercher");
        String rawDir = text(input, "dossier");
        if (!query.isEmpty() && rawDir.isEmpty()) {
            return search(query);
        }
        String dir = cleanDir(rawDir);
        if (dir == null) {
            return Outcome.error("dossier requis : un chemin relatif au sujet (lettres, chiffres, . _ -), sans « .. » "
                    + "ni racine, ex. video/cours-eks.");
        }
        List<String> wanted;
        try {
            wanted = iconNames(input);
        } catch (IllegalArgumentException e) {
            return Outcome.error(e.getMessage());
        }

        // 1. Le guide et les briques : sans eux, rien ne sert — un échec est dit, jamais tu.
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(GovernancePackageSeeder.GUIDE_COURS_VIDEO_PATH, resource(GovernancePackageSeeder.GUIDE_COURS_VIDEO_CLASSPATH));
        for (String name : BRIQUES) {
            files.put(dir + "/briques/" + name, resource(BRIQUES_ROOT + name));
        }
        int index = 0;
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            if (file.getValue() == null) {
                return Outcome.error("Briques vidéo indisponibles sur cette instance (ressource « " + file.getKey()
                        + " » absente).");
            }
            if (deposit.deposit(userId, workspace, callId + ".k" + index++, file.getKey(), file.getValue(),
                    contentType(file.getKey()), VideoKitToolCatalog.TOOL) == null) {
                return Outcome.error("Dépôt des briques sur le poste impossible (poste hors ligne ou runner à mettre "
                        + "à jour) : réessaie.");
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("Déposés : ").append(GovernancePackageSeeder.GUIDE_COURS_VIDEO_PATH).append(" (le guide), ")
                .append(dir).append("/briques/ (charte.css, briques.js, LISEZMOI.md).\n");

        // 2. Les icônes : un manque n'empêche rien, il se dit.
        if (!wanted.isEmpty()) {
            out.append(depositIcons(userId, workspace, callId, dir, wanted));
        }
        out.append("Lis maintenant le guide en entier (read_file ").append(GovernancePackageSeeder.GUIDE_COURS_VIDEO_PATH)
                .append(") puis ").append(dir).append("/briques/LISEZMOI.md. La composition va dans ").append(dir)
                .append("/index.html ; GSAP sera copié dans briques/ par video_render_check.");
        return new Outcome(out.toString().strip(), false);
    }

    private Outcome search(String query) {
        if (query.length() > MAX_QUERY_CHARS) {
            return Outcome.error("chercher : " + MAX_QUERY_CHARS + " caractères au plus.");
        }
        if (icons == null || !icons.isAvailable()) {
            return Outcome.error("Recherche d'icônes indisponible sur cette instance : dessine des pictogrammes "
                    + "simples en CSS/SVG, sans logo imité.");
        }
        List<String> found;
        try {
            found = icons.search(query);
        } catch (DiagramRejectedException e) {
            return Outcome.error(e.getMessage());
        } catch (DiagramRendererUnavailableException e) {
            return Outcome.error("Recherche d'icônes indisponible pour l'instant : réessaie dans un moment.");
        }
        if (found.isEmpty()) {
            return new Outcome("Aucune icône pour « " + query + " ». Essaie un autre mot, en anglais (load balancer, "
                    + "gateway, database, dns, firewall, vnet, subnet…), ou un nom de service.", false);
        }
        StringBuilder out = new StringBuilder("Icônes pour « " + query + " » (" + found.size() + ") :\n");
        found.forEach(name -> out.append("- ").append(name).append('\n'));
        out.append("Passe les noms retenus à video_briques(dossier, icones: [...]).");
        return new Outcome(out.toString(), false);
    }

    private String depositIcons(UUID userId, Workspace workspace, String callId, String dir, List<String> wanted) {
        if (icons == null || !icons.isAvailable()) {
            return "Icônes indisponibles sur cette instance : dessine des pictogrammes simples en CSS/SVG.\n";
        }
        VideoIconSource.Batch batch;
        try {
            batch = icons.fetch(wanted);
        } catch (DiagramRejectedException e) {
            return "Icônes refusées : " + e.getMessage() + "\n";
        } catch (DiagramRendererUnavailableException e) {
            return "Icônes indisponibles pour l'instant (service muet) : relance video_briques avec icones plus tard.\n";
        }
        StringBuilder out = new StringBuilder();
        List<String> placed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int index = 0;
        for (VideoIconSource.Icon icon : batch.icons()) {
            String file = dir + "/icons/" + iconFileName(icon.name(), used);
            if (deposit.deposit(userId, workspace, callId + ".i" + index++, file, icon.png(), "image/png",
                    VideoKitToolCatalog.TOOL) == null) {
                failed.add(icon.name());
            } else {
                placed.add(file.substring(dir.length() + 1) + " ← " + icon.name());
            }
        }
        if (!placed.isEmpty()) {
            out.append("Icônes (").append(placed.size()).append(") : ").append(String.join(", ", placed)).append(".\n");
        }
        List<String> missing = new ArrayList<>(batch.missing());
        missing.addAll(failed);
        if (!missing.isEmpty()) {
            out.append("Introuvables : ").append(String.join(", ", missing))
                    .append(" — cherche le nom exact avec video_briques(chercher: \"…\").\n");
        }
        if (!batch.invalid().isEmpty()) {
            out.append("Noms refusés (forme fournisseur/catégorie/icône ; aws, azure, gcp, k8s, onprem, generic) : ")
                    .append(String.join(", ", batch.invalid())).append(".\n");
        }
        return out.toString();
    }

    /** {@code aws/network/route-53} → {@code route-53.png} ; homonyme d'un autre fournisseur → {@code azure-…png}. */
    static String iconFileName(String name, Set<String> used) {
        String[] parts = name.split("/");
        String base = parts[parts.length - 1].replaceAll("[^a-z0-9._-]", "");
        String file = base + ".png";
        if (!used.add(file)) {
            file = parts[0] + "-" + base + ".png";
            used.add(file);
        }
        return file;
    }

    /** Les noms d'icônes demandés (chaînes), 40 au plus. */
    static List<String> iconNames(JsonNode input) {
        JsonNode raw = input == null ? null : input.get("icones");
        if (raw == null || raw.isNull()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        if (raw.isTextual()) {
            for (String part : raw.asText("").split("[,\\s]+")) {
                if (!part.isBlank()) {
                    names.add(part.strip());
                }
            }
        } else if (raw.isArray()) {
            raw.forEach(node -> {
                String value = node.asText("").strip();
                if (!value.isEmpty()) {
                    names.add(value);
                }
            });
        }
        List<String> distinct = names.stream().map(n -> n.toLowerCase(Locale.ROOT)).distinct().toList();
        if (distinct.size() > MAX_ICONS) {
            throw new IllegalArgumentException("icones : " + MAX_ICONS + " au plus par appel (" + distinct.size()
                    + " demandées) — garde celles qui servent vraiment à l'écran.");
        }
        return distinct;
    }

    /**
     * Le dossier relatif, nettoyé : segments {@code [A-Za-z0-9._-]}, ni racine, ni lecteur, ni {@code ..} ; ou
     * {@code null} s'il n'est pas sûr. Rien n'est réécrit en silence : un dossier douteux est refusé.
     */
    static String cleanDir(String raw) {
        String value = raw == null ? "" : raw.strip().replace('\\', '/');
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.startsWith("./")) {
            value = value.substring(2);
        }
        if (value.isEmpty() || value.length() > MAX_DIR_CHARS || value.startsWith("/") || value.contains(":")) {
            return null;
        }
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || segment.matches("\\.+") || !segment.matches("[A-Za-z0-9._-]+")) {
                return null;
            }
        }
        return value;
    }

    private static String contentType(String path) {
        if (path.endsWith(".css")) {
            return "text/css";
        }
        if (path.endsWith(".js")) {
            return "text/javascript";
        }
        return "text/markdown";
    }

    /** Une ressource livrée avec le produit, ou {@code null} si elle manque. */
    byte[] resource(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static String text(JsonNode input, String field) {
        if (input == null || !input.hasNonNull(field)) {
            return "";
        }
        return input.get(field).asText("").strip();
    }

    /** L'issue d'un appel. */
    public record Outcome(String content, boolean error) {

        static Outcome error(String message) {
            return new Outcome(message, true);
        }
    }
}
