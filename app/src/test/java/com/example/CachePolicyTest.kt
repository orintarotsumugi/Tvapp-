package com.example

import com.example.kurdishtv.repository.SERVE_STALE_MS
import com.example.kurdishtv.repository.isServableCacheAge
import com.example.kurdishtv.repository.shouldReplaceCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that stops a bad refresh from becoming a long one.
 *
 * The failure this prevents is invisible and persistent at once: a refresh that
 * loses a source writes the smaller list to disk, the next cold start reads that
 * cache as fresh, and nothing prompts a refetch — so a two-second network blip
 * becomes thirty minutes of missing channels that the viewer has no way to
 * diagnose.
 *
 * The rule is one comparison, which is exactly why it needs a test. A predicate
 * this small gets "tidied" — inverted, flipped to `>`, changed to compare the
 * wrong pair — and nothing else in the build would notice. Its correctness is
 * not visible in any screenshot.
 */
class CachePolicyTest {

    @Test
    fun `a partial merge does not replace a richer cache`() {
        // The case the rule exists for. 1000 channels on disk, this refresh
        // managed 200 because two of the four sources failed.
        assertFalse(
            "a merge that lost channels must not overwrite the cache",
            shouldReplaceCache(mergedSize = 200, cachedSize = 1000)
        )
    }

    @Test
    fun `a full or better merge replaces the cache`() {
        assertTrue("a larger merge is a real update", shouldReplaceCache(1200, 1000))
    }

    @Test
    fun `an identical merge replaces the cache`() {
        // Deliberately allowed. The channels may well be different even at the
        // same count, and a tie is not evidence of a regression.
        assertTrue(shouldReplaceCache(1000, 1000))
    }

    @Test
    fun `an empty cache is always replaced`() {
        // First run: nothing on disk, so there is nothing to protect.
        assertTrue(shouldReplaceCache(mergedSize = 0, cachedSize = 0))
        assertTrue(shouldReplaceCache(mergedSize = 1, cachedSize = 0))
    }

    @Test
    fun `the bundled catalogue alone never shrinks a merged cache`() {
        // A total loss of every remote source still yields the offline
        // catalogue, so the floor is well above zero and the guard engages
        // rather than being silently bypassed.
        val catalogue = 98
        assertFalse(
            "losing every source must not shrink the cache",
            shouldReplaceCache(mergedSize = catalogue, cachedSize = 1000)
        )
    }

    @Test
    fun `the comparison is not inverted`() {
        // A single assertion that fails loudly if someone ever swaps the two
        // arguments' meaning, which is the realistic way this goes wrong.
        assertTrue(shouldReplaceCache(10, 9))
        assertFalse(shouldReplaceCache(9, 10))
    }

    // ── Stale-serving ───────────────────────────────────────────────────
    //
    // The cold-start path shows a stale cache while the merge refreshes behind
    // it. These pin the boundaries of "too old to show", which is invisible in
    // a screenshot and permanent in behaviour: get it wrong upwards and a
    // viewer browses month-old dead links; get it wrong at zero and an absent
    // cache (which reports age 0) is *shown*, blanketing the bundled catalogue
    // with nothing.

    @Test
    fun `a recent or day-old cache is servable`() {
        assertTrue(isServableCacheAge(1))
        assertTrue(isServableCacheAge(24 * 60 * 60 * 1000L))
        // Inside the freshness window this is also "fresh", and it is servable
        // for the same reason.
        assertTrue(isServableCacheAge(30 * 60 * 1000L))
    }

    @Test
    fun `age zero means nothing is cached and is not servable`() {
        // ChannelCacheStorage.getCacheAgeMs reports 0 for a missing or empty
        // file. Serving "an empty cache" would replace the bundled catalogue
        // with no channels at all.
        assertFalse(isServableCacheAge(0))
    }

    @Test
    fun `a cache older than the stale horizon is not servable`() {
        assertFalse(isServableCacheAge(SERVE_STALE_MS))
        assertFalse(isServableCacheAge(SERVE_STALE_MS + 1))
        assertFalse(isServableCacheAge(Long.MAX_VALUE))
    }

    @Test
    fun `a cache stamped in the future is not servable`() {
        // The storage layer clamps a future timestamp to the largest age there
        // is, but a negative age must not slip through this predicate either —
        // it is the one answer that would read as "written just now".
        assertFalse(isServableCacheAge(-1))
        assertFalse(isServableCacheAge(Long.MIN_VALUE))
    }
}
