import {
  AtelierTeamsCard,
  AtelierTeamsCertainty,
  AtelierTeamsLine,
  AtelierTeamsMoment,
  AtelierTerminalBlock,
} from '../../core/models/atelier.models';

/**
 * **Lire un bloc riche** (F-89 / SF-89-03) : les quelques décisions d'affichage du compte rendu,
 * écrites une fois, en fonctions pures et testables seules.
 *
 * <p>Rien ici ne **filtre** : la validation est à l'émission (SF-89-02), et un écran qui écarterait
 * des lignes afficherait un compte rendu amputé sans le dire. Ces fonctions disent seulement
 * **comment** lire ce qui arrive.</p>
 */

/**
 * Le niveau de certitude **en toutes lettres**. Un seul endroit le dit, pour que deux vues ne le
 * disent pas autrement — et **jamais un chiffre** : un pourcentage donnerait une apparence de mesure
 * à une interprétation.
 */
export function certaintyLabel(certainty: AtelierTeamsCertainty | undefined): string {
  return certainty === 'EXPLICITE' ? 'explicite' : 'à confirmer';
}

/**
 * Vrai quand la ligne est une **lecture** et non une citation. C'est ce qui déclenche l'italique et
 * la mention écrite — et **rien d'autre** : pas de pictogramme d'avertissement, pas de couleur
 * d'alerte. Un triangle jaune dirait « danger » là où la ligne dit « je l'ai déduit ».
 *
 * <p>Le défaut penche du côté qui n'affirme rien : une certitude absente vaut « à confirmer ».</p>
 */
export function isUncertain(line: AtelierTeamsLine): boolean {
  return line.certainty !== 'EXPLICITE';
}

/**
 * L'heure telle qu'on l'écrit dans un compte rendu : `14:32`. L'horodatage complet reste dans
 * l'infobulle — on lit une réunion à l'heure, on la vérifie à la seconde.
 *
 * <p>Un horodatage illisible est **rendu tel quel** plutôt que remplacé par un tiret : c'est une
 * information sur ce que l'adaptateur a lu, et l'effacer masquerait une dérive de format.</p>
 */
export function shortTime(at: string | undefined): string {
  if (!at) {
    return '';
  }
  const parsed = new Date(at);
  if (Number.isNaN(parsed.getTime())) {
    return at;
  }
  const hours = `${parsed.getHours()}`.padStart(2, '0');
  const minutes = `${parsed.getMinutes()}`.padStart(2, '0');
  return `${hours}:${minutes}`;
}

/**
 * Vrai si le bloc a quelque chose à montrer. Un bloc sans ligne **ni** moment est ignoré plutôt que
 * rendu vide : mieux vaut rien qu'un cadre creux qui laisse croire qu'on a regardé.
 */
export function hasContent(card: AtelierTeamsCard | null | undefined): boolean {
  if (!card) {
    return false;
  }
  const lines = (card.sections ?? []).reduce((total, section) => total + (section.lines?.length ?? 0), 0);
  return lines > 0 || (card.moments?.length ?? 0) > 0;
}

/**
 * **La carte à afficher pour ce bloc, ou `null`** — et c'est ici que se tient la règle non
 * négociable du volet, pour la troisième et dernière fois.
 *
 * <p>*Un terminal de projet reste textuel pour toujours* : hors d'un terminal Teams, cette fonction
 * rend `null`, et le bloc est rendu **en texte** — jamais masqué. Masquer ferait disparaître une
 * information sans le dire ; la règle interdit la **carte**, pas le contenu.</p>
 *
 * <p>Les deux premiers verrous sont côté gateway (les outils ne sont pas déclarés, et l'appel est
 * refusé s'il arrive quand même). Celui-ci protège le cas où un bloc porteur de carte atteindrait
 * malgré tout un terminal de projet — un historique relu après un changement de marque, par exemple.</p>
 */
export function cardOf(
  block: AtelierTerminalBlock,
  teamsTerminal: boolean,
): AtelierTeamsCard | null {
  if (!teamsTerminal || !hasContent(block.card)) {
    return null;
  }
  return block.card ?? null;
}

/**
 * **Le repli textuel d'un bloc riche** : ce qu'on affiche quand la carte n'a pas le droit d'exister
 * — sur un terminal de projet — et ce qui garantit que rien n'est perdu en route.
 *
 * <p>Le format est celui d'un compte rendu écrit à la main : une ligne par affirmation, avec son
 * auteur, son heure, son niveau de certitude et son lien. Tout ce que la carte montre, en texte.</p>
 */
