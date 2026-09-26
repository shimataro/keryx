package works.merc.keryx.app.platform

/** [size] bytes from the platform's cryptographically secure random source. */
expect fun secureRandomBytes(size: Int): ByteArray
