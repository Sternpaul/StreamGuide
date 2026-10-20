package com.sternpaul.streamguide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sternpaul.streamguide.core.*
import com.sternpaul.streamguide.data.ProxyCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppScreen { GUIDE, SEARCH, SETTINGS, DIAGNOSTICS, PROXY, ORGANIZE, EDIT_PROVIDER, IMPORT_STATUS, MULTIVIEW, PLAYER, SETUP }
enum class ChannelSort { MANUAL, ALPHABETICAL, PROVIDER }
enum class OverlayMenu { NONE, APP, CHANNEL }
enum class OptionsContext { CHANNEL, GROUP }

object NavigationPolicy {
    fun afterDestinationSelected(): OverlayMenu = OverlayMenu.NONE
    fun onOptionsPressed(screen: AppScreen): OverlayMenu = if (screen == AppScreen.GUIDE) OverlayMenu.CHANNEL else OverlayMenu.NONE
}

class ProgramIndex private constructor(private val byChannelId: Map<String, List<Program>>) {
    constructor(programs: List<Program>) : this(programs.groupBy { it.channelId })

    // Rows ask for the same channel on every recomposition; handing back the same
    // list instance lets unchanged rows skip.
    private val perChannel = HashMap<String, List<Program>>()

    private fun channelIds(channel: Channel): List<String> = listOf(channel.id, channel.tvgId).filter { it.isNotBlank() }.distinct()

    fun forChannel(channel: Channel): List<Program> = perChannel.getOrPut(channel.id + "\u0000" + channel.tvgId) {
        channelIds(channel).asSequence()
            .flatMap { byChannelId[it].orEmpty().asSequence() }
            .distinct()
            .sortedBy { it.startEpochMs }
            .toList()
    }

    fun containsTitle(channel: Channel, query: String): Boolean = channelIds(channel).any { id ->
        byChannelId[id].orEmpty().any { it.title.contains(query, ignoreCase = true) }
    }

    /** Swaps the programmes of [channelIds] without regrouping everything else. */
    fun replacing(channelIds: Set<String>, programs: List<Program>): ProgramIndex =
        ProgramIndex(byChannelId - channelIds + programs.groupBy { it.channelId })
}

/** Single-entry cache: UiState is copied on every change, its derived lists rarely change. */
private class Memo<T> {
    private var key: List<Any?>? = null
    private var value: T? = null
    @Suppress("UNCHECKED_CAST")
    @Synchronized fun get(key: List<Any?>, compute: () -> T): T {
        if (this.key != key) { value = compute(); this.key = key }
        return value as T
    }
}
private val groupCountsMemo = Memo<Map<String, Int>>()
private val favoriteCountMemo = Memo<Int>()
private val recentChannelsMemo = Memo<List<Channel>>()
private val groupsMemo = Memo<List<String>>()
private val visibleChannelsMemo = Memo<List<Channel>>()
private val channelsByIdMemo = Memo<Map<String, Channel>>()

data class ProxyTestState(val running: Boolean = false, val success: Boolean = false, val message: String = "")

/** Guide focus that must survive leaving the screen but must not churn UiState. */
class GuideUiState {
    /** Time the programme cursor sits on; null follows the current time. */
    var cursorTime by mutableStateOf<Long?>(null)
    var firstRow = 0
}

