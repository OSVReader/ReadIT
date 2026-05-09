@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.example.booklibrary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.booklibrary.tts.TtsModelManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Language
import java.util.Locale

class TtsSettingsSheet : BottomSheetDialogFragment() {

    interface Host {
        fun getTtsSpeed(): Double
        fun getTtsPitch(): Double
        fun getTtsEngine(): String
        fun getTtsVoices(): Set<AndroidTtsEngine.Voice>
        fun getTtsSelectedVoiceId(): AndroidTtsEngine.Voice.Id?
        fun getTtsLanguage(): Language?
        fun isPiperModelReady(): Boolean
        fun getSelectedPiperModelId(): String
        fun getSelectedKokoroSpeakerId(): Int
        fun getSelectedKokoroModelId(): String
        fun isKokoroPreloadEnabled(): Boolean
        fun onTtsSpeedChanged(speed: Double)
        fun onTtsPitchChanged(pitch: Double)
        fun onTtsVoiceSelected(voice: AndroidTtsEngine.Voice)
        fun onTtsEngineSelected(engine: String)
        fun onPiperModelSelected(modelId: String)
        fun onKokoroSpeakerSelected(speakerId: Int)
        fun onKokoroModelSelected(modelId: String)
        fun onKokoroPreloadEnabledChanged(enabled: Boolean)
    }

    companion object {
        fun newInstance(): TtsSettingsSheet = TtsSettingsSheet()
    }

    // Callbacks -- set by caller for initial creation, Host fallback on recreation
    var onSpeedChanged: ((Double) -> Unit)? = null
    var onPitchChanged: ((Double) -> Unit)? = null
    var onVoiceSelected: ((AndroidTtsEngine.Voice) -> Unit)? = null
    var onEngineSelected: ((String) -> Unit)? = null
    var onPiperModelSelected: ((String) -> Unit)? = null
    var onKokoroSpeakerSelected: ((Int) -> Unit)? = null
    var onKokoroModelSelected: ((String) -> Unit)? = null
    var onKokoroPreloadEnabledChanged: ((Boolean) -> Unit)? = null

    private var currentSpeed: Double = 1.0
    private var currentPitch: Double = 1.0
    private var currentEngine: String = "system"
    private var voices: List<AndroidTtsEngine.Voice> = emptyList()
    private var selectedVoiceId: AndroidTtsEngine.Voice.Id? = null
    private var currentLanguage: Language? = null
    private var piperModelReady: Boolean = false
    private var selectedPiperModelId: String = TtsModelManager.PIPER_MODELS.first().id
    private var selectedKokoroSpeakerId: Int = 0
    private var selectedKokoroModelId: String = TtsModelManager.KOKORO_MODELS.first().id
    private var kokoroPreloadEnabled: Boolean = false
    private var initializedFromHost = false

    fun setCurrentValues(
        speed: Double,
        pitch: Double,
        voices: Set<AndroidTtsEngine.Voice>,
        selectedVoiceId: AndroidTtsEngine.Voice.Id?,
        language: Language?,
        engine: String = "system",
        piperReady: Boolean = false,
        piperModelId: String = TtsModelManager.PIPER_MODELS.first().id,
        kokoroSpeakerId: Int = 0,
        kokoroModelId: String = TtsModelManager.KOKORO_MODELS.first().id,
        kokoroPreloadEnabled: Boolean = false
    ) {
        this.currentSpeed = speed
        this.currentPitch = pitch
        this.currentLanguage = language
        this.selectedVoiceId = selectedVoiceId
        this.currentEngine = engine
        this.piperModelReady = piperReady
        this.selectedPiperModelId = piperModelId
        this.selectedKokoroSpeakerId = kokoroSpeakerId
        this.selectedKokoroModelId = kokoroModelId
        this.kokoroPreloadEnabled = kokoroPreloadEnabled
        this.initializedFromHost = false

        this.voices = voices
            .filter { voice ->
                language == null || voice.language.locale.language == language.locale.language
            }
            .sortedWith(compareByDescending<AndroidTtsEngine.Voice> { it.quality }
                .thenBy { it.id.value })
    }

