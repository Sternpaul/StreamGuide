package com.sternpaul.streamguide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sternpaul.streamguide.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppScreen { GUIDE, SEARCH, SETTINGS, DIAGNOSTICS, ORGANIZE, EDIT_PROVIDER, IMPORT_STATUS, MULTIVIEW, PLAYER, SETUP }
enum class ChannelSort { MANUAL, ALPHABETICAL, PROVIDER }
enum class OverlayMenu { NONE, APP, CHANNEL }
enum class OptionsContext { CHANNEL, GROUP }

object NavigationPolicy {
    fun afterDestinationSelected(): OverlayMenu = OverlayMenu.NONE
    fun onOptionsPressed(screen: AppScreen): OverlayMenu = if (screen == AppScreen.GUIDE) OverlayMenu.CHANNEL else OverlayMenu.NONE
}

class ProgramIndex(programs: List<Program>) {
    private val byChannelId: Map<String, List<Program>> = programs.groupBy { it.channelId }

    private fun channelIds(channel: Channel): List<String> = listOf(channel.id, channel.tvgId).filter { it.isNotBlank() }.distinct()

    fun forChannel(channel: Channel): List<Program> {
        return channelIds(channel).asSequence()
            .flatMap { byChannelId[it].orEmpty().asSequence() }
            .distinct()
            .sortedBy { it.startEpochMs }
            .toList()
    }

    fun containsTitle(channel: Channel, query: String): Boolean = channelIds(channel).any { id ->
        byChannelId[id].orEmpty().any { it.title.contains(query, ignoreCase = true) }
    }
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
    val diagnosticsLoading: Boolean = false,
    val diagnosticsError: String = "",
    val overlayMenu: OverlayMenu = OverlayMenu.NONE
) {
    private val visibleGroupCounts: Map<String, Int> by lazy(LazyThreadSafetyMode.NONE) {
        channels.asSequence().filterNot { it.hidden }.groupingBy { it.displayGroup }.eachCount()
    }
    private val visibleFavoriteCount: Int by lazy(LazyThreadSafetyMode.NONE) {
        channels.count { it.favorite && !it.hidden }
    }
    val recentChannels: List<Channel> by lazy(LazyThreadSafetyMode.NONE) {
        val available = channels.filterNot { it.hidden }.associateBy { it.id }
        recentChannelIds.distinct().mapNotNull(available::get).take(RecentChannels.limit)
    }
    fun channelCountForGroup(group: String): Int = when (group) {
        "Favorites" -> visibleFavoriteCount
        "Recently watched" -> recentChannels.size
        else -> visibleGroupCounts[group] ?: 0
    }
    val groups: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        val discovered = channels.filterNot { it.hidden }.map { it.displayGroup }
            .filterNot { it in setOf("Favorites", "Recently watched", "All channels") }.distinct().sorted()
        listOf("Favorites", "Recently watched") + GroupOrdering.apply(discovered, groupOrder)
    }
    val visibleChannels: List<Channel> by lazy(LazyThreadSafetyMode.NONE) {
        val searching = screen == AppScreen.SEARCH ||
            (screen == AppScreen.PLAYER && playbackSourceScreen == AppScreen.SEARCH)
        if (!searching && selectedGroup == "Recently watched") return@lazy recentChannels
        val normalizedQuery = if (searching) query.trim() else ""
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
    fun programsFor(channel: Channel): List<Program> = programIndex.forChannel(channel)
    val playingChannel: Channel? get() = channels.firstOrNull { it.id == playingChannelId }
}

class MainViewModel(private val app: StreamGuideApp) : ViewModel() {
    val appUpdates = AppUpdateController(app, viewModelScope)
    private val store = app.container.store
    private val repository = app.container.repository
    private var searchJob: Job? = null
    private var playbackChannelIds: List<String> = emptyList()
    private var diagnosticsRequestId: Long = 0
    private var programLoadGeneration: Long = 0
    private var observedEpgRefresh: Long = 0
    var state by mutableStateOf(loadState())
        private set

