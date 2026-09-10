package fr.claudegateway.governance;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;

/**
 * Un <b>contrôle</b> qu'un paquet de gouvernance peut activer (F-51 / SF-51-01).
 *
 * <p><b>C'est un composant du serveur, jamais du code reçu de l'extérieur.</b> Une implémentation
 * est une classe de ce dépôt, déclarée comme bean Spring et découverte au démarrage ; un paquet ne
 * porte que des <b>identifiants</b>, et la publication refuse un identifiant que le registre ne
 * connaît pas. C'est la limite héritée de F-50 (« F-51 décidera quels contrôles s'activent pour
 * qui ; il ne permettra pas d'en apporter de nouveaux »), et elle est structurante : un catalogue de
 * crochets ouvert ferait s'exécuter, sur la machine de chaque utilisateur qui retient un paquet, du
 * code que quelqu'un d'autre a écrit.</p>
 *
 * <p>Le contrat d'exécution est celui de {@code AtelierCheckpoint} (F-50) : <b>rapide</b>, appelé
 * dans le tour de l'utilisateur ; <b>sans effet de bord</b>, un contrôle juge et n'écrit pas ; et le
 * verdict <b>porte le geste</b> attendu, parce qu'il est lu par un modèle qui doit corriger.</p>
 *
 * <p>Aucune implémentation n'est livrée par F-51 : le registre est vide, et un paquet peut n'activer
 * aucun contrôle. F-52 apportera les premières.</p>
 */
public interface GovernanceControl {

    /**
     * Identifiant stable, cité par les paquets. Convention : minuscules, chiffres et tirets
     * (ex. {@code commit-sans-trace-llm}). Il ne change jamais — un paquet publié le référence.
     */
    String id();

    /** Le point d'accroche de F-50 auquel ce contrôle se branche ; il n'en sert qu'un. */
    AtelierCheckpointKind kind();

    /** Une ligne, pour l'écran d'administration : ce que le contrôle vérifie, en clair. */
    String description();

    /**
     * Juge la situation.
     *
     * @return {@link AtelierCheckpointVerdict#proceed()} pour laisser passer, ou
     *         {@link AtelierCheckpointVerdict#block(String)} avec l'action corrective
     */
    AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context);
}