    private fun restoreFromHost() {
        val host = activity as? Host ?: return
        if (initializedFromHost) return
        initializedFromHost = true

        currentSpeed = host.getTtsSpeed()
        currentPitch = host.getTtsPitch()
        currentEngine = host.getTtsEngine()
        selectedVoiceId = host.getTtsSelectedVoiceId()
        currentLanguage = host.getTtsLanguage()
        piperModelReady = host.isPiperModelReady()
        selectedPiperModelId = host.getSelectedPiperModelId()
        selectedKokoroSpeakerId = host.getSelectedKokoroSpeakerId()
        selectedKokoroModelId = host.getSelectedKokoroModelId()
        kokoroPreloadEnabled = host.isKokoroPreloadEnabled()

        val allVoices = host.getTtsVoices()
        voices = allVoices
            .filter { voice ->
                currentLanguage == null || voice.language.locale.language == currentLanguage!!.locale.language
            }
            .sortedWith(compareByDescending<AndroidTtsEngine.Voice> { it.quality }
                .thenBy { it.id.value })

        // Wire callbacks to host
        if (onSpeedChanged == null) onSpeedChanged = { host.onTtsSpeedChanged(it) }
        if (onPitchChanged == null) onPitchChanged = { host.onTtsPitchChanged(it) }
        if (onVoiceSelected == null) onVoiceSelected = { host.onTtsVoiceSelected(it) }
        if (onEngineSelected == null) onEngineSelected = { host.onTtsEngineSelected(it) }
        if (onPiperModelSelected == null) onPiperModelSelected = { host.onPiperModelSelected(it) }
        if (onKokoroSpeakerSelected == null) onKokoroSpeakerSelected = { host.onKokoroSpeakerSelected(it) }
        if (onKokoroModelSelected == null) onKokoroModelSelected = { host.onKokoroModelSelected(it) }
        if (onKokoroPreloadEnabledChanged == null) {
            onKokoroPreloadEnabledChanged = { host.onKokoroPreloadEnabledChanged(it) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (savedInstanceState != null) restoreFromHost()
        return inflater.inflate(R.layout.fragment_tts_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tvSpeedValue = view.findViewById<TextView>(R.id.tv_speed_value)
        val sliderSpeed = view.findViewById<Slider>(R.id.slider_speed)
        val tvPitchValue = view.findViewById<TextView>(R.id.tv_pitch_value)
        val sliderPitch = view.findViewById<Slider>(R.id.slider_pitch)
        val tvSelectedVoice = view.findViewById<TextView>(R.id.tv_selected_voice)
        val rvVoices = view.findViewById<RecyclerView>(R.id.rv_voices)
        val tvPiperStatus = view.findViewById<TextView>(R.id.tv_piper_status)
        val progressDownload = view.findViewById<LinearProgressIndicator>(R.id.progress_download)

        val chipGroupEngine = view.findViewById<ChipGroup>(R.id.chip_group_engine)
        val chipSystem = view.findViewById<Chip>(R.id.chip_engine_system)
        val chipPiper = view.findViewById<Chip>(R.id.chip_engine_piper)
        val chipKokoro = view.findViewById<Chip>(R.id.chip_engine_kokoro)
        val switchKokoroPreload = view.findViewById<SwitchMaterial>(R.id.switch_kokoro_preload)

        when (currentEngine) {
            "piper" -> chipPiper.isChecked = true
            "kokoro" -> chipKokoro.isChecked = true
            else -> chipSystem.isChecked = true
        }

        updatePiperStatus(tvPiperStatus, progressDownload)
        updateVoiceSectionVisibility(view)
        setupPiperVoicesList(view)
        setupKokoroSection(view)

        switchKokoroPreload.isChecked = kokoroPreloadEnabled
        switchKokoroPreload.setOnCheckedChangeListener { _, checked ->
            kokoroPreloadEnabled = checked
            onKokoroPreloadEnabledChanged?.invoke(checked)
        }

        chipGroupEngine.setOnCheckedStateChangeListener { _, checkedIds ->
            val engine = when {
                checkedIds.contains(R.id.chip_engine_kokoro) -> "kokoro"
                checkedIds.contains(R.id.chip_engine_piper) -> "piper"
                else -> "system"
            }
            currentEngine = engine
            updatePiperStatus(tvPiperStatus, progressDownload)
            updateVoiceSectionVisibility(view)
            onEngineSelected?.invoke(engine)
        }

        sliderSpeed.value = currentSpeed.toFloat().coerceIn(0.5f, 3.0f)
        tvSpeedValue.text = formatRate(currentSpeed)

        sliderSpeed.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentSpeed = value.toDouble()
                tvSpeedValue.text = formatRate(currentSpeed)
                onSpeedChanged?.invoke(currentSpeed)
            }
        }

        sliderPitch.value = currentPitch.toFloat().coerceIn(0.5f, 2.0f)
        tvPitchValue.text = formatRate(currentPitch)

        sliderPitch.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentPitch = value.toDouble()
                tvPitchValue.text = formatRate(currentPitch)
                onPitchChanged?.invoke(currentPitch)
            }
        }

