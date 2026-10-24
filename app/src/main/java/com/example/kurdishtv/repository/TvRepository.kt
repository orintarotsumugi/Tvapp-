package com.example.kurdishtv.repository

import com.example.kurdishtv.data.ChannelCacheStorage
import com.example.kurdishtv.data.CustomPlaylistStorage
import com.example.kurdishtv.data.FavoriteStorage
import com.example.kurdishtv.data.RecentStorage
import com.example.kurdishtv.model.Channel
import com.example.kurdishtv.model.KurdishChannelCatalog
import com.example.kurdishtv.network.FetchOutcome
import com.example.kurdishtv.network.NetworkClient
import com.example.kurdishtv.network.NetworkClient.fetchBody
import com.example.kurdishtv.network.retryDelayMs
import com.example.kurdishtv.parser.KurdishTvParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.random.Random

/**
 * Whether a freshly merged list should replace what is already on disk.
 *
 * A refresh that produces *fewer* channels than the cache almost always means
 * some source failed rather than that the catalogue genuinely shrank. The
 * bundled offline catalogue is a floor, so a merge can only get this small by
 * losing a remote source — and a lost source is a temporary condition, while
 * the cache is treated as current for [TvRepository.CACHE_FRESH_MS].
 *
 * Writing it anyway is how one bad refresh becomes thirty minutes of missing
 * channels: the gap is written to disk, the next cold start reads the cache as
 * fresh, and nothing prompts a refetch. The viewer sees a smaller grid and no
 * way to tell that a transient network blip did it.
 *
 * The trade is accepted in the other direction too: if a viewer removes a custom
 * playlist, its channels linger in the cache until it expires. That is the
 * right way round — a few extra channels that stop appearing after one interval
 * is a far smaller harm than hundreds that vanish and stay vanished.
 *
 * Pinned by [com.example.CachePolicyTest] so the comparison cannot be silently
 * inverted, which is the one way this could be edited into the bug it prevents.
 */
internal fun shouldReplaceCache(mergedSize: Int, cachedSize: Int): Boolean =
    mergedSize >= cachedSize

/**
 * Whether a cache of this age is still worth *showing* on a cold start, even when
 * it is too old to trust without refreshing.
 *
 * Zero means "nothing is cached" (see [ChannelCacheStorage.getCacheAgeMs]) and a
 * cache file stamped in the *future* by a corrected clock reports the largest age
 * there is — so neither can pass this by accident. [CachePolicyTest] pins the
 * boundaries.
 */
internal fun isServableCacheAge(ageMs: Long): Boolean =
    ageMs > 0L && ageMs < SERVE_STALE_MS

/**
 * How long a *stale* cache is still shown on a cold start while the merge
 * refreshes behind it.
 *
 * A week. Past this the channels are as likely to be mostly dead as alive — IPTV
 * links rotate — and showing them would be a worse guess than the curated bundled
 * catalogue, which is audited. Below it the trade is clear: six hundred
 * possibly-stale channels the viewer can browse *now*, over ninety-eight fresh
 * ones they can browse in eight seconds.
 */
internal const val SERVE_STALE_MS = 7 * 24 * 60 * 60 * 1000L

/**
 * What [TvRepository.getInstantInitialChannels] produced, and where it came from.
 *
 * A named pair rather than a bare list because the caller has to act on the second
 * half: only a list that came from a *fresh* cache makes a follow-up fetch
 * redundant, and inferring that from the list itself is not possible.
 */
data class InitialChannels(
    val channels: List<Channel>,
    val fromFreshCache: Boolean
)

