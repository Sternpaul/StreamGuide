package com.sternpaul.streamguide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sternpaul.streamguide.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppScreen { GUIDE, SEARCH, SETTINGS, ORGANIZE, EDIT_PROVIDER, IMPORT_STATUS, MULTIVIEW, PLAYER, SETUP }
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
    val selectedGroup: String = "All channels",
    val groupOrder: List<String> = emptyList(),
    val focusedGroup: String? = null,
    val optionsContext: OptionsContext = OptionsContext.CHANNEL,
    val selectedChannelId: String? = null,
    val playingChannelId: String? = null,
    val playingUrl: String? = null,
    val favoritesOnly: Boolean = false,
    val sort: ChannelSort = ChannelSort.MANUAL,
    val loading: Boolean = false,
    val error: String? = null,
    val status: RefreshStatus = RefreshStatus(),
    val epgHours: Int = AppSettings.DEFAULT_EPG_HOURS,
    val epgAutoUpdate: Boolean = true,
    val updateEpgOnStart: Boolean = true,
    val updatePlaylistOnStart: Boolean = false,
    val query: String = "",
    val multiviewIds: List<String> = emptyList(),
    val hasParentalPin: Boolean = false,
    val pendingPinChannelId: String? = null,
    val timelineStart: Long = System.currentTimeMillis() / 1_800_000L * 1_800_000L,
    val timelineHours: Int = 3,
    val selectedProgram: Program? = null,
    val recentChannelIds: List<String> = emptyList(),
    val importLog: List<String> = emptyList(),
    val importFinished: Boolean = false,
    val overlayMenu: OverlayMenu = OverlayMenu.NONE
) {
    private val visibleGroupCounts: Map<String, Int> by lazy(LazyThreadSafetyMode.NONE) {
        channels.asSequence().filterNot { it.hidden }.groupingBy { it.displayGroup }.eachCount()
    }
    private val visibleFavoriteCount: Int by lazy(LazyThreadSafetyMode.NONE) {
        channels.count { it.favorite && !it.hidden }
    }
    private val visibleChannelCount: Int by lazy(LazyThreadSafetyMode.NONE) {
        channels.count { !it.hidden }
    }
    fun channelCountForGroup(group: String): Int = when (group) {
        "All channels" -> visibleChannelCount
        "Favorites" -> visibleFavoriteCount
        else -> visibleGroupCounts[group] ?: 0
    }
    val groups: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        val discovered = channels.filterNot { it.hidden }.map { it.displayGroup }.distinct().sorted()
        listOf("All channels", "Favorites") + GroupOrdering.apply(discovered, groupOrder)
    }
    val visibleChannels: List<Channel> by lazy(LazyThreadSafetyMode.NONE) {
        val normalizedQuery = query.trim()
        val filtered = channels.asSequence().filterNot { it.hidden }.filter {
            when {
                favoritesOnly || selectedGroup == "Favorites" -> it.favorite
                selectedGroup != "All channels" -> it.displayGroup == selectedGroup
                else -> true
            }
        }.filter { channel ->
            normalizedQuery.isBlank() ||
                channel.displayName.contains(normalizedQuery, ignoreCase = true) ||
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
    private val store = app.container.store
    private val repository = app.container.repository
    var state by mutableStateOf(loadState())
        private set

    init {
        val action = RefreshPolicy.onAppStart(
            hasProvider = state.provider != null,
            playlistOnStart = state.updatePlaylistOnStart,
            epgOnStart = state.updateEpgOnStart,
            epgIsStale = store.lastRefresh() == 0L || System.currentTimeMillis() - store.lastRefresh() > state.epgHours * 3_600_000L
        )
        when (action) {
            StartupRefreshAction.FULL_PLAYLIST -> refresh()
            StartupRefreshAction.EPG_ONLY -> refreshEpgOnly()
            StartupRefreshAction.NONE -> Unit
        }
    }

    private fun loadState(): UiState {
        val provider = store.getProvider(); val channels = store.getChannels(); val programs = store.getPrograms()
        return UiState(
            screen = if (provider == null) AppScreen.SETUP else AppScreen.GUIDE,
            provider = provider, channels = channels, programs = programs,
            selectedChannelId = channels.firstOrNull()?.id,
            groupOrder = store.groupOrder(),
            timelineHours = store.timelineHours(),
            epgHours = store.epgHours(), epgAutoUpdate = store.epgAutoUpdate(), updateEpgOnStart = store.updateEpgOnStart(), updatePlaylistOnStart = store.updatePlaylistOnStart(),
            hasParentalPin = store.hasParentalPin(), recentChannelIds = store.recentChannelIds(), multiviewIds = store.multiviewChannelIds(),
            status = RefreshStatus(false, store.lastRefresh(), store.lastError().ifBlank { if (store.lastRefresh() > 0) "Guide is up to date" else "Refresh required" }, channels.size, programs.size)
        )
    }

    fun navigate(screen: AppScreen) { state = state.copy(screen = screen, query = if (screen == AppScreen.SEARCH) state.query else "", overlayMenu = NavigationPolicy.afterDestinationSelected()) }
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
    fun selectGroup(group: String) { state = state.copy(selectedGroup = group, focusedGroup = group, optionsContext = OptionsContext.GROUP, favoritesOnly = group == "Favorites", selectedChannelId = state.channels.firstOrNull { group == "All channels" || (group == "Favorites" && it.favorite) || it.displayGroup == group }?.id) }
    fun selectChannel(id: String) { state = state.copy(selectedChannelId = id, optionsContext = OptionsContext.CHANNEL) }
    fun selectProgram(channelId: String, program: Program?) { state = state.copy(selectedChannelId = channelId, selectedProgram = program, optionsContext = OptionsContext.CHANNEL) }
    fun moveFocusedGroupToTop() = updateFocusedGroupOrder { current, group -> GroupOrdering.moveToTop(current, group) }
    fun moveFocusedGroup(delta: Int) = updateFocusedGroupOrder { current, group -> GroupOrdering.move(current, group, delta) }
    private fun updateFocusedGroupOrder(change: (List<String>, String) -> List<String>) {
        val group = state.focusedGroup ?: state.selectedGroup
        if (group == "All channels" || group == "Favorites") return
        val providerGroups = state.groups.filterNot { it == "All channels" || it == "Favorites" }
        val order = change(providerGroups, group)
        store.saveGroupOrder(order)
        state = state.copy(groupOrder = order, overlayMenu = OverlayMenu.NONE)
    }
    fun shiftTimeline(hours: Int) { state = state.copy(timelineStart = state.timelineStart + hours * 3_600_000L, selectedProgram = null) }
    fun jumpTimelineToNow() { state = state.copy(timelineStart = System.currentTimeMillis() / 1_800_000L * 1_800_000L, selectedProgram = null) }
    fun setTimelineHours(hours: Int) { store.setTimelineHours(hours); state = state.copy(timelineHours = hours) }
    fun play(id: String) {
        val channel = state.channels.firstOrNull { it.id == id } ?: return
        if (channel.locked && state.hasParentalPin) {
            state = state.copy(pendingPinChannelId = id)
        } else {
            val recent = (listOf(id) + state.recentChannelIds.filter { it != id }).take(30)
            store.saveRecentChannelIds(recent)
            state = state.copy(playingChannelId = id, playingUrl = channel.url, selectedChannelId = id, screen = AppScreen.PLAYER, recentChannelIds = recent)
        }
    }
    fun submitParentalPin(pin: String) {
        val id = state.pendingPinChannelId ?: return
        state = if (store.verifyParentalPin(pin)) state.copy(pendingPinChannelId = null, playingChannelId = id, playingUrl = state.channels.firstOrNull { it.id == id }?.url, selectedChannelId = id, screen = AppScreen.PLAYER) else state.copy(error = "Incorrect parental PIN")
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
        state = state.copy(playingChannelId = channel.id, playingUrl = url, selectedChannelId = channel.id, screen = AppScreen.PLAYER)
    }
    fun playAdjacent(delta: Int) {
        val list = state.visibleChannels
        if (list.isEmpty()) return
        val current = list.indexOfFirst { it.id == state.playingChannelId }.let { if (it < 0) 0 else it }
        play(list[(current + delta).coerceIn(0, list.lastIndex)].id)
    }
    fun closePlayer() { state = state.copy(screen = AppScreen.GUIDE) }
    fun playPreviousChannel() { state.recentChannelIds.getOrNull(1)?.let(::play) }
    fun addToMultiview(id: String) {
        if (state.channels.firstOrNull { it.id == id }?.locked == true) { state = state.copy(error = "Unlock this channel before adding it to Multiview"); return }
        val ids = (state.multiviewIds + id).distinct().take(4)
        store.saveMultiviewChannelIds(ids)
        state = state.copy(multiviewIds = ids, screen = AppScreen.MULTIVIEW)
    }
    fun removeFromMultiview(id: String) { val ids = state.multiviewIds - id; store.saveMultiviewChannelIds(ids); state = state.copy(multiviewIds = ids) }
    fun setQuery(value: String) { state = state.copy(query = value) }
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
                val channels = store.getChannels(); val programs = store.getPrograms()
                state = state.copy(loading = false, channels = channels, programs = programs, programIndex = ProgramIndex(programs), status = status, selectedChannelId = channels.firstOrNull()?.id, importLog = state.importLog + "Setup complete", importFinished = true)
            }.onFailure { error ->
                val message = error.message ?: "The provider could not be loaded"
                state = state.copy(loading = false, error = message, status = state.status.copy(running = false, message = message), importLog = state.importLog + "ERROR: $message", importFinished = false)
            }
        }
    }

    fun finishImport() { if (state.importFinished) state = state.copy(screen = AppScreen.GUIDE, error = null) }
    fun retryImport() { state.provider?.let(::saveProvider) }
    fun editProviderFromImport() { state = state.copy(screen = AppScreen.EDIT_PROVIDER, error = null) }

    fun refresh() {
        if (state.loading || state.provider == null) return
        state = state.copy(loading = true, error = null, status = state.status.copy(running = true, message = "Updating playlist and EPG…"))
        viewModelScope.launch {
            runCatching { repository.refreshAll() }
                .onSuccess { status ->
                    val channels = store.getChannels()
                    val programs = store.getPrograms()
                    state = state.copy(loading = false, channels = channels, programs = programs, programIndex = ProgramIndex(programs), status = status, selectedChannelId = state.selectedChannelId ?: channels.firstOrNull()?.id)
                }
                .onFailure { error -> state = state.copy(loading = false, error = error.message ?: "Refresh failed", status = state.status.copy(running = false, message = error.message ?: "Refresh failed")) }
        }
    }

    fun refreshEpgOnly() {
        if (state.loading || state.provider == null) return
        state = state.copy(loading = true, error = null, status = state.status.copy(running = true, message = "Updating TV guide…"))
        viewModelScope.launch {
            runCatching { repository.refreshEpg() }
                .onSuccess { count ->
                    val programs = store.getPrograms()
                    state = state.copy(
                        loading = false,
                        programs = programs,
                        programIndex = ProgramIndex(programs),
                        status = state.status.copy(running = false, lastSuccessEpochMs = store.lastRefresh(), message = "Updated $count guide programmes", programCount = count)
                    )
                }
                .onFailure { error ->
                    val message = error.message ?: "TV guide update failed"
                    state = state.copy(loading = false, error = message, status = state.status.copy(running = false, message = message))
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
    fun setUpdateEpgOnStart(enabled: Boolean) { store.setUpdateEpgOnStart(enabled); state = state.copy(updateEpgOnStart = enabled) }
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
