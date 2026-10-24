import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Une voix du fournisseur (F-215 / SF-215-01). */
export interface SpeechVoice {
  id: string;
  label: string;
}

/** La voix par défaut du compte. */
export interface SpeechPreference {
  voice: string;
  instructions: string | null;
}

/** Ce que l'écran Paramètres › Vidéos affiche. */
export interface SpeechVoicesView {
  enabled: boolean;
  voices: SpeechVoice[];
  defaultVoice: string;
  defaultInstructions: string;
  preference: SpeechPreference | null;
}

/** Voix off des vidéos : voix disponibles, voix par défaut du compte, échantillons (F-215 / SF-215-01). */
@Injectable({ providedIn: 'root' })
export class SpeechService {
  private readonly http = inject(HttpClient);

  voices(): Observable<SpeechVoicesView> {
    return this.http.get<SpeechVoicesView>('/api/speech/voices');
  }

  save(preference: { voice: string; instructions: string | null }): Observable<SpeechVoicesView> {
    return this.http.put<SpeechVoicesView>('/api/speech/preference', preference);
  }

  reset(): Observable<void> {
    return this.http.delete<void>('/api/speech/preference');
  }

  /** L'échantillon fixe d'une voix (mp3), lu avec le jeton : un `<audio src>` ne le porterait pas. */
  sample(voice: string): Observable<Blob> {
    return this.http.get(`/api/speech/voices/${encodeURIComponent(voice)}/sample`, { responseType: 'blob' });
  }
}
