"""Render our instrumental to AAC. Requires Python, numpy, scipy, and ffmpeg.

From any directory: python3 render.py. All voices and percussion are synthesized here.
The fixed seeds keep noise drums, string attacks, and reverb repeatable for verification.
Use python3 render.py --verify to check the existing encoded performance without rewriting it.
"""

from functools import lru_cache
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess
import sys

import numpy as np
from scipy import signal
from scipy.io import wavfile

from score import BACKING, BAR_COUNT, BPM, DURATION_MS, FORM, HARMONY, bell_answer_at, bell_answers, chord_at, lead_attacks, score_tag, section_at

RATE = 32_000
BEAT = 60 / BPM
TICK = BEAT / 4
TAU = 2 * np.pi
LENGTH = RATE * DURATION_MS // 1000
rng = np.random.default_rng(127)
mix = np.zeros((LENGTH, 2), np.float32)
room = np.zeros((LENGTH, 2), np.float32)


def filtered(samples, cutoff, mode):
    return signal.sosfilt(signal.butter(3, cutoff, fs=RATE, btype=mode, output="sos"), samples)


def frequency(note):
    return 440 * 2 ** ((note - 69) / 12)


def place(samples, tick, gain, pan=0, send=0):
    start = round(tick * TICK * RATE)
    size = min(len(samples), LENGTH - start)
    if size <= 0:
        return
    mono = samples[:size] * gain
    pans = (np.cos((pan + 1) * np.pi / 4), np.sin((pan + 1) * np.pi / 4))
    for side, weight in enumerate(pans):
        value = mono * weight
        mix[start:start + size, side] += value
        if send:
            room[start:start + size, side] += value * send


def envelope(t, duration, attack, release, decay=0):
    onset = 1 - np.exp(-t / attack)
    sustain = np.exp(-decay * np.minimum(t, duration))
    return onset * sustain * np.exp(-np.maximum(t - duration, 0) * 5 / release)


@lru_cache(maxsize=1024)
def pitched(voice, midi, ticks):
    hz = frequency(midi)
    duration = TICK * ticks
    lengths = {"reed": .062, "air": .14, "pluck": .22, "pad": .18, "bass": .055, "bell": .35}
    release = lengths[voice]
    t = np.arange(round((duration + release) * RATE)) / RATE
    if voice in ("reed", "air"):
        # Oscillator phase has a short breath of pitch, then settled vibrato.
        attack_scoop = -.021 * np.exp(-t / .023)
        vibrato = .0035 * np.minimum(t / .14, 1) * np.sin(TAU * 5.4 * t)
        phase = TAU * hz * np.cumsum(1 + attack_scoop + vibrato) / RATE
        sound = np.zeros_like(t)
        if voice == "reed":
            # Two musette ranks plus a quietly coupled lower reed.
            for harmonic, amplitude in enumerate((1.0, .44, .59, .18, .20, .10, .11, .065, .035), 1):
                if harmonic * hz > RATE * .45:
                    break
                close = np.sin(harmonic * phase)
                wide = np.sin(harmonic * phase * 1.0035 + .18)
                sound += amplitude * (.72 * close + .28 * wide)
            sound += .12 * np.sin(phase * .5)
            sound *= envelope(t, duration, .009, release, .27) * .55
            sound *= .92 + .08 * np.sin(TAU * 2.3 * t + .4)
        else:
            # Sustained singing synth: fundamental and moving upper formants.
            # This is a wordless instrument and contains no recorded voice.
            for harmonic in range(1, 13):
                centre = harmonic * hz
                if centre > 7200:
                    break
                colour = (1.08 * np.exp(-((centre - 720) / 320) ** 2) +
                          .58 * np.exp(-((centre - 1280) / 400) ** 2) +
                          .18 * np.exp(-((centre - 2450) / 580) ** 2))
                weight = (1.15 if harmonic == 1 else .65 * colour / harmonic ** .65)
                sound += weight * np.sin(phase * harmonic + .10 * harmonic)
            sound += .08 * np.sin(phase * 1.0021)
            sound *= envelope(t, duration, .018, release, .25) * .60
    elif voice == "pad":
        phase = TAU * hz * t
        sound = sum((np.sin(h * phase * .9985 + .18 * h) +
                     np.sin(h * phase * 1.0016)) / (2 * h ** 1.7) for h in range(1, 5))
        sound *= envelope(t, duration, .105, release)
    elif voice == "bass":
        phase = TAU * hz * t
        colour = np.exp(-t / .105)
        sound = (.93 * np.sin(phase) + (.27 + .16 * colour) * np.sin(2 * phase) +
                 .24 * colour * np.sin(3 * phase) + .11 * colour * np.sin(5 * phase))
        sound *= envelope(t, duration, .006, release, 1.10)
    else:
        phase = TAU * hz * t
        if voice == "bell":
            sound = np.sin(phase + .76 * np.sin(phase * 2.007) * np.exp(-t / .22))
            sound += .18 * np.sin(phase * 3.985) * np.exp(-t / .16)
            decay = 2.8
        else:
            sound = np.sin(phase + 1.55 * np.exp(-t / .07) * np.sin(phase * 1.999))
            sound += .25 * np.sin(phase * 2.006) * np.exp(-t / .13)
            decay = 4.3
        sound *= envelope(t, duration, .005, release, decay)
    return np.asarray(sound, dtype=np.float32)