        val selectedVoice = voices.find { it.id == selectedVoiceId }
        tvSelectedVoice.text = selectedVoice?.let { formatVoiceName(it) }
            ?: getString(R.string.tts_voice_default)

        rvVoices.layoutManager = LinearLayoutManager(requireContext())
        val adapter = VoiceAdapter(voices, selectedVoiceId) { voice ->
            selectedVoiceId = voice.id
            tvSelectedVoice.text = formatVoiceName(voice)
            rvVoices.adapter?.notifyDataSetChanged()
            onVoiceSelected?.invoke(voice)
        }
        rvVoices.adapter = adapter
    }

    private fun updatePiperStatus(tvStatus: TextView, progress: LinearProgressIndicator) {
        if (currentEngine != "piper") {
            tvStatus.visibility = View.GONE
            progress.visibility = View.GONE
            return
        }
        tvStatus.visibility = View.VISIBLE
        progress.visibility = View.GONE
        if (piperModelReady) {
            val activeModel = TtsModelManager.PIPER_MODELS.find { it.id == selectedPiperModelId }
            tvStatus.text = if (activeModel != null)
                getString(R.string.piper_voice_active) + ": ${activeModel.displayName}"
            else
                getString(R.string.piper_ready)
        } else {
            tvStatus.text = getString(R.string.piper_not_downloaded)
        }
    }

    private fun updateVoiceSectionVisibility(view: View) {
        val rvVoices = view.findViewById<RecyclerView>(R.id.rv_voices)
        val tvSystemVoiceLabel = view.findViewById<TextView>(R.id.tv_system_voice_label)
        val tvSelectedVoice = view.findViewById<TextView>(R.id.tv_selected_voice)
        val pitchSection = view.findViewById<View>(R.id.pitch_section)
        val piperVoicesLabel = view.findViewById<TextView>(R.id.tv_piper_voices_label)
        val rvPiperVoices = view.findViewById<RecyclerView>(R.id.rv_piper_voices)
        val tvKokoroModelsLabel = view.findViewById<TextView>(R.id.tv_kokoro_models_label)
        val rvKokoroModels = view.findViewById<RecyclerView>(R.id.rv_kokoro_models)
        val tvKokoroVoicesLabel = view.findViewById<TextView>(R.id.tv_kokoro_voices_label)
        val rvKokoroVoices = view.findViewById<RecyclerView>(R.id.rv_kokoro_voices)
        val kokoroPreloadSection = view.findViewById<View>(R.id.kokoro_preload_section)

        val showSystemControls = currentEngine == "system"
        val showPiperControls = currentEngine == "piper"
        val showKokoroControls = currentEngine == "kokoro"

        tvSystemVoiceLabel?.visibility = if (showSystemControls) View.VISIBLE else View.GONE
        tvSelectedVoice?.visibility = if (showSystemControls) View.VISIBLE else View.GONE
        rvVoices?.visibility = if (showSystemControls) View.VISIBLE else View.GONE
        pitchSection?.visibility = if (showSystemControls) View.VISIBLE else View.GONE
        piperVoicesLabel?.visibility = if (showPiperControls) View.VISIBLE else View.GONE
        rvPiperVoices?.visibility = if (showPiperControls) View.VISIBLE else View.GONE

        if (showKokoroControls) {
            val manager = TtsModelManager(requireContext())
            kokoroPreloadSection?.visibility = View.VISIBLE
            tvKokoroModelsLabel?.visibility = View.VISIBLE
            rvKokoroModels?.visibility = View.VISIBLE

            val selectedModel = TtsModelManager.KOKORO_MODELS.find { it.id == selectedKokoroModelId }
            val selectedDownloaded = selectedModel != null && manager.isKokoroDownloaded(selectedModel)
            tvKokoroVoicesLabel?.visibility = if (selectedDownloaded) View.VISIBLE else View.GONE
            rvKokoroVoices?.visibility = if (selectedDownloaded) View.VISIBLE else View.GONE
        } else {
            kokoroPreloadSection?.visibility = View.GONE
            tvKokoroModelsLabel?.visibility = View.GONE
            rvKokoroModels?.visibility = View.GONE
            tvKokoroVoicesLabel?.visibility = View.GONE
            rvKokoroVoices?.visibility = View.GONE
        }
    }

    private fun enableRecyclerViewScrollInBottomSheet(rv: RecyclerView) {
        rv.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                if (rv.canScrollVertically(1) || rv.canScrollVertically(-1)) {
                    rv.parent?.requestDisallowInterceptTouchEvent(true)
                }
                return false
            }
            override fun onTouchEvent(rv: RecyclerView, e: android.view.MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    private fun setupKokoroSection(view: View) {
        val manager = TtsModelManager(requireContext())
        val rvKokoroModels = view.findViewById<RecyclerView>(R.id.rv_kokoro_models) ?: return
        val rvKokoroVoices = view.findViewById<RecyclerView>(R.id.rv_kokoro_voices) ?: return

        rvKokoroModels.layoutManager = LinearLayoutManager(requireContext())
        rvKokoroVoices.layoutManager = LinearLayoutManager(requireContext())
        enableRecyclerViewScrollInBottomSheet(rvKokoroModels)
        enableRecyclerViewScrollInBottomSheet(rvKokoroVoices)

        // Setup speakers for the currently selected (and downloaded) model
        refreshKokoroSpeakers(rvKokoroVoices, manager)

        val modelAdapter = KokoroModelAdapter(
            models = TtsModelManager.KOKORO_MODELS,
            manager = manager,
            selectedModelId = selectedKokoroModelId,
            onSelect = { model ->
                selectedKokoroModelId = model.id
                selectedKokoroSpeakerId = 0
                onKokoroModelSelected?.invoke(model.id)
                onKokoroSpeakerSelected?.invoke(0)
                refreshKokoroSpeakers(rvKokoroVoices, manager)
                updateVoiceSectionVisibility(view)
            },
            onDownloadRequest = { model, holder ->
                downloadKokoroVoice(model, holder, view, rvKokoroVoices, manager)
            },
            onDeleteRequest = { model, holder ->
                manager.deleteKokoroModel(model)
                if (selectedKokoroModelId == model.id) {
                    val firstReady = TtsModelManager.KOKORO_MODELS.firstOrNull { manager.isKokoroDownloaded(it) }
                    if (firstReady != null) {
                        selectedKokoroModelId = firstReady.id
                        selectedKokoroSpeakerId = 0
                        onKokoroModelSelected?.invoke(firstReady.id)
                        onKokoroSpeakerSelected?.invoke(0)
                    }
                }
                holder.bindState(model, manager.isKokoroDownloaded(model), selectedKokoroModelId)
                refreshKokoroSpeakers(rvKokoroVoices, manager)
                updateVoiceSectionVisibility(view)
                Toast.makeText(requireContext(), R.string.kokoro_voice_deleted, Toast.LENGTH_SHORT).show()
            }
        )
        rvKokoroModels.adapter = modelAdapter
    }

    private fun refreshKokoroSpeakers(rvKokoroVoices: RecyclerView, manager: TtsModelManager) {
        val selectedModel = TtsModelManager.KOKORO_MODELS.find { it.id == selectedKokoroModelId }
        if (selectedModel != null && manager.isKokoroDownloaded(selectedModel)) {
            rvKokoroVoices.adapter = KokoroSpeakerAdapter(selectedModel.speakers, selectedKokoroSpeakerId) { speaker ->
                selectedKokoroSpeakerId = speaker.id
                onKokoroSpeakerSelected?.invoke(speaker.id)
            }
        } else {
            rvKokoroVoices.adapter = null
        }
    }

    private fun downloadKokoroVoice(
        model: TtsModelManager.KokoroModel,
        holder: KokoroModelAdapter.VH,
        rootView: View,
        rvKokoroVoices: RecyclerView,
        manager: TtsModelManager
    ) {
        holder.progress.visibility = View.VISIBLE
        holder.progress.isIndeterminate = false
        holder.progress.progress = 0
        holder.actionBtn.isEnabled = false

        // Use activity scope so download survives sheet dismissal
        val activityScope = (activity as? androidx.appcompat.app.AppCompatActivity)?.lifecycleScope ?: lifecycleScope
        activityScope.launch {
            val result = manager.downloadKokoroModel(
                model = model,
                onProgress = { fraction ->
                    activity?.runOnUiThread {
                        if (isAdded) holder.progress.progress = (fraction * 100).toInt()
                    }
                },
                onExtracting = {
                    activity?.runOnUiThread {
                        if (isAdded) holder.progress.isIndeterminate = true
                    }
                }
            )
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                result.fold(
                    onSuccess = {
                        holder.progress.visibility = View.GONE
                        holder.actionBtn.isEnabled = true
                        selectedKokoroModelId = model.id
                        selectedKokoroSpeakerId = 0
                        onKokoroModelSelected?.invoke(model.id)
                        onKokoroSpeakerSelected?.invoke(0)
                        holder.bindState(model, true, selectedKokoroModelId)
                        (rootView.findViewById<RecyclerView>(R.id.rv_kokoro_models)?.adapter as? KokoroModelAdapter)
                            ?.updateSelectedId(model.id)
                        refreshKokoroSpeakers(rvKokoroVoices, manager)
                        updateVoiceSectionVisibility(rootView)
                        onEngineSelected?.invoke("kokoro")
                    },
                    onFailure = { e ->
                        holder.progress.visibility = View.GONE
                        holder.actionBtn.isEnabled = true
                        Toast.makeText(requireContext(), getString(R.string.kokoro_download_failed, e.message), Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    private fun setupPiperVoicesList(view: View) {
        val rvPiperVoices = view.findViewById<RecyclerView>(R.id.rv_piper_voices) ?: return
        val manager = TtsModelManager(requireContext())
        rvPiperVoices.layoutManager = LinearLayoutManager(requireContext())
        enableRecyclerViewScrollInBottomSheet(rvPiperVoices)
        val adapter = PiperVoiceAdapter(
            models = TtsModelManager.PIPER_MODELS,
            manager = manager,
            selectedModelId = selectedPiperModelId,
            onSelect = { model ->
                selectedPiperModelId = model.id
                piperModelReady = true
                val tvStatus = view.findViewById<TextView>(R.id.tv_piper_status)
                val progressDownload = view.findViewById<LinearProgressIndicator>(R.id.progress_download)
                updatePiperStatus(tvStatus, progressDownload)
                onPiperModelSelected?.invoke(model.id)
            },
            onDownloadRequest = { model, holder ->
                downloadVoice(model, holder, view)
            },
            onDeleteRequest = { model, holder ->
                manager.deleteModel(model)
                if (selectedPiperModelId == model.id) {
                    val firstReady = TtsModelManager.PIPER_MODELS.firstOrNull { manager.isModelDownloaded(it) }
                    if (firstReady != null) {
                        selectedPiperModelId = firstReady.id
                        onPiperModelSelected?.invoke(firstReady.id)
                    } else {
                        piperModelReady = false
                        val tvStatus = view.findViewById<TextView>(R.id.tv_piper_status)
                        val progressDownload = view.findViewById<LinearProgressIndicator>(R.id.progress_download)
                        updatePiperStatus(tvStatus, progressDownload)
                    }
                }
                holder.bindState(model, manager.isModelDownloaded(model), selectedPiperModelId)
                Toast.makeText(requireContext(), R.string.piper_voice_deleted, Toast.LENGTH_SHORT).show()
            }
        )
        rvPiperVoices.adapter = adapter
    }

    private fun downloadVoice(
        model: TtsModelManager.PiperModel,
        holder: PiperVoiceAdapter.VH,
        rootView: View
    ) {
        val manager = TtsModelManager(requireContext())
        holder.progress.visibility = View.VISIBLE
        holder.progress.isIndeterminate = false
        holder.progress.progress = 0
        holder.actionBtn.isEnabled = false

        // Use activity scope so download survives sheet dismissal
        val activityScope = (activity as? androidx.appcompat.app.AppCompatActivity)?.lifecycleScope ?: lifecycleScope
        activityScope.launch {
            val result = manager.downloadModel(model) { fraction ->
                activity?.runOnUiThread {
                    if (isAdded) holder.progress.progress = (fraction * 100).toInt()
                }
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                result.fold(
                    onSuccess = {
                        holder.progress.visibility = View.GONE
                        holder.actionBtn.isEnabled = true
                        holder.bindState(model, true, selectedPiperModelId)
                        if (!piperModelReady) {
                            piperModelReady = true
                            selectedPiperModelId = model.id
                            val tvStatus = rootView.findViewById<TextView>(R.id.tv_piper_status)
                            val progressDownload = rootView.findViewById<LinearProgressIndicator>(R.id.progress_download)
                            updatePiperStatus(tvStatus, progressDownload)
                            onPiperModelSelected?.invoke(model.id)
                            (rootView.findViewById<RecyclerView>(R.id.rv_piper_voices)?.adapter as? PiperVoiceAdapter)
                                ?.updateSelectedId(model.id)
                        }
                    },
                    onFailure = { e ->
                        holder.progress.visibility = View.GONE
                        holder.actionBtn.isEnabled = true
                        Toast.makeText(requireContext(), getString(R.string.piper_voice_download_failed, e.message), Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    private fun formatRate(value: Double): String {
        return String.format(Locale.US, "%.1fx", value)
    }

    private fun formatVoiceName(voice: AndroidTtsEngine.Voice): String {
        val lang = voice.language.locale.displayName
        val quality = when (voice.quality) {
            AndroidTtsEngine.Voice.Quality.Highest -> "Highest"
            AndroidTtsEngine.Voice.Quality.High -> "High"
            AndroidTtsEngine.Voice.Quality.Normal -> "Normal"
            AndroidTtsEngine.Voice.Quality.Low -> "Low"
            AndroidTtsEngine.Voice.Quality.Lowest -> "Lowest"
        }
        val network = if (voice.requiresNetwork) " (online)" else ""
        return "${voice.id.value} - $lang ($quality$network)"
    }

    private class VoiceAdapter(
        private val voices: List<AndroidTtsEngine.Voice>,
        private val selectedId: AndroidTtsEngine.Voice.Id?,
        private val onSelect: (AndroidTtsEngine.Voice) -> Unit
    ) : RecyclerView.Adapter<VoiceAdapter.VH>() {

        private var currentSelectedId = selectedId

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val radio: RadioButton = view.findViewById(R.id.voice_radio)
            val name: TextView = view.findViewById(R.id.voice_name)
            val details: TextView = view.findViewById(R.id.voice_details)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_voice, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val voice = voices[position]
            holder.radio.isChecked = voice.id == currentSelectedId
            holder.name.text = voice.id.value

            val quality = when (voice.quality) {
                AndroidTtsEngine.Voice.Quality.Highest -> "Highest quality"
                AndroidTtsEngine.Voice.Quality.High -> "High quality"
                AndroidTtsEngine.Voice.Quality.Normal -> "Normal quality"
                AndroidTtsEngine.Voice.Quality.Low -> "Low quality"
                AndroidTtsEngine.Voice.Quality.Lowest -> "Lowest quality"
            }
            val lang = voice.language.locale.displayName
            val network = if (voice.requiresNetwork) " \u00b7 Requires internet" else ""
            holder.details.text = "$lang \u00b7 $quality$network"

            holder.itemView.setOnClickListener {
                currentSelectedId = voice.id
                notifyDataSetChanged()
                onSelect(voice)
            }
        }

        override fun getItemCount(): Int = voices.size
    }

    class PiperVoiceAdapter(
        private val models: List<TtsModelManager.PiperModel>,
        private val manager: TtsModelManager,
        private var selectedModelId: String,
        private val onSelect: (TtsModelManager.PiperModel) -> Unit,
        private val onDownloadRequest: (TtsModelManager.PiperModel, VH) -> Unit,
        private val onDeleteRequest: (TtsModelManager.PiperModel, VH) -> Unit
    ) : RecyclerView.Adapter<PiperVoiceAdapter.VH>() {

        fun updateSelectedId(id: String) {
            selectedModelId = id
            notifyDataSetChanged()
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val radio: RadioButton = view.findViewById(R.id.voice_radio)
            val name: TextView = view.findViewById(R.id.tv_voice_name)
            val details: TextView = view.findViewById(R.id.tv_voice_details)
            val progress: LinearProgressIndicator = view.findViewById(R.id.progress_voice_download)
            val actionBtn: ImageButton = view.findViewById(R.id.btn_voice_action)

            fun bindState(model: TtsModelManager.PiperModel, downloaded: Boolean, selectedId: String) {
                radio.isChecked = downloaded && model.id == selectedId
                radio.isEnabled = downloaded
                val ctx = itemView.context
                if (downloaded) {
                    details.text = ctx.getString(
                        R.string.piper_voice_downloaded,
                        model.language, model.quality, model.gender
                    )
                    actionBtn.setImageResource(R.drawable.ic_delete)
                    actionBtn.contentDescription = ctx.getString(R.string.piper_delete_voice)
                } else {
                    details.text = ctx.getString(
                        R.string.piper_voice_size,
                        model.language, model.quality, model.gender,
                        model.sizeBytes / 1_000_000
                    )
                    actionBtn.setImageResource(R.drawable.ic_download)
                    actionBtn.contentDescription = ctx.getString(R.string.piper_download_voice)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_piper_voice, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val model = models[position]
            val downloaded = manager.isModelDownloaded(model)
            holder.name.text = model.displayName
            holder.bindState(model, downloaded, selectedModelId)
            holder.progress.visibility = View.GONE

            holder.itemView.setOnClickListener {
                if (downloaded) {
                    selectedModelId = model.id
                    notifyDataSetChanged()
                    onSelect(model)
                }
            }

            holder.actionBtn.setOnClickListener {
                if (downloaded) {
                    onDeleteRequest(model, holder)
                } else {
                    onDownloadRequest(model, holder)
                }
            }
        }

        override fun getItemCount(): Int = models.size
    }

    class KokoroModelAdapter(
        private val models: List<TtsModelManager.KokoroModel>,
        private val manager: TtsModelManager,
        private var selectedModelId: String,
        private val onSelect: (TtsModelManager.KokoroModel) -> Unit,
        private val onDownloadRequest: (TtsModelManager.KokoroModel, VH) -> Unit,
        private val onDeleteRequest: (TtsModelManager.KokoroModel, VH) -> Unit
    ) : RecyclerView.Adapter<KokoroModelAdapter.VH>() {

        fun updateSelectedId(id: String) {
            selectedModelId = id
            notifyDataSetChanged()
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val radio: RadioButton = view.findViewById(R.id.voice_radio)
            val name: TextView = view.findViewById(R.id.tv_voice_name)
            val details: TextView = view.findViewById(R.id.tv_voice_details)
            val progress: LinearProgressIndicator = view.findViewById(R.id.progress_voice_download)
            val actionBtn: ImageButton = view.findViewById(R.id.btn_voice_action)

            fun bindState(model: TtsModelManager.KokoroModel, downloaded: Boolean, selectedId: String) {
                radio.isChecked = downloaded && model.id == selectedId
                radio.isEnabled = downloaded
                val ctx = itemView.context
                if (downloaded) {
                    details.text = ctx.getString(
                        R.string.kokoro_model_downloaded,
                        model.language, model.speakers.size
                    )
                    actionBtn.setImageResource(R.drawable.ic_delete)
                    actionBtn.contentDescription = ctx.getString(R.string.piper_delete_voice)
                } else {
                    details.text = ctx.getString(
                        R.string.kokoro_model_size,
                        model.language, model.speakers.size, model.sizeBytes / 1_000_000
                    )
                    actionBtn.setImageResource(R.drawable.ic_download)
                    actionBtn.contentDescription = ctx.getString(R.string.piper_download_voice)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_piper_voice, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val model = models[position]
            val downloaded = manager.isKokoroDownloaded(model)
            holder.name.text = model.displayName
            holder.bindState(model, downloaded, selectedModelId)
            holder.progress.visibility = View.GONE

            holder.itemView.setOnClickListener {
                if (downloaded) {
                    selectedModelId = model.id
                    notifyDataSetChanged()
                    onSelect(model)
                }
            }

            holder.actionBtn.setOnClickListener {
                if (downloaded) {
                    onDeleteRequest(model, holder)
                } else {
                    onDownloadRequest(model, holder)
                }
            }
        }

        override fun getItemCount(): Int = models.size
    }

    private class KokoroSpeakerAdapter(
        private val speakers: List<TtsModelManager.KokoroSpeaker>,
        private var selectedId: Int,
        private val onSelect: (TtsModelManager.KokoroSpeaker) -> Unit
    ) : RecyclerView.Adapter<KokoroSpeakerAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val radio: RadioButton = view.findViewById(R.id.voice_radio)
            val name: TextView = view.findViewById(R.id.voice_name)
            val details: TextView = view.findViewById(R.id.voice_details)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_voice, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val speaker = speakers[position]
            holder.radio.isChecked = speaker.id == selectedId
            holder.name.text = speaker.displayName
            holder.details.text = "${speaker.language} \u00b7 ${speaker.gender} \u00b7 ${speaker.codeName}"

            holder.itemView.setOnClickListener {
                selectedId = speaker.id
                notifyDataSetChanged()
                onSelect(speaker)
            }
        }

        override fun getItemCount(): Int = speakers.size
    }
}
