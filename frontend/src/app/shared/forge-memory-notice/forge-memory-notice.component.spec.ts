import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { GovernanceHostSummary, HostMemoryState } from '../../core/models/governance.models';
import { ForgeMemoryNoticeComponent } from './forge-memory-notice.component';

/**
 * Les clients sans mémoire, signalés dans la Forge (F-135 / SF-135-02).
 *
 * <p>Ce qui compte ici : le bandeau ne parle que des postes qui n'apprennent <b>pas</b>, il ne
 * propose jamais un geste sans effet (poste « Hébergé »), et une API en échec n'écrit rien.</p>
 */
describe('ForgeMemoryNoticeComponent', () => {
  let fixture: ComponentFixture<ForgeMemoryNoticeComponent>;
  let http: HttpTestingController;

  const URL = '/api/governance/hosts';

  function host(name: string, memory: HostMemoryState, over: Partial<GovernanceHostSummary> = {}): GovernanceHostSummary {
    return {
      ref: name.toLowerCase(),
      id: name.toLowerCase(),
      name,
      virtual: memory === 'UNSUPPORTED',
      projects: 1,
      active: memory === 'ABSENT' ? 0 : 1,
      outdated: 0,
      memory,
      facts: memory === 'ACTIVE' ? 2593 : 0,
      ...over,
    };
  }

  function setup(hosts: GovernanceHostSummary[]): void {
    fixture.detectChanges();
    http.expectOne(URL).flush(hosts);
    fixture.detectChanges();
  }

  function dom(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function banner(): HTMLElement | null {
    return dom().querySelector('.memory-notice');
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ForgeMemoryNoticeComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
    fixture = TestBed.createComponent(ForgeMemoryNoticeComponent);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('nomme les clients qui n\'accumulent aucun savoir', () => {
    setup([host('CAGIP', 'ACTIVE'), host('FREE', 'ABSENT'), host('EDENRED', 'PENDING')]);

    expect(banner()).not.toBeNull();
    expect(dom().textContent).toContain('FREE');
    expect(dom().textContent).toContain('EDENRED');
    // Celui qui apprend n'a rien à faire là.
    expect(dom().textContent).not.toContain('CAGIP');
    expect(dom().textContent).toContain('2 clients');
  });

  it('distingue « activé mais fichiers non posés » de « rien du tout »', () => {
    // C'est l'état le plus trompeur : de l'extérieur, le poste semble gouverné.
    setup([host('EDENRED', 'PENDING')]);

    expect(dom().textContent).toContain('activé, fichiers non posés');
  });

  it('ne dit rien quand tous les clients apprennent', () => {
    setup([host('CAGIP', 'ACTIVE')]);

    expect(banner()).toBeNull();
  });

  it('ne propose jamais le geste sur le poste « Hébergé »', () => {
    // Il n'a pas de racine, donc pas de carte : le geste serait sans effet.
    setup([host('Hébergé', 'UNSUPPORTED')]);

    expect(banner()).toBeNull();
  });

  it('n\'affiche rien et ne casse pas l\'écran quand la gateway échoue', () => {
    fixture.detectChanges();
    http.expectOne(URL).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(banner()).toBeNull();
  });

  it('met le poste en mémoire, puis RELIT l\'état plutôt que de le supposer', () => {
    setup([host('FREE', 'ABSENT')]);

    (banner()!.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();

    const posted = http.expectOne('/api/governance/hosts/free/memory');
    expect(posted.request.method).toBe('POST');
    posted.flush('ACTIVE');
    fixture.detectChanges();

    // La machine a pu ne pas répondre : on ne suppose pas le résultat, on relit.
    http.expectOne(URL).flush([host('FREE', 'ACTIVE')]);
    fixture.detectChanges();

    expect(banner()).toBeNull();
  });

  it('laisse le poste listé quand la machine n\'a pas répondu', () => {
    setup([host('FREE', 'ABSENT')]);

    (banner()!.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    http.expectOne('/api/governance/hosts/free/memory').flush('PENDING');
    fixture.detectChanges();
    http.expectOne(URL).flush([host('FREE', 'PENDING')]);
    fixture.detectChanges();

    expect(banner()).not.toBeNull();
    expect(dom().textContent).toContain('activé, fichiers non posés');
  });

  it('un geste refusé ne casse pas le bandeau', () => {
    setup([host('FREE', 'ABSENT')]);

    (banner()!.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    http.expectOne('/api/governance/hosts/free/memory')
      .flush('non', { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(banner()).not.toBeNull();
    expect(dom().querySelector('button')).not.toBeNull();
  });
});