export function cardAsText(card: AtelierTeamsCard): string {
  const lines: string[] = [];
  // **La mention en PREMIÈRE ligne** (F-91 / SF-91-03), jamais en bas : quelqu'un qui copie ce
  // compte rendu ailleurs doit emporter avec lui l'information que les participants n'ont pas été
  // avertis par Teams. La trace voyage avec l'artefact, texte compris.
  if (card.recordingNotice) {
    lines.push(card.recordingNotice, '');
  }
  lines.push(card.title);
  if (card.subtitle) {
    lines.push(card.subtitle);
  }
  for (const section of card.sections ?? []) {
    if (section.title) {
      lines.push('', section.title);
    }
    for (const line of section.lines ?? []) {
      lines.push(`- ${line.text}${sourceSuffix(line)}`);
    }
  }
  for (const moment of card.moments ?? []) {
    lines.push(`- ${shortTime(moment.at)} ${momentSpeaker(moment)}« ${moment.quote} »`);
  }
  lines.push('', `Fenêtre lue : ${card.window}`);
  lines.push(gapsLabel(card));
  return lines.join('\n');
}

/** Ce qu'on écrit après une ligne, en texte : qui, quand, quelle certitude, et où vérifier. */
function sourceSuffix(line: AtelierTeamsLine): string {
  const parts: string[] = [];
  if (line.author) {
    parts.push(line.author);
  }
  const time = shortTime(line.at);
  if (time) {
    parts.push(time);
  }
  parts.push(certaintyLabel(line.certainty));
  if (line.webUrl) {
    parts.push(line.webUrl);
  }
  return ` (${parts.join(', ')})`;
}

/** Le locuteur d'un moment, suivi d'une espace — ou rien quand on ne le connaît pas. */
export function momentSpeaker(moment: AtelierTeamsMoment): string {
  return moment.speaker ? `${moment.speaker} : ` : '';
}

/**
 * **Ce qui n'a pas pu être lu**, en une phrase — et jamais le silence. Une liste vide dit
 * « aucun manque signalé », pas « j'ai tout lu » : la nuance est toute la règle du volet.
 */
export function gapsLabel(card: AtelierTeamsCard): string {
  const gaps = card.gaps ?? [];
  return gaps.length === 0
    ? 'Aucun manque signalé par les outils de lecture.'
    : `Non lu : ${gaps.join(' ; ')}`;
}

/**
 * **Le bloc de transcription d'une carte reçue au fil de l'eau** (F-89 / SF-89-02).
 *
 * <p>Il n'a ni commande ni sortie : ce n'est pas ce que la machine a répondu, c'est ce que l'agent
 * rend. Son `toolUseId` est celui de l'appel, pour que le rejeu du tour ne l'affiche pas deux fois.</p>
 */
export function cardBlock(toolUseId: string, card: AtelierTeamsCard): AtelierTerminalBlock {
  return {
    tool: 'teams_card',
    toolUseId,
    threadId: null,
    output: '',
    hasOutput: false,
    error: false,
    expanded: false,
    card,
  };
}

/**
 * **Insère les cartes reçues parmi les blocs d'étapes**, chacune à la place où elle est arrivée.
 *
 * <p>Les blocs vivants sont **recalculés** à chaque étape reçue (`chatStepsToBlocks`) : une carte
 * simplement ajoutée à la liste serait effacée au relais suivant. On garde donc, pour chaque carte,
 * le **nombre d'étapes déjà reçues** quand elle est arrivée, et on la replace là — ce qui la laisse
 * exactement entre les commandes qui l'ont précédée et celles qui la suivent.</p>
 */
export function withCards(
  blocks: AtelierTerminalBlock[],
  cards: { afterSteps: number; block: AtelierTerminalBlock }[],
): AtelierTerminalBlock[] {
  if (cards.length === 0) {
    return blocks;
  }
  const merged: AtelierTerminalBlock[] = [];
  for (let index = 0; index <= blocks.length; index++) {
    if (index < blocks.length) {
      merged.push(blocks[index]);
    }
    for (const card of cards) {
      if (card.afterSteps === index + 1 || (index === blocks.length && card.afterSteps > blocks.length)) {
        merged.push(card.block);
      }
    }
  }
  // Les cartes arrivées AVANT toute étape ouvrent la liste : un compte rendu peut être la première
  // chose qu'un tour produit, quand tout ce qu'il fallait lire était déjà en mémoire.
  return [...cards.filter((card) => card.afterSteps === 0).map((card) => card.block), ...merged];
}