data class UiState(
    val screen: AppScreen = AppScreen.GUIDE,
    val provider: ProviderConfig? = null,
    val channels: List<Channel> = emptyList(),
    val programs: List<Program> = emptyList(),
    val programIndex: ProgramIndex = ProgramIndex(programs),
    val selectedGroup: String = "Favorites",
    val groupOrder: List<String> = emptyList(),
    val focusedGroup: String? = null,
    val optionsContext: OptionsContext = OptionsContext.CHANNEL,
    val selectedChannelId: String? = null,
    val selectedChannelByGroup: Map<String, String> = emptyMap(),
    val playbackSourceScreen: AppScreen = AppScreen.GUIDE,
    val playingChannelId: String? = null,
    val playingUrl: String? = null,
    val favoritesOnly: Boolean = false,
    val sort: ChannelSort = ChannelSort.MANUAL,
    val loading: Boolean = false,
    val error: String? = null,
    val status: RefreshStatus = RefreshStatus(),
    val epgHours: Int = AppSettings.DEFAULT_EPG_HOURS,
    val epgAutoUpdate: Boolean = true,
    val updatePlaylistOnStart: Boolean = false,
    val query: String = "",
    val programSearchChannelIds: Set<String> = emptySet(),
    val programsLoadedFor: Set<String> = emptySet(),
    val multiviewIds: List<String> = emptyList(),
    val hasParentalPin: Boolean = false,
    val pendingPinChannelId: String? = null,
    val timelineStart: Long = System.currentTimeMillis() / 1_800_000L * 1_800_000L,
    val timelineHours: Int = 3,
    val selectedProgram: Program? = null,
    val recentChannelIds: List<String> = emptyList(),
    val importLog: List<String> = emptyList(),
    val importFinished: Boolean = false,
    val epgDiagnostics: EpgDiagnostics? = null,
    val diagnosticErrors: List<DiagnosticLogEntry> = emptyList(),
    val importReports: List<ImportReport> = emptyList(),
    val diagnosticsLoading: Boolean = false,
    val diagnosticsError: String = "",
    val proxy: ProxySettings = ProxySettings(),
    val proxyTest: ProxyTestState = ProxyTestState(),
    val overlayMenu: OverlayMenu = OverlayMenu.NONE
) {
    private val visibleGroupCounts: Map<String, Int> by lazy(LazyThreadSafetyMode.NONE) {
        groupCountsMemo.get(listOf(channels)) { channels.asSequence().filterNot { it.hidden }.groupingBy { it.displayGroup }.eachCount() }
    }
    private val visibleFavoriteCount: Int by lazy(LazyThreadSafetyMode.NONE) {
        favoriteCountMemo.get(listOf(channels)) { channels.count { it.favorite && !it.hidden } }
    }
    val channelsById: Map<String, Channel> by lazy(LazyThreadSafetyMode.NONE) {
        channelsByIdMemo.get(listOf(channels)) { channels.associateBy { it.id } }
    }
    val recentChannels: List<Channel> by lazy(LazyThreadSafetyMode.NONE) {
        recentChannelsMemo.get(listOf(channels, recentChannelIds)) {
            val available = channels.filterNot { it.hidden }.associateBy { it.id }
            recentChannelIds.distinct().mapNotNull(available::get).take(RecentChannels.limit)
        }
    }
    fun channelCountForGroup(group: String): Int = when (group) {
        "Favorites" -> visibleFavoriteCount
        "Recently watched" -> recentChannels.size
        else -> visibleGroupCounts[group] ?: 0
    }
    val groups: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        groupsMemo.get(listOf(channels, groupOrder)) {
            val discovered = channels.filterNot { it.hidden }.map { it.displayGroup }
                .filterNot { it in setOf("Favorites", "Recently watched", "All channels") }.distinct().sorted()
            listOf("Favorites", "Recently watched") + GroupOrdering.apply(discovered, groupOrder)
        }
    }
    val visibleChannels: List<Channel> by lazy(LazyThreadSafetyMode.NONE) {
        val searching = screen == AppScreen.SEARCH ||
            (screen == AppScreen.PLAYER && playbackSourceScreen == AppScreen.SEARCH)
        if (!searching && selectedGroup == "Recently watched") return@lazy recentChannels
        val normalizedQuery = if (searching) query.trim() else ""
        val key = listOf(channels, searching, selectedGroup, favoritesOnly, normalizedQuery, sort,
            if (normalizedQuery.isBlank()) null else programSearchChannelIds,
            if (normalizedQuery.isBlank()) null else programIndex)
        visibleChannelsMemo.get(key) {
            val filtered = channels.asSequence().filterNot { it.hidden }.filter {
                when {
                    searching -> true
                    favoritesOnly || selectedGroup == "Favorites" -> it.favorite
                    else -> it.displayGroup == selectedGroup
                }
            }.filter { channel ->
                normalizedQuery.isBlank() ||
                    channel.displayName.contains(normalizedQuery, ignoreCase = true) ||
                    listOf(channel.id, channel.tvgId).any { it.isNotBlank() && it in programSearchChannelIds } ||
                    programIndex.containsTitle(channel, normalizedQuery)
            }.toList()
            when (sort) {
                ChannelSort.MANUAL -> filtered.sortedWith(ChannelOrdering.manual)
                ChannelSort.ALPHABETICAL -> filtered.sortedWith(ChannelOrdering.alphabetical)
                ChannelSort.PROVIDER -> filtered.sortedWith(ChannelOrdering.provider)
            }
        }
    }
    fun programsFor(channel: Channel): List<Program> = programIndex.forChannel(channel)
    val playingChannel: Channel? get() = playingChannelId?.let(channelsById::get)
}

