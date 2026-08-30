package com.sternpaul.streamguide.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sternpaul.streamguide.core.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class AppStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("streamguide", Context.MODE_PRIVATE)
    private val masterKey by lazy { MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build() }
    private val secure by lazy {
        EncryptedSharedPreferences.create(
            context, "streamguide_secure", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    private val channelsFile = File(context.filesDir, "channels.json")
    private val programsFile = File(context.filesDir, "programs.json")

    fun getProvider(): ProviderConfig? = secure.getString("provider", null)?.let(::providerFromJson)
    fun saveProvider(provider: ProviderConfig) { secure.edit().putString("provider", providerToJson(provider).toString()).apply() }
    fun clearProvider() { secure.edit().remove("provider").apply(); channelsFile.delete(); programsFile.delete() }

    fun getChannels(): List<Channel> = readArray(channelsFile).mapNotNull(::channelFromJson)
    fun saveChannels(channels: List<Channel>) = writeArray(channelsFile, channels.map(::channelToJson))
    fun getPrograms(): List<Program> = readArray(programsFile).mapNotNull(::programFromJson)
    fun savePrograms(programs: List<Program>) = writeArray(programsFile, programs.map(::programToJson))

    fun epgHours(): Int = prefs.getInt("epg_hours", AppSettings.DEFAULT_EPG_HOURS).takeIf { it in AppSettings.allowedEpgHours } ?: AppSettings.DEFAULT_EPG_HOURS
    fun setEpgHours(hours: Int) { require(hours in AppSettings.allowedEpgHours); prefs.edit().putInt("epg_hours", hours).apply() }
    fun lastRefresh(): Long = prefs.getLong("last_refresh", 0L)
    fun setLastRefresh(value: Long) { prefs.edit().putLong("last_refresh", value).apply() }
    fun lastError(): String = prefs.getString("last_error", "").orEmpty()
    fun setLastError(value: String) { prefs.edit().putString("last_error", value).apply() }

    private fun writeArray(file: File, values: List<JSONObject>) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(JSONArray(values).toString())
        if (!temp.renameTo(file)) { file.writeText(temp.readText()); temp.delete() }
    }
    private fun readArray(file: File): List<JSONObject> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { array.getJSONObject(it) }
    }.getOrElse { emptyList() }

    private fun providerToJson(p: ProviderConfig) = JSONObject().put("type", p.type.name).put("name", p.name).put("playlistUrl", p.playlistUrl).put("serverUrl", p.serverUrl).put("username", p.username).put("password", p.password).put("epgUrl", p.epgUrl)
    private fun providerFromJson(raw: String): ProviderConfig? = runCatching { JSONObject(raw).let { ProviderConfig(ProviderType.valueOf(it.getString("type")), it.optString("name", "Playlist"), it.optString("playlistUrl"), it.optString("serverUrl"), it.optString("username"), it.optString("password"), it.optString("epgUrl")) } }.getOrNull()
    private fun channelToJson(c: Channel) = JSONObject().put("id",c.id).put("name",c.name).put("url",c.url).put("group",c.group).put("tvgId",c.tvgId).put("logo",c.logoUrl).put("providerOrder",c.providerOrder).put("manualRank",c.manualRank ?: JSONObject.NULL).put("favorite",c.favorite).put("hidden",c.hidden)
    private fun channelFromJson(o: JSONObject): Channel? = runCatching { Channel(o.getString("id"),o.getString("name"),o.getString("url"),o.optString("group","Other"),o.optString("tvgId"),o.optString("logo"),o.optInt("providerOrder"),if(o.isNull("manualRank")) null else o.getLong("manualRank"),o.optBoolean("favorite"),o.optBoolean("hidden")) }.getOrNull()
    private fun programToJson(p: Program) = JSONObject().put("channelId",p.channelId).put("title",p.title).put("description",p.description).put("start",p.startEpochMs).put("end",p.endEpochMs)
    private fun programFromJson(o: JSONObject): Program? = runCatching { Program(o.getString("channelId"),o.getString("title"),o.optString("description"),o.getLong("start"),o.getLong("end")) }.getOrNull()
}
