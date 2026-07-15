package com.copilot.remote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "copilot_remote_settings")

data class AppSettings(
    val wsUrl: String = "",
    val key: String = "",
    val selectedModelId: String = "",
    val selectedReasoningEffort: String = "",
    val selectedParticipant: String = "",
    val permissionLevel: String = "default",
    val enabledTools: Set<String>? = null,
    val activeProfileId: String = "",
    val serverProfiles: List<ServerProfile> = emptyList(),
)

class SettingsStore(private val context: Context) {

    private val WS_URL_KEY = stringPreferencesKey("ws_url")
    private val AUTH_KEY = stringPreferencesKey("auth_key")
    private val MODEL_ID_KEY = stringPreferencesKey("selected_model_id")
    private val REASONING_EFFORT_KEY = stringPreferencesKey("selected_reasoning_effort")
    private val PARTICIPANT_KEY = stringPreferencesKey("selected_participant")
    private val PERMISSION_LEVEL_KEY = stringPreferencesKey("permission_level")
    private val ENABLED_TOOLS_KEY = stringSetPreferencesKey("enabled_tools")
    private val ACTIVE_PROFILE_KEY = stringPreferencesKey("active_profile_id")
    private val PROFILES_KEY = stringPreferencesKey("server_profiles_json")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            wsUrl = prefs[WS_URL_KEY] ?: "",
            key = prefs[AUTH_KEY] ?: "",
            selectedModelId = prefs[MODEL_ID_KEY] ?: "",
            selectedReasoningEffort = prefs[REASONING_EFFORT_KEY] ?: "",
            selectedParticipant = prefs[PARTICIPANT_KEY] ?: "",
            permissionLevel = prefs[PERMISSION_LEVEL_KEY] ?: "default",
            enabledTools = prefs[ENABLED_TOOLS_KEY],
            activeProfileId = prefs[ACTIVE_PROFILE_KEY] ?: "",
            serverProfiles = parseProfiles(prefs[PROFILES_KEY] ?: "[]"),
        )
    }

    suspend fun updateWsUrl(url: String) {
        context.dataStore.edit { it[WS_URL_KEY] = url }
    }

    suspend fun updateKey(key: String) {
        context.dataStore.edit { it[AUTH_KEY] = key }
    }

    suspend fun updateSelectedModel(modelId: String) {
        context.dataStore.edit { it[MODEL_ID_KEY] = modelId }
    }

    suspend fun updateSelectedReasoningEffort(effort: String) {
        context.dataStore.edit { it[REASONING_EFFORT_KEY] = effort }
    }

    suspend fun updateSelectedParticipant(participant: String) {
        context.dataStore.edit { it[PARTICIPANT_KEY] = participant }
    }

    suspend fun updatePermissionLevel(level: String) {
        context.dataStore.edit { it[PERMISSION_LEVEL_KEY] = level }
    }

    suspend fun updateEnabledTools(tools: Set<String>?) {
        context.dataStore.edit { prefs -> if (tools == null) prefs.remove(ENABLED_TOOLS_KEY) else prefs[ENABLED_TOOLS_KEY] = tools }
    }

    suspend fun setActiveProfile(profileId: String) {
        context.dataStore.edit { it[ACTIVE_PROFILE_KEY] = profileId }
    }

    suspend fun saveProfile(profile: ServerProfile) {
        context.dataStore.edit { prefs ->
            val current = parseProfiles(prefs[PROFILES_KEY] ?: "[]").toMutableList()
            val idx = current.indexOfFirst { it.id == profile.id }
            if (idx >= 0) {
                current[idx] = profile
            } else {
                current.add(profile)
            }
            prefs[PROFILES_KEY] = serializeProfiles(current)
        }
    }

    suspend fun deleteProfile(profileId: String) {
        context.dataStore.edit { prefs ->
            val current = parseProfiles(prefs[PROFILES_KEY] ?: "[]").toMutableList()
            current.removeAll { it.id == profileId }
            prefs[PROFILES_KEY] = serializeProfiles(current)
        }
    }

    private fun parseProfiles(json: String): List<ServerProfile> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                ServerProfile(
                    id = p.optString("id"),
                    label = p.optString("label"),
                    wsUrl = p.optString("wsUrl"),
                    key = p.optString("key"),
                    lastConnected = p.optLong("lastConnected"),
                    lastInstanceId = p.optString("lastInstanceId", ""),
                    lastWorkspaceName = p.optString("lastWorkspaceName", ""),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serializeProfiles(profiles: List<ServerProfile>): String {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("label", p.label)
                put("wsUrl", p.wsUrl)
                put("key", p.key)
                put("lastConnected", p.lastConnected)
                put("lastInstanceId", p.lastInstanceId)
                put("lastWorkspaceName", p.lastWorkspaceName)
            })
        }
        return arr.toString()
    }
}
