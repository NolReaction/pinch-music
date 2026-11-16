"""Bake a clear marble with coloured internal ribbons under a fixed studio environment.

The sprite atlas rotates the internal glass inclusions. The reflected softboxes stay
in camera space, as they would on a rotating sphere. Run with NumPy and Pillow.
"""
from pathlib import Path

import numpy as np
from PIL import Image


TILE = 112
COLUMNS = 8
ROWS = 6
STEPS = COLUMNS * ROWS
SAMPLES = TILE * 3
PADDING = 2  # Transparent texels prevent neighbouring angular frames from bleeding.
y, x = np.mgrid[:SAMPLES, :SAMPLES]
x = (x + .5 - SAMPLES / 2) / (SAMPLES / 2 - PADDING * 3)
y = -(y + .5 - SAMPLES / 2) / (SAMPLES / 2 - PADDING * 3)
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
sky = tint(rgb(66, 84, 95), rgb(219, 226, 221), smooth(.44 + .6*reflection[..., 1]))
left_box = studio_panel([-.68, .75, .22], [1, -.15, .16], .21, .73)
right_box = studio_panel([.84, .28, -.13], [-.04, 1, .25], .19, .68)
rim_box = studio_panel([-.65, -.2, -.63], [.1, 1, .2], .032, .77)
# Snell's law at the front surface. Sample the furnace-worked interior on a second sphere.
eta = 1 / 1.52
ray = -eta * eye + (eta*z - np.sqrt(1 - eta*eta*(1-z*z)))[..., None] * normal
n_dot_ray = np.sum(normal*ray, axis=-1)
intersection = n_dot_ray*n_dot_ray - (1 - .52**2)
core_mask = smooth((intersection + .015) / .043)
depth = -n_dot_ray - np.sqrt(np.maximum(0, intersection))
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
    # The same blue/turquoise, violet and amber ribbons as the native sphere.
    # The clear volume remains visible between them, unlike a painted solid ball.
    thread = u + .24*np.sin(4.3*v + 3.0*w)
    ribbon = (1 - smooth((np.abs(thread) - .060) / .055)) * core_mask
    violet = (1 - smooth((np.abs(thread + .27 - .16*v) - .045) / .050)) * core_mask
    curl = v - .19*np.sin(4.8*w - 2.0*u) - .13
    amber = (1 - smooth((np.abs(curl) - .035) / .04)) * smooth((u + .40) / .55) * core_mask
    glass = tint(rgb(187, 209, 214), rgb(232, 240, 231), .35 + .60*transmission)
    glass *= (.94 + .06*light)[..., None]
    core = tint(rgb(8, 56, 138), rgb(9, 156, 138), smooth((v + .40) / .92))
    glass = tint(glass, core, ribbon*.88)
    glass = tint(glass, rgb(66, 41, 125), violet*.77)
    glass = tint(glass, rgb(237, 158, 41), amber*.87)
    # A fine light edge and the faint submerged shadow give the ribbon thickness.
    lip = np.exp(-((np.abs(thread) - .062) / .016)**2) * core_mask
    glass = tint(glass, rgb(126, 221, 214), lip*.28)
    glass = tint(glass, rgb(104, 141, 151), .12*(1-z)**2)
    glass = tint(glass, rgb(219, 248, 218), smooth((-y-.33)*1.95)*window*.26)
    surface = tint(glass, sky, .055 + .82*fresnel)
    surface = tint(surface, rgb(222, 232, 226), .13*dome)
    surface = tint(surface, rgb(42, 63, 83), .13*np.exp(-((horizon+.095)/.13)**2))
    surface = tint(surface, rgb(230, 241, 240), .045*np.exp(-((horizon-.045)/.095)**2)*dome)

    surface = tint(surface, rgb(255, 252, 239), left_box * (.62+.30*fresnel))
    surface = tint(surface, rgb(238, 249, 247), right_box * (.40+.46*fresnel))
    surface = tint(surface, rgb(226, 239, 246), rim_box * (.38+.47*fresnel))
    shine = np.exp(-((x+.23)**2/.0016 + (y-.44)**2/.00058))
    surface = tint(surface, rgb(255, 248, 234), shine*.88)
    core_opacity = np.maximum(ribbon*.95, np.maximum(violet*.86, amber*.95))
    shell_opacity = np.maximum(fresnel, np.maximum(left_box*.62, np.maximum(right_box*.50, shine*.80)))
    opacity = .34 + .66*np.maximum(core_opacity, shell_opacity)
    rgba = np.dstack((np.clip(surface, 0, 1), alpha*opacity))
    tile = Image.fromarray(np.rint(rgba*255).astype("uint8")).resize((TILE, TILE), Image.Resampling.LANCZOS)
    # Remove Lanczos ringing in the padding, so atlas sampling cannot leak an
    # adjacent angle through a barely opaque one-pixel frame edge.
    pixels = np.asarray(tile).copy()
    pixels[0, :, 3] = pixels[-1, :, 3] = 0
    pixels[:, 0, 3] = pixels[:, -1, 3] = 0
    tile = Image.fromarray(pixels)
    atlas.paste(tile, ((frame % COLUMNS)*TILE, (frame // COLUMNS)*TILE))
out = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/composeResources/drawable/studio_marble.png"
atlas.save(out, optimize=True)
