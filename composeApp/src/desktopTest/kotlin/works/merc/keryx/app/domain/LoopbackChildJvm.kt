package works.merc.keryx.app.domain

import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import kotlin.system.exitProcess

/**
 * Entry point of the child JVM `LoopbackRedirectTransportTest` launches with
 * `-Djava.net.preferIPv6Addresses=true`, a setting the JVM reads only at startup and so cannot be
 * flipped inside the test's own JVM.
 *
 * Runs one [LoopbackRedirectTransport.capture] and, as the "browser", requests the redirect URI it
 * is handed with a matching state and a code. Prints `code=<code>` and exits 0 when the callback
 * arrived; any failure (such as a refused connection) prints the error and exits non-zero.
 */
internal object LoopbackChildJvm {
    const val STATE = "child-state"
    const val CODE = "child-code"

    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = try {
            val params = runBlocking {
                LoopbackRedirectTransport { "Done" }.capture(STATE, timeoutMillis = 10_000) { redirectUri ->
                    println("redirect=$redirectUri")
                    val connection = URI("$redirectUri/?state=$STATE&code=$CODE").toURL().openConnection() as HttpURLConnection
                    connection.connectTimeout = 5_000
                    try {
                        check(connection.responseCode == 200) { "callback answered ${connection.responseCode}" }
                    } finally {
                        connection.disconnect()
                    }
                }
            }
            println("code=${params?.code}")
            if (params?.code == CODE) 0 else 2
        } catch (e: Throwable) {
            println("failed: $e")
            1
        }
        exitProcess(exitCode)
    }
}
