"""Bake the view-dependent studio reflection of the stage's metallic sphere into RGBA."""
from pathlib import Path
import numpy as np
from PIL import Image

s = 600
y, x = np.mgrid[:s, :s]
x = (x + .5 - s / 2) / (s / 2 - 2)
y = -(y + .5 - s / 2) / (s / 2 - 2)
r2 = x*x + y*y
z = np.sqrt(np.maximum(0, 1-r2))
n = np.stack((x, y, z), axis=-1)
reflection = n * (2*z[...,None]) - [0,0,1]
rx, ry, rz = reflection.transpose(2,0,1)

def colour(rgb):
    return np.asarray(rgb, dtype=float)/255

def mix(a,b,t):
    return (1-t[...,None])*a + t[...,None]*b

def soft(t):
    t=np.clip(t,0,1)
    return t*t*(3-2*t)

def boxlight(cx,cy,wx,wy,tilt,amount,shade):
    # Planar softboxes reflected in a metal ball.
    u, v = rx - cx + tilt * (ry-cy), ry - cy
    g=soft((1-np.abs(u)/wx)*7)*soft((1-np.abs(v)/wy)*6)*soft((rz+.42)*2.8)
    return mix(amount,colour(shade),g*.94)

# Copper/carminite coating under broad warm and cool studio panels.
horizon = ry + .18*np.sin(rx*3.2+rz*.95) -.02
sky = mix(colour([172,58,74]),colour([255,177,146]),soft(.45+.5*ry+.19*rx))
ground = mix(colour([58,29,80]),colour([175,70,83]),soft(-.17-.88*ry+.22*rx))
surface = mix(ground,sky,soft((horizon+.09)*6))
cool_band = np.exp(-((horizon+.13)/.13)**2)
surface = mix(surface,colour([47,50,102]),cool_band*.67)
surface = boxlight(-.40,.57,.27,.15,.34,surface,[255,246,225])
surface = boxlight(.72,.06,.088,.53,-.26,surface,[248,149,167])
surface = boxlight(-.78,-.24,.05,.31,.32,surface,[190,193,251])
# Diffuse falloff, skirt of reflected violet and bottom honey coloured bounce.
surface*= (.85+.15*z)[...,None]
surface = mix(surface,colour([211,169,239]), .40*(1-z)**2)
spec=np.exp(-((x+.28)**2/.005+(y-.48)**2/.002))
surface=mix(surface,colour([255,252,243]),spec*.8)
rgba=np.concatenate([surface,np.clip((1-np.sqrt(r2))*s/2,0,1)[...,None]],axis=-1)
image=Image.fromarray(np.rint(255*np.clip(rgba,0,1)).astype('uint8'))
out=Path(__file__).resolve().parents[1] / 'composeApp/src/commonMain/composeResources/drawable/studio_marble.png'
image.resize((200,200),Image.Resampling.LANCZOS).save(out,optimize=True)
