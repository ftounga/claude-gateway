package fr.claudegateway.atelier.dto;

/**
 * Corps d'une correction de passation (F-117 / SF-117-09). La validation (non vide, bornée, gabarit
 * respecté) est faite au service pour rendre un message qui dit <b>quoi</b> corriger.
 *
 * @param summary le texte corrigé de la passation
 */
public record ThreadHandoffUpdateRequest(String summary) {
}
