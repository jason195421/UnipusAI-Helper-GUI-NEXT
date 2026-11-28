package io.github.unipus.helper.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.unipus.helper.MainActivity
import org.json.JSONObject

/**
 * Snapshot state shared between the Java controller and the Compose UI.
 *
 * The controller keeps its own mutable fields and publishes them with [sync]; the UI never
 * mutates them. Every JSON value is copied before it is published so that Compose sees a new
 * instance and recomposes the affected pages.
 */
class UiBridge {
    var page by mutableStateOf("browser")
        private set
    var filter by mutableStateOf("all")
        private set
    var subtitle by mutableStateOf("正在启动手机工作台…")
        private set
    var snapshot by mutableStateOf(JSONObject())
        private set
    var config by mutableStateOf(JSONObject())
        private set
    var dark by mutableStateOf(false)
        private set
    var audioMessage by mutableStateOf("语音依赖尚未检查")
        private set
    var speech by mutableStateOf(JSONObject())
        private set
    var media by mutableStateOf(JSONObject())
        private set
    var busy by mutableStateOf(false)
        private set
    var audioBusy by mutableStateOf(false)
        private set
    var importProgress by mutableStateOf(-1f)
        private set

    /** Non-null while the controller wants the window picker to be shown. */
    var windowHandles by mutableStateOf<List<String>?>(null)

    /** Non-null while the controller wants the offline voice picker to be shown. */
    var voiceChoices by mutableStateOf<List<Pair<String, String>>?>(null)

    lateinit var activity: MainActivity
        private set

    fun bind(activity: MainActivity) {
        this.activity = activity
    }

    fun postSubtitle(text: String) {
        if (text != subtitle) subtitle = text
    }

    fun showWindows(handles: List<String>) {
        windowHandles = handles
    }

    fun showVoices(labels: List<String>, names: List<String>) {
        voiceChoices = labels.zip(names)
    }

    fun sync(activity: MainActivity) {
        page = activity.pageName
        filter = activity.filterName
        dark = activity.darkMode
        busy = activity.pageBusy
        audioBusy = activity.audioBusy
        audioMessage = activity.audioStatusText
        importProgress = activity.modelImportProgress
        snapshot = copy(activity.snapshotJson)
        config = copy(activity.draftConfigJson)
        speech = copy(activity.speechStateJson)
        media = copy(activity.mediaStateJson)
    }

    private fun copy(source: JSONObject): JSONObject = try {
        JSONObject(source.toString())
    } catch (error: Exception) {
        JSONObject()
    }
}
