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
        mainRuledOut: Boolean? = null,
        validateCloudSchema: () -> Boolean? = { error("must not validate the schema for this category") },
        cloudIntegrityOk: () -> Boolean? = { error("must not check the cloud file's integrity for this category") },
    ) = MergeFailureClassifier.classify(
        category = category,
        errorCodeName = errorCodeName,
        mainRuledOut = mainRuledOut,
        validateCloudSchema = validateCloudSchema,
        cloudIntegrityOk = cloudIntegrityOk,
    )

    @Test
    fun constraintIsCloudDataIncompatibleWhenMainIsRuledOut() {
        val classified = classify(
            category = SqliteFailureCategory.CONSTRAINT,
            errorCodeName = "SQLITE_CONSTRAINT_UNIQUE",
            mainRuledOut = true,
        )

        assertNotNull(classified)
        assertEquals("Cloud DB unusable (SQLITE_CONSTRAINT_UNIQUE)", classified.message)
    }

    @Test
    fun constraintIsUnclassifiedWhenMainIsTheCause() {
        assertNull(
            classify(
                category = SqliteFailureCategory.CONSTRAINT,
                errorCodeName = "SQLITE_CONSTRAINT_FOREIGNKEY",
                mainRuledOut = false,
            ),
        )
    }

    @Test
    fun constraintIsUnclassifiedWhenWhetherMainIsTheCauseIsUndetermined() {
        assertNull(
            classify(
                category = SqliteFailureCategory.CONSTRAINT,
                errorCodeName = "SQLITE_CONSTRAINT_UNIQUE",
                mainRuledOut = null,
            ),
        )
    }

    @Test
    fun corruptIsCloudDataIncompatibleWhenTheCloudFileFailsItsIntegrityCheck() {
        val classified = classify(
            category = SqliteFailureCategory.CORRUPT,
            errorCodeName = "SQLITE_CORRUPT",
            // A verdict about main must not matter for a corrupt-file failure.
            mainRuledOut = false,
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
                    mainRuledOut = true,
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
            mainRuledOut = true,
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
                    mainRuledOut = true,
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
            mainRuledOut = true,
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
    fun mainRuledOutVerdictAppliesOnlyToTheFailureItWasRecordedFor() {
        val failure = IllegalStateException("merge statement failed")
        val verdict = MainRuledOutVerdict()
        assertNull(verdict.mainRuledOutFor(failure))

        verdict.record(failure, mainRuledOut = true)

        assertTrue(verdict.mainRuledOutFor(failure) == true)
        // A different throwable (e.g. one a cleanup step threw instead) gets no verdict.
        assertNull(verdict.mainRuledOutFor(IllegalStateException("merge statement failed")))
    }

    // --- mainRuledOut: is main's own FOREIGN KEY violation the cause of a constraint failure? ---

    private val orphan = ForeignKeyViolation(table = "articles", rowId = 1, parent = "feeds", foreignKeyId = 0)
    private val otherOrphan = ForeignKeyViolation(table = "articles", rowId = 2, parent = "feeds", foreignKeyId = 0)
    private val folderOrphan = ForeignKeyViolation(table = "feeds", rowId = 1, parent = "folders", foreignKeyId = 0)

    private val noRerun: () -> DeferredRerunOutcome = { error("must not re-run the merge for this case") }

    @Test
    fun mainIsRuledOutWithoutARerunWhenItHoldsNoForeignKeyViolation() {
        assertEquals(true, MergeFailureClassifier.mainRuledOut(emptySet(), noRerun))
    }

    @Test
    fun undeterminedWithoutARerunWhenMainsViolationsCouldNotBeRead() {
        assertNull(MergeFailureClassifier.mainRuledOut(null, noRerun))
    }

    @Test
    fun mainIsRuledOutWhenTheDeferredRerunFailsOnAConstraint() {
        assertEquals(true, MergeFailureClassifier.mainRuledOut(setOf(orphan)) { DeferredRerunOutcome.ConstraintFailed })
    }

    @Test
    fun mainIsRuledOutWhenTheDeferredRerunLeavesANewViolation() {
        assertEquals(
            true,
            MergeFailureClassifier.mainRuledOut(setOf(orphan)) { DeferredRerunOutcome.Completed(setOf(orphan, folderOrphan)) },
        )
    }

    /** Compared by members, not counts: the re-run may resolve an old violation while adding a new one. */
    @Test
    fun mainIsRuledOutWhenTheDeferredRerunSwapsAViolationForANewOneOfTheSameCount() {
        assertEquals(
            true,
            MergeFailureClassifier.mainRuledOut(setOf(orphan)) { DeferredRerunOutcome.Completed(setOf(otherOrphan)) },
        )
    }

    @Test
    fun mainIsTheCauseWhenTheDeferredRerunLeavesNoNewViolation() {
        assertEquals(
            false,
            MergeFailureClassifier.mainRuledOut(setOf(orphan, otherOrphan)) { DeferredRerunOutcome.Completed(setOf(orphan, otherOrphan)) },
        )
        // Resolving one of main's own violations adds nothing new either.
        assertEquals(
            false,
            MergeFailureClassifier.mainRuledOut(setOf(orphan, otherOrphan)) { DeferredRerunOutcome.Completed(setOf(orphan)) },
        )
    }

    @Test
    fun undeterminedWhenTheDeferredRerunIsUndetermined() {
        assertNull(MergeFailureClassifier.mainRuledOut(setOf(orphan)) { DeferredRerunOutcome.Undetermined })
    }

    @Test
    fun theRerunCoversTheFailedStatementAndEveryOneAfterIt() {
        val statements = listOf("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), MergeFailureClassifier.remainingStatements(statements, failedIndex = 0))
        assertEquals(listOf("b", "c"), MergeFailureClassifier.remainingStatements(statements, failedIndex = 1))
        assertEquals(listOf("c"), MergeFailureClassifier.remainingStatements(statements, failedIndex = 2))
    }
}
