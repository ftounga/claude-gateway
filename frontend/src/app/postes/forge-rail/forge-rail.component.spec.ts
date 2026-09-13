import { ComponentFixture, TestBed } from '@angular/core/testing';

import { RunnerHostOverview } from '../../core/models/atelier.models';
import { hostTone } from '../../shared/host-identity';
import { ForgeGroup, groupHosts } from '../forge-fleet';
import { ForgeRailComponent } from './forge-rail.component';

/** La colonne des postes (F-98 / SF-98-01) : ce qu'elle montre, et ce qu'elle émet. */
describe('ForgeRailComponent', () => {
  let fixture: ComponentFixture<ForgeRailComponent>;
  let component: ForgeRailComponent;

  const host = (id: string, name: string, extra: Partial<RunnerHostOverview> = {}): RunnerHostOverview => ({
    id, name, connected: true, activeProjects: 0, createdAt: '',
    lastSeenAt: new Date(Date.now() - 12_000).toISOString(),
    projects: [
      { id: `${id}-w1`, name: 'audit-iam', calls: 0, active: false },
      { id: `${id}-w2`, name: 'rapport', calls: 0, active: false },
    ],
    ...extra,
  });

  const hosted: RunnerHostOverview = {
    id: null, name: 'Hébergé', virtual: true, connected: false, activeProjects: 0, createdAt: null,
    projects: [],
  };

  function render(groups: ForgeGroup[], inputs: { selectedRef?: string; filter?: string;
    closedOpen?: boolean } = {}): HTMLElement {
    TestBed.configureTestingModule({ imports: [ForgeRailComponent] });
    fixture = TestBed.createComponent(ForgeRailComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('groups', groups);
    fixture.componentRef.setInput('selectedRef', inputs.selectedRef ?? null);
    fixture.componentRef.setInput('filter', inputs.filter ?? '');
    fixture.componentRef.setInput('closedOpen', inputs.closedOpen ?? false);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const rows = (root: HTMLElement) => Array.from(root.querySelectorAll<HTMLElement>('.forge-rail__host'));

  it('écrit chaque groupe et chaque poste, avec son statut daté et son nombre de projets', () => {
    const root = render(groupHosts([host('h1', 'FREE'), host('h2', 'CAGIP', { connected: false }), hosted],
      (h) => h.connected, ''));

    expect(Array.from(root.querySelectorAll('.forge-rail__group')).map((g) => g.textContent?.trim()))
      .toEqual(['En ligne', 'Hors ligne', 'Sans machine']);
    expect(rows(root)[0].textContent).toContain('FREE');
    // F-98 / SF-98-05 : écrit comme une phrase.
    expect(rows(root)[0].textContent).toContain('En ligne · vu il y a');
    expect(rows(root)[0].querySelector('.forge-rail__count')?.textContent?.trim()).toBe('2');
    expect(rows(root)[1].textContent).toContain('Hors ligne · vu il y a');
  });

  it('écrit « Jamais connecté » pour un poste qui n’a jamais battu', () => {
    const root = render(groupHosts([host('h9', 'Richemont', { connected: false, lastSeenAt: null })],
      (h) => h.connected, ''));

    expect(rows(root)[0].textContent).toContain('Jamais connecté');
  });

  it('porte le drapeau « attend » quand une autorisation attend (§12)', () => {
    const waiting = host('h1', 'EDENRED', {
      projects: [{ id: 'w', name: 'x', calls: 0, active: false,
        terminalPreview: { activity: 'AWAITING_APPROVAL', lines: [] } }],
    });
    const root = render(groupHosts([waiting], (h) => h.connected, ''));

    const flag = root.querySelector('.forge-rail__flag');
    expect(flag?.textContent?.trim()).toBe('1 attend');
    expect(flag?.classList).toContain('badge--warning');
  });

  it('marque la ligne ouverte d’un filet de la couleur du poste, et aucune autre', () => {
    const root = render(groupHosts([host('h1', 'FREE'), host('h2', 'CAGIP')], (h) => h.connected, ''),
      { selectedRef: 'h2' });
    const [free, cagip] = rows(root);

    expect(free.classList).not.toContain('forge-rail__host--selected');
    expect(cagip.classList).toContain('forge-rail__host--selected');
    expect(cagip.getAttribute('aria-current')).toBe('true');
    expect(cagip.style.getPropertyValue('--forge-tone').trim().toLowerCase())
      .toBe(hostTone('CAGIP').solid.toLowerCase());
  });

  it('ne donne au poste « Hébergé » ni pastille d’initiales, ni point de statut, ni couleur', () => {
    const root = render(groupHosts([hosted], () => false, ''), { selectedRef: 'heberge' });
    const [row] = rows(root);

    expect(row.querySelector('.host-badge__mark')).toBeNull();
    expect(row.querySelector('.forge-rail__dot')).toBeNull();
    expect(row.style.getPropertyValue('--forge-tone')).toBe('');
  });

  it('dit « projets trouvés » pour un poste retenu par ses projets', () => {
    const root = render(groupHosts([host('h1', 'FREE')], (h) => h.connected, 'audit'), { filter: 'audit' });

    expect(rows(root)[0].textContent).toContain('1 projet trouvé');
  });

  it('replie les clôturées au départ, et les montre repli ouvert', () => {
    const groups = groupHosts([host('h1', 'Ancien', { missionStatus: 'CLOSED' })], (h) => h.connected, '');
    const closed = render(groups);

    expect(closed.textContent).toContain('Missions clôturées (1)');
    expect(rows(closed).length).toBe(0);

    fixture.componentRef.setInput('closedOpen', true);
    fixture.detectChanges();
    expect(rows(closed).length).toBe(1);
  });

  it('émet la sélection, le filtre, le repli et « Connecter un poste »', () => {
    const groups = groupHosts([host('h1', 'FREE'), host('h2', 'Z', { missionStatus: 'CLOSED' })],
      (h) => h.connected, '');
    const root = render(groups);
    const selected: string[] = [];
    const filters: string[] = [];
    let toggled = 0;
    let connected = 0;
    component.selectHost.subscribe((row) => selected.push(row.ref));
    component.filterChange.subscribe((value) => filters.push(value));
    component.toggleClosed.subscribe(() => toggled++);
    component.connectHost.subscribe(() => connected++);

    rows(root)[0].click();
    const input = root.querySelector('input') as HTMLInputElement;
    input.value = 'fr';
    input.dispatchEvent(new Event('input'));
    (root.querySelector('.forge-rail__group--toggle') as HTMLButtonElement).click();
    (root.querySelector('.forge-rail__connect') as HTMLButtonElement).click();

    expect(selected).toEqual(['h1']);
    expect(filters).toEqual(['fr']);
    expect(toggled).toBe(1);
    expect(connected).toBe(1);
  });

  it('dit quand rien ne correspond au filtre', () => {
    const root = render([], { filter: 'zzz' });

    expect(root.textContent).toContain('Aucun poste ni projet ne correspond.');
  });
});