def drums():
    result = {}
    t = np.arange(round(.36 * RATE)) / RATE
    # Sweep quickly through the kick transient, landing on G# well below the bass.
    phase = TAU * (52 * t + 130 * .037 * (1 - np.exp(-t / .037)))
    click = filtered(rng.normal(0, 1, len(t)), 2000, "high") * np.exp(-t / .0035)
    result["kick"] = np.tanh(1.45 * (np.sin(phase) * np.exp(-t / .081) + .13 * click)) * np.minimum(t / .002, 1)
    t = np.arange(round(.22 * RATE)) / RATE
    noise = filtered(rng.normal(0, 1, len(t)), (750, 7000), "band")
    slap = sum((t >= onset) * np.exp(-np.maximum(t - onset, 0) / .011)
               for onset in (0.0, .012, .026))
    result["clap"] = noise * (slap * .5 + .64 * np.exp(-t / .059)) * np.minimum(t / .0015, 1)
    for name, length in (("tick", .042), ("hat", .145), ("shaker", .089)):
        t = np.arange(round(length * RATE)) / RATE
        noise = filtered(rng.normal(0, 1, len(t)), (4900, 14000), "band")
        result[name] = noise * np.exp(-t / (length / 5)) * np.sin(np.pi * np.minimum(t / .002, 1) / 2)
    t = np.arange(round(.16 * RATE)) / RATE
    result["rim"] = np.sin(TAU * 1320 * t + .43 * np.sin(TAU * 590 * t)) * np.exp(-t / .019)
    return result


GROOVES = {
    # percussion, bass, harmonic bed, high-frequency percussion
    "intro":  (.23, .45, .78, .26),
    "soft":   (.30, .52, .78, .30),
    "lift":   (.65, .70, .80, .63),
    "club":   (.97, .94, .84, 1.0),
    "turn":   (.49, .66, .84, .50),
    "groove": (.80, .82, .75, .70),
    "build":  (.83, .84, .86, .80),
    "break":  (.16, .38, .88, .23),
    "peak":   (1.0, .98, .96, 1.0),
    "bridge": (.50, .68, .84, .40),
    "final":  (.94, .92, 1.0, .90),
    "outro":  (.77, .82, .84, .74),
}


