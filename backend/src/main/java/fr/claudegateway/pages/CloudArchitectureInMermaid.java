package fr.claudegateway.pages;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Une architecture cloud ne se dessine pas à la main</b> (F-142 / SF-142-11).
 *
 * <p><b>Le défaut qu'il ferme.</b> La page « data-ingestion — 4 vues » du 2026-09-26 portait
 * <b>cinq blocs Mermaid écrits à la main</b> et <b>aucune image</b> : sa vue réseau décrivait un
 * VPC, des sous-réseaux, huit endpoints et un Transit Gateway — en rectangles nommés, sans une
 * seule icône AWS. L'agent n'avait pas mal choisi le moteur : <b>il n'avait jamais ouvert l'outil
 * de diagramme</b>. La description de l'outil, si complète soit-elle, ne pouvait rien y faire —
 * on ne lit pas la notice d'un outil qu'on n'ouvre pas.</p>
 *
 * <p><b>Pourquoi une porte plutôt qu'une consigne.</b> Trois patrons du produit le montrent déjà
 * (porte de complétude SF-121-05, porte du runner SF-161-01, arrêt net SF-161-02) : on n'attend pas
 * la bonne volonté du modèle, on l'empêche de se tromper — par un contrôle déterministe et
 * gratuit.</p>
 *
 * <p><b>Le doute ne ferme rien.</b> Un diagramme de séquence qui cite S3 une fois n'est pas une
 * architecture. Il faut <b>plusieurs</b> marqueurs d'infrastructure, dont au moins un
 * <b>structurant</b> — un service isolé ne suffit jamais.</p>
 */
public final class CloudArchitectureInMermaid {

    /**
     * Les marqueurs <b>structurants</b> : ils ne décrivent pas un composant, ils décrivent une
     * <b>topologie</b>. En citer un, c'est dessiner une infrastructure, pas un flux métier.
     */
    private static final List<Pattern> STRUCTURAL = compile(
            "\\bvpc\\b", "\\bsous[- ]?r[ée]seaux?\\b", "\\bsubnets?\\b", "\\btransit ?gateway\\b",
            "\\bvpce-\\w", "\\bprivatelink\\b", "\\bnat ?gateway\\b", "\\binternet ?gateway\\b",
            "\\bdirect ?connect\\b", "\\bavailability ?zone\\b", "\\bzone de disponibilit[ée]\\b",
            "\\b(?:10|172|192)\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}/\\d{1,2}");

    /** Les marqueurs de <b>service</b> : présents partout, y compris dans un diagramme de séquence. */
    private static final List<Pattern> SERVICES = compile(
            "\\beks\\b", "\\becs\\b", "\\bs3\\b", "\\brds\\b", "\\blambda\\b", "\\bec2\\b",
            "\\balb\\b", "\\bnlb\\b", "\\broute ?53\\b", "\\bcloudfront\\b", "\\bdynamodb\\b",
            "\\bsqs\\b", "\\bsns\\b", "\\bmwaa\\b", "\\bfargate\\b", "\\bkms\\b", "\\becr\\b",
            "\\bsecrets ?manager\\b", "\\bapi ?gateway\\b", "\\bglue\\b", "\\bathena\\b",
            "\\beu-(?:west|central|north|south)-\\d\\b", "\\bus-(?:east|west)-\\d\\b");

    /**
     * Le seuil. <b>Deux marqueurs dont un structurant</b> : « le bucket S3 des logs » dans un
     * diagramme de séquence en a un seul, et de service — il passe. Une vue réseau en a dix.
     */
    private static final int MIN_MARKERS = 2;

    /** Un bloc Mermaid tel que la page le porte, ou tel qu'un bloc Markdown clôturé le déclare. */
    private static final Pattern BLOCKS = Pattern.compile(
            "<pre[^>]*class=\"[^\"]*mermaid[^\"]*\"[^>]*>(.*?)</pre>"
                    + "|```\\s*mermaid\\s*\\n(.*?)```",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private CloudArchitectureInMermaid() {
    }

    private static List<Pattern> compile(String... expressions) {
        return java.util.Arrays.stream(expressions)
                .map(e -> Pattern.compile(e, Pattern.CASE_INSENSITIVE))
                .toList();
    }

    /**
     * Ce que le HTML dessine à la main et qui aurait dû passer par l'outil.
     *
     * @return les marqueurs trouvés, dans l'ordre, ou <b>vide</b> si rien ne l'exige — vide veut
     *         dire « laisse passer », jamais « je n'ai pas su décider »
     */
    public static Set<String> handDrawnCloud(String html) {
        if (html == null || html.isEmpty()) {
            return Set.of();
        }
        Matcher blocks = BLOCKS.matcher(html);
        while (blocks.find()) {
            String code = blocks.group(1) != null ? blocks.group(1) : blocks.group(2);
            Set<String> found = markersOf(code);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return Set.of();
    }

    /** Les marqueurs d'UN bloc, ou vide si ce bloc n'est pas une architecture. */
    private static Set<String> markersOf(String code) {
        if (code == null || code.isBlank()) {
            return Set.of();
        }
        String text = code.replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        Set<String> found = new LinkedHashSet<>();
        boolean structural = false;
        for (Pattern pattern : STRUCTURAL) {
            Matcher m = pattern.matcher(text);
            if (m.find()) {
                structural = true;
                found.add(m.group().trim().toLowerCase());
            }
        }
        // Sans marqueur STRUCTURANT, ce n'est pas une topologie : on ne ferme pas.
        if (!structural) {
            return Set.of();
        }
        for (Pattern pattern : SERVICES) {
            Matcher m = pattern.matcher(text);
            if (m.find()) {
                found.add(m.group().trim().toLowerCase());
            }
        }
        return found.size() >= MIN_MARKERS ? found : Set.of();
    }

    /** Le refus <b>dit quoi faire</b>, pas seulement ce qui ne va pas. */
    public static String refusal(Set<String> markers) {
        return "Cette page dessine une architecture cloud À LA MAIN, en Mermaid : elle sortira en "
                + "rectangles nommés, sans une seule icône officielle. Marqueurs relevés : "
                + String.join(", ", markers) + ".\n"
                + "Reprends ce schéma avec l'outil de diagramme en engine=\"cloud\" : il rend les "
                + "VRAIES icônes AWS/Azure/GCP. Chaque type doit porter sa famille en préfixe — "
                + "aws.eks, aws.s3, aws.transitgateway — sinon le composant n'a pas d'icône. Sers-toi "
                + "des groups pour le VPC et les sous-réseaux, puis insère l'image rendue dans la "
                + "page. Pour un schéma que l'utilisateur pourra retoucher, engine=\"drawio\" rend en "
                + "plus un fichier éditable.\n"
                + "Mermaid reste le bon outil pour une séquence, un flux ou un organigramme.";
    }
}
