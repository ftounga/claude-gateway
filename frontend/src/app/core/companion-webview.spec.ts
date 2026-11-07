import { dropServiceWorkerInCompanion, inCompanionWebView, SW_DROPPED_KEY } from './companion-webview';

describe('companion-webview (F-233 / SF-233-16)', () => {
  function storage(): Pick<Storage, 'getItem' | 'setItem'> & { data: Record<string, string> } {
    const data: Record<string, string> = {};
    return { data, getItem: (k) => data[k] ?? null, setItem: (k, v) => { data[k] = v; } };
  }

  function container(controlled: boolean, count = 1) {
    const unregister = jasmine.createSpy('unregister').and.resolveTo(true);
    return {
      unregister,
      nav: { serviceWorker: {
        controller: controlled ? ({} as ServiceWorker) : null,
        getRegistrations: () => Promise.resolve(Array.from({ length: count }, () => ({ unregister }) as never)),
      } },
    };
  }

  it('repère la WebView du compagnon par son pont', () => {
    expect(inCompanionWebView({ JarvisCompanion: {} })).toBeTrue();
    expect(inCompanionWebView({})).toBeFalse();
    expect(inCompanionWebView(undefined)).toBeFalse();
  });

  it('hors compagnon : rien n’est désinscrit', async () => {
    const c = container(true);
    const reload = jasmine.createSpy('reload');
    expect(await dropServiceWorkerInCompanion({}, c.nav, storage(), reload)).toBe('none');
    expect(c.unregister).not.toHaveBeenCalled();
    expect(reload).not.toHaveBeenCalled();
  });

  it('dans le compagnon, page servie par le service worker : désinscrit et rechargée une fois', async () => {
    const c = container(true);
    const s = storage();
    const reload = jasmine.createSpy('reload');
    expect(await dropServiceWorkerInCompanion({ JarvisCompanion: {} }, c.nav, s, reload)).toBe('reloading');
    expect(c.unregister).toHaveBeenCalled();
    expect(reload).toHaveBeenCalledTimes(1);
    expect(s.data[SW_DROPPED_KEY]).toBe('1');
    // Toujours servie par lui après le rechargement : jamais de boucle.
    expect(await dropServiceWorkerInCompanion({ JarvisCompanion: {} }, c.nav, s, reload)).toBe('dropped');
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('dans le compagnon, page venue du réseau : désinscrit sans recharger', async () => {
    const c = container(false);
    const reload = jasmine.createSpy('reload');
    expect(await dropServiceWorkerInCompanion({ JarvisCompanion: {} }, c.nav, storage(), reload)).toBe('dropped');
    expect(reload).not.toHaveBeenCalled();
    const none = container(false, 0);
    expect(await dropServiceWorkerInCompanion({ JarvisCompanion: {} }, none.nav, storage(), reload)).toBe('none');
  });
});