class MainViewModel(private val app: StreamGuideApp) : ViewModel() {
    val appUpdates = AppUpdateController(app, viewModelScope)
    private val store = app.container.store
    private val repository = app.container.repository
    private val http = app.container.http
    private var proxyTestJob: Job? = null
    private var searchJob: Job? = null
    private var playbackChannelIds: List<String> = emptyList()
    private var diagnosticsRequestId: Long = 0
    private var programLoadGeneration: Long = 0
    private var observedEpgRefresh: Long = 0
    private var playbackListKey: String? = null
    private var timelineHome: Long = 0
    private var loadedRangeStart: Long = Long.MAX_VALUE
    private var loadedRangeEnd: Long = Long.MIN_VALUE
    val guide = GuideUiState()
    var state by mutableStateOf(loadState())
        private set

    init {
        observedEpgRefresh = store.lastEpgRefresh()
        timelineHome = state.timelineStart
        refreshEpgDiagnostics()
        // The RESUMED lifecycle callback is the single startup refresh trigger.
    }

    private fun loadState(): UiState {
        val provider = store.getProvider(); val channels = store.getChannels()
        val initial = UiState(
            screen = if (provider == null) AppScreen.SETUP else AppScreen.GUIDE,
            provider = provider, channels = channels,
            groupOrder = store.groupOrder(),
            timelineHours = store.timelineHours(),
            epgHours = store.epgHours(), epgAutoUpdate = store.epgAutoUpdate(), updatePlaylistOnStart = store.updatePlaylistOnStart(),
            hasParentalPin = store.hasParentalPin(), recentChannelIds = store.recentChannelIds(), multiviewIds = store.multiviewChannelIds(),
            diagnosticErrors = store.diagnosticErrors(), importReports = store.importReports(), proxy = store.proxySettings(),
            status = RefreshStatus(false, store.lastRefresh(), store.lastError().ifBlank { if (store.lastRefresh() > 0) "Guide is up to date" else "Refresh required" }, channels.size, 0)
        )
        return initial.copy(selectedChannelId = initial.visibleChannels.firstOrNull()?.id)
    }

