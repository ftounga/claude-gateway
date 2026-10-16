import { Pipe, PipeTransform } from '@angular/core';

import { MessageSegment } from './copy-block.model';
import { splitMessageBlocks } from './message-segments';

/**
 * Pipe `messageBlocks` (F-203 / SF-203-02) : découpe un texte d'agent en prose / blocs « message à
 * envoyer » (```mail, ```message). Pipe pur : recalculé seulement quand le texte change.
 */
@Pipe({ name: 'messageBlocks', standalone: true })
export class MessageBlocksPipe implements PipeTransform {
  transform(content: string | null | undefined): MessageSegment[] {
    return splitMessageBlocks(content);
  }
}
