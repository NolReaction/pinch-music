#ifndef PINCH_STAGE3D_H
#define PINCH_STAGE3D_H
#ifdef __cplusplus
extern "C" {
#endif

/* All calls run on the thread owning the current OpenGL ES 2.0 context.
 * The caller provides a framebuffer with a depth attachment. World Y points down. */
void* stage3d_create(void);
void stage3d_destroy(void* stage);
/* keys: [faceContactX, faceContactY, side (-1/+1), style (0 accent, 1 target),
 *        signed slope dy/dx]. The first two values locate the tangent point on the key.
 * A style of -1 denotes a silent launcher; its x/y are the rail origin and no key is drawn.
 * Rails: [panel index, x, y] in panel/travel order. (x,y,0) is the
 * centre of the marble at contact. Data is copied, counts are numbers of rows. */
#define STAGE3D_KEY_STRIDE 5
void stage3d_set_course(void* stage, const float* keys, int keyCount, const float* rails, int railPointCount);
/* Frame: ball XYZ, cameraY, roll, activePanel, impact, capTravel, seatedBallTravel,
 * nextPanel, cameraX, wallMotif, cameraSpeed, four packed 24-bit sRGB colours
 * (wall, light, panel, accent), then four (panel, signed score opacity) pairs.
 * Positive score means a hit, negative a miss. Missing indices are -1. */
#define STAGE3D_FRAME_SIZE 25
void stage3d_draw(void* stage, int width, int height, const float* frame);

#ifdef __cplusplus
}
#endif
#endif
