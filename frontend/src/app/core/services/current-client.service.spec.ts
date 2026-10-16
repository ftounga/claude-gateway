import { TestBed } from '@angular/core/testing';

import { CurrentClientService } from './current-client.service';

/** Le client courant (F-199 / SF-199-01). */
describe('CurrentClientService', () => {
  let service: CurrentClientService;

  beforeEach(() => {
    service = TestBed.inject(CurrentClientService);
  });

  it('est vide tant qu\'aucun client n\'est ouvert', () => {
    expect(service.ref()).toBeNull();
  });

  it('retient le dernier client ouvert, et bascule sur le suivant', () => {
    service.set('h1');
    expect(service.ref()).toBe('h1');
    service.set('h2');
    expect(service.ref()).toBe('h2');
  });

  it('une référence vide efface, comme clear()', () => {
    service.set('h1');
    service.set('  ');
    expect(service.ref()).toBeNull();
    service.set('heberge');
    service.clear();
    expect(service.ref()).toBeNull();
  });
});
