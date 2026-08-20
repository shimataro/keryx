package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MergeFailureClassifierTest {

    @Test
    fun corruptOrConstraintIsAlwaysCloudDataIncompatible() {
        val classified = MergeFailureClassifier.classify(
            category = SqliteFailureCategory.CORRUPT_OR_CONSTRAINT,
            errorCodeName = "SQLITE_CONSTRAINT_UNIQUE",
        ) { error("must not validate the schema for this category") }

        assertNotNull(classified)
        assertEquals("Cloud DB unusable (SQLITE_CONSTRAINT_UNIQUE)", classified.message)
    }

    @Test
    fun statementErrorIsCloudDataIncompatibleOnlyWhenTheCloudSchemaIsProvenWrong() {
        val classified = MergeFailureClassifier.classify(
            category = SqliteFailureCategory.STATEMENT_ERROR,
            errorCodeName = "SQLITE_ERROR",
        ) { false }

        assertNotNull(classified)
        assertEquals("Cloud DB schema is incompatible", classified.message)
    }

    @Test
    fun statementErrorIsUnclassifiedWhenTheCloudSchemaLooksFineOrIsUndetermined() {
        for (verdict in listOf(true, null)) {
            assertNull(
                MergeFailureClassifier.classify(
                    category = SqliteFailureCategory.STATEMENT_ERROR,
                    errorCodeName = "SQLITE_ERROR",
                ) { verdict },
                "verdict=$verdict must never be classified as a cloud-data problem",
            )
        }
    }

    @Test
    fun otherCategoryIsLeftUnclassifiedWithoutTouchingTheCloudFile() {
        var validated = false
        val classified = MergeFailureClassifier.classify(
            category = SqliteFailureCategory.OTHER,
            errorCodeName = "SQLITE_BUSY",
        ) {
            validated = true
            false
        }

        assertNull(classified)
        assertFalse(validated)
    }
}
