import { NdjsonLines, base64Bytes, takeAlignedLine } from './jarvis-voice.service';
import type { Alignment } from '../../jarvis/face/jarvis-alignment';

describe('F-250 / SF-250-02 — la lecture alignée', () => {
  const enc = new TextEncoder();

  it('NdjsonLines recolle les lignes arrivées en morceaux, ignore les vides', () => {
    const lines = new NdjsonLines();
    expect(lines.push(enc.encode('{"a":"QU'))).toEqual([]);
    expect(lines.push(enc.encode('JD"}\n\n{"a":"RE'))).toEqual(['{"a":"QUJD"}']);
    expect(lines.push(enc.encode('VG"}'))).toEqual([]);
    expect(lines.end()).toEqual(['{"a":"REVG"}']);
    expect(lines.end()).toEqual([]);
  });

  it('une ligne alignée : audio décodé, alignement ajouté (temps absolus, morceaux successifs)', () => {
    const al: Alignment = { chars: [], starts: [], ends: [] };
    const audio = takeAlignedLine('{"a":"QUJD","c":["O","u"],"s":[0,0.1],"e":[0.1,0.2]}', al);
    expect(Array.from(audio!)).toEqual([65, 66, 67]);
    takeAlignedLine('{"a":"","c":["i"],"s":[0.2],"e":[0.3]}', al);
    expect(al.chars).toEqual(['O', 'u', 'i']);
    expect(al.ends).toEqual([0.1, 0.2, 0.3]);
  });

  it('alignement incohérent ou ligne illisible : le son passe seul, rien n’est ajouté (repli amplitude)', () => {
    const al: Alignment = { chars: [], starts: [], ends: [] };
    expect(takeAlignedLine('{"a":"QUJD","c":["x"],"s":[0,1],"e":[1]}', al)!.length).toBe(3);
    expect(takeAlignedLine('{"a":"QUJD","c":["x"],"s":["0"],"e":[1]}', al)!.length).toBe(3);
    expect(takeAlignedLine('pas du json', al)).toBeNull();
    expect(al.chars).toEqual([]);
    expect(base64Bytes('%%%').length).toBe(0);
  });
});
