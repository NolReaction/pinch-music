#!/usr/bin/env python3
"""Generate deterministic demo chart, samples and video; no network access required.

Usage: python3 tools/generate_demo_assets.py
Dependencies: Python 3, numpy, Pillow, ffmpeg and ffprobe on PATH.
"""

from __future__ import annotations

import bisect
import json
import math
from pathlib import Path
import subprocess
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "media"
REFERENCE = ROOT / "build" / "demo-reference"
POSTER = ROOT / "composeApp/src/commonMain/composeResources/drawable/first_rhythm.png"
WIDTH, HEIGHT, FPS = 540, 960, 60
RATE, DURATION_MS, LEAD_IN_MS = 48000, 20000, 2000
BPM = 120
BALL_RADIUS = 15
KEY_TOP = 650
PITCHES = [67, 69, 71, 72, 74, 76, 77, 79, 81, 83]
LABELS = ["G4", "A4", "B4", "C5", "D5", "E5", "F5", "G5", "A5", "B5"]
BARS = [
    [(72, .5), (76, .5), (79, 1), (76, 1), (74, 1)],
    [(74, .5), (77, .5), (81, 1), (79, 1), (76, 1)],
    [(76, .5), (79, .5), (83, 1), (81, .5), (79, .5), (76, 1)],
    [(74, 1), (79, 1), (72, 2)],
    [(69, .5), (72, .5), (76, .5), (79, .5), (81, 1), (79, 1)],
    [(77, .5), (81, .5), (79, 1), (76, 1), (74, 1)],
    [(72, .5), (74, .5), (76, .5), (79, .5), (77, .5), (76, .5), (74, 1)],
    [(67, 1), (71, 1), (72, 2)],
]


def font(size: int):
    try:
        return ImageFont.truetype("DejaVuSans.ttf", size)
    except OSError:
        return ImageFont.load_default(size=size)


def make_chart() -> dict:
    notes, beat = [], 0.0
    for bar in BARS:
        assert sum(duration for _, duration in bar) == 4
        for midi, duration in bar:
            notes.append({
                "id": f"n{len(notes) + 1:03d}",
                "atMs": LEAD_IN_MS + round(beat * 60000 / BPM),
                "midi": midi,
                "durationMs": round(duration * 60000 / BPM),
                "velocity": .8,
            })
            beat += duration
    assert beat == 32
    return {
        "schemaVersion": 1,
        "id": "first-rhythm",
        "title": "Первый ритм",
        "bpm": BPM,
        "timeSignature": {"numerator": 4, "denominator": 4},
        "leadInMs": LEAD_IN_MS,
        "durationMs": DURATION_MS,
        "video": {
            "file": "demo-ball-muted.mp4", "fps": FPS,
            "width": WIDTH, "height": HEIGHT, "chartTimeAtVideoZeroMs": 0,
        },
        "audio": {
            "mode": "oneShotSamples", "sampleRateHz": RATE,
            "sampleDirectory": "samples",
        },
        "notes": notes,
    }


def synthesize_sample(midi: int) -> np.ndarray:
    t = np.arange(round(.9 * RATE), dtype=np.float64) / RATE
    frequency = 440 * 2 ** ((midi - 69) / 12)
    attack = np.minimum(t / .006, 1)
    decay = np.exp(-6.0 * t)
    release = np.clip((.9 - t) / .05, 0, 1)
    # Soft bell-like additive tone: no third-party recording or soundfont.
    signal = (
        np.sin(2 * np.pi * frequency * t)
        + .25 * np.sin(2 * np.pi * 2 * frequency * t) * np.exp(-3 * t)
        + .10 * np.sin(2 * np.pi * 3 * frequency * t) * np.exp(-8 * t)
    )
    signal *= attack * decay * release
    signal *= .55 / np.max(np.abs(signal))
    return signal


def save_wav(path: Path, signal: np.ndarray) -> None:
    assert np.max(np.abs(signal)) < 1.0, "Clipping would occur"
    pcm = np.rint(signal * 32767).astype("<i2")
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(RATE)
        output.writeframes(pcm.tobytes())


def x_for_pitch(midi: int) -> float:
    return 54 + PITCHES.index(midi) * 48


