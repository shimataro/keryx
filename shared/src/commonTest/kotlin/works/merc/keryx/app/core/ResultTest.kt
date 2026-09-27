package works.merc.keryx.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultTest {
    @Test
    fun okCarriesValue() {
        val r: Result<Int> = Result.Ok(42)
        assertTrue(r.isOk)
        assertEquals(42, r.valueOrNull)
        assertNull(r.errorOrNull)
    }

    @Test
    fun errCarriesException() {
        val r: Result<Int> = Result.Err(FeedTimeoutException())
        assertTrue(r.isErr)
        assertNull(r.valueOrNull)
        assertTrue(r.errorOrNull is FeedTimeoutException)
    }

    @Test
    fun foldSelectsBranch() {
        assertEquals("ok:1", Result.Ok(1).fold(ok = { "ok:$it" }, err = { "err" }))
        assertEquals("err", (Result.Err(FeedParseException("x")) as Result<Int>).fold(ok = { "ok" }, err = { "err" }))
    }

    @Test
    fun mapTransformsOkOnly() {
        assertEquals(4, Result.Ok(2).map { it * 2 }.valueOrNull)
        val err: Result<Int> = Result.Err(FeedParseException("x"))
        assertTrue(err.map { it * 2 }.isErr)
    }
}
