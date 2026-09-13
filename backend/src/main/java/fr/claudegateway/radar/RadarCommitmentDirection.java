package fr.claudegateway.radar;

/**
 * Sens d'un engagement (F-99, cadrage §3). « Moi », c'est le propriétaire du Radar : il n'est jamais
 * une personne de l'annuaire, il est représenté par l'absence de personne.
 */
public enum RadarCommitmentDirection {

    /** Moi → autre : « à faire par moi ». {@code fromPerson} est vide, {@code toPerson} facultatif. */
    ME_TO_OTHER,

    /** Autre → moi : « j'attends des autres ». {@code fromPerson} est la personne attendue. */
    OTHER_TO_ME,

    /** Mise en relation : moi → A et B. {@code toPerson} = A, {@code otherPerson} = B. */
    INTRODUCTION
}