def render():
    kit = drums()
    for n in lead_attacks():
        voice = n.voice
        # Leave every melody attack in the native audio stream, including the player's accents.
        # Keep the room return behind the attack so repeated sixteenths remain distinct.
        gain = {"reed": .56, "air": .55, "pluck": .47}[voice]
        place(pitched(voice, n.midi, n.length), n.tick, gain * n.gain, -.045, .17)

    for bar in range(BAR_COUNT):
        section = section_at(bar)
        local = bar - section.bar
        tick = bar * 16
        groove = section.groove
        percussion, bass, bed, hats = GROOVES[groove]
        last = (bar == BAR_COUNT - 1)
        if groove == "build":
            hats *= .75 + local / 23
        if groove == "outro":
            percussion *= (1 - local / 3)
            bass *= (1 - local / 4)
        root, chord = HARMONY[chord_at(section, local)]
        for j, pitch in enumerate(chord):
            place(pitched("pad", pitch, 14.9 if not last else 9.0), tick,
                  .091 * bed, (-.48, .12, .50)[j], .25)
        bell = bell_answer_at(bar)
        if bell:
            place(pitched(bell.voice, bell.midi, bell.length), bell.tick, .20 * bell.gain, .42, .55)
        if last:
            place(kit["kick"], tick, .40)
            place(pitched("bass", root, 8), tick, .31, send=.08)
            continue

        house = groove not in ("intro", "soft", "break")
        for beat in range(4):
            pos = tick + 4 * beat
            if house or beat in (0, 2):
                place(kit["kick"], pos, .63 * percussion * (1 if beat == 0 else .92))
            if beat in (1, 3):
                place(kit["clap"], pos + .055, .31 * percussion, .05, .28)
                if house:
                    place(kit["rim"], pos + .09, .067 * percussion, -.31, .08)
            if house or beat in (1, 3):
                place(kit["hat"], pos + 2.015, .102 * hats, .29, .09)
            if house:
                place(kit["tick"], pos + .02, .053 * hats * (1 if beat % 2 else .8), -.26)
            if house and bar % 4 >= 2:
                place(kit["shaker"], pos + 3.10, .059 * hats, -.37)
            if house:
                bass_note = root + (12 if beat == 3 and bar % 4 == 3 else 0)
                place(pitched("bass", bass_note, 1.75), pos + 2, .39 * bass)
                # Offbeat chord stabs give each kick a fresh pocket, with small accents across a bar.
                for j, pitch in enumerate(chord):
                    place(pitched("pluck", pitch, 1.4), pos + 2.05,
                          .048 * bed * (1.2 if beat == 2 else 1), (-.4, .0, .37)[j], .12)
            elif beat % 2 == 0:
                place(pitched("bass", root, 4.7), pos, .28 * bass)
        if groove == "build" and local >= 8:
            for off in range(0, 16, 2 if local < 11 else 1):
                place(kit["clap"], tick + off, .07 + .018 * (local - 8) + off * .0028, -.08, .34)

    # A shaped band of filtered noise leads into the hook/drop, followed by a short wash.
    for section in FORM:
        if section.groove not in ("club", "peak", "final"):
            continue
        tick = section.bar * 16
        length = round(BEAT * 4 * RATE)
        time = np.arange(length) / RATE
        progress = np.linspace(0, 1, length)
        noise = filtered(rng.normal(0, 1, length), (1100, 8800), "band")
        swell = noise * progress ** 2.5 * (.70 + .30 * np.cos(TAU * time / BEAT))
        place(swell, tick - 16, .16, -.12, .32)
        place(noise * np.exp(-time / .32), tick, .12, .15, .48)

    # Short stereo room for the band, and a tempo-locked echo crossing the field.
    for side in range(2):
        early = np.zeros(round(.40 * RATE), dtype=np.float32)
        times = np.arange(len(early)) / RATE
        early[:] = rng.normal(0, 1, len(early)) * np.exp(-times / .078)
        early = np.asarray(filtered(early, 4500, "low"), dtype=np.float32)
        early *= .48 / np.sqrt(np.sum(early * early))
        wet = signal.oaconvolve(room[:, side], early, mode="full")[:LENGTH]
        mix[:, side] += wet
        for division, gain in ((.75, .34), (1.5, .17), (2.25, .075)):
            delay = round(BEAT * division * RATE)
            mix[delay:, side] += room[:-delay, 1-side] * gain
    mix[:] = np.tanh(mix * 1.45)
    mix[:] *= .60 / np.max(np.abs(mix))
    fade = round(.38 * RATE)
    mix[-fade:] *= np.linspace(1, 0, fade, dtype=np.float32)[:, None]


