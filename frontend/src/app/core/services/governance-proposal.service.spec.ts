import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { GovernanceProposalService } from './governance-proposal.service';

/** Les propositions de gouvernance d'un terminal (F-177) et leur recopie sur tous mes postes (F-266). */
describe('GovernanceProposalService', () => {
  let service: GovernanceProposalService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(GovernanceProposalService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('relit, applique et refuse une proposition par son terminal', () => {
    service.get('ws-1', 'p-1').subscribe();
    service.apply('ws-1', 'p-1').subscribe();
    service.refuse('ws-1', 'p-1').subscribe();

    expect(http.expectOne('/api/workspaces/ws-1/governance-proposals/p-1').request.method).toBe('GET');
    expect(http.expectOne('/api/workspaces/ws-1/governance-proposals/p-1/apply').request.method).toBe('POST');
    expect(http.expectOne('/api/workspaces/ws-1/governance-proposals/p-1/refuse').request.method).toBe('POST');
  });

  it('F-266 : recopie une règle appliquée sur tous mes postes', () => {
    let title: string | undefined;
    service.promote('ws-1', 'p-1').subscribe((rule) => (title = rule.title));

    const request = http.expectOne('/api/workspaces/ws-1/governance-proposals/p-1/promote');
    expect(request.request.method).toBe('POST');
    request.flush({ id: 'r-1', title: 'Jira', content: 'Commentaires Jira courts.' });
    expect(title).toBe('Jira');
  });
});
