#ifndef PINCH_STAGE3D_H
#define PINCH_STAGE3D_H
#ifdef __cplusplus
extern "C" {
#endif

/* All calls run on the thread owning the current OpenGL ES 2.0 context.
 * The caller provides a framebuffer with a depth attachment. World Y points down. */
void* stage3d_create(void);
void stage3d_destroy(void* stage);
/* keys: [faceContactX, faceContactY, side (-1/+1), style (0 pink, 1 amber, 2 echo),
 *        signed slope dy/dx]. The first two values locate the tangent point on the key.
 * Rails and LEDs: [panel index, x, y] in panel/travel order. (x,y,0) is the
 * centre of the marble at contact. Data is copied, counts are numbers of rows. */
#define STAGE3D_KEY_STRIDE 5
void stage3d_set_course(void* stage, const float* keys, int keyCount, const float* rails, int railPointCount,
                        const float* lamps, int lampCount);
/* [ballX, ballY, ballZ, cameraY, rollRadians, activePanel, impactStrength,
 *  rollingPanel, lastCrossedLocalLed, nextPanel, nextStyle, activeStyle]. Missing indices are -1. */
void stage3d_draw(void* stage, int width, int height, const float* frame);

#ifdef __cplusplus
}
#endif
#endif
