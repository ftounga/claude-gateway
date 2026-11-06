import { OAUTH_RETRY_KEY, claimRetry } from './oauth-callback.component';

describe('claimRetry (SF-01-10)', () => {
  beforeEach(() => sessionStorage.removeItem(OAUTH_RETRY_KEY));
  afterEach(() => sessionStorage.removeItem(OAUTH_RETRY_KEY));

  it('permet une reprise, puis la refuse dans la minute (pas de boucle)', () => {
    expect(claimRetry(1_000_000)).toBeTrue();
    expect(claimRetry(1_030_000)).toBeFalse();
  });

  it('permet à nouveau une reprise après une minute', () => {
    expect(claimRetry(1_000_000)).toBeTrue();
    expect(claimRetry(1_061_000)).toBeTrue();
  });
});
