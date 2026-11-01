import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { JarvisCompanionDevice } from '../core/models/jarvis.models';
import { JarvisService } from '../core/services/jarvis.service';
import { JarvisCompanionsComponent } from './jarvis-companions.component';

/** F-232 / SF-232-03 — appairer le compagnon, voir les téléphones, en révoquer un. */
describe('JarvisCompanionsComponent', () => {
  let api: jasmine.SpyObj<JarvisService>;
  let fixture: ComponentFixture<JarvisCompanionsComponent>;
  const PIXEL: JarvisCompanionDevice = {
    id: 'c-1', label: 'Pixel de Franck', pairedAt: '2026-11-01T10:00:00Z', lastSeenAt: '2026-11-01T10:05:00Z',
  };

  function create(devices: JarvisCompanionDevice[], android: boolean): HTMLElement {
    api.companions.and.returnValue(of(devices));
    fixture = TestBed.createComponent(JarvisCompanionsComponent);
    fixture.componentRef.setInput('android', android);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function open(el: HTMLElement): void {
    (el.querySelector('.jc__toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  beforeEach(() => {
    api = jasmine.createSpyObj<JarvisService>('JarvisService',
      ['companions', 'companionPairingCode', 'revokeCompanion']);
    TestBed.configureTestingModule({
      imports: [JarvisCompanionsComponent],
      providers: [provideNoopAnimations(), { provide: JarvisService, useValue: api }],
    });
  });

  it('ne montre rien hors Android quand aucun téléphone n’est appairé', () => {
    const el = create([], false);
    expect(el.querySelector('.jc__toggle')).toBeNull();
  });

  it('affiche le code d’appairage sur Android', () => {
    api.companionPairingCode.and.returnValue(of({ code: 'ABCD2345', expiresAt: '2026-11-01T10:05:00Z' }));
    const el = create([], true);
    open(el);
    (el.querySelector('.jc__pair') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.jc__code-value')?.textContent).toContain('ABCD2345');
  });

  it('dit pourquoi au-delà de cinq téléphones', () => {
    api.companionPairingCode.and.returnValue(throwError(() => ({ status: 409 })));
    const el = create([], true);
    open(el);
    (el.querySelector('.jc__pair') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.jc__error')?.textContent).toContain('cinq');
  });

  it('liste les téléphones appairés et révoque après confirmation en ligne', () => {
    api.revokeCompanion.and.returnValue(of(undefined));
    const el = create([PIXEL], false);
    expect(el.querySelector('.jc__toggle')?.textContent).toContain('(1)');
    open(el);
    expect(el.querySelector('.jc__label')?.textContent).toContain('Pixel de Franck');

    (el.querySelector('.jc__revoke') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(api.revokeCompanion).not.toHaveBeenCalled();
    (el.querySelector('.jc__yes') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(api.revokeCompanion).toHaveBeenCalledWith('c-1');
    expect(el.querySelector('.jc__label')).toBeNull();
  });
});
