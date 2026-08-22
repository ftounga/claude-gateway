import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Type } from '@angular/core';

import { MentionsLegalesComponent } from './mentions-legales.component';
import { ConfidentialiteComponent } from './confidentialite.component';
import { CguComponent } from './cgu.component';
import { ContactComponent } from './contact.component';
import { LEGAL_COMPANY, LEGAL_HOST } from './legal-info';

/** Monte un composant légal et renvoie son texte rendu. */
function render<T>(component: Type<T>): { fixture: ComponentFixture<T>; text: string } {
  TestBed.configureTestingModule({
    imports: [component],
    providers: [provideRouter([]), provideNoopAnimations()],
  });
  const fixture = TestBed.createComponent(component);
  fixture.detectChanges();
  return { fixture, text: (fixture.nativeElement as HTMLElement).textContent ?? '' };
}

describe('MentionsLegalesComponent', () => {
  it("identifie l'éditeur : raison sociale, forme, capital, SIREN et siège", () => {
    const { text } = render(MentionsLegalesComponent);
    expect(text).toContain(LEGAL_COMPANY.name);
    expect(text).toContain(LEGAL_COMPANY.legalForm);
    expect(text).toContain(LEGAL_COMPANY.capital);
    expect(text).toContain(LEGAL_COMPANY.siren);
    expect(text).toContain(LEGAL_COMPANY.address);
  });

  it('nomme un directeur de la publication personne physique (LCEN art. 6-III)', () => {
    const { text } = render(MentionsLegalesComponent);
    expect(text).toContain('Directeur de la publication');
    expect(text).toContain(LEGAL_COMPANY.publicationDirector);
  });

  it("identifie l'hébergeur", () => {
    const { text } = render(MentionsLegalesComponent);
    expect(text).toContain(LEGAL_HOST.name);
    expect(text).toContain(LEGAL_HOST.address);
  });

  it("indique que le service n'est pas un service d'anonymisation", () => {
    const { text } = render(MentionsLegalesComponent);
    expect(text).toMatch(/anonym/i);
    expect(text).toMatch(/contourner/i);
  });
});

describe('ConfidentialiteComponent', () => {
  it('désigne le responsable du traitement et son contact', () => {
    const { text } = render(ConfidentialiteComponent);
    expect(text).toContain(LEGAL_COMPANY.name);
    expect(text).toContain(LEGAL_COMPANY.contactEmail);
  });

  it('déclare les sous-traitants et les transferts hors Union européenne', () => {
    const { text } = render(ConfidentialiteComponent);
    expect(text).toContain('Anthropic');
    expect(text).toContain('Stripe');
    expect(text).toContain(LEGAL_HOST.name);
    expect(text).toMatch(/hors Union européenne/i);
    expect(text).toMatch(/clauses contractuelles types/i);
  });

  it('énonce les droits des personnes et la voie de réclamation', () => {
    const { text } = render(ConfidentialiteComponent);
    expect(text).toMatch(/accès, de rectification, d'effacement/i);
    expect(text).toContain('CNIL');
  });

  it('précise les durées de conservation', () => {
    const { text } = render(ConfidentialiteComponent);
    expect(text).toMatch(/dix ans/i);
  });
});

describe('CguComponent', () => {
  it('couvre les sections attendues', () => {
    const { text } = render(CguComponent);
    for (const section of [
      'Objet',
      'Description du service',
      'Accès et compte',
      'Usage acceptable',
      'Offres, facturation',
      'Disponibilité',
      'Responsabilité',
      'Données personnelles',
      'Suspension et résiliation',
      'Droit applicable',
    ]) {
      expect(text).toContain(section);
    }
  });

  it('interdit explicitement le contournement de filtrage et le relais de trafic', () => {
    const { text } = render(CguComponent);
    expect(text).toMatch(/contourner un dispositif de filtrage/i);
    expect(text).toMatch(/relayer du trafic de tiers/i);
  });

  it("avertit que les réponses de l'assistant peuvent être inexactes", () => {
    const { text } = render(CguComponent);
    expect(text).toMatch(/inexactes/i);
  });
});

describe('ContactComponent', () => {
  it("affiche l'adresse électronique et l'adresse postale", () => {
    const { text } = render(ContactComponent);
    expect(text).toContain(LEGAL_COMPANY.contactEmail);
    expect(text).toContain(LEGAL_COMPANY.address);
  });

  it("ne contient aucun formulaire (aucun endpoint n'est créé)", () => {
    const { fixture } = render(ContactComponent);
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('form').length).toBe(0);
    expect(host.querySelectorAll('input').length).toBe(0);
  });
});
