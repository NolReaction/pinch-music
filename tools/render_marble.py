"""Bake a rotating, glass-encased red marble under a fixed studio environment.

The sprite atlas rotates the internal glass inclusions. The reflected softboxes stay
in camera space, as they would on a rotating sphere. Run with NumPy and Pillow.
"""
from pathlib import Path

import numpy as np
from PIL import Image


TILE = 112
COLUMNS = 6
ROWS = 4
STEPS = COLUMNS * ROWS
SAMPLES = TILE * 3
y, x = np.mgrid[:SAMPLES, :SAMPLES]
x = (x + .5 - SAMPLES / 2) / (SAMPLES / 2 - 2)
y = -(y + .5 - SAMPLES / 2) / (SAMPLES / 2 - 2)
r2 = x*x + y*y
z = np.sqrt(np.maximum(0, 1 - r2))
alpha = np.clip((1 - np.sqrt(r2)) * SAMPLES / 2, 0, 1)
normal = np.stack((x, y, z), axis=-1)
eye = np.array([0, 0, 1])
reflection = 2*z[..., None]*normal - eye


def smooth(value):
    u = np.clip(value, 0, 1)
    return u*u*(3 - 2*u)


def tint(a, b, mask):
    return a + (b - a) * mask[..., None]


def rgb(r, g, b):
    return np.array((r, g, b), dtype=float) / 255


def normalize(vector):
    return vector / np.linalg.norm(vector)


def studio_panel(direction, right, half_width, half_height):
    axis = normalize(direction)
    u = normalize(np.cross(axis, right))
    v = np.cross(axis, u)
    front = np.maximum(.0001, reflection @ axis)
    horizontal = np.abs(reflection @ u / front)
    vertical = np.abs(reflection @ v / front)
    return (smooth((half_width - horizontal) * 16) *
            smooth((half_height - vertical) * 17) * (reflection @ axis > 0))


fresnel = .042 + .958 * (1-z)**5
sky = tint(rgb(38, 30, 44), rgb(146, 145, 174), smooth(.44 + .6*reflection[..., 1]))
left_box = studio_panel([-.68, .75, .22], [1, -.15, .16], .21, .73)
right_box = studio_panel([.84, .28, -.13], [-.04, 1, .25], .19, .68)
rim_box = studio_panel([-.65, -.2, -.63], [.1, 1, .2], .032, .77)
# Snell's law at the front surface. Sample the furnace-worked interior on a second sphere.
eta = 1 / 1.52
ray = -eta * eye + (eta*z - np.sqrt(1 - eta*eta*(1-z*z)))[..., None] * normal
n_dot_ray = np.sum(normal*ray, axis=-1)
depth = -n_dot_ray - np.sqrt(np.maximum(0, n_dot_ray*n_dot_ray - (1 - .88**2)))
inside = normal + depth[..., None]*ray
# Refract a second time through the rear wall. The rear studio window becomes
# a curved caustic at the bottom of the sphere, separate from the front reflection.
rear = normal - 2*n_dot_ray[..., None]*ray
incidence = np.sum(rear*ray, axis=-1)
emergent = 1.52*ray + (np.sqrt(np.maximum(0, 1 - 1.52**2*(1-incidence**2))) - 1.52*incidence)[..., None]*rear
ex, ey, ez = emergent.transpose(2,0,1)
transmission = smooth(.58 + .65*ex - .42*ey + .35*ez)
window = smooth((.4-np.abs(ey+.44)) * 4) * smooth((.53-np.abs(ex-.29))*3.6)
horizon = reflection[..., 1] - .11*reflection[..., 0] - .12
dome = smooth((horizon-.055)*4) * smooth(.83-.74*reflection[..., 0])
light = np.maximum(0, normal @ normalize([-.55, .65, .56]))
atlas = Image.new("RGBA", (COLUMNS*TILE, ROWS*TILE))
for frame in range(STEPS):
    angle = 2*np.pi*frame/STEPS
    c, s = np.cos(angle), np.sin(angle)
    # The swirl rolls around a diagonal axle while front-surface reflections hold still.
    u = inside[..., 0]*c + inside[..., 1]*s
    v = -inside[..., 0]*s + inside[..., 1]*c
    w = inside[..., 2]
    thread = v + .42*np.sin(2.8*u - 1.15*w) + .22*u + .15
    glass = tint(rgb(49, 5, 29), rgb(144, 15, 26), smooth(.28 + .52*light + .13*u))
    glass *= (.43 + .57*transmission)[..., None]
    glass = tint(glass, rgb(218, 45, 30), window*(.32+.42*light))
    glass = tint(glass, rgb(202, 34, 37), smooth(.48 - 1.45*thread + .16*u) *
                 (.34 + .48*light))
    glass = tint(glass, rgb(241, 77, 32), np.exp(-((thread+.115)/.052)**2) *
                 smooth(.60+.85*u) * .78)
    glass = tint(glass, rgb(24, 23, 46), np.exp(-((thread-.16)/.15)**2) * .91)
    glass = tint(glass, rgb(131, 133, 160), np.exp(-((thread-.125)/.038)**2) *
                 smooth(.58 - .67*u) * .72)
    glass = tint(glass, rgb(253, 105, 35), smooth((-y-.33)*1.95)*window*.72)
    surface = tint(glass, sky, .08 + .78*fresnel)
    surface = tint(surface, rgb(110, 91, 119), .33*dome)
    surface = tint(surface, rgb(18, 22, 39), .45*np.exp(-((horizon+.095)/.13)**2))
    surface = tint(surface, rgb(210, 201, 221), .30*np.exp(-((horizon-.045)/.055)**2)*dome)

    surface = tint(surface, rgb(255, 242, 233), left_box * (.71+.25*fresnel))
    surface = tint(surface, rgb(255, 190, 123), right_box * (.56+.41*fresnel))
    surface = tint(surface, rgb(209, 207, 238), rim_box * (.45+.40*fresnel))
    shine = np.exp(-((x+.23)**2/.0016 + (y-.44)**2/.00058))
    surface = tint(surface, rgb(255, 248, 234), shine*.88)
    rgba = np.dstack((np.clip(surface, 0, 1), alpha))
    tile = Image.fromarray(np.rint(rgba*255).astype("uint8")).resize((TILE, TILE), Image.Resampling.LANCZOS)
    atlas.paste(tile, ((frame % COLUMNS)*TILE, (frame // COLUMNS)*TILE))
out = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/composeResources/drawable/studio_marble.png"
atlas.save(out, optimize=True)