def correlation(reference, actual):
    norm = np.linalg.norm(actual) * np.linalg.norm(reference)
    return float(np.dot(reference, actual) / norm) if norm else 0.0


def verify_backing(path):
    """Match the melody and right-panned bell responses against the encoded performance."""
    audio = subprocess.check_output([
        "ffmpeg", "-v", "error", "-i", str(path), "-vn", "-f", "f32le",
        "-ac", "2", "-ar", str(RATE), "-",
    ])
    stereo = np.frombuffer(audio, dtype="<f4").reshape(-1, 2)
    pcm = stereo.mean(axis=1)
    contacts = lead_attacks()
    minimum = 1.0
    for n in contacts:
        start = round(n.tick * TICK * RATE)
        # Check the start of the note, excluding its release and other onsets.
        size = round(min(.080, .9 * n.length * TICK) * RATE)
        lead = filtered(pitched(n.voice, n.midi, n.length)[:size], 250, "high")
        actual = filtered(pcm[start:start + size], 250, "high")
        match = correlation(lead, actual)
        if match < .5:
            raise ValueError(f"Missing or mistimed MIDI {n.midi} at tick {n.tick} (correlation {match:.3f})")
        minimum = min(minimum, match)
    print(f"Matched {len(contacts)} melody onsets in decoded AAC. Minimum correlation {minimum:.3f}")

    # The lead and bell can sound together. Subtract the left channel at the
    # lead's pan ratio and isolate the bell fundamental in its first 94 ms.
    lead_pan_ratio = np.tan((1 - .045) * np.pi / 4)
    bells = bell_answers()
    minimum = 1.0
    for bell in bells:
        hz = frequency(bell.midi)
        sos = signal.butter(2, (.96 * hz, 1.04 * hz), fs=RATE, btype="bandpass", output="sos")
        start = round(bell.tick * TICK * RATE)
        window = stereo[start:start + round(.094 * RATE)]
        actual = signal.sosfilt(sos, window[:, 1] - lead_pan_ratio * window[:, 0])
        template = signal.sosfilt(sos, pitched(bell.voice, bell.midi, bell.length)[:len(window)])
        match = correlation(template, actual)
        if match < .65:
            raise ValueError(f"Missing or mistimed bell MIDI {bell.midi} at tick {bell.tick} (correlation {match:.3f})")
        minimum = min(minimum, match)
    print(f"Matched {len(bells)} bell answers in decoded AAC. Minimum correlation {minimum:.3f}")


def write_backing():
    destination = BACKING
    destination.parent.mkdir(parents=True, exist_ok=True)
    render()
    with TemporaryDirectory() as temporary:
        wave = Path(temporary) / "master.wav"
        wavfile.write(wave, RATE, np.rint(mix * 32767).astype(np.int16))
        subprocess.run([
            "ffmpeg", "-loglevel", "error", "-y", "-i", str(wave), "-c:a", "aac",
            "-b:a", "96k", "-movflags", "+faststart", "-map_metadata", "-1",
            "-metadata", f"comment={score_tag()}", str(destination),
        ], check=True)
    print(f"{destination.name}: {DURATION_MS} ms at {BPM} BPM, {len(lead_attacks())} melody attacks")


if __name__ == "__main__":
    if sys.argv[1:] != ["--verify"]:
        write_backing()
    verify_backing(BACKING)
