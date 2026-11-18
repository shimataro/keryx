package works.merc.keryx.app.tray

import kotlin.test.Test
import kotlin.test.assertEquals

/** [chooseTrayImplementation]: every branch, in its priority order. */
class TrayImplementationChoiceTest {

    private fun choose(mac: Boolean = false, sni: Boolean = false, windows: Boolean = false, supported: Boolean = false) =
        chooseTrayImplementation(mac, sni, windows, supported)

    @Test
    fun `macOS uses the mac tray when AWT supports a tray`() {
        assertEquals(TrayImplementation.MAC, choose(mac = true, supported = true))
    }

    @Test
    fun `macOS without tray support shows nothing even with other inputs set`() {
        assertEquals(TrayImplementation.NONE, choose(mac = true, sni = true, supported = false))
    }

    @Test
    fun `macOS wins over an SNI connection`() {
        assertEquals(TrayImplementation.MAC, choose(mac = true, sni = true, supported = true))
    }

    @Test
    fun `an SNI connection uses the Linux tray whether or not AWT supports a tray`() {
        assertEquals(TrayImplementation.LINUX_SNI, choose(sni = true, supported = false))
        assertEquals(TrayImplementation.LINUX_SNI, choose(sni = true, supported = true))
    }

    @Test
    fun `an SNI connection wins over Windows`() {
        assertEquals(TrayImplementation.LINUX_SNI, choose(sni = true, windows = true))
    }

    @Test
    fun `Windows uses its own tray regardless of AWT support`() {
        assertEquals(TrayImplementation.WINDOWS, choose(windows = true, supported = false))
        assertEquals(TrayImplementation.WINDOWS, choose(windows = true, supported = true))
    }

    @Test
    fun `otherwise a supported AWT tray uses Compose's own Tray`() {
        assertEquals(TrayImplementation.COMPOSE_AWT, choose(supported = true))
    }

    @Test
    fun `no tray support and no other route shows nothing`() {
        assertEquals(TrayImplementation.NONE, choose())
    }
}
