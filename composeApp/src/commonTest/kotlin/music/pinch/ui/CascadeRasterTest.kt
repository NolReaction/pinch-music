package music.pinch.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CascadeRasterTest {
    @Test
    fun retainsNativePixelsWhenTheyFitAndBoundsRetinaAndTallScreens() {
        assertEquals(1.0, CascadeRaster.scale(360.0, 640.0, 1.0))
        assertEquals(2.0, CascadeRaster.scale(375.0, 667.0, 2.0))
        assertEquals(864.0 / 600.0, CascadeRaster.scale(900.0, 600.0, 2.0))
        assertEquals(1600.0 / 932.0, CascadeRaster.scale(430.0, 932.0, 3.0))
        assertEquals(1600.0 / 3200.0, CascadeRaster.scale(1440.0, 3200.0, 1.0))
    }
}
