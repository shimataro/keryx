package works.merc.keryx.app.platform

/** Minimal cross-platform filesystem access for settings/DB files. */
expect object FileIO {
    fun exists(path: String): Boolean
    fun readText(path: String): String?
    fun writeText(path: String, content: String)
    fun readBytes(path: String): ByteArray?
    fun writeBytes(path: String, bytes: ByteArray)
    fun delete(path: String)
    fun join(vararg parts: String): String
}
