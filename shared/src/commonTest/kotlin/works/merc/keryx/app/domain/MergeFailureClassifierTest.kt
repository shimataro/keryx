package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MergeFailureClassifierTest {

    private fun classify(
        category: SqliteFailureCategory,
        errorCodeName: String,
        localForeignKeysSound: Boolean? = null,
        validateCloudSchema: () -> Boolean? = { error("must not validate the schema for this category") },
        cloudIntegrityOk: () -> Boolean? = { error("must not check the cloud file's integrity for this category") },
    ) = MergeFailureClassifier.classify(
        category = category,
        errorCodeName = errorCodeName,
        localForeignKeysSound = localForeignKeysSound,
        validateCloudSchema = validateCloudSchema,
        cloudIntegrityOk = cloudIntegrityOk,
    )

    @Test
    fun constraintIsCloudDataIncompatibleWhenTheLocalForeignKeysAreSound() {
        val classified = classify(
            category = SqliteFailureCategory.CONSTRAINT,
            errorCodeName = "SQLITE_CONSTRAINT_UNIQUE",
            localForeignKeysSound = true,
        )

        assertNotNull(classified)
        assertEquals("Cloud DB unusable (SQLITE_CONSTRAINT_UNIQUE)", classified.message)
    }

    @Test
    fun constraintIsUnclassifiedWhenTheLocalDbAlreadyViolatesAForeignKey() {
        assertNull(
            classify(
                category = SqliteFailureCategory.CONSTRAINT,
                errorCodeName = "SQLITE_CONSTRAINT_FOREIGNKEY",
                localForeignKeysSound = false,
            ),
        )
    }

    @Test
    fun constraintIsUnclassifiedWhenTheLocalForeignKeyCheckIsUndetermined() {
        assertNull(
            classify(
                category = SqliteFailureCategory.CONSTRAINT,
                errorCodeName = "SQLITE_CONSTRAINT_UNIQUE",
                localForeignKeysSound = null,
            ),
        )
    }

    @Test
    fun corruptIsCloudDataIncompatibleWhenTheCloudFileFailsItsIntegrityCheck() {
        val classified = classify(
            category = SqliteFailureCategory.CORRUPT,
            errorCodeName = "SQLITE_CORRUPT",
            // A verdict about main must not matter for a corrupt-file failure.
            localForeignKeysSound = false,
            cloudIntegrityOk = { false },
        )

        assertNotNull(classified)
        assertEquals("Cloud DB unusable (SQLITE_CORRUPT)", classified.message)
    }

    @Test
    fun corruptIsUnclassifiedWhenTheCloudFileIsIntactOrUndetermined() {
        for (verdict in listOf(true, null)) {
            assertNull(
                classify(
                    category = SqliteFailureCategory.CORRUPT,
                    errorCodeName = "SQLITE_CORRUPT",
                    localForeignKeysSound = true,
                    cloudIntegrityOk = { verdict },
                ),
                "cloudIntegrityOk=$verdict must never be classified as a cloud-data problem",
            )
        }
    }

    @Test
    fun statementErrorIsCloudDataIncompatibleOnlyWhenTheCloudSchemaIsProvenWrong() {
        val classified = classify(
            category = SqliteFailureCategory.STATEMENT_ERROR,
            errorCodeName = "SQLITE_ERROR",
            localForeignKeysSound = true,
            validateCloudSchema = { false },
        )

        assertNotNull(classified)
        assertEquals("Cloud DB schema is incompatible", classified.message)
    }

    @Test
    fun statementErrorIsUnclassifiedWhenTheCloudSchemaLooksFineOrIsUndetermined() {
        for (verdict in listOf(true, null)) {
            assertNull(
                classify(
                    category = SqliteFailureCategory.STATEMENT_ERROR,
                    errorCodeName = "SQLITE_ERROR",
                    localForeignKeysSound = true,
                    validateCloudSchema = { verdict },
                ),
                "verdict=$verdict must never be classified as a cloud-data problem",
            )
        }
    }

    @Test
    fun otherCategoryIsLeftUnclassifiedWithoutTouchingTheCloudFile() {
        var validated = false
        var integrityChecked = false
        val classified = classify(
            category = SqliteFailureCategory.OTHER,
            errorCodeName = "SQLITE_BUSY",
            localForeignKeysSound = true,
            validateCloudSchema = {
                validated = true
                false
            },
            cloudIntegrityOk = {
                integrityChecked = true
                false
            },
        )

        assertNull(classified)
        assertFalse(validated)
        assertFalse(integrityChecked)
    }

    @Test
    fun localForeignKeyVerdictAppliesOnlyToTheFailureItWasRecordedFor() {
        val failure = IllegalStateException("merge statement failed")
        val verdict = LocalForeignKeyVerdict()
        assertNull(verdict.soundFor(failure))

        verdict.record(failure, sound = true)

        assertTrue(verdict.soundFor(failure) == true)
        // A different throwable (e.g. one a cleanup step threw instead) gets no verdict.
        assertNull(verdict.soundFor(IllegalStateException("merge statement failed")))
    }
}
