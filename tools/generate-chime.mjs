import {writeFileSync} from 'node:fs';

const rate = 22050;
const offline = process.argv[3] === 'offline';
const notes = offline
  ? [[880, 0.22], [659.25, 0.24], [523.25, 0.34]]
  : [[587.33, 0.16], [739.99, 0.17], [880, 0.30]];
const gap = Math.round(rate * (offline ? 0.10 : 0.08));
const lengths = notes.map(([, seconds]) => Math.round(rate * seconds));
const length = lengths.reduce((total, samples) => total + samples, 0) + gap * (notes.length - 1);
const samples = new Float64Array(length);
let offset = 0;

for (const [index, [frequency, seconds]] of notes.entries()) {
  const noteLength = lengths[index];
  for (let i = 0; i < noteLength; i++) {
    const t = i / rate;
    const attack = Math.min(t / 0.016, 1);
    const release = Math.min(Math.max(0, (seconds - t) / 0.082), 1);
    const phase = 2 * Math.PI * frequency * t;
    const timbre = Math.sin(phase) + 0.24 * Math.sin(2 * phase) + 0.09 * Math.sin(3 * phase);
    const envelope = Math.sin(attack * Math.PI / 2) * Math.sin(release * Math.PI / 2) * Math.exp(-0.70 * t / seconds);
    samples[offset + i] = timbre * envelope;
  }
  offset += noteLength + gap;
}

const peak = samples.reduce((highest, sample) => Math.max(highest, Math.abs(sample)), 0);
const gain = 24575 / peak;
const wave = Buffer.alloc(44 + 2 * length);
wave.write('RIFF', 0);
wave.writeUInt32LE(wave.length - 8, 4);
wave.write('WAVEfmt ', 8);
wave.writeUInt32LE(16, 16);
wave.writeUInt16LE(1, 20);
wave.writeUInt16LE(1, 22);
wave.writeUInt32LE(rate, 24);
wave.writeUInt32LE(rate * 2, 28);
wave.writeUInt16LE(2, 32);
wave.writeUInt16LE(16, 34);
wave.write('data', 36);
wave.writeUInt32LE(length * 2, 40);

for (let i = 0; i < length; i++) {
  wave.writeInt16LE(Math.round(samples[i] * gain), 44 + i * 2);
}

writeFileSync(process.argv[2], wave);
