import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { TurnOutcome } from './turn-suggestions';
import { TurnSuggestionsComponent } from './turn-suggestions.component';

/**
 * Les puces de suite, sous la zone de saisie (F-144 / SF-144-01).
 *
 * <p>Deux garanties : un clic <b>remplit</b> et n'envoie rien — rien ne part vers la machine d'un
 * client sans un geste — et cette feature <b>ne fait aucun appel réseau</b>, ce qui est le cœur de
 * sa conception.</p>
 */
describe('TurnSuggestionsComponent', () => {
  @Component({
    imports: [TurnSuggestionsComponent],
    template: `<app-turn-suggestions [report]="report()" [draft]="draft()" (pick)="picked = $event" />`,
  })
  class HostComponent {
    readonly report = signal<TurnOutcome | null>(null);
    readonly draft = signal('');
    picked: string | null = null;
  }

  let fixture: ComponentFixture<HostComponent>;
  let http: HttpTestingController;

  function dom(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function chips(): HTMLButtonElement[] {
    return Array.from(dom().querySelectorAll('.suggestions__chip'));
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HostComponent],
      // Fournis pour PROUVER qu'aucun appel n'est fait : `http.verify()` échouerait sinon.
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(HostComponent);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('LE CRITÈRE : un clic REMPLIT le champ et n\'envoie rien', () => {
    fixture.componentInstance.report.set({ interrupted: true });
    fixture.detectChanges();

    chips()[0].click();
    fixture.detectChanges();

    expect(fixture.componentInstance.picked).toBe('Reprends là où tu t\'es arrêté.');
    // Et rien n'est parti : c'est la règle du produit, et ça permet de corriger la phrase avant.
    http.verify();
  });

  it('cette feature ne fait AUCUN appel réseau', () => {
    // C'est le cœur de sa conception : un appel modèle coûterait un tour de plus par réponse.
    fixture.componentInstance.report.set({
      plan: [{ title: 'Vérifier le certificat', status: 'pending' }],
      interrupted: true,
      diffs: [{}],
    });
    fixture.detectChanges();

    expect(chips().length).toBeGreaterThan(0);
    http.expectNone(() => true);
  });

  it('les puces s\'effacent dès que l\'utilisateur écrit', () => {
    fixture.componentInstance.report.set({ interrupted: true });
    fixture.detectChanges();
    expect(chips()).toHaveSize(1);

    fixture.componentInstance.draft.set('autre chose');
    fixture.detectChanges();

    expect(chips()).toHaveSize(0);
  });

  it('n\'affiche rien quand il n\'y a rien à suggérer', () => {
    fixture.componentInstance.report.set({});
    fixture.detectChanges();

    expect(dom().querySelector('.suggestions')).toBeNull();
  });
});
