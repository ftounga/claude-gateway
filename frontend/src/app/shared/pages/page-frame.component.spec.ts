import { TestBed } from '@angular/core/testing';

import { PAGE_SANDBOX, PageFrameComponent, isPageUrl } from './page-frame.component';

/** L'unique fabrique d'`iframe` de page (F-109 / SF-109-03, cadrage §3.1). */
describe('PageFrameComponent', () => {
  function render(url: string | null, thumbnail = false): HTMLElement {
    TestBed.configureTestingModule({ imports: [PageFrameComponent] });
    const fixture = TestBed.createComponent(PageFrameComponent);
    fixture.componentRef.setInput('url', url);
    fixture.componentRef.setInput('pageTitle', 'Maquette');
    fixture.componentRef.setInput('thumbnail', thumbnail);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('pose le bac à sable EXACT : scripts et fenêtres, et rien d\'autre', () => {
    const frame = render('/api/p/t1.abc.def/').querySelector('iframe');

    expect(frame).not.toBeNull();
    expect(frame!.getAttribute('sandbox')).toBe(PAGE_SANDBOX);
    expect(PAGE_SANDBOX).toBe('allow-scripts allow-popups');
    for (const forbidden of ['allow-same-origin', 'allow-forms', 'allow-top-navigation', 'allow-modals']) {
      expect(frame!.getAttribute('sandbox')).not.toContain(forbidden);
    }
    expect(frame!.getAttribute('referrerpolicy')).toBe('no-referrer');
    expect(frame!.getAttribute('src')).toBe('/api/p/t1.abc.def/');
    expect(frame!.getAttribute('title')).toBe('Page « Maquette »');
  });

  it("n'accorde sa confiance qu'aux adresses de lecture de la gateway", () => {
    expect(render('https://evil.example/page').querySelector('iframe')).toBeNull();
    expect(isPageUrl('javascript:alert(1)')).toBeFalse();
    expect(isPageUrl('/api/pages/1/content')).toBeFalse();
    expect(isPageUrl('/api/p/../me')).toBeFalse();
    expect(isPageUrl(null)).toBeFalse();
    expect(isPageUrl('/api/p/t1.a.b/')).toBeTrue();
  });

  it('la vignette est réduite et inerte', () => {
    const frame = render('/api/p/t1.abc.def/', true).querySelector('iframe')!;

    expect(frame.classList).toContain('page-frame--thumbnail');
    expect(frame.getAttribute('tabindex')).toBe('-1');
    expect(frame.getAttribute('aria-hidden')).toBe('true');
    expect(frame.getAttribute('loading')).toBe('lazy');
    expect(frame.getAttribute('sandbox')).toBe(PAGE_SANDBOX);
  });
});