def make_background() -> Image.Image:
    y = np.linspace(0, 1, HEIGHT)[:, None, None]
    upper = np.array([9, 17, 34])[None, None, :]
    lower = np.array([16, 31, 46])[None, None, :]
    gradient = (upper * (1 - y) + lower * y)
    image = Image.fromarray(np.repeat(gradient.astype(np.uint8), WIDTH, axis=1))
    draw = ImageDraw.Draw(image)
    # Faint architectural rings give depth without competing with the ball.
    for radius in [160, 220, 280, 340]:
        draw.ellipse((270-radius, 380-radius, 270+radius, 380+radius), outline=(21, 40, 58), width=1)
    draw.text((270, 190), "DEMO", anchor="mm", font=font(15), fill=(93, 125, 148))
    draw.line((54, 734, 486, 734), fill=(38, 57, 74), width=1)
    for midi, label in zip(PITCHES, LABELS):
        x = x_for_pitch(midi)
        draw.rounded_rectangle((x-19, KEY_TOP+7, x+19, KEY_TOP+55), radius=9, fill=(5, 11, 23))
        draw.rounded_rectangle((x-19, KEY_TOP, x+19, KEY_TOP+45), radius=8,
                               fill=(36, 61, 80), outline=(64, 92, 110), width=1)
        draw.line((x-12, KEY_TOP+6, x+12, KEY_TOP+6), fill=(88, 121, 137), width=2)
        draw.text((x, KEY_TOP+73), label, anchor="mm", font=font(12), fill=(130, 155, 170))
    return image


def ball_position(time_ms: float, notes: list[dict], times: list[int]) -> tuple[float, float]:
    current = bisect.bisect_right(times, time_ms) - 1
    contact_y = KEY_TOP - BALL_RADIUS
    if current < 0:
        u = np.clip((time_ms - 600) / (times[0] - 600), 0, 1)
        return x_for_pitch(notes[0]["midi"]), float(285 + (contact_y - 285) * u * u)
    if current == len(notes)-1:
        # Only one final impact; the ball then lifts and stays suspended.
        u = min((time_ms - times[-1]) / 700, 1)
        return x_for_pitch(notes[-1]["midi"]), contact_y - 115 * math.sin(u * math.pi / 2)
    left, right = notes[current], notes[current+1]
    u = (time_ms - left["atMs"]) / (right["atMs"] - left["atMs"])
    x = x_for_pitch(left["midi"]) * (1-u) + x_for_pitch(right["midi"]) * u
    interval = right["atMs"] - left["atMs"]
    height = min(225, 105 + interval * .13)
    y = contact_y - 4 * height * u * (1-u)
    return x, y


def render_frame(time_ms: float, notes: list[dict], background: Image.Image) -> Image.Image:
    image = background.copy()
    draw = ImageDraw.Draw(image)
    times = [note["atMs"] for note in notes]
    current = bisect.bisect_right(times, time_ms) - 1
    next_index = current+1
    if next_index < len(notes):
        next_note = notes[next_index]
        remaining = next_note["atMs"] - time_ms
        if remaining <= 800:
            x = x_for_pitch(next_note["midi"])
            draw.rounded_rectangle((x-20, KEY_TOP-1, x+20, KEY_TOP+46), radius=9,
                                   outline=(69, 164, 186), width=2)
    if current >= 0:
        elapsed = time_ms - times[current]
        if elapsed < 180:
            amount = 1-elapsed/180
            x = x_for_pitch(notes[current]["midi"])
            color = tuple(round(base + amount*(bright-base)) for base,bright in zip((36,61,80),(70,210,218)))
            draw.rounded_rectangle((x-19, KEY_TOP, x+19, KEY_TOP+45), radius=8, fill=color)
            width = 17 + 32 * (1-amount)
            color = tuple(round(channel*amount) for channel in (49,135,154))
            draw.ellipse((x-width, KEY_TOP-5, x+width, KEY_TOP+7), outline=color, width=2)
    x, y = ball_position(time_ms, notes, times)
    shadow_width = max(5, 20 - (KEY_TOP-y)/22)
    draw.ellipse((x-shadow_width, KEY_TOP-4, x+shadow_width, KEY_TOP+3), fill=(10, 24, 35))
    # Three solid concentric circles avoid costly full-frame blur while retaining depth.
    draw.ellipse((x-19, y-19, x+19, y+19), fill=(78, 66, 45))
    draw.ellipse((x-15, y-15, x+15, y+15), fill=(255, 192, 92))
    draw.ellipse((x-11, y-12, x+8, y+7), fill=(255, 215, 135))
    draw.ellipse((x-8, y-10, x-2, y-4), fill=(255, 241, 204))
    return image


