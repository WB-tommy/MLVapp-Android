package fm.magiclantern.forum.features.export

import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportServicePolicyTest {

    @Test
    fun `android 10 uses data sync foreground type`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            exportForegroundServiceTypeForSdk(Build.VERSION_CODES.Q)
        )
    }

    @Test
    fun `android 14 uses data sync foreground type`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            exportForegroundServiceTypeForSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )
    }

    @Test
    fun `android 15 and newer use media processing foreground type`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            exportForegroundServiceTypeForSdk(Build.VERSION_CODES.VANILLA_ICE_CREAM)
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            exportForegroundServiceTypeForSdk(Build.VERSION_CODES.BAKLAVA)
        )
    }
}