    fun navigate(screen: AppScreen) {
        if (state.screen == AppScreen.PROXY && screen != AppScreen.PROXY) { proxyTestJob?.cancel(); proxyTestJob = null }
        state = state.copy(screen = screen, query = if (screen == AppScreen.SEARCH) state.query else "", overlayMenu = NavigationPolicy.afterDestinationSelected(),
            proxyTest = if (screen == AppScreen.PROXY) ProxyTestState() else state.proxyTest)
        if (screen == AppScreen.SETTINGS) refreshEpgDiagnostics()
    }
    fun refreshEpgDiagnostics() {
        if (state.provider == null) return
        val channels = state.channels
        val requestId = ++diagnosticsRequestId
        state = state.copy(diagnosticsLoading = true, diagnosticsError = "")
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.epgDiagnostics(channels) } }
                .onSuccess { diagnostics ->
                    if (requestId != diagnosticsRequestId) return@onSuccess
                    state = state.copy(
                        epgDiagnostics = diagnostics,
                        diagnosticsLoading = false,
                        diagnosticsError = "",
                        diagnosticErrors = store.diagnosticErrors(), importReports = store.importReports(),
                        status = state.status.copy(programCount = diagnostics.totalPrograms)
                    )
                }
                .onFailure { error ->
                    if (requestId != diagnosticsRequestId) return@onFailure
                    store.appendDiagnosticError("Diagnostics", error.message ?: error.javaClass.simpleName)
                    state = state.copy(diagnosticsLoading = false, diagnosticsError = error.message ?: "Diagnostics unavailable", diagnosticErrors = store.diagnosticErrors())
                }
        }
    }
    fun recordDiagnosticError(area: String, message: String) {
        store.appendDiagnosticError(area, message)
        state = state.copy(diagnosticErrors = store.diagnosticErrors())
    }
    fun clearDiagnosticErrors() {
        store.clearDiagnosticErrors()
        state = state.copy(diagnosticErrors = emptyList())
    }
    fun toggleChannelMenu() {
        if (state.screen != AppScreen.GUIDE) return
        state = state.copy(overlayMenu = if (state.overlayMenu == OverlayMenu.CHANNEL) OverlayMenu.NONE else OverlayMenu.CHANNEL)
    }
    fun onRemoteOptionsPressed(): Boolean {
        val menu = NavigationPolicy.onOptionsPressed(state.screen)
        if (menu == OverlayMenu.NONE) return false
        toggleChannelMenu()
        return true
    }
    fun closeOverlayMenu() { state = state.copy(overlayMenu = OverlayMenu.NONE) }
    fun focusGroup(group: String) { state = state.copy(focusedGroup = group, optionsContext = OptionsContext.GROUP) }
    fun selectGroup(group: String) { state = GuideNavigation.selectGroup(state, group) }
    fun selectChannel(id: String) {
        if (state.selectedChannelId == id && state.optionsContext == OptionsContext.CHANNEL) return
        state = state.copy(selectedChannelId = id, optionsContext = OptionsContext.CHANNEL)
    }
    fun ensurePrograms(channelId: String) = ensurePrograms(listOf(channelId))
    /** Loads guide data for several rows with one query per batch instead of one per row. */
    fun ensurePrograms(channelIds: Collection<String>) {
        val missing = channelIds.asSequence().filter { it !in state.programsLoadedFor }.distinct().mapNotNull(state.channelsById::get).toList()
        if (missing.isEmpty()) return
        if (state.programsLoadedFor.size + missing.size > 600) resetLoadedPrograms()
        state = state.copy(programsLoadedFor = state.programsLoadedFor + missing.map { it.id })
        val generation = programLoadGeneration
        val timelineStart = state.timelineStart
        val timelineEnd = timelineStart + state.timelineHours * 3_600_000L
        val now = System.currentTimeMillis()
        val start = minOf(timelineStart - 3_600_000L, now - 2 * 3_600_000L)
        val end = maxOf(timelineEnd + 3 * 3_600_000L, now + 6 * 3_600_000L)
        loadedRangeStart = start; loadedRangeEnd = end
        missing.chunked(150).forEach { batch ->
            val keys = batch.flatMap { listOf(it.id, it.tvgId) }.filter(String::isNotBlank).toSet()
            viewModelScope.launch {
                val programs = withContext(Dispatchers.IO) { store.getPrograms(keys, start, end, 5_000 + 400 * batch.size) }
                if (generation != programLoadGeneration) return@launch
                val merged = state.programs.filterNot { it.channelId in keys } + programs
                state = state.copy(programs = merged, programIndex = state.programIndex.replacing(keys, programs))
            }
        }
    }
    private fun resetLoadedPrograms() {
        programLoadGeneration++
        state = state.copy(programs = emptyList(), programIndex = ProgramIndex(emptyList()), programsLoadedFor = emptySet())
    }
    /** Marks guide data stale but keeps showing it until the reload lands, so rows never flash empty. */
    private fun invalidatePrograms() {
        if (state.programs.size > 40_000) { resetLoadedPrograms(); return }
        programLoadGeneration++
        state = state.copy(programsLoadedFor = emptySet())
    }
    private fun resetAndReloadPrograms(requestedChannelIds: Set<String>) {
        resetLoadedPrograms()
        ensurePrograms(requestedChannelIds)
    }
    fun moveFocusedGroupToTop() = updateFocusedGroupOrder { current, group -> GroupOrdering.moveToTop(current, group) }
    fun moveFocusedGroup(delta: Int) = updateFocusedGroupOrder { current, group -> GroupOrdering.move(current, group, delta) }
    private fun updateFocusedGroupOrder(change: (List<String>, String) -> List<String>) {
        val group = state.focusedGroup ?: state.selectedGroup
        if (group in setOf("Favorites", "Recently watched")) return
        val providerGroups = state.groups.filterNot { it in setOf("Favorites", "Recently watched") }
        val order = change(providerGroups, group)
        store.saveGroupOrder(order)
        state = state.copy(groupOrder = order, overlayMenu = OverlayMenu.NONE)
    }
    private fun currentHalfHour(): Long = System.currentTimeMillis() / 1_800_000L * 1_800_000L
    private fun windowOutsideLoadedRange(): Boolean =
        state.timelineStart < loadedRangeStart || state.timelineStart + state.timelineHours * 3_600_000L > loadedRangeEnd
    /** Scrolls the guide through time; it never goes before the current half-hour or past one week. */
    fun shiftTimeline(deltaMs: Long) {
        val home = currentHalfHour()
        val target = (state.timelineStart + deltaMs).coerceIn(home, home + 7 * 24 * 3_600_000L)
        if (target == state.timelineStart) return
        state = state.copy(timelineStart = target)
        if (windowOutsideLoadedRange()) invalidatePrograms()
    }
    /** Follow the current half-hour unless the viewer has scrolled ahead, and pick up EPG refreshes. */
    fun refreshGuideForCurrentTime(onResume: Boolean = false) {
        val home = currentHalfHour()
        val refreshedAt = store.lastEpgRefresh()
        val epgChanged = refreshedAt != observedEpgRefresh
        if (state.timelineStart != home && (state.timelineStart <= timelineHome || state.timelineStart < home)) {
            state = state.copy(timelineStart = home)
        }
        // Rows are reloaded together so every one of them covers the same time range.
        if (epgChanged || home != timelineHome || (state.programsLoadedFor.isNotEmpty() && windowOutsideLoadedRange())) invalidatePrograms()
        timelineHome = home
        observedEpgRefresh = refreshedAt
        if (!onResume || state.loading) return
        when (RefreshPolicy.onAppStart(state.provider != null, state.updatePlaylistOnStart)) {
            StartupRefreshAction.FULL_PLAYLIST -> refreshInBackground(background = true)
            StartupRefreshAction.EPG_ONLY -> refreshEpgInBackground(background = true)
            StartupRefreshAction.NONE -> Unit
        }
    }

    fun jumpTimelineToNow() {
        guide.cursorTime = null
        val home = currentHalfHour()
        timelineHome = home
        if (state.timelineStart == home) return
        state = state.copy(timelineStart = home)
        if (windowOutsideLoadedRange()) invalidatePrograms()
    }
    fun setTimelineHours(hours: Int) {
        store.setTimelineHours(hours)
        state = state.copy(timelineHours = hours)
        invalidatePrograms()
    }
    fun play(id: String) {
        val channel = state.channelsById[id] ?: return
        if (state.screen != AppScreen.PLAYER) state = state.copy(playbackSourceScreen = state.screen)
        if (channel.locked && state.hasParentalPin) {
            state = state.copy(pendingPinChannelId = id)
        } else {
            startPlayback(channel, channel.url)
        }
    }
    private fun startPlayback(channel: Channel, url: String) {
        // Keep channel surfing stable while watching changes the recent order, but
        // follow the viewer when they pick a channel from another category.
        val listKey = "${state.selectedGroup}|${state.query}"
        if (state.screen != AppScreen.PLAYER || listKey != playbackListKey) {
            playbackChannelIds = state.visibleChannels.map { it.id }
            playbackListKey = listKey
        }
        val recent = RecentChannels.record(state.recentChannelIds, channel.id)
        store.saveRecentChannelIds(recent)
        state = state.copy(playingChannelId = channel.id, playingUrl = url, selectedChannelId = channel.id,
            pendingPinChannelId = null, recentChannelIds = recent,
            playbackSourceScreen = if (state.screen == AppScreen.PLAYER) state.playbackSourceScreen else state.screen,
            screen = AppScreen.PLAYER)
    }
    fun submitParentalPin(pin: String) {
        val id = state.pendingPinChannelId ?: return
        if (!store.verifyParentalPin(pin)) { state = state.copy(error = "Incorrect parental PIN"); return }
        val channel = state.channels.firstOrNull { it.id == id } ?: run { cancelParentalPin(); return }
        startPlayback(channel, channel.url)
    }
    fun cancelParentalPin() { state = state.copy(pendingPinChannelId = null) }
    fun setParentalPin(pin: String) {
        runCatching { store.setParentalPin(pin) }.onSuccess { state = state.copy(hasParentalPin = true) }.onFailure { state = state.copy(error = it.message) }
    }
    fun toggleLock(id: String) {
        if (!state.hasParentalPin) { state = state.copy(error = "Set a parental PIN in Settings first"); return }
        mutateChannels { list -> list.map { if (it.id == id) it.copy(locked = !it.locked) else it } }
    }
    fun playCatchup(channel: Channel, program: Program) {
        val url = CatchupUrl.forProgram(channel, program) ?: run { state = state.copy(error = "Catch-up is not available for this programme"); return }
        if (channel.locked && state.hasParentalPin) { state = state.copy(error = "Unlock the live channel before using catch-up"); return }
        startPlayback(channel, url)
    }
    fun playAdjacent(delta: Int) {
        val available = state.channels.filterNot { it.hidden }.map { it.id }.toHashSet()
        val list = playbackChannelIds.filter { it in available }
        if (list.isEmpty()) return
        val current = list.indexOf(state.playingChannelId).coerceAtLeast(0)
        play(list[(current + delta).coerceIn(0, list.lastIndex)])
    }
    /** Returns to the running channel in full screen without restarting it. */
    fun showPlayer(): Boolean {
        if (state.playingChannel == null || state.playingUrl == null) return false
        state = state.copy(screen = AppScreen.PLAYER, playbackSourceScreen = AppScreen.GUIDE)
        return true
    }
    fun closePlayer() { state = state.copy(screen = state.playbackSourceScreen) }
    fun playPreviousChannel() { state.recentChannelIds.getOrNull(1)?.let(::play) }
    fun addToMultiview(id: String) {
        if (state.channels.firstOrNull { it.id == id }?.locked == true) { state = state.copy(error = "Unlock this channel before adding it to Multiview"); return }
        val ids = (state.multiviewIds + id).distinct().take(4)
        store.saveMultiviewChannelIds(ids)
        state = state.copy(multiviewIds = ids, screen = AppScreen.MULTIVIEW)
    }
    fun removeFromMultiview(id: String) { val ids = state.multiviewIds - id; store.saveMultiviewChannelIds(ids); state = state.copy(multiviewIds = ids) }
    fun setQuery(value: String) {
        state = state.copy(query = value, programSearchChannelIds = emptySet())
        searchJob?.cancel()
        if (value.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(150)
            val ids = withContext(Dispatchers.IO) { store.searchProgramChannelIds(value) }
            if (state.query == value) state = state.copy(programSearchChannelIds = ids)
        }
    }
    fun setSort(sort: ChannelSort) { state = state.copy(sort = sort) }
    fun clearError() { state = state.copy(error = null) }

    fun saveProvider(provider: ProviderConfig) {
        store.saveProvider(provider)
        app.scheduleEpg()
        state = state.copy(provider = provider, screen = AppScreen.IMPORT_STATUS, loading = true, error = null, importLog = listOf("Setup saved"), importFinished = false)
        viewModelScope.launch {
            runCatching {
                repository.refreshAll { message -> withContext(Dispatchers.Main) { state = state.copy(importLog = state.importLog + message) } }
            }.onSuccess { status ->
                val channels = withContext(Dispatchers.IO) { store.getChannels() }
                state = state.copy(
                    loading = false,
                    channels = channels,
                    programs = emptyList(),
                    programIndex = ProgramIndex(emptyList()),
                    programsLoadedFor = emptySet(),
                    status = status,
                    selectedChannelId = channels.firstOrNull()?.id,
                    importLog = state.importLog + "Setup complete",
                    importFinished = true
                )
                refreshEpgDiagnostics()
            }.onFailure { error ->
                val message = error.message ?: "The provider could not be loaded"
                state = state.copy(loading = false, error = message, status = state.status.copy(running = false, message = message), importLog = state.importLog + "ERROR: $message", importFinished = false)
            }
        }
    }

    fun finishImport() { if (state.importFinished) state = state.copy(screen = AppScreen.GUIDE, error = null) }
    fun retryImport() { state.provider?.let(::saveProvider) }
    fun editProviderFromImport() { state = state.copy(screen = AppScreen.EDIT_PROVIDER, error = null) }

    fun refresh() = refreshInBackground(background = false)

    private fun refreshInBackground(background: Boolean) {
        if (state.loading || state.provider == null) return
        state = state.copy(loading = true, error = null, status = state.status.copy(running = true, message = "Updating playlist and EPG…"))
        viewModelScope.launch {
            runCatching { repository.refreshAll() }
                .onSuccess { status ->
                    observedEpgRefresh = store.lastEpgRefresh()
                    val requestedChannelIds = state.programsLoadedFor
                    val channels = withContext(Dispatchers.IO) { store.getChannels() }
                    state = state.copy(
                        loading = false,
                        channels = channels,
                        programs = emptyList(),
                        programIndex = ProgramIndex(emptyList()),
                        programsLoadedFor = emptySet(),
                        status = status,
                        selectedChannelId = state.selectedChannelId ?: channels.firstOrNull()?.id
                    )
                    resetAndReloadPrograms(requestedChannelIds)
                    refreshEpgDiagnostics()
                }
                .onFailure { error ->
                    val message = error.message ?: "Refresh failed"
                    state = state.copy(loading = false, error = if (background) null else message, diagnosticErrors = store.diagnosticErrors(), status = state.status.copy(running = false, message = message))
                    // A playlist outage must not prevent the mandatory foreground EPG attempt.
                    if (background) refreshEpgInBackground(background = true)
                }
        }
    }

    fun refreshEpgOnly() = refreshEpgInBackground(background = false)

    private fun refreshEpgInBackground(background: Boolean) {
        if (state.loading || state.provider == null) return
        state = state.copy(loading = true, error = null, status = state.status.copy(running = true, message = "Updating TV guide…"))
        viewModelScope.launch {
            runCatching { repository.refreshEpg(force = true) }
                .onSuccess { count ->
                    observedEpgRefresh = store.lastEpgRefresh()
                    val requestedChannelIds = state.programsLoadedFor
                    state = state.copy(
                        loading = false,
                        programs = emptyList(),
                        programIndex = ProgramIndex(emptyList()),
                        programsLoadedFor = emptySet(),
                        status = state.status.copy(running = false, lastSuccessEpochMs = store.lastEpgRefresh(), message = "Updated $count guide programmes", programCount = count)
                    )
                    resetAndReloadPrograms(requestedChannelIds)
                    refreshEpgDiagnostics()
                }
                .onFailure { error ->
                    val message = error.message ?: "TV guide update failed"
                    state = state.copy(loading = false, error = if (background) null else message, diagnosticErrors = store.diagnosticErrors(), status = state.status.copy(running = false, message = message))
                }
        }
    }

    fun toggleFavorite(id: String) = mutateChannels { list -> list.map { if (it.id == id) it.copy(favorite = !it.favorite) else it } }
    fun moveChannel(id: String, delta: Int) = mutateChannels { ChannelReconciler.move(it, id, delta) }
    fun moveChannelTo(id: String, oneBasedPosition: Int) = mutateChannels { ChannelReconciler.moveTo(it, id, oneBasedPosition) }
    fun toggleHidden(id: String) = mutateChannels { list -> list.map { if (it.id == id) it.copy(hidden = !it.hidden) else it } }
    fun customizeChannel(id: String, name: String, group: String) = mutateChannels { list -> list.map { if (it.id == id) it.copy(customName = name.trim(), customGroup = group.trim()) else it } }
    fun hideChannel(id: String) = mutateChannels { list -> list.map { if (it.id == id) it.copy(hidden = true) else it } }
    private fun mutateChannels(change: (List<Channel>) -> List<Channel>) {
        val changed = change(state.channels); state = state.copy(channels = changed); viewModelScope.launch(Dispatchers.IO) { store.saveChannels(changed) }
    }

    fun setEpgHours(hours: Int) { store.setEpgHours(hours); state = state.copy(epgHours = hours); app.scheduleEpg() }
    fun setEpgAutoUpdate(enabled: Boolean) { store.setEpgAutoUpdate(enabled); state = state.copy(epgAutoUpdate = enabled); app.scheduleEpg() }
    /** Returns why [settings] cannot be saved, or null once every client uses them. */
    fun saveProxy(settings: ProxySettings): String? {
        val cleaned = settings.copy(host = settings.host.trim(), username = settings.username.trim())
        ProxyValidation.problem(cleaned, http.socksSupported)?.let { return it }
        proxyTestJob?.cancel()
        store.saveProxySettings(cleaned)
        http.apply(cleaned)
        state = state.copy(proxy = cleaned, proxyTest = ProxyTestState())
        navigate(AppScreen.SETTINGS)
        return null
    }

    /** Asks [ProxyCheck.SERVICE] through the entered (not necessarily saved) proxy which address it sees. */
    fun testProxy(settings: ProxySettings) {
        val candidate = settings.copy(enabled = true, host = settings.host.trim(), username = settings.username.trim())
        ProxyValidation.problem(candidate, http.socksSupported)?.let { state = state.copy(proxyTest = ProxyTestState(message = it)); return }
        proxyTestJob?.cancel()
        state = state.copy(proxyTest = ProxyTestState(running = true, message = "Connecting through ${candidate.address}…"))
        proxyTestJob = viewModelScope.launch {
            val result = try {
                val seen = withContext(Dispatchers.IO) { ProxyCheck.publicAddress(http, candidate) }
                ProxyTestState(success = true, message = listOf("Public IP ${seen.ip}", seen.location, seen.network).filter { it.isNotBlank() }.joinToString(" · "))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = ProxyErrors.find(error)?.message
                    ?: "Test failed: ${DiagnosticMessageSanitizer.sanitize(error.message ?: error.javaClass.simpleName).take(160)}"
                store.appendDiagnosticError("Proxy test", message)
                ProxyTestState(message = message)
            }
            state = state.copy(proxyTest = result, diagnosticErrors = store.diagnosticErrors())
        }
    }

    fun setUpdatePlaylistOnStart(enabled: Boolean) { store.setUpdatePlaylistOnStart(enabled); state = state.copy(updatePlaylistOnStart = enabled) }
    fun clearProvider() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clearProvider() }
            state = UiState(screen = AppScreen.SETUP)
        }
    }
}

class MainViewModelFactory(private val app: StreamGuideApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
}
