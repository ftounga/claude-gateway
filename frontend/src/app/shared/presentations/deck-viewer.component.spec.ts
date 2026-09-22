import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { HttpResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { ExportService } from '../../core/services/export.service';
import { PresentationService } from '../../core/services/presentation.service';
import { DeckViewerComponent } from './deck-viewer.component';

/** La visionneuse de deck (F-129 / SF-129-03) : slide en grand + miniatures + navigation. */
describe('DeckViewerComponent', () => {
  let fixture: ComponentFixture<DeckViewerComponent>;
  let service: jasmine.SpyObj<PresentationService>;
  let files: jasmine.SpyObj<ExportService>;

  function build(slideCount: number | null): HTMLElement {
    service = jasmine.createSpyObj<PresentationService>('PresentationService', ['slide', 'download']);
    service.slide.and.returnValue(of(new Blob(['img'], { type: 'image/png' })));
    service.download.and.returnValue(of(new HttpResponse({ body: new Blob(['PK']) })));
    files = jasmine.createSpyObj<ExportService>('ExportService', ['triggerDownload']);
    TestBed.configureTestingModule({
      imports: [DeckViewerComponent],
      providers: [
        provideNoopAnimations(),
        { provide: PresentationService, useValue: service },
        { provide: ExportService, useValue: files },
        { provide: MatSnackBar, useValue: jasmine.createSpyObj('MatSnackBar', ['open']) },
      ],
    });
    fixture = TestBed.createComponent(DeckViewerComponent);
    fixture.componentRef.setInput('presentationId', 'd1');
    fixture.componentRef.setInput('title', 'Onboarding CI/CD');
    fixture.componentRef.setInput('slideCount', slideCount);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('rend une miniature par slide et charge chaque image', fakeAsync(() => {
    const root = build(3);
    flushMicrotasks();
    fixture.detectChanges();
    expect(service.slide).toHaveBeenCalledTimes(3);
    expect(service.slide).toHaveBeenCalledWith('d1', 1);
    expect(service.slide).toHaveBeenCalledWith('d1', 3);
    expect(root.querySelectorAll('.deckview__thumb').length).toBe(3);
    expect(root.querySelector('.deckview__pos')?.textContent).toContain('1 / 3');
  }));

  it('navigue avec les flèches et par clic sur une miniature', fakeAsync(() => {
    build(3);
    flushMicrotasks();
    fixture.detectChanges();
    const c = fixture.componentInstance;
    expect(c.current()).toBe(1);
    c.onRight();
    expect(c.current()).toBe(2);
    c.onLeft();
    expect(c.current()).toBe(1);
    // Pas de débordement des bornes.
    c.onLeft();
    expect(c.current()).toBe(1);
    c.go(3);
    expect(c.current()).toBe(3);
    c.onRight();
    expect(c.current()).toBe(3);
  }));

  it('Échap ferme la visionneuse', () => {
    build(2);
    const c = fixture.componentInstance;
    let closed = false;
    c.close.subscribe(() => (closed = true));
    c.onEscape();
    expect(closed).toBeTrue();
  });

  it('sans rendu (slideCount null) : état de repli, aucune image chargée, téléchargement possible', () => {
    const root = build(null);
    expect(service.slide).not.toHaveBeenCalled();
    expect(root.textContent).toContain("aperçu n'est pas encore disponible");
    fixture.componentInstance.download();
    expect(service.download).toHaveBeenCalledWith('d1');
    expect(files.triggerDownload).toHaveBeenCalled();
  });
});
