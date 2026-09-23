package fr.claudegateway.governance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Les profils métier</b>, semés au catalogue (F-138 / SF-138-01).
 *
 * <p><b>Le défaut qu'ils corrigent.</b> La première phrase que lit le modèle à chaque tour est
 * « Tu es un assistant de développement qui travaille sur le projet de l'utilisateur ». Pour un
 * travail d'architecture, de réseau, de sécurité ou d'exploitation chez un grand compte, ce rôle est
 * faux — et les réflexes d'un développeur ne sont pas ceux d'un ingénieur d'infrastructure. La
 * discipline d'investigation (F-119) dit <b>comment</b> vérifier ; un profil dit <b>ce qui vaut
 * preuve</b> dans ce métier-là.</p>
 *
 * <p><b>Un profil ne dépose RIEN sur la machine du client</b>, et c'est ce qui le distingue du
 * paquet « savoir durable » : il ne porte que du texte, qui rejoint la consigne système quand on
 * l'active. Aucun fichier, aucun contrôle de fin de tour, aucune écriture.</p>
 *
 * <p><b>Aucun n'est retenu par défaut.</b> Choisir un métier est une décision, pas un réglage : le
 * produit ne la prend pas à la place de l'utilisateur.</p>
 *
 * <p><b>Un semis en échec n'empêche jamais le démarrage</b> — même geste que
 * {@link GovernancePackageSeeder} : un catalogue incomplet coûte une fonctionnalité, un démarrage
 * raté coûte le produit.</p>
 */
@Component
public class GovernanceProfileSeeder {

    private static final Logger log = LoggerFactory.getLogger(GovernanceProfileSeeder.class);

    private static final String ROOT = "governance/profils/";

    /**
     * Préfixe de slug d'un profil métier (F-148 / SF-148-02). Il distingue un profil d'un paquet de
     * savoir durable parmi les activations d'un poste, sans avoir à ajouter une colonne ni une table :
     * les slugs livrés (« profil-architecte »…) le portent déjà.
     */
    public static final String PROFILE_SLUG_PREFIX = "profil-";

    /** Un profil : son identifiant stable, son nom, ce qu'il promet, et le fichier qui le porte. */
    record Profile(String slug, String name, String summary, String resource) {
    }

    /**
     * Les profils livrés, bornés au domaine du PO : infrastructure, architecture, sécurité, données.
     *
     * <p>Le catalogue n'a pas vocation à couvrir tous les métiers du monde. Ceux-ci sont ceux qu'on
     * exerce réellement chez les clients, et chacun a été écrit pour dire une chose que le prompt
     * générique ne dit pas.</p>
     */
    static final List<Profile> PROFILES = List.of(
            new Profile("profil-architecte", "Profil — Architecte",
                    "Comprendre un existant avant de proposer : constaté / rapporté / supposé jamais "
                            + "confondus, dépendances et points de rupture, note d'état avant toute "
                            + "recommandation.",
                    "architecte.md"),
            new Profile("profil-infrastructure-production", "Profil — Infrastructure / Production",
                    "L'état réel de la machine plutôt que l'intention d'un fichier : état d'avant, "
                            + "commande, état d'après, et comment revenir en arrière — ce qu'on relit "
                            + "à trois heures du matin.",
                    "infrastructure-production.md"),
            new Profile("profil-securite", "Profil — Sécurité",
                    "Le droit effectif plutôt que le droit déclaré, l'impact réel plutôt que la "
                            + "gravité théorique, et jamais la valeur d'un secret — seulement où il "
                            + "vit et qui l'accorde.",
                    "securite.md"),
            new Profile("profil-donnees", "Profil — Données",
                    "Une sauvegarde n'existe que restaurée : comptage avant, comptage après, point "
                            + "de retour arrière, et rien en masse sans demande explicite.",
                    "donnees.md"));

    private final GovernancePackageRepository packages;
    private final boolean enabled;

    public GovernanceProfileSeeder(GovernancePackageRepository packages,
            @Value("${app.governance.seed-profiles:true}") boolean enabled) {
        this.packages = packages;
        this.enabled = enabled;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void seedOnStartup() {
        if (!enabled) {
            log.info("Profils métier non semés : semeur désactivé.");
            return;
        }
        for (Profile profile : PROFILES) {
            try {
                seed(profile);
            } catch (RuntimeException ex) {
                // Un profil manquant coûte une fonctionnalité ; un démarrage raté coûte le produit.
                log.warn("Profil « {} » non semé.", profile.slug(), ex);
            }
        }
    }

    /**
     * Sème un profil, ou le met à jour si son texte a changé.
     *
     * @return vrai si le catalogue a été touché
     */
    @Transactional
    public boolean seed(Profile profile) {
        String rules = readResource(profile.resource());
        if (rules == null || rules.isBlank()) {
            log.warn("Profil « {} » non semé : ressource « {} » absente.", profile.slug(),
                    profile.resource());
            return false;
        }
        if (rules.length() > GovernancePackage.MAX_RULES_LENGTH) {
            // Ce texte part dans la consigne système à CHAQUE tour : la borne vaut aussi pour ce que
            // le produit livre lui-même, sinon elle ne veut plus rien dire.
            log.warn("Profil « {} » non semé : ses règles dépassent {} caractères.", profile.slug(),
                    GovernancePackage.MAX_RULES_LENGTH);
            return false;
        }
        Optional<GovernancePackage> existing = packages.findBySlug(profile.slug());
        if (existing.isEmpty()) {
            GovernancePackage pkg = GovernancePackage.builder()
                    .slug(profile.slug()).name(profile.name()).summary(profile.summary())
                    .rules(rules).version(1)
                    .published(true).publishedAt(OffsetDateTime.now())
                    .build();
            // Ni contrôle, ni fichier : un profil est une doctrine, pas un outillage.
            pkg.setControlIdList(List.of());
            packages.save(pkg);
            log.info("Profil « {} » publié en version 1.", profile.slug());
            return true;
        }
        GovernancePackage pkg = existing.get();
        if (profile.name().equals(pkg.getName()) && profile.summary().equals(pkg.getSummary())
                && rules.equals(pkg.getRules())) {
            return false; // Rien n'a changé : ni écriture, ni incrément de version.
        }
        pkg.setName(profile.name());
        pkg.setSummary(profile.summary());
        pkg.setRules(rules);
        pkg.setVersion(pkg.getVersion() + 1);
        // `published` n'est pas touché : un profil que l'admin a rangé reste rangé.
        packages.save(pkg);
        log.info("Profil « {} » mis à jour en version {}.", profile.slug(), pkg.getVersion());
        return true;
    }

    private String readResource(String name) {
        ClassPathResource resource = new ClassPathResource(ROOT + name);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("Ressource de profil « {} » illisible.", name);
            return null;
        }
    }
}
