import { TestBed } from '@angular/core/testing';

import {
  HostPresenceService,
  PRESENCE_CLOCK_MS,
  elapsedLabel,
  presenceLabel,
} from './host-presence.service';

/**
 * F-97 / SF-97-02 — l'état des postes tenu à un seul endroit, et un statut qui date au lieu
 * d'affirmer.
 */
describe('HostPresenceService', () => {
  const NOW = Date.parse('2026-09-13T10:00:00Z');
  const iso = (msAgo: number) => new Date(NOW - msAgo).toISOString();
  let service: HostPresenceService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(HostPresenceService);
  });

  describe('presenceLabel', () => {
    it('date un poste en ligne', () => {
      expect(presenceLabel(true, iso(12_000), NOW)).toBe('en ligne · vu il y a 12 s');
    });

    it('date un poste hors ligne', () => {
      expect(presenceLabel(false, iso(18 * 60_000), NOW)).toBe('hors ligne · vu il y a 18 min');
    });

    it('dit « jamais connecté » quand lastSeenAt est nul', () => {
      expect(presenceLabel(false, null, NOW)).toBe('jamais connecté');
    });

    it('passe aux heures puis aux jours', () => {
      expect(elapsedLabel(iso(3 * 3_600_000), NOW)).toBe('il y a 3 h');
      expect(elapsedLabel(iso(2 * 86_400_000), NOW)).toBe('il y a 2 j');
    });

    it('ne rend jamais une durée négative quand le navigateur retarde', () => {
      expect(presenceLabel(true, iso(-5_000), NOW)).toBe('en ligne · vu il y a 0 s');
    });
  });

  describe('les deux écrivains', () => {
    it('un refus met le poste hors ligne en gardant son dernier battement', () => {
      service.record('h1', true, iso(20_000));

      service.markOffline('h1', NOW);

      expect(service.isOnline('h1', true)).toBeFalse();
      expect(service.lastSeenAt('h1', null)).toBe(iso(20_000));
    });

    it('ignore un refus antérieur au dernier battement : le poste est revenu depuis', () => {
      service.record('h1', true, iso(5_000));

      service.markOffline('h1', NOW - 60_000);

      expect(service.isOnline('h1', false)).toBeTrue();
    });

    it("un relevé parti avant le refus ne le contredit pas", () => {
      service.markOffline('h1', NOW);

      service.record('h1', true, iso(30_000));

      expect(service.isOnline('h1', true)).toBeFalse();
    });

    it('un battement postérieur au refus rétablit « en ligne »', () => {
      service.markOffline('h1', NOW - 10_000);

      service.record('h1', true, iso(2_000));

      expect(service.isOnline('h1', false)).toBeTrue();
      expect(service.presence('h1')?.refusedAt).toBeNull();
    });

    it("retombe sur la donnée de l'écran quand il ne sait rien du poste", () => {
      expect(service.isOnline('inconnu', true)).toBeTrue();
      expect(service.label('inconnu', false, null)).toBe('jamais connecté');
    });

    it('ignore un poste absent', () => {
      service.markOffline(null);
      service.record(undefined, true, null);
      expect(service.presence(null)).toBeNull();
    });

    it("n'écrit rien pour un poste, sinon ce poste", () => {
      service.record('h1', true, iso(1_000));
      service.markOffline('h2', NOW);

      expect(service.isOnline('h1', false)).toBeTrue();
      expect(service.isOnline('h2', true)).toBeFalse();
    });
  });

  describe("l'horloge", () => {
    beforeEach(() => {
      jasmine.clock().install();
      jasmine.clock().mockDate(new Date(NOW));
    });

    afterEach(() => {
      service.ngOnDestroy();
      jasmine.clock().uninstall();
    });

    it('fait avancer le libellé à la seconde, sans aucun appel', () => {
      service.record('h1', true, iso(10_000));
      const release = service.watchClock();
      expect(service.label('h1', false, null)).toBe('en ligne · vu il y a 10 s');

      jasmine.clock().tick(PRESENCE_CLOCK_MS * 5);

      expect(service.label('h1', false, null)).toBe('en ligne · vu il y a 15 s');
      release();
    });

    it("s'arrête au dernier écran relâché", () => {
      const first = service.watchClock();
      const second = service.watchClock();
      first();
      first(); // Relâcher deux fois ne compte qu'une.
      jasmine.clock().tick(PRESENCE_CLOCK_MS);
      const afterOne = service.now();
      expect(afterOne).toBe(NOW + PRESENCE_CLOCK_MS);

      second();
      jasmine.clock().tick(PRESENCE_CLOCK_MS * 3);

      expect(service.now()).toBe(afterOne);
    });
  });
});
