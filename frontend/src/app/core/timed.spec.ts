import { fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';

import { timed } from './timed';

describe('timed (F-233 / SF-233-16)', () => {
  it('rend la valeur à temps', fakeAsync(() => {
    let value: string | null = null;
    void timed(Promise.resolve('ok'), 1_000).then((v) => (value = v));
    flushMicrotasks();
    expect(value as string | null).toBe('ok');
    tick(1_000);
  }));

  it('rejette après le délai ; une valeur tardive part à onLate', fakeAsync(() => {
    let resolveLate: (v: string) => void = () => undefined;
    const late = new Promise<string>((r) => (resolveLate = r));
    const seenLate: string[] = [];
    let error: unknown = null;
    void timed(late, 1_000, (v) => seenLate.push(v)).catch((e) => (error = e));
    tick(1_000);
    flushMicrotasks();
    expect((error as Error).message).toBe('timeout');
    resolveLate('tard');
    flushMicrotasks();
    expect(seenLate).toEqual(['tard']);
  }));

  it('propage un rejet à temps', fakeAsync(() => {
    let error: unknown = null;
    void timed(Promise.reject(new Error('refus')), 1_000).catch((e) => (error = e));
    flushMicrotasks();
    expect((error as Error).message).toBe('refus');
    tick(1_000);
  }));
});