def probe(path: Path) -> dict:
    return json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)
    ]))


def main() -> None:
    REFERENCE.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / "samples").mkdir(exist_ok=True)
    chart = make_chart()
    (ASSETS / "demo-chart.json").write_text(json.dumps(chart, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    notes = chart["notes"]
    samples = {pitch: synthesize_sample(pitch) for pitch in PITCHES}
    for pitch, sample in samples.items():
        save_wav(ASSETS / "samples" / f"midi-{pitch}.wav", sample)
    preview = np.zeros(round(DURATION_MS * RATE / 1000))
    for note in notes:
        start = round(note["atMs"] * RATE / 1000)
        sample = samples[note["midi"]] * note["velocity"]
        preview[start:start+len(sample)] += sample
    save_wav(REFERENCE / "demo-preview.wav", preview)
    background = make_background()
    silent_path = ASSETS / "demo-ball-muted.mp4"
    command = [
        "ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
        "-s", f"{WIDTH}x{HEIGHT}", "-r", str(FPS), "-i", "-", "-an",
        "-c:v", "libx264", "-preset", "fast", "-crf", "20", "-pix_fmt", "yuv420p",
        "-movflags", "+faststart", str(silent_path),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE)
    assert process.stdin is not None
    for frame in range(DURATION_MS * FPS // 1000):
        image = render_frame(frame*1000/FPS, notes, background)
        process.stdin.write(image.tobytes())
    process.stdin.close()
    if process.wait() != 0:
        raise RuntimeError("ffmpeg encoding failed")
    subprocess.run([
        "ffmpeg", "-y", "-loglevel", "error", "-i", str(silent_path),
        "-i", str(REFERENCE / "demo-preview.wav"), "-map", "0:v:0", "-map", "1:a:0",
        "-c:v", "copy", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart",
        str(ASSETS / "demo-preview.mp4"),
    ], check=True)
    render_frame(3000, notes, background).save(POSTER)
    contact = Image.new("RGB", (WIDTH*3, HEIGHT*2), (9,17,34))
    for index, when in enumerate([1750, 2000, 2250, 2625, 3000, 17000]):
        frame = render_frame(when, notes, background)
        ImageDraw.Draw(frame).text((24,24), f"t = {when} ms", font=font(20), fill=(200,220,230))
        contact.paste(frame, ((index%3)*WIDTH, (index//3)*HEIGHT))
    contact.save(REFERENCE / "qa-contact-sheet.jpg", quality=90)
    media = probe(silent_path)
    streams = media["streams"]
    assert len(streams) == 1 and streams[0]["codec_type"] == "video"
    assert streams[0]["codec_name"] == "h264"
    assert streams[0]["r_frame_rate"] == "60/1"
    assert streams[0]["pix_fmt"] == "yuv420p"
    assert abs(float(media["format"]["duration"])-20) < .02
    assert all(a["atMs"] < b["atMs"] for a,b in zip(notes, notes[1:]))
    assert all(note["atMs"] * FPS % 1000 == 0 for note in notes)
    times = [note["atMs"] for note in notes]
    assert all(abs(ball_position(note["atMs"], notes, times)[1]-(KEY_TOP-BALL_RADIUS)) < .0001 for note in notes)
    verification = {
        "chartNotes": len(notes), "distinctPitches": len(samples),
        "firstNoteMs": times[0], "lastNoteMs": times[-1],
        "durationMs": DURATION_MS, "videoFps": FPS,
        "videoFrames": int(streams[0]["nb_frames"]), "videoHasAudio": False,
        "allImpactTimesExactlyOnFrames": True,
        "allBallImpactsTouchKeyTop": True,
        "previewPeakAmplitude": round(float(np.max(np.abs(preview))), 6),
        "notesDurationMeaning": "composition metadata; one-shot sample playback does not require holding",
    }
    (REFERENCE / "asset-verification.json").write_text(json.dumps(verification, indent=2)+"\n")
    print(json.dumps(verification, indent=2))


if __name__ == "__main__":
    main()
