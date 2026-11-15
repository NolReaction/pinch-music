"""The performance and the single-finger chart share this sixteenth-note score.

Run from any directory with python3 score.py [--check] to update or verify StereoLove.kt.
A measure has 16 ticks. Phrases describe onset, MIDI pitch and sounding length in ticks.
"""

from dataclasses import dataclass
from pathlib import Path
import sys

BPM = 127
DURATION_MS = 184_000
ROOT = Path(__file__).resolve().parents[2]
GRID = ROOT / "composeApp/src/commonMain/kotlin/music/pinch/StereoLove.kt"


@dataclass(frozen=True)
class Section:
    bar: int
    label: str
    phrase: str
    groove: str


FORM = (
    Section(0, "Вступление", "teaser", "intro"),
    Section(2, "Вокальная тема", "opening", "soft"),
    Section(10, "Вокальная тема", "opening", "lift"),
    Section(18, "Аккордеон", "hook", "club"),
    Section(26, "Переход", "turn", "turn"),
    Section(28, "Куплет", "verse", "groove"),
    Section(38, "Нарастание", "rise", "build"),
    Section(50, "Вокальная тема", "opening", "break"),
    Section(58, "Припев", "refrain", "peak"),
    Section(74, "Бридж", "bridge", "bridge"),
    Section(82, "Финал", "bridge", "final"),
    Section(90, "Аккордеон", "hook", "peak"),
    Section(94, "Кода", "coda", "outro"),
)
BAR_COUNT = 97

# Root, and close inversions above the bass. G#m brings the refrain back to C#m.
HARMONY = {
    "C#m": (37, (56, 61, 64)),
    "A":   (33, (57, 61, 64)),
    "E":   (40, (56, 59, 64)),
    "G#m": (32, (56, 59, 63)),
    "B":   (35, (54, 59, 63)),
    "F#m": (30, (54, 57, 61)),
}

# The accordion jumps up a sixth, reattacks the low reed, and walks down in pairs.
HOOK = (
    ((0,68,2.8),(3,76,.8),(4,68,.9),(6,76,1.8),(8,75,1.8),(10,76,1.8),(12,78,1.8),(14,76,1.0)),
    ((0,75,1.9),(2,76,1.0),(4,73,1.9),(6,75,1.0),(8,71,1.9),(10,73,1.0),(12,69,1.9),(14,71,1.0)),
    ((0,68,1.4),(3,80,.65),(4,80,.8),(5,80,.7),(7,80,.7),(8,78,.9),(9,78,2.6),(13,76,.9),(14,76,.9),(15,76,.9)),
    ((0,75,1.8),(2,75,1.1),(4,75,2.4),(11,71,1.4),(14,73,.95)),
)
# Instrumental responses carry the vocal contour. Breath marks and long notes break up
# the rapid accordion articulation, giving the successive sections distinct registers.
OPENING = (
    ((0,68,2.3),(3,68,1.3),(5,68,1.3),(7,69,1.0),(8,68,2.2),(11,66,1.5),(14,64,1.8)),
    ((0,66,3.2),(4,64,2.7),(8,61,5.8)),
    ((12,64,1.1),(14,66,1.0)),
    ((0,68,4.0),(8,66,2.0),(12,64,2.8)),
    ((0,68,1.5),(2,68,2.2),(5,68,1.2),(7,69,1.2),(9,68,2.4),(12,66,2.5)),
    ((0,64,4.0),(6,66,3.2),(10,64,4.0)),
    ((0,61,6.3),(8,63,2.0),(12,64,2.7)),
    ((0,63,5.1),(8,61,6.0)),
)
VERSE = (
    ((0,64,1.3),(2,66,1.2),(4,68,1.4),(7,68,1.4),(9,71,3.0),(14,68,1.2)),
    ((0,66,1.4),(2,68,1.4),(4,69,1.6),(7,69,1.4),(9,68,4.0)),
    ((0,64,1.4),(2,66,1.5),(4,68,3.0),(9,71,1.4),(11,69,1.4),(13,68,2.2)),
    ((0,66,3.2),(4,64,2.0),(8,63,4.2)),
)
RISE = (
    ((0,68,2.4),(3,68,1.2),(5,71,2.2),(8,73,2.1),(11,71,1.3),(13,68,2.0)),
    ((0,68,1.8),(3,71,1.4),(5,73,2.4),(8,75,4.9)),
    ((0,71,1.9),(3,73,1.3),(5,75,2.0),(8,76,2.4),(11,75,1.2),(13,73,2.0)),
    ((0,71,2.8),(4,68,2.0),(8,68,5.4)),
)
BRIDGE = (
    ((0,68,1.2),(2,71,1.1),(4,73,2.8),(8,73,1.2),(10,71,1.3),(13,68,2.0)),
    ((0,66,2.7),(4,68,1.3),(7,69,2.4),(10,68,2.0),(14,64,1.4)),
    ((0,68,3.0),(4,71,2.6),(8,73,1.6),(11,75,1.4),(13,76,2.7)),
    ((0,75,2.0),(3,73,2.5),(7,71,2.1),(10,68,4.5)),
)


