package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NameReactivationTest {
    @Test
    fun createOrReactivateIdGeneratesNewIdAndInvokesUpsertWithItWhenExistingIdIsNull() {
        var invokedWith: String? = null

        val result = createOrReactivateId(existingId = null) { id -> invokedWith = id }

        assertEquals(result, invokedWith)
        assertNotEquals("", result)
    }

    @Test
    fun createOrReactivateIdReusesExistingIdAndInvokesUpsertWithIt() {
        var invokedWith: String? = null

        val result = createOrReactivateId(existingId = "existing-id") { id -> invokedWith = id }

        assertEquals("existing-id", result)
        assertEquals("existing-id", invokedWith)
    }
}
