package works.merc.keryx.app.core

/**
 * Lightweight Result type for *expected* errors (network, sync conflict, bad
 * user input). Unexpected/fatal errors (DB failure, bugs) are thrown as regular
 * exceptions instead.
 */
sealed interface Result<out T> {
    data class Ok<out T>(val value: T) : Result<T>
    data class Err(val exception: KeryxException) : Result<Nothing>
}

val <T> Result<T>.isOk: Boolean get() = this is Result.Ok
val <T> Result<T>.isErr: Boolean get() = this is Result.Err

val <T> Result<T>.valueOrNull: T?
    get() = (this as? Result.Ok)?.value

val <T> Result<T>.errorOrNull: KeryxException?
    get() = (this as? Result.Err)?.exception

inline fun <T, R> Result<T>.fold(
    ok: (T) -> R,
    err: (KeryxException) -> R,
): R = when (this) {
    is Result.Ok -> ok(value)
    is Result.Err -> err(exception)
}

inline fun <T> Result<T>.onOk(block: (T) -> Unit): Result<T> {
    if (this is Result.Ok) block(value)
    return this
}

inline fun <T> Result<T>.onErr(block: (KeryxException) -> Unit): Result<T> {
    if (this is Result.Err) block(exception)
    return this
}

/** Maps the success value; passes errors through unchanged. */
inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
    is Result.Ok -> Result.Ok(transform(value))
    is Result.Err -> this
}
