package fr.claudegateway.runner;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Interception TLS — le <b>verdict</b>, rendu par une fonction pure (F-57 / SF-57-02).
 *
 * <p>Une racine non publique dans la chaîne présentée par la gateway prouve qu'un équipement du
 * réseau déchiffre le trafic et le re-signe. C'est le fonctionnement <b>normal</b> d'un proxy
 * d'inspection d'entreprise, et c'est une information de <b>diagnostic</b> précieuse : elle explique
 * la moitié des échecs TLS rencontrés sur un poste client.</p>
 *
 * <p><b>La seule détection légitime de F-57</b>, et elle ne regarde que nos propres connexions —
 * jamais l'état du poste. Le runner l'<b>affiche</b> ; il ne la contourne pas, ne relâche aucune
 * vérification, et ne transmet rien à la gateway.</p>
 *
 * <p>Une racine est dite « publique » quand elle figure dans le magasin livré avec le JDK : c'est la
 * définition opératoire, et la seule dont on dispose hors ligne. Une racine absente de ce magasin
 * qui valide malgré tout prouve que la confiance a été ajoutée localement (D1).</p>
 *
 * <p><b>Faux négatif accepté, faux positif interdit</b> (D2) : une DSI qui a ajouté sa racine au
 * magasin du JDK rendra cette classe muette. Tant mieux — un message qui crierait à l'interception
 * là où il n'y en a pas détruirait la confiance dans tous les autres messages du runner.</p>
 */
public final class TlsInspection {

    private TlsInspection() {
    }

    /**
     * Un maillon de la chaîne présentée par le serveur, réduit à ce qui décide du verdict.
     *
     * @param subject distinguished name du porteur du certificat
     * @param issuer distinguished name de celui qui l'a signé
     */
    public record ChainLink(String subject, String issuer) {
    }

    /**
     * La racine qui re-signe le trafic, quand il y en a une.
     *
     * @param chain chaîne présentée par le serveur, du certificat de site vers la racine
     * @param publicRoots sujets des racines publiques, telles que le JDK les livre
     * @return le distinguished name de la racine non publique, ou vide — le silence est le défaut
     */
    public static Optional<String> interceptingRoot(List<ChainLink> chain, Set<String> publicRoots) {
        if (chain == null || chain.isEmpty() || publicRoots == null || publicRoots.isEmpty()) {
            // Sans chaîne, ou sans référence de racines publiques, on ne peut rien affirmer (D6).
            return Optional.empty();
        }
        Set<String> roots = normalized(publicRoots);
        ChainLink last = null;
        for (ChainLink link : chain) {
            if (link == null) {
                continue;
            }
            // Un maillon qui EST lui-même une racine publique suffit à se taire (D3) : les chaînes
            // croisées — racine héritée re-signée par une racine moderne — sont courantes, et ne
            // regarder que l'émetteur du dernier maillon y produirait un faux positif.
            if (roots.contains(normalize(link.subject()))) {
                return Optional.empty();
            }
            last = link;
        }
        if (last == null || roots.contains(normalize(last.issuer()))) {
            return Optional.empty();
        }
        String issuer = last.issuer() == null ? "" : last.issuer().trim();
        return issuer.isEmpty() ? Optional.empty() : Optional.of(issuer);
    }

    /**
     * Nom lisible d'une autorité : son {@code CN}, à défaut le distinguished name entier.
     *
     * <p>Un DN complet est illisible dans une console. Le {@code CN} est ce que l'utilisateur
     * reconnaîtra, et c'est aussi ce qu'il retrouvera dans son navigateur.</p>
     */
    public static String commonName(String distinguishedName) {
        return attribute(distinguishedName, "CN=", distinguishedName == null ? ""
                : distinguishedName.trim());
    }

    /**
     * Organisation déclarée par un distinguished name ({@code O=}), ou chaîne vide.
     *
     * <p>C'est elle que l'utilisateur reconnaît en premier (F-80 / SF-80-01) : sur le poste du PO,
     * le certificat présenté portait {@code O=Zscaler Inc.} — le nom de l'éditeur, lisible d'un
     * coup d'œil, là où le {@code CN} de l'autorité ({@code Zscaler Intermediate Root CA}) demande
     * de savoir ce qu'est une autorité intermédiaire.</p>
     */
    public static String organisation(String distinguishedName) {
        return attribute(distinguishedName, "O=", "");
    }

