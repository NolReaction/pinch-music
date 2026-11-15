#!/usr/bin/env python3
"""Regenerate the original mono PCM instrument bank. Requires Python and NumPy."""
from pathlib import Path
import wave

import numpy as np

DESTINATION = Path(__file__).resolve().parents[1] / "media" / "instruments"
RATE = 44100


def sample(timbre: str, midi: int) -> np.ndarray:
    frequency = 440 * 2 ** ((midi - 69) / 12)
    seconds = {"piano": 1.34, "mallet": 1.08, "synth": 0.95}[timbre]
    t = np.arange(round(seconds * RATE)) / RATE
    phase = 2 * np.pi * frequency * t
    result = np.zeros_like(t)
    # Leave space below Nyquist for native transposition of up to seven semitones.
    ceiling = .46 * RATE / (2 ** (7 / 12))
    if timbre == "piano":
        # Coupled strings, stiff-string overtones and a soft hammer attack.
        for overtone, weight in enumerate([1, .44, .22, .14, .082, .048, .027, .018], 1):
            ratio = overtone * (1 + .00016 * overtone ** 2)
            if ratio * frequency < ceiling:
                env = np.exp(-t * (2.6 + .7 * overtone))
                result += weight * env * (.68 * np.sin(phase * ratio) + .32 * np.sin(phase * ratio * 1.0018))
        result *= 1 - np.exp(-t / .0035)
    elif timbre == "mallet":
        # Tuned bar strike with a dark fundamental and fast-decaying higher modes.
        for ratio, weight, decay in [(1, 1, 3.9), (4.01, .48, 15), (9.98, .25, 25), (14.1, .08, 38)]:
            if ratio * frequency < ceiling:
                result += weight * np.sin(phase * ratio) * np.exp(-decay * t)
        result *= 1 - np.exp(-t / .0017)
    else:
        # Warm chorus saw with a closing filter and a rounded release.
        for harmonic in range(1, 14):
            if harmonic * frequency >= ceiling:
                break
            weight = (-1) ** (harmonic + 1) / harmonic ** 1.23
            osc = .58 * np.sin(phase * harmonic) + .42 * np.sin(phase * harmonic * 1.003)
            result += weight * osc * np.exp(-t * (1.8 + .62 * harmonic))
        result *= 1 - np.exp(-t / .014)
        result *= np.exp(-np.maximum(t - .50, 0) * 9)
    # An explicit release ramp keeps resampled tails click-free.
    result *= np.minimum(1, np.maximum(0, (seconds - t) / .045))
    result *= .52 / max(.01, np.max(np.abs(result)))
    return np.rint(result * 32767).astype("<i2")


def contact(timbre: str) -> np.ndarray:
    """Short unpitched contact, added by the player over the recorded performance."""
    modes = {"piano": (960, 2310), "mallet": (1570, 3720), "synth": (1220, 2880)}
    low, high = modes[timbre]
    t = np.arange(round(.044 * RATE)) / RATE
    attack = 1 - np.exp(-t / .0007)
    noise = np.random.default_rng(127).normal(0, .35, size=t.shape)
    wavelet = np.sin(2 * np.pi * low * t) * np.exp(-t / .004)
    wavelet += .55 * np.sin(2 * np.pi * high * t) * np.exp(-t / .007)
    wavelet += noise * np.exp(-t / .002)
    waveform = wavelet * attack * np.minimum(1, (.044 - t) / .004)
    return np.rint(waveform * (.58 * 32767 / np.max(np.abs(waveform)))).astype("<i2")


def write_wav(path: Path, pcm: np.ndarray):
    with wave.open(str(path), "wb") as out:
        out.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
        out.writeframes(pcm.tobytes())


if __name__ == "__main__":
    feedback = DESTINATION.parent / "feedback"
    feedback.mkdir(parents=True, exist_ok=True)
    for name in ("piano", "mallet", "synth"):
        write_wav(feedback / f"{name}.wav", contact(name))
        directory = DESTINATION / name
        directory.mkdir(parents=True, exist_ok=True)
        for midi in range(0, 121, 12):
            write_wav(directory / f"{midi}.wav", sample(name, midi))