class TvRepository(
    private val favoriteStorage: FavoriteStorage,
    private val recentStorage: RecentStorage,
    private val customPlaylistStorage: CustomPlaylistStorage,
    private val channelCacheStorage: ChannelCacheStorage,
    private val okHttpClient: OkHttpClient
) {
    private val gistChannelsUrl =
        "https://gist.githubusercontent.com/RekoPlany/58da74c17bd9fdccda3033c77710a7f0/raw/channels.json"
    private val primaryEndpointUrl =
        "https://raw.githubusercontent.com/BotanAtomic/Kurdistan-TV/master/canal.txt"
    private val secondaryEndpointUrl =
        "https://iptv-org.github.io/iptv/languages/kur.m3u"
    private val fallbackGistUrl =
        "https://gist.githubusercontent.com/nevzatokcu/e0fc485c276b509bcbe452ce798606ff/raw/liste.m3u"

    /**
     * The list to show before any network call, and where it came from.
     *
     * [fromFreshCache] is the part callers act on. It is true only when the list is
     * the on-disk cache *and* that cache is young enough that a fetch would serve
     * the same bytes back. When it is true, a subsequent non-forced
     * [fetchChannels] is a no-op by construction, so the caller can skip it rather
     * than re-reading the file and re-filtering the list it already has on screen.
     *
     * ## Stale is served too, and that is deliberate
     *
     * When the cache is older than [CACHE_FRESH_MS] this used to fall back to the
     * 98-channel bundled catalogue and refetch from scratch — so a viewer whose
     * cache held six hundred channels from yesterday watched them *shrink* to
     * ninety-eight for the duration of the merge, then grow back. The network was
     * doing the right thing; the cold start was throwing away the best answer it
     * already had.
     *
     * A stale list is now shown while the merge runs behind it (bounded by
     * [SERVE_STALE_MS], past which the file is treated as gone): the grid is
     * complete and browsable on the first frame, channels from the refreshed
     * sources land when they land, and the viewer never sees a screen get smaller.
     * Only the *fresh* cache short-circuits the fetch — a stale one still reports
     * `fromFreshCache = false` so the caller knows to refresh over the top.
     */
    fun getInstantInitialChannels(): InitialChannels {
        val ageMs = channelCacheStorage.getCacheAgeMs()
        val cached = if (isServableCacheAge(ageMs)) {
            channelCacheStorage.getCachedChannels()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val channels = cached ?: KurdishChannelCatalog.getDefaultChannels()
        return InitialChannels(
            channels = applyFavorites(channels),
            fromFreshCache = cached != null && ageMs < CACHE_FRESH_MS
        )
    }

    /**
     * Stamps the stored favourites onto [channels].
     *
     * One definition of what "is this channel a favourite" means, used by both the
     * cold-start path and the post-merge path. The test is deliberately against the
     * channel's *original* id as well as its current one: [Channel.originalId] is
     * the id before duplicate resolution, so a channel whose id picked up a
     * collision suffix is still the same channel to someone who hearted it before
     * the merge, and a lookup on the suffixed id alone silently dropped the heart.
     *
     * Favourites are re-applied on every read rather than cached alongside the
     * channels, which is why the on-disk cache never persists the flag.
     */
    private fun applyFavorites(channels: List<Channel>): List<Channel> {
        val favIds = favoriteStorage.getFavoriteIds()
        if (favIds.isEmpty()) return channels
        return channels.map { channel ->
            val isFav = favIds.contains(channel.id) || favIds.contains(channel.originalId)
            if (channel.isFavorite == isFav) channel else channel.copy(isFavorite = isFav)
        }
    }

    /**
     * The on-disk list, but only while it is still fresh enough to be served without
     * going to the network. Null when there is no cache, when it is empty, or when
     * it is older than [CACHE_FRESH_MS].
     *
     * The single place that decides this. [fetchChannels] used to inline the same
     * age-then-read-then-check-empty sequence, and the cold-start path ran its own
     * looser version beside it — so the two could disagree about whether the cache
     * was usable, and a list could be read and filtered twice in the same launch.
     */
    private fun freshCachedChannels(): List<Channel>? {
        if (channelCacheStorage.getCacheAgeMs() >= CACHE_FRESH_MS) return null
        return channelCacheStorage.getCachedChannels()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Fetches and merges every remote source.
     *
     * When [forceRefresh] is false and the on-disk cache is younger than
     * [CACHE_FRESH_MS], the cached list is returned without touching the network. Cold
     * starts then render instantly; explicit refreshes pass `true` to bypass the check.
     */
    suspend fun fetchChannels(forceRefresh: Boolean = true): Result<List<Channel>> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                // A zero age now means exactly one thing: nothing is cached. A
                // file stamped in the future by a clock that has since been
                // corrected reports the largest age there is, so it is refetched
                // rather than trusted indefinitely. Either way the read below is
                // what actually decides — a fresh age only lets us *try* the
                // cache, and an empty or unreadable one falls through to the
                // network exactly as if it had been stale.
                val cached = freshCachedChannels()
                if (cached != null) {
                    return@withContext Result.success(applyFavorites(cached))
                }
            }

            val allChannels = mutableListOf<Channel>()
            // Initialize with default fast catalog
            allChannels.addAll(KurdishChannelCatalog.getDefaultChannels())

            try {
                // supervisorScope so a single dead source cannot cancel the others
                // while they are still running.
                //
                // It does NOT do the other half of that job, which is what made
                // this silently lose every remote channel. A supervisorScope still
                // rethrows a child's exception once its children finish, so a
                // single `async` that threw propagated out of the scope — and
                // because each result was collected with an `await()` *inside* the
                // scope, the awaits after the failing one never ran. The channels
                // that had already loaded were never added, and the catch below
                // swallowed the evidence. The viewer got the 98-channel offline
                // catalogue and no indication that three playlists had just
                // answered.
                //
                // [fetchSource] is now a total function — it cannot throw, so
                // there is nothing left for the scope to rethrow.
                //
                // Every source, as (url, id-tag, is-json). Built up front so the
                // four built-ins and the viewer's own playlists are fetched by
                // exactly the same code path — the difference between them used
                // to be four copies of the same block.
                val sources = buildList {
                    add(Triple(gistChannelsUrl, "gist", true))
                    add(Triple(primaryEndpointUrl, "krd", false))
                    add(Triple(secondaryEndpointUrl, "iptv", false))
                    add(Triple(fallbackGistUrl, "fbk", false))
                    customPlaylistStorage.getCustomPlaylistUrls()
                        .forEachIndexed { idx, url ->
                            add(Triple(url, "usr$idx", false))
                        }
                }

                // One slot per source, each written by exactly one child.
                //
                // Not a shared list. The children run concurrently on
                // Dispatchers.IO, so they would be appending to one collection
                // from several threads at a time. A slot per source needs no
                // lock, and reading the array after the scope has joined is
                // ordered by that join.
                val fetched = arrayOfNulls<List<Channel>>(sources.size)

                // Bounded, and the bound is the point.
                //
                // A source is asked up to three times with an eight second
                // timeout and a growing backoff, so one unreachable playlist can
                // spend about twenty five seconds finding that out. Four of those
                // in a row — or a viewer who has imported twenty playlists — is a
                // spinner measured in minutes, on a merge that was supposed to
                // make the app *faster*. The deadline cancels whatever is still in
                // flight and keeps every slot that already landed, so a slow
                // merge degrades to a smaller list rather than to a grid that
                // never appears at all.
                val finishedInTime = withTimeoutOrNull(MERGE_DEADLINE_MS) {
                    supervisorScope {
                        sources.forEachIndexed { index, (url, tag, asJson) ->
                            // Safe to launch bare: fetchSource is total, so a
                            // source that fails returns an empty list rather than
                            // taking the scope — and every other source — down
                            // with it.
                            launch { fetched[index] = fetchSource(url, tag, asJson) }
                        }
                    }
                }
                if (finishedInTime == null) {
                    NetworkClient.logDebug(
                        "Channel merge hit the ${MERGE_DEADLINE_MS}ms deadline; " +
                            "keeping the sources that answered."
                    )
                }
                for (list in fetched) {
                    if (list != null) allChannels.addAll(list)
                }
            } catch (e: CancellationException) {
                // Rethrown, deliberately.
                //
                // This used to be `catch (_: Exception)`, which quietly ate
                // cancellation. That is the worst version of this bug: a merge
                // cancelled midway — by a refresh arriving, by a playlist being
                // edited, by the ViewModel being cleared — did not stop, it
                // carried on to a normal return with whatever it had managed to
                // collect. Swallowing cancellation breaks structured concurrency
                // and it also hid this failure behind a plausible-looking result.
                throw e
            } catch (e: Exception) {
                NetworkClient.logDebug("Channel merge failed: ${e.javaClass.simpleName}")
            }

            // Deduplicate streams
            val deduplicated = KurdishTvParser.deduplicate(allChannels)

            // Ensure absolute key uniqueness for Lazy Layouts.
            //
            // The suffix counts collisions rather than using the index, so an id is
            // derived only from the channel itself. Using the list index made the id
            // depend on position, which broke in two ways: any channel that shifted
            // position between refreshes was treated as a different channel, and a
            // favourited channel whose id gained a suffix could never match its
            // stored favourite id again, so the heart silently reset.
            val uniqueChannels = mutableListOf<Channel>()
            val seenIds = mutableSetOf<String>()
            for (ch in deduplicated) {
                var candidate = ch.id
                var suffix = 2
                while (!seenIds.add(candidate)) {
                    candidate = "${ch.id}_$suffix"
                    suffix++
                }
                uniqueChannels.add(ch.copy(id = candidate))
            }

            // Apply favorite states. See [applyFavorites] — the merge path and the
            // cold-start path must agree on what a favourite *is*, or the same
            // channel reads as a favourite on one launch and not on the next.
            val channelsWithFavs = applyFavorites(uniqueChannels)

            // Persisted to the on-disk cache, still on `Dispatchers.IO` so the
            // serialise-and-write is off the main thread.
            //
            // Deliberately *not* fired at a detached coroutine: the obvious version
            // of this — `scope.launch { save }` built from the current context — is
            // cancelled the instant `withContext` returns, because the Job in
            // `currentCoroutineContext()` there is the `withContext` block's own.
            // The write has to finish inside this block, or be given a real owner.
            //
            // Guarded against a partial merge overwriting a good cache. The retry
            // above makes a source failing much less likely, but it does not make
            // it impossible, and the cost of getting this wrong is thirty minutes
            // of missing channels that no subsequent launch will even try to
            // replace — see [shouldReplaceCache].
            //
            // Counted, not parsed. This used to be
            // `getCachedChannels()?.size`, which read the whole file and built a
            // Channel for every entry in it — six hundred objects — in order to
            // read one integer off the end, on every refresh.
            val cachedCount = channelCacheStorage.getCachedChannelCount()
            if (shouldReplaceCache(channelsWithFavs.size, cachedCount)) {
                channelCacheStorage.saveChannels(channelsWithFavs)
            } else {
                NetworkClient.logDebug(
                    "Merge shrank the catalogue ($cachedCount -> ${channelsWithFavs.size}); " +
                        "keeping the richer cache."
                )
            }

            Result.success(channelsWithFavs)
        }

    /**
     * Fetches one source, retrying the failures that can change their mind.
     *
     * This is the difference between a blip and half an hour of missing channels.
     *
     * A source is fetched once per refresh and the merged result is written to a
     * cache the app then treats as current for [CACHE_FRESH_MS]. So a source that
     * gave up on the first dropped connection did not lose a request, it lost
     * every channel from that playlist for thirty minutes — and the next cold
     * start would serve the gap from cache without ever asking again.
     *
     * The community playlists this app depends on are exactly the kind of host
     * that hiccups: an audit of all 98 catalogue streams found several that only
     * returned media on a second attempt, seconds after a first that had failed.
     * That is the population a single-shot fetch handles worst.
     *
     * Permanent failures — a 404, a 403 — are not retried. They are the source's
     * answer, and repeating the request would only make the viewer wait for
     * nothing.
     */
    /**
     * Fetches and parses one source, and cannot throw.
     *
     * Total by design. Every caller treats a source as optional, so a source that
     * throws is strictly worse than a source that returns nothing: it takes the
     * surrounding [supervisorScope] down with it, and that is what used to cost
     * the viewer every remote channel at once.
     *
     * Cancellation is the one thing it refuses to absorb. A cancelled merge must
     * actually stop; returning an empty list instead would let the caller carry
     * on and publish a half-finished result as if it were complete.
     */
    private suspend fun fetchSource(
        url: String,
        tag: String,
        asJson: Boolean = false
    ): List<Channel> = try {
        val body = fetchUrlContent(url)
        when {
            body == null -> emptyList()
            asJson -> KurdishTvParser.parseJson(body, tag)
            else -> KurdishTvParser.parse(body, tag)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        NetworkClient.logDebug("Source $tag failed: ${e.javaClass.simpleName}: ${e.message}")
        emptyList()
    }

    suspend fun fetchUrlContent(url: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "KurdishTV-Android/3.5")
            .addHeader("Accept", "*/*")
            .build()

        var attempt = 0
        while (true) {
            attempt++
            val outcome = try {
                withTimeoutOrNull(timeoutMs) { okHttpClient.fetchBody(request) }
                    ?: FetchOutcome.Transient("timeout")
            } catch (e: Exception) {
                FetchOutcome.Transient(e.javaClass.simpleName)
            }

            when (outcome) {
                is FetchOutcome.Success -> return outcome.body
                is FetchOutcome.Permanent -> {
                    NetworkClient.logDebug("Source gave a final answer: $url -> ${outcome.code}")
                    return null
                }
                is FetchOutcome.Transient -> {
                    if (attempt >= MAX_FETCH_ATTEMPTS) {
                        NetworkClient.logDebug(
                            "Source still failing after $attempt attempts: $url (${outcome.reason})"
                        )
                        return null
                    }
                    delay(retryDelayMs(attempt, Random.nextFloat()))
                }
            }
        }
    }

    fun toggleFavorite(channelId: String): Boolean = favoriteStorage.toggleFavorite(channelId)

    /**
     * Records a channel as most recently watched and returns the stored history.
     *
     * The list comes back from the write rather than from a second read of it —
     * see [RecentStorage.addRecentChannel].
     */
    fun addRecentChannel(channelId: String): List<String> = recentStorage.addRecentChannel(channelId)

    fun getRecentChannelIds(): List<String> = recentStorage.getRecentChannelIds()

    fun addCustomPlaylistUrl(url: String): Boolean = customPlaylistStorage.addCustomPlaylistUrl(url)

    fun getCustomPlaylistUrls(): Set<String> = customPlaylistStorage.getCustomPlaylistUrls()

    fun removeCustomPlaylistUrl(url: String): Boolean = customPlaylistStorage.removeCustomPlaylistUrl(url)

    fun clearFavorites(): Boolean = favoriteStorage.clear()

    fun clearRecents(): Boolean = recentStorage.clearRecents()

    fun clearCustomPlaylists(): Boolean = customPlaylistStorage.clear()

    fun clearChannelCache(): Boolean = channelCacheStorage.clearCache()

    private companion object {
        /** Per-attempt fetch ceiling. A dead source must not stall the whole merge. */
        const val DEFAULT_TIMEOUT_MS = 8_000L

        /**
         * How many times a source is asked before it is written off.
         *
         * Three, not two and not five. One retry covers the transient case this
         * exists for; a third absorbs a source that is genuinely struggling. Past
         * three the source is not having a moment, it is down, and the viewer is
         * waiting on the rest of the merge for nothing.
         */
        const val MAX_FETCH_ATTEMPTS = 3

        /**
         * A cached list this young is still considered current, so an automatic load
         * reuses it instead of re-fetching every playlist.
         */
        const val CACHE_FRESH_MS = 30 * 60 * 1000L

        /**
         * The ceiling on one merge, counted across every source at once.
         *
         * Comfortably above what a healthy merge needs — a few seconds — and
         * comfortably below the point at which a viewer would decide the app had
         * hung. It cannot be tightened to the per-source worst case, because the
         * sources now run concurrently and the whole point is that the slowest
         * one sets the time rather than their sum.
         */
        const val MERGE_DEADLINE_MS = 30_000L
    }
}
