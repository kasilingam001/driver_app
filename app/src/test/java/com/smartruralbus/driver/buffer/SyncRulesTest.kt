package com.smartruralbus.driver.buffer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncRulesTest {
    @Test fun backoffGrowsAndStopsAtThirtySeconds() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..6).map { SyncRules.backoffMillis(it) })
        assertEquals(2_000L, SyncRules.backoffMillis(0))
    }

    @Test fun overflowDropsOnlyWhatIsOverTheLimit() {
        assertEquals(0, SyncRules.overflow(SyncRules.MAX_PENDING))
        assertEquals(3, SyncRules.overflow(SyncRules.MAX_PENDING + 3))
        assertEquals(0, SyncRules.overflow(10))
    }

    @Test fun smallerBatchHalvesButNeverReachesZero() {
        assertEquals(25, SyncRules.smallerBatch(50))
        assertEquals(1, SyncRules.smallerBatch(1))
        assertEquals(1, SyncRules.smallerBatch(2))
    }

    @Test fun everyConfirmedPointIsRemoved() {
        val ids = listOf(5L, 4L, 3L)
        assertEquals(ids, SyncRules.idsToRemove(ids, listOf("saved", "duplicate", "rejected")))
    }

    @Test fun anAnswerThatDoesNotMatchDeletesNothing() {
        assertNull(SyncRules.idsToRemove(listOf(1L, 2L), listOf("saved")))
        assertNull(SyncRules.idsToRemove(listOf(1L), listOf("maybe")))
        assertNull(SyncRules.idsToRemove(listOf(1L), listOf(null)))
    }
}
