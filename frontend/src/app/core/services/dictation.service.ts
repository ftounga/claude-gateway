import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** Ce qui peut empêcher une dictée (F-145 / SF-145-01). */
export type DictationError = 'micro-refuse' | 'non-configure' | 'echec' | 'trop-long';

/** Durée maximale d'un extrait : au-delà, on arrête et on transcrit quand même. */
export const MAX_CLIP_MS = 120_000;

/** En deçà, c'est un clic sur le bouton, pas une dictée. */
export const MIN_CLIP_MS = 500;

/**
 * **Dicter sa demande** (F-145 / SF-145-01) : le micro, puis un aller-retour.
 *
 * <p><b>Un extrait, pas un flux.</b> La dictée en continu (mot à mot pendant qu'on parle)
 * demanderait une connexion ouverte et coûterait environ dix fois plus ; un aller-retour au
 * relâchement suffit à écrire une demande.</p>
 *
 * <p><b>Rien n'est conservé</b> : le flux du micro est arrêté dès l'enregistrement fini — le
 * témoin du navigateur s'éteint, ce qui est la seule preuve visible qu'on n'écoute plus.</p>
 */
@Injectable({ providedIn: 'root' })
export class DictationService {
  private readonly http = inject(HttpClient);

  private recorder: MediaRecorder | null = null;
  private chunks: Blob[] = [];
  private startedAt = 0;
  private stopTimer: ReturnType<typeof setTimeout> | null = null;

  /** Le navigateur sait-il enregistrer ? (contexte non sécurisé, navigateur ancien…) */
  get supported(): boolean {
    return (
      typeof navigator !== 'undefined' &&
      !!navigator.mediaDevices?.getUserMedia &&
      typeof MediaRecorder !== 'undefined'
    );
  }

  get recording(): boolean {
    return this.recorder !== null;
  }

  /**
   * Le flux du micro de l'enregistrement en cours, ou `null` (F-231 / SF-231-02) : l'écran Jarvis y lit le
   * niveau sonore — l'orbe réagit à la voix et la fin de phrase est repérée au silence. Rien d'autre n'en sort.
   */
  get stream(): MediaStream | null {
    return this.recorder?.stream ?? null;
  }

  /**
   * Ouvre le micro et commence à enregistrer.
   *
   * @throws 'micro-refuse' si l'utilisateur ou le navigateur refuse
   */
  async start(audio: boolean | MediaTrackConstraints = true): Promise<void> {
    if (this.recorder) {
      return;
    }
    let stream: MediaStream;
    try {
      // F-233 / SF-233-05 : Jarvis demande l'annulation d'écho (sa propre voix au haut-parleur).
      stream = await navigator.mediaDevices.getUserMedia({ audio });
    } catch {
      throw 'micro-refuse' as DictationError;
    }
    this.chunks = [];
    this.startedAt = Date.now();
    const recorder = new MediaRecorder(stream);
    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) {
        this.chunks.push(event.data);
      }
    };
    recorder.start();
    this.recorder = recorder;
    // Borne dure : un micro resté ouvert par mégarde s'arrête de lui-même.
    this.stopTimer = setTimeout(() => void this.stop(), MAX_CLIP_MS);
  }

  /**
   * Arrête l'enregistrement et rend le texte transcrit.
   *
   * @returns le texte, ou une chaîne vide si l'extrait était trop court
   */
  async stop(): Promise<string> {
    const blob = await this.stopRecording();
    if (!blob) {
      return '';
    }
    return this.transcribe(blob, 'dictee.webm');
  }

  /**
   * F-239 / SF-239-04 — arrête l'enregistrement et rend l'extrait **sans le transcrire** (Jarvis le confie à sa propre
   * retranscription), ou `null` s'il était trop court.
   */
  async stopRecording(): Promise<Blob | null> {
    const recorder = this.recorder;
    if (!recorder) {
      return null;
    }
    this.recorder = null;
    if (this.stopTimer) {
      clearTimeout(this.stopTimer);
      this.stopTimer = null;
    }
    const duration = Date.now() - this.startedAt;
    const blob = await new Promise<Blob>((resolve) => {
      recorder.onstop = () => resolve(new Blob(this.chunks, { type: recorder.mimeType }));
      recorder.stop();
    });
    // Le flux est coupé TOUT DE SUITE : le témoin du navigateur s'éteint, et c'est la seule preuve
    // visible que l'on n'écoute plus.
    recorder.stream.getTracks().forEach((track) => track.stop());

    if (duration < MIN_CLIP_MS || blob.size === 0) {
      return null;
    }
    return blob;
  }

  /**
   * F-233 / SF-233-08 — transcrit un extrait déjà capté (la parole qui a coupé Jarvis, captée par la détection de
   * voix), par la même route que la dictée.
   */
  async transcribe(blob: Blob, filename = 'coupure.wav', via?: 'JARVIS'): Promise<string> {
    if (blob.size === 0) {
      return '';
    }
    const form = new FormData();
    form.append('audio', blob, filename);
    // F-244 / SF-244-01 — appelée depuis /jarvis, la dictée compte au budget de Jarvis.
    if (via) {
      form.append('via', via);
    }
    try {
      const answer = await firstValueFrom(
        this.http.post<{ text?: string }>('/api/atelier/transcription', form),
      );
      return answer?.text ?? '';
    } catch (error) {
      const status = (error as { status?: number })?.status;
      throw (status === 503 ? 'non-configure' : status === 413 ? 'trop-long' : 'echec') as DictationError;
    }
  }

  /** Arrête sans transcrire — pour quitter l'écran proprement. */
  cancel(): void {
    const recorder = this.recorder;
    this.recorder = null;
    if (this.stopTimer) {
      clearTimeout(this.stopTimer);
      this.stopTimer = null;
    }
    if (recorder) {
      recorder.stop();
      recorder.stream.getTracks().forEach((track) => track.stop());
    }
    this.chunks = [];
  }
}
