import { Pipe, PipeTransform } from '@angular/core';

import { MessageSegment } from './copy-block.model';
import { splitMessageSegments } from './message-segments';

/**
 * Pipe `messageSegments` : découpe le contenu d'un message assistant en segments ordonnés
 * (prose / bloc copiable) pour un rendu inline façon ChatGPT (F-26).
 *
 * <p>Pipe **pur** : mémoïsé sur la valeur du contenu, il ne recalcule que lorsque le texte change
 * (utile pendant le streaming). Aucune dépendance réseau ni fournisseur IA.</p>
 */
@Pipe({ name: 'messageSegments', standalone: true })
export class MessageSegmentsPipe implements PipeTransform {
  transform(content: string | null | undefined): MessageSegment[] {
    return splitMessageSegments(content);
  }
}
