import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { AppRequestStatus, AppRequestView, AtelierAppRequest } from '../../core/models/atelier.models';
import { AppRequestService } from '../../core/services/app-request.service';
import { BesoinCardComponent, besoinBlock } from './besoin-card.component';

describe('BesoinCardComponent (F-205 / SF-205-02)', () => {
  const besoin: AtelierAppRequest = {
    requestId: 'r-1',
    kind: 'BUG',
    title: 'Le terminal se fige',
    description: 'quand je colle une image le terminal se fige',
    reformulation: 'Le collage d’image gèle la saisie.',
    observedContext: 'Terminal du poste, Chrome',
    attachments: [{ position: 1, name: 'capture.png', contentType: 'image/png', size: 2048 }],
  };

  function view(status: AppRequestStatus): AppRequestView {
    return {
      id: 'r-1', workspaceId: 'ws-1', kind: 'BUG', title: besoin.title, description: besoin.description,
      status, createdAt: '2026-10-16T10:00:00Z', updatedAt: '2026-10-16T10:00:00Z', attachments: [],
    };
  }

  let fixture: ComponentFixture<BesoinCardComponent>;
  let service: jasmine.SpyObj<AppRequestService>;

  function create(status: AppRequestStatus = 'PROPOSE', readOnly = false): HTMLElement {
    service.get.and.returnValue(of(view(status)));
    fixture = TestBed.createComponent(BesoinCardComponent);
    fixture.componentRef.setInput('besoin', besoin);
    fixture.componentRef.setInput('workspaceId', 'ws-1');
    fixture.componentRef.setInput('readOnly', readOnly);
    fixture.detectChanges();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function buttons(el: HTMLElement): HTMLButtonElement[] {
    return Array.from(el.querySelectorAll('button'));
  }

  beforeEach(() => {
    service = jasmine.createSpyObj('AppRequestService', ['get', 'record', 'dismiss']);
    TestBed.configureTestingModule({
      imports: [BesoinCardComponent],
      providers: [{ provide: AppRequestService, useValue: service }],
    });
  });

  it('montre les mots, la reformulation, le contexte et les pièces, sans rien enregistrer', () => {
    const el = create();
    expect(el.textContent).toContain('Bug de l’application · Le terminal se fige');
    expect(el.textContent).toContain('quand je colle une image le terminal se fige');
    expect(el.textContent).toContain('Le collage d’image gèle la saisie.');
    expect(el.textContent).toContain('Terminal du poste, Chrome');
    expect(el.textContent).toContain('capture.png');
    expect(el.textContent).toContain('2 Ko');
    expect(service.record).not.toHaveBeenCalled();
    expect(buttons(el).map((b) => b.textContent?.trim())).toEqual(['Enregistrer', 'Non']);
  });

  it('[Enregistrer] enregistre et le dit', () => {
    service.record.and.returnValue(of(view('EN_ATTENTE')));
    const el = create();
    buttons(el)[0].click();
    fixture.detectChanges();
    expect(service.record).toHaveBeenCalledWith('ws-1', 'r-1');
    expect(el.textContent).toContain('Enregistré au registre des besoins.');
    expect(buttons(el).length).toBe(0);
  });

  it('[Non] écarte', () => {
    service.dismiss.and.returnValue(of(view('REJETE')));
    const el = create();
    buttons(el)[1].click();
    fixture.detectChanges();
    expect(service.dismiss).toHaveBeenCalledWith('ws-1', 'r-1');
    expect(el.textContent).toContain('Écarté');
  });

  it('un 409 relit le statut réel', () => {
    service.record.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 409, error: { message: 'Ce besoin est déjà enregistré.' },
    })));
    const el = create();
    service.get.and.returnValue(of(view('EN_ATTENTE')));
    buttons(el)[0].click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Ce besoin est déjà enregistré.');
    expect(el.textContent).toContain('Enregistré au registre des besoins.');
  });

  it('relu après le tour : un besoin déjà enregistré ne propose plus de bouton', () => {
    const el = create('CADRE');
    expect(buttons(el).length).toBe(0);
    expect(el.textContent).toContain('Enregistré');
  });

  it('en lecture seule, aucun bouton', () => {
    const el = create('PROPOSE', true);
    expect(buttons(el).length).toBe(0);
    expect(el.textContent).toContain('En attente de décision.');
  });

  it('statut illisible : la carte reste décidable', () => {
    service.get.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
    fixture = TestBed.createComponent(BesoinCardComponent);
    fixture.componentRef.setInput('besoin', besoin);
    fixture.componentRef.setInput('workspaceId', 'ws-1');
    fixture.detectChanges();
    fixture.detectChanges();
    expect(buttons(fixture.nativeElement).length).toBe(2);
  });

  it('besoinBlock range la carte dans un bloc de transcription', () => {
    const block = besoinBlock('t-1', besoin);
    expect(block.besoin).toBe(besoin);
    expect(block.tool).toBe('signaler_besoin');
    expect(block.toolUseId).toBe('t-1');
  });
});