    /**
     * Qui présente ce certificat, en une ligne lisible (F-80 / SF-80-01).
     *
     * <p>Deux informations, dans l'ordre où elles servent : l'<b>organisation</b> du certificat
     * présenté — « Zscaler Inc. » — puis le <b>CN de l'autorité</b> qui l'a signé. La seconde seule
     * ne dit rien à qui découvre le problème ; la première seule ne permet pas de retrouver la
     * racine dans un magasin.</p>
     *
     * @param chain chaîne présentée par le serveur, du certificat de site vers la racine
     * @param rootDn distinguished name de la racine non publique
     * @return par exemple {@code Zscaler Inc. (CN=Zscaler Intermediate Root CA)}, jamais vide dès
     *     lors que {@code rootDn} n'est pas vide
     */
    public static String presenter(List<ChainLink> chain, String rootDn) {
        String authority = commonName(rootDn);
        String organisation = "";
        if (chain != null) {
            for (ChainLink link : chain) {
                if (link != null) {
                    organisation = organisation(link.subject());
                    break;
                }
            }
        }
        if (organisation.isEmpty()) {
            return authority;
        }
        if (authority.isEmpty()) {
            return organisation;
        }
        return organisation + " (CN=" + authority + ")";
    }

    /** Valeur d'un attribut de DN, ou {@code fallback} quand il est absent. */
    private static String attribute(String distinguishedName, String prefix, String fallback) {
        if (distinguishedName == null || distinguishedName.isBlank()) {
            return fallback.isEmpty() ? "" : fallback;
        }
        for (String part : distinguishedName.split(",")) {
            String piece = part.trim();
            if (piece.regionMatches(true, 0, prefix, 0, prefix.length())) {
                String value = piece.substring(prefix.length()).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return fallback;
    }

    /**
     * Ce que la console dit d'une interception constatée.
     *
     * <p>Trois choses, dans cet ordre : le <b>fait</b>, sa <b>normalité</b> — c'est un proxy
     * d'entreprise, pas une attaque —, et ce que le runner <b>ne fait pas</b> : contourner.</p>
     *
     * @param host hôte de la gateway, tel qu'il a été joint
     * @param rootDn distinguished name de la racine non publique
     */
    public static String message(String host, String rootDn) {
        String nl = System.lineSeparator();
        return "TLS       : le trafic vers " + host + " est déchiffré et re-signé par un équipement "
                + "du réseau —" + nl
                + "            racine « " + commonName(rootDn) + " », absente des racines publiques "
                + "livrées avec Java." + nl
                + "            C'est le fonctionnement normal d'un proxy d'inspection d'entreprise. "
                + "Le runner" + nl
                + "            l'affiche pour le diagnostic ; il ne le contourne pas et ne relâche "
                + "aucune vérification.";
    }

    /**
     * Ce que la console dit quand le <b>contrôle de vol a échoué</b> sur une poignée de main TLS
     * (F-80 / SF-80-01).
     *
     * <p>Ce n'est pas le même message que {@link #message(String, String)} : là-bas, la connexion
     * fonctionne et l'interception n'est qu'une information. Ici, elle est la <b>cause</b> de
     * l'échec — et l'utilisateur vient de lire un {@code PKIX path building failed} qui ne nomme
     * personne.</p>
     *
     * <p>Le message tient en trois temps : <b>qui</b> présente le certificat, <b>ce que cela
     * signifie</b>, et que c'est <b>normal</b>. Aucun remède n'y figure : il appartient au message
     * de la panne, qui l'affiche juste au-dessus.</p>
     *
     * @param presenter tel que {@link #presenter(List, String)} le compose
     */
    public static String handshakeFailure(String presenter) {
        String nl = System.lineSeparator();
        return "         Certificat présenté par : " + presenter + nl
                + "         Un équipement du réseau déchiffre le trafic et le re-signe. C'est le "
                + "fonctionnement" + nl
                + "         normal d'un proxy d'inspection d'entreprise.";
    }

    private static Set<String> normalized(Set<String> names) {
        java.util.Set<String> result = new java.util.HashSet<>();
        for (String name : names) {
            String key = normalize(name);
            if (!key.isEmpty()) {
                result.add(key);
            }
        }
        return result;
    }

    /**
     * Forme de comparaison d'un distinguished name : casse et espacement autour des virgules ne
     * doivent pas suffire à faire croire à une interception.
     */
    private static String normalize(String distinguishedName) {
        if (distinguishedName == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String part : distinguishedName.trim().split(",")) {
            String piece = part.trim();
            if (piece.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(piece.toLowerCase(Locale.ROOT));
        }
        return text.toString();
    }
}
