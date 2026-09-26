package works.merc.keryx.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises [hostArchitectureFor]'s `os.arch` string mapping directly, independent of the real
 * property value this test itself runs under — see that function's own KDoc for why it's pulled
 * out as a pure function rather than tested only through the real [hostArchitecture].
 */
class DesktopOsTest {
    @Test
    fun recognizesEveryJvmSpellingOfX86_64() {
        for (value in listOf("amd64", "x86_64")) {
            assertEquals(HostArchitecture.X86_64, hostArchitectureFor(value), "value=$value")
        }
    }

    @Test
    fun recognizesEveryJvmSpellingOfArm64() {
        for (value in listOf("aarch64", "arm64")) {
            assertEquals(HostArchitecture.ARM64, hostArchitectureFor(value), "value=$value")
        }
    }

    @Test
    fun anUnrecognizedOrMissingArchitectureIsUnknown() {
        for (value in listOf("i386", "x86", "arm", "riscv64", "")) {
            assertEquals(HostArchitecture.UNKNOWN, hostArchitectureFor(value), "value=$value")
        }
    }
}