@dataclass(frozen=True)
class Attack:
    tick: int
    midi: int
    length: float
    voice: str
    gain: float
    panel: bool = False


def section_at(bar):
    return next(s for s in reversed(FORM) if s.bar <= bar)


def chord_at(section, local):
    if section.groove == "intro":
        return "C#m"
    if section.groove in ("turn", "outro"):
        return ("G#m", "C#m", "C#m")[min(local, 2)]
    if section.groove == "build" and local >= 8:
        return "C#m"
    if section.phrase in ("opening", "verse", "rise"):
        return ("C#m", "G#m", "A", "B", "C#m", "A", "F#m", "G#m")[local % 8]
    return ("C#m", "A", "E", "G#m")[local % 4]


def phrase_at(section, local):
    if section.phrase in ("hook", "teaser"):
        return HOOK[local % 4], "reed"
    if section.phrase == "refrain":
        if local % 8 < 4:
            return HOOK[local % 4], "reed"
        return BRIDGE[local % 4], "air"
    if section.phrase == "turn":
        return (((0,68,4.5),(8,71,2),(12,73,2)) if local == 0 else ((0,68,7.5),(12,63,2))), "pluck"
    if section.phrase == "coda":
        if local == 2:
            return ((0,61,6.5),), "reed"
        return HOOK[local], "reed"
    phrases = {"opening": OPENING, "verse": VERSE, "rise": RISE, "bridge": BRIDGE}
    bars = phrases[section.phrase]
    return bars[local % len(bars)], "air"


def lead_attacks():
    notes = []
    for bar in range(BAR_COUNT):
        section = section_at(bar)
        local = bar - section.bar
        phrase, voice = phrase_at(section, local)
        # A two-beat phrase, then a rolling answer. Several downbeats are left
        # unscored. In busier passages occasional quicker pairs land a beat apart.
        if section.groove in ("club", "peak", "final"):
            selected = ((0, 8), (4, 12), (0, 9), (0, 4, 11))[local % 4]
        elif section.groove in ("soft", "break", "intro", "outro"):
            selected = ((0,), (0, 8), (12,), (0,))[local % 4]
        else:
            selected = ((0, 9), (4,), (0, 8), (4, 12))[local % 4]
        selected_indexes = set()
        for target in selected:
            closest = min(range(len(phrase)), key=lambda i: abs(phrase[i][0] - target))
            if abs(phrase[closest][0] - target) <= 2:
                selected_indexes.add(closest)
        for i, (offset, midi, length) in enumerate(phrase):
            tick = bar * 16 + offset
            panel = i in selected_indexes and tick >= 24
            if panel and any(n.panel and tick - n.tick < 4 for n in notes[-8:]):
                panel = False
            velocity = (0.82 if offset % 4 == 0 else 0.71) + (0.05 if length >= 2 else 0)
            notes.append(Attack(tick, midi, length, voice, velocity, panel))
    return notes


def kotlin_source():
    attacks = lead_attacks()
    section_rows = "\n".join(
        f'    {s.bar * 16} to "{s.label}",' for s in FORM
    )
    bars = []
    for bar in range(BAR_COUNT):
        hits = [n for n in attacks if n.panel and n.tick // 16 == bar]
        if hits:
            entries = ", ".join(f"Panel({n.tick}, {n.midi})" for n in hits)
            bars.append(f"    {entries},")
    return f'''// Generated from tools/stereo_love/score.py. Both the backing and the panels use these ticks.
package music.pinch

import kotlin.math.roundToLong
import music.pinch.core.Chart
import music.pinch.core.Note

private const val stereoBpm = {BPM}
private fun millisAtTick(tick: Int) = (tick * 15_000.0 / stereoBpm).roundToLong()

private data class Panel(val tick: Int, val midi: Int)

private val stereoSections = listOf(
{section_rows}
)

internal fun stereoLoveSectionAt(positionMs: Long): String =
    stereoSections.lastOrNull {{ millisAtTick(it.first) <= positionMs }}?.second ?: stereoSections.first().second

private val stereoPanels = listOf(
{chr(10).join(bars)}
)

internal val stereoLoveSong = Song(
    id = "stereo-love",
    title = "Stereo Love",
    artist = "Edward Maya & Vika Jigulina",
    bundled = Chart(
        "Stereo Love",
        stereoBpm,
        {DURATION_MS:_}L,
        stereoPanels.mapIndexed {{ index, panel ->
            Note("stereo-$index", millisAtTick(panel.tick), panel.midi, 250, 0.88f)
        }},
    ),
    backingAsset = "backing/stereo-love.m4a",
)
'''


if __name__ == "__main__":
    generated = kotlin_source()
    if "--check" in sys.argv:
        if GRID.read_text() != generated:
            raise SystemExit("StereoLove.kt and score.py disagree, regenerate the chart.")
    else:
        GRID.write_text(generated)