    init {
        observedEpgRefresh = store.lastEpgRefresh()
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
            diagnosticErrors = store.diagnosticErrors(),
            status = RefreshStatus(false, store.lastRefresh(), store.lastError().ifBlank { if (store.lastRefresh() > 0) "Guide is up to date" else "Refresh required" }, channels.size, 0)
        )
        return initial.copy(selectedChannelId = initial.visibleChannels.firstOrNull()?.id)
    }

    fun navigate(screen: AppScreen) {
        state = state.copy(screen = screen, query = if (screen == AppScreen.SEARCH) state.query else "", overlayMenu = NavigationPolicy.afterDestinationSelected())
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
                        diagnosticErrors = store.diagnosticErrors(),
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
    fun toggleAppMenu() { state = state.copy(overlayMenu = if (state.overlayMenu == OverlayMenu.APP) OverlayMenu.NONE else OverlayMenu.APP) }
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
    fun selectChannel(id: String) { state = state.copy(selectedChannelId = id, optionsContext = OptionsContext.CHANNEL); ensurePrograms(id) }
    fun selectProgram(channelId: String, program: Program?) { state = state.copy(selectedChannelId = channelId, selectedProgram = program, optionsContext = OptionsContext.CHANNEL); ensurePrograms(channelId) }
    fun ensurePrograms(channelId: String) {
        if (channelId in state.programsLoadedFor) return
        val channel = state.channels.firstOrNull { it.id == channelId } ?: return
        if (state.programsLoadedFor.size >= 300) {
            state = state.copy(programs = emptyList(), programIndex = ProgramIndex(emptyList()), programsLoadedFor = emptySet())
        }
        state = state.copy(programsLoadedFor = state.programsLoadedFor + channelId)
        val generation = programLoadGeneration
        val timelineStart = state.timelineStart
        val timelineEnd = timelineStart + state.timelineHours * 3_600_000L
        val now = System.currentTimeMillis()
        val start = minOf(timelineStart - 3_600_000L, now - 2 * 3_600_000L)
        val end = maxOf(timelineEnd + 3_600_000L, now + 6 * 3_600_000L)
        viewModelScope.launch {
            val programs = withContext(Dispatchers.IO) { store.getPrograms(listOf(channel.id, channel.tvgId), start, end, 2_000) }
            if (generation != programLoadGeneration || channelId !in state.programsLoadedFor) return@launch
            val ids = setOf(channel.id, channel.tvgId).filter(String::isNotBlank).toSet()
            val merged = state.programs.filterNot { it.channelId in ids } + programs
            state = state.copy(programs = merged, programIndex = ProgramIndex(merged))
        }
    }
    private fun resetLoadedPrograms() {
        programLoadGeneration++
        state = state.copy(programs = emptyList(), programIndex = ProgramIndex(emptyList()), programsLoadedFor = emptySet())
    }
    private fun resetAndReloadPrograms(requestedChannelIds: Set<String>) {
        resetLoadedPrograms()
        requestedChannelIds.forEach(::ensurePrograms)
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
    fun shiftTimeline(hours: Int) {
        state = state.copy(timelineStart = state.timelineStart + hours * 3_600_000L, selectedProgram = null)
        resetLoadedPrograms()
    }
    /** Refresh the guide window when the current half-hour changes. */
    fun refreshGuideForCurrentTime(onResume: Boolean = false) {
        val now = System.currentTimeMillis() / 1_800_000L * 1_800_000L
        val refreshedAt = store.lastEpgRefresh()
        val epgChanged = refreshedAt != observedEpgRefresh
        if (state.timelineStart != now || epgChanged) {
            val requestedChannelIds = state.programsLoadedFor
            state = state.copy(timelineStart = now, selectedProgram = null)
            resetAndReloadPrograms(requestedChannelIds)
        }
        observedEpgRefresh = refreshedAt
        if (!onResume || state.loading) return
        when (RefreshPolicy.onAppStart(state.provider != null, state.updatePlaylistOnStart)) {
            StartupRefreshAction.FULL_PLAYLIST -> refreshInBackground(background = true)
            StartupRefreshAction.EPG_ONLY -> refreshEpgInBackground(background = true)
            StartupRefreshAction.NONE -> Unit
        }
    }

    fun jumpTimelineToNow() {
        state = state.copy(timelineStart = System.currentTimeMillis() / 1_800_000L * 1_800_000L, selectedProgram = null)
        resetLoadedPrograms()
    }
    fun setTimelineHours(hours: Int) {
        store.setTimelineHours(hours)
        state = state.copy(timelineHours = hours)
        resetLoadedPrograms()
    }
    fun play(id: String) {
        val channel = state.channels.firstOrNull { it.id == id } ?: return
        if (state.screen != AppScreen.PLAYER) state = state.copy(playbackSourceScreen = state.screen)
        if (channel.locked && state.hasParentalPin) {
            state = state.copy(pendingPinChannelId = id)
        } else {
            startPlayback(channel, channel.url)
        }
    }
    private fun startPlayback(channel: Channel, url: String) {
        if (state.screen != AppScreen.PLAYER) {
            // Keep channel surfing stable while watching changes the recent order.
            playbackChannelIds = state.visibleChannels.map { it.id }
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
