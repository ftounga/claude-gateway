import { of } from 'rxjs';

import { TerminalActionsService } from './terminal-actions.service';

/**
 * **Le double de test du service des attentes** (F-180) : un espion pour **chaque** méthode du service,
 * et des réponses neutres pour les lectures que le panneau fait d'office (listes vides). Un écran qui
 * gagne une lecture ne casse plus les tests qui ne la regardent pas.
 */
export function mockTerminalActionsService(): jasmine.SpyObj<TerminalActionsService> {
  const methods = Object.getOwnPropertyNames(TerminalActionsService.prototype)
    .filter(name => name !== 'constructor') as (keyof TerminalActionsService)[];
  const service = jasmine.createSpyObj<TerminalActionsService>('TerminalActionsService', methods);
  service.review.and.returnValue(of([]));
  service.duplicates.and.returnValue(of([]));
  service.qualify.and.returnValue(of([]));
  return service;
}
