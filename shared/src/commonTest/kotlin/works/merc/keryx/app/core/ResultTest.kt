package works.merc.keryx.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    private fun unwrap(r: Result<Int>, lambdaCalls: IntArray): String {
        val v = r.valueOrElse {
            lambdaCalls[0]++
            return "early:${it.exception.messageText}"
        }
        return "value:$v"
    }

    @Test
    fun valueOrElseReturnsOkValueWithoutInvokingLambda() {
        val calls = intArrayOf(0)
        assertEquals("value:7", unwrap(Result.Ok(7), calls))
        assertEquals(0, calls[0])
    }

    @Test
    fun valueOrElsePreservesANullSuccessWithoutInvokingTheErrorHandler() {
        val result: Result<String?> = Result.Ok(null)
        var called = false

        val value = result.valueOrElse {
            called = true
            error("unexpected error")
        }

        assertNull(value)
        assertTrue(!called)
    }

    @Test
    fun valueOrElseInvokesLambdaWithSameErrAndTakesEarlyReturn() {
        val err = Result.Err(FeedParseException("boom"))
        var received: Result.Err? = null
        fun run(): String {
            (err as Result<Int>).valueOrElse {
                received = it
                return "early"
            }
            return "not reached"
        }
        assertEquals("early", run())
        assertSame(err, received)
        val calls = intArrayOf(0)
        assertEquals("early:boom", unwrap(err, calls))
        assertEquals(1, calls[0])
    }

    @Test
    fun onOkRunsOnlyForOkAndReturnsReceiverUnchanged() {
        val ok: Result<Int> = Result.Ok(3)
        var seen: Int? = null
        assertSame(ok, ok.onOk { seen = it })
        assertEquals(3, seen)

        val err: Result<Int> = Result.Err(FeedTimeoutException())
        var called = false
        assertSame(err, err.onOk { called = true })
        assertTrue(!called)
    }

    @Test
    fun onErrRunsOnlyForErrAndReturnsReceiverUnchanged() {
        val err: Result<Int> = Result.Err(FeedParseException("x"))
        var seen: KeryxException? = null
        assertSame(err, err.onErr { seen = it })
        assertSame((err as Result.Err).exception, seen)

        val ok: Result<Int> = Result.Ok(3)
        var called = false
        assertSame(ok, ok.onErr { called = true })
        assertTrue(!called)
    }
}
