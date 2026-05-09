package com.example.booklibrary.tts

import android.content.Context
import com.example.booklibrary.util.SafeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class TtsModelManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsModelManager"
        private const val MODELS_DIR = "tts_models"

        val KOKORO_MODELS = listOf(
            KokoroModel(
                id = "kokoro-en-v0_19",
                displayName = "Kokoro English v0.19",
                language = "en",
                modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2",
                dirName = "kokoro-en-v0_19",
                modelFileName = "model.onnx",
                voicesFileName = "voices.bin",
                dataDirName = "espeak-ng-data",
                sizeBytes = 340_000_000L,
                sampleRate = 24000,
                speakers = listOf(
                    KokoroSpeaker(0, "af", "American Female", "Female", "en-US"),
                    KokoroSpeaker(1, "af_bella", "Bella", "Female", "en-US"),
                    KokoroSpeaker(2, "af_nicole", "Nicole", "Female", "en-US"),
                    KokoroSpeaker(3, "af_sarah", "Sarah", "Female", "en-US"),
                    KokoroSpeaker(4, "af_sky", "Sky", "Female", "en-US"),
                    KokoroSpeaker(5, "am_adam", "Adam", "Male", "en-US"),
                    KokoroSpeaker(6, "am_michael", "Michael", "Male", "en-US"),
                    KokoroSpeaker(7, "bf_emma", "Emma", "Female", "en-GB"),
                    KokoroSpeaker(8, "bf_isabella", "Isabella", "Female", "en-GB"),
                    KokoroSpeaker(9, "bm_george", "George", "Male", "en-GB"),
                    KokoroSpeaker(10, "bm_lewis", "Lewis", "Male", "en-GB")
                )
            ),
            KokoroModel(
                id = "kokoro-multi-lang-v1_0",
                displayName = "Kokoro Multi-lang v1.0",
                language = "en+zh",
                modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
                dirName = "kokoro-multi-lang-v1_0",
                modelFileName = "model.onnx",
                voicesFileName = "voices.bin",
                dataDirName = "espeak-ng-data",
                lexiconFileNames = listOf("lexicon-us-en.txt", "lexicon-zh.txt"),
                dictDirName = "dict",
                sizeBytes = 333_000_000L,
                sampleRate = 24000,
                speakers = listOf(
                    KokoroSpeaker(0, "af_alloy", "Alloy", "Female", "en-US"),
                    KokoroSpeaker(1, "af_aoede", "Aoede", "Female", "en-US"),
                    KokoroSpeaker(2, "af_bella", "Bella", "Female", "en-US"),
                    KokoroSpeaker(3, "af_heart", "Heart", "Female", "en-US"),
                    KokoroSpeaker(4, "af_jessica", "Jessica", "Female", "en-US"),
                    KokoroSpeaker(5, "af_kore", "Kore", "Female", "en-US"),
                    KokoroSpeaker(6, "af_nicole", "Nicole", "Female", "en-US"),
                    KokoroSpeaker(7, "af_nova", "Nova", "Female", "en-US"),
                    KokoroSpeaker(8, "af_river", "River", "Female", "en-US"),
                    KokoroSpeaker(9, "af_sarah", "Sarah", "Female", "en-US"),
                    KokoroSpeaker(10, "af_sky", "Sky", "Female", "en-US"),
                    KokoroSpeaker(11, "am_adam", "Adam", "Male", "en-US"),
                    KokoroSpeaker(12, "am_echo", "Echo", "Male", "en-US"),
                    KokoroSpeaker(13, "am_eric", "Eric", "Male", "en-US"),
                    KokoroSpeaker(14, "am_fenrir", "Fenrir", "Male", "en-US"),
                    KokoroSpeaker(15, "am_liam", "Liam", "Male", "en-US"),
                    KokoroSpeaker(16, "am_michael", "Michael", "Male", "en-US"),
                    KokoroSpeaker(17, "am_onyx", "Onyx", "Male", "en-US"),
                    KokoroSpeaker(18, "am_puck", "Puck", "Male", "en-US"),
                    KokoroSpeaker(19, "am_santa", "Santa", "Male", "en-US"),
                    KokoroSpeaker(20, "bf_alice", "Alice", "Female", "en-GB"),
                    KokoroSpeaker(21, "bf_emma", "Emma", "Female", "en-GB"),
                    KokoroSpeaker(22, "bf_isabella", "Isabella", "Female", "en-GB"),
                    KokoroSpeaker(23, "bf_lily", "Lily", "Female", "en-GB"),
                    KokoroSpeaker(24, "bm_daniel", "Daniel", "Male", "en-GB"),
                    KokoroSpeaker(25, "bm_fable", "Fable", "Male", "en-GB"),
                    KokoroSpeaker(26, "bm_george", "George", "Male", "en-GB"),
                    KokoroSpeaker(27, "bm_lewis", "Lewis", "Male", "en-GB"),
                    KokoroSpeaker(28, "ef_dora", "ef_dora", "Female", "other"),
                    KokoroSpeaker(29, "em_alex", "em_alex", "Male", "other"),
                    KokoroSpeaker(30, "ff_siwis", "ff_siwis", "Female", "other"),
                    KokoroSpeaker(31, "hf_alpha", "hf_alpha", "Female", "other"),
                    KokoroSpeaker(32, "hf_beta", "hf_beta", "Female", "other"),
                    KokoroSpeaker(33, "hm_omega", "hm_omega", "Male", "other"),
                    KokoroSpeaker(34, "hm_psi", "hm_psi", "Male", "other"),
                    KokoroSpeaker(35, "if_sara", "if_sara", "Female", "other"),
                    KokoroSpeaker(36, "im_nicola", "im_nicola", "Male", "other"),
                    KokoroSpeaker(37, "jf_alpha", "jf_alpha", "Female", "other"),
                    KokoroSpeaker(38, "jf_gongitsune", "jf_gongitsune", "Female", "other"),
                    KokoroSpeaker(39, "jf_nezumi", "jf_nezumi", "Female", "other"),
                    KokoroSpeaker(40, "jf_tebukuro", "jf_tebukuro", "Female", "other"),
                    KokoroSpeaker(41, "jm_kumo", "jm_kumo", "Male", "other"),
                    KokoroSpeaker(42, "pf_dora", "pf_dora", "Female", "other"),
                    KokoroSpeaker(43, "pm_alex", "pm_alex", "Male", "other"),
                    KokoroSpeaker(44, "pm_santa", "pm_santa", "Male", "other"),
                    KokoroSpeaker(45, "zf_xiaobei", "zf_xiaobei", "Female", "zh"),
                    KokoroSpeaker(46, "zf_xiaoni", "zf_xiaoni", "Female", "zh"),
                    KokoroSpeaker(47, "zf_xiaoxiao", "zf_xiaoxiao", "Female", "zh"),
                    KokoroSpeaker(48, "zf_xiaoyi", "zf_xiaoyi", "Female", "zh"),
                    KokoroSpeaker(49, "zm_yunjian", "zm_yunjian", "Male", "zh"),
                    KokoroSpeaker(50, "zm_yunxi", "zm_yunxi", "Male", "zh"),
                    KokoroSpeaker(51, "zm_yunxia", "zm_yunxia", "Male", "zh"),
                    KokoroSpeaker(52, "zm_yunyang", "zm_yunyang", "Male", "zh")
                )
            ),
            KokoroModel(
                id = "kokoro-multi-lang-v1_1",
                displayName = "Kokoro Multi-lang v1.1",
                language = "en+zh",
                modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_1.tar.bz2",
                dirName = "kokoro-multi-lang-v1_1",
                modelFileName = "model.onnx",
                voicesFileName = "voices.bin",
                dataDirName = "espeak-ng-data",
                lexiconFileNames = listOf("lexicon-us-en.txt", "lexicon-zh.txt"),
                dictDirName = "dict",
                sizeBytes = 348_000_000L,
                sampleRate = 24000,
                speakers = listOf(
                    KokoroSpeaker(0, "af_maple", "Maple", "Female", "en-US"),
                    KokoroSpeaker(1, "af_sol", "Sol", "Female", "en-US"),
                    KokoroSpeaker(2, "bf_vale", "Vale", "Female", "en-GB"),
                    KokoroSpeaker(3, "zf_001", "zf_001", "Female", "zh"),
                    KokoroSpeaker(4, "zf_002", "zf_002", "Female", "zh"),
                    KokoroSpeaker(5, "zf_003", "zf_003", "Female", "zh"),
                    KokoroSpeaker(6, "zf_004", "zf_004", "Female", "zh"),
                    KokoroSpeaker(7, "zf_005", "zf_005", "Female", "zh"),
                    KokoroSpeaker(8, "zf_006", "zf_006", "Female", "zh"),
                    KokoroSpeaker(9, "zf_007", "zf_007", "Female", "zh"),
                    KokoroSpeaker(10, "zf_008", "zf_008", "Female", "zh"),
                    KokoroSpeaker(11, "zf_017", "zf_017", "Female", "zh"),
                    KokoroSpeaker(12, "zf_018", "zf_018", "Female", "zh"),
                    KokoroSpeaker(13, "zf_019", "zf_019", "Female", "zh"),
                    KokoroSpeaker(14, "zf_021", "zf_021", "Female", "zh"),
                    KokoroSpeaker(15, "zf_022", "zf_022", "Female", "zh"),
                    KokoroSpeaker(16, "zf_023", "zf_023", "Female", "zh"),
                    KokoroSpeaker(17, "zf_024", "zf_024", "Female", "zh"),
                    KokoroSpeaker(18, "zf_026", "zf_026", "Female", "zh"),
                    KokoroSpeaker(19, "zf_027", "zf_027", "Female", "zh"),
                    KokoroSpeaker(20, "zf_028", "zf_028", "Female", "zh"),
                    KokoroSpeaker(21, "zf_032", "zf_032", "Female", "zh"),
                    KokoroSpeaker(22, "zf_036", "zf_036", "Female", "zh"),
                    KokoroSpeaker(23, "zf_038", "zf_038", "Female", "zh"),
                    KokoroSpeaker(24, "zf_039", "zf_039", "Female", "zh"),
                    KokoroSpeaker(25, "zf_040", "zf_040", "Female", "zh"),
                    KokoroSpeaker(26, "zf_042", "zf_042", "Female", "zh"),
                    KokoroSpeaker(27, "zf_043", "zf_043", "Female", "zh"),
                    KokoroSpeaker(28, "zf_044", "zf_044", "Female", "zh"),
                    KokoroSpeaker(29, "zf_046", "zf_046", "Female", "zh"),
                    KokoroSpeaker(30, "zf_047", "zf_047", "Female", "zh"),
                    KokoroSpeaker(31, "zf_048", "zf_048", "Female", "zh"),
                    KokoroSpeaker(32, "zf_049", "zf_049", "Female", "zh"),
                    KokoroSpeaker(33, "zf_051", "zf_051", "Female", "zh"),
                    KokoroSpeaker(34, "zf_059", "zf_059", "Female", "zh"),
                    KokoroSpeaker(35, "zf_060", "zf_060", "Female", "zh"),
                    KokoroSpeaker(36, "zf_067", "zf_067", "Female", "zh"),
                    KokoroSpeaker(37, "zf_070", "zf_070", "Female", "zh"),
                    KokoroSpeaker(38, "zf_071", "zf_071", "Female", "zh"),
                    KokoroSpeaker(39, "zf_072", "zf_072", "Female", "zh"),
                    KokoroSpeaker(40, "zf_073", "zf_073", "Female", "zh"),
                    KokoroSpeaker(41, "zf_074", "zf_074", "Female", "zh"),
                    KokoroSpeaker(42, "zf_075", "zf_075", "Female", "zh"),
                    KokoroSpeaker(43, "zf_076", "zf_076", "Female", "zh"),
                    KokoroSpeaker(44, "zf_077", "zf_077", "Female", "zh"),
                    KokoroSpeaker(45, "zf_078", "zf_078", "Female", "zh"),
                    KokoroSpeaker(46, "zf_079", "zf_079", "Female", "zh"),
                    KokoroSpeaker(47, "zf_083", "zf_083", "Female", "zh"),
                    KokoroSpeaker(48, "zf_084", "zf_084", "Female", "zh"),
                    KokoroSpeaker(49, "zf_085", "zf_085", "Female", "zh"),
                    KokoroSpeaker(50, "zf_086", "zf_086", "Female", "zh"),
                    KokoroSpeaker(51, "zf_087", "zf_087", "Female", "zh"),
                    KokoroSpeaker(52, "zf_088", "zf_088", "Female", "zh"),
                    KokoroSpeaker(53, "zf_090", "zf_090", "Female", "zh"),
                    KokoroSpeaker(54, "zf_092", "zf_092", "Female", "zh"),
                    KokoroSpeaker(55, "zf_093", "zf_093", "Female", "zh"),
                    KokoroSpeaker(56, "zf_094", "zf_094", "Female", "zh"),
                    KokoroSpeaker(57, "zf_099", "zf_099", "Female", "zh"),
                    KokoroSpeaker(58, "zm_009", "zm_009", "Male", "zh"),
                    KokoroSpeaker(59, "zm_010", "zm_010", "Male", "zh"),
                    KokoroSpeaker(60, "zm_011", "zm_011", "Male", "zh"),
                    KokoroSpeaker(61, "zm_012", "zm_012", "Male", "zh"),
                    KokoroSpeaker(62, "zm_013", "zm_013", "Male", "zh"),
                    KokoroSpeaker(63, "zm_014", "zm_014", "Male", "zh"),
                    KokoroSpeaker(64, "zm_015", "zm_015", "Male", "zh"),
                    KokoroSpeaker(65, "zm_016", "zm_016", "Male", "zh"),
                    KokoroSpeaker(66, "zm_020", "zm_020", "Male", "zh"),
                    KokoroSpeaker(67, "zm_025", "zm_025", "Male", "zh"),
                    KokoroSpeaker(68, "zm_029", "zm_029", "Male", "zh"),
                    KokoroSpeaker(69, "zm_030", "zm_030", "Male", "zh"),
                    KokoroSpeaker(70, "zm_031", "zm_031", "Male", "zh"),
                    KokoroSpeaker(71, "zm_033", "zm_033", "Male", "zh"),
                    KokoroSpeaker(72, "zm_034", "zm_034", "Male", "zh"),
                    KokoroSpeaker(73, "zm_035", "zm_035", "Male", "zh"),
                    KokoroSpeaker(74, "zm_037", "zm_037", "Male", "zh"),
                    KokoroSpeaker(75, "zm_041", "zm_041", "Male", "zh"),
                    KokoroSpeaker(76, "zm_045", "zm_045", "Male", "zh"),
                    KokoroSpeaker(77, "zm_050", "zm_050", "Male", "zh"),
                    KokoroSpeaker(78, "zm_052", "zm_052", "Male", "zh"),
                    KokoroSpeaker(79, "zm_053", "zm_053", "Male", "zh"),
                    KokoroSpeaker(80, "zm_054", "zm_054", "Male", "zh"),
                    KokoroSpeaker(81, "zm_055", "zm_055", "Male", "zh"),
                    KokoroSpeaker(82, "zm_056", "zm_056", "Male", "zh"),
                    KokoroSpeaker(83, "zm_057", "zm_057", "Male", "zh"),
                    KokoroSpeaker(84, "zm_058", "zm_058", "Male", "zh"),
                    KokoroSpeaker(85, "zm_061", "zm_061", "Male", "zh"),
                    KokoroSpeaker(86, "zm_062", "zm_062", "Male", "zh"),
                    KokoroSpeaker(87, "zm_063", "zm_063", "Male", "zh"),
                    KokoroSpeaker(88, "zm_064", "zm_064", "Male", "zh"),
                    KokoroSpeaker(89, "zm_065", "zm_065", "Male", "zh"),
                    KokoroSpeaker(90, "zm_066", "zm_066", "Male", "zh"),
                    KokoroSpeaker(91, "zm_068", "zm_068", "Male", "zh"),
                    KokoroSpeaker(92, "zm_069", "zm_069", "Male", "zh"),
                    KokoroSpeaker(93, "zm_080", "zm_080", "Male", "zh"),
                    KokoroSpeaker(94, "zm_081", "zm_081", "Male", "zh"),
                    KokoroSpeaker(95, "zm_082", "zm_082", "Male", "zh"),
                    KokoroSpeaker(96, "zm_089", "zm_089", "Male", "zh"),
                    KokoroSpeaker(97, "zm_091", "zm_091", "Male", "zh"),
                    KokoroSpeaker(98, "zm_095", "zm_095", "Male", "zh"),
                    KokoroSpeaker(99, "zm_096", "zm_096", "Male", "zh"),
                    KokoroSpeaker(100, "zm_097", "zm_097", "Male", "zh"),
                    KokoroSpeaker(101, "zm_098", "zm_098", "Male", "zh"),
                    KokoroSpeaker(102, "zm_100", "zm_100", "Male", "zh")
                )
            )
        )

        val PIPER_MODELS = listOf(
            PiperModel(
                id = "en_US-lessac-medium",
                displayName = "Lessac",
                language = "en-US",
                quality = "Medium",
                gender = "Male",
                modelFileName = "en_US-lessac-medium.onnx",
                modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2",
                dataDirName = "espeak-ng-data",
                sizeBytes = 63_000_000L
            ),
            PiperModel(
                id = "en_US-amy-low",
                displayName = "Amy",
                language = "en-US",
                quality = "Low",
                gender = "Female",
                modelFileName = "en_US-amy-low.onnx",
                modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-low.tar.bz2",
                dataDirName = "espeak-ng-data",
                sizeBytes = 64_000_000L
            ),
            PiperModel(
                id = "en_US-kristin-medium",
                displayName = "Kristin",
                language = "en-US",
                quality = "Medium",
                gender = "Female",
                modelFileName = "en_US-kristin-medium.onnx",
                modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-kristin-medium.tar.bz2",
                dataDirName = "espeak-ng-data",
                sizeBytes = 64_000_000L
            ),
            PiperModel(
                id = "en_GB-jenny_dioco-medium",
                displayName = "Jenny",
                language = "en-GB",
                quality = "Medium",
                gender = "Female",
                modelFileName = "en_GB-jenny_dioco-medium.onnx",
                modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-jenny_dioco-medium.tar.bz2",
                dataDirName = "espeak-ng-data",
                sizeBytes = 64_000_000L
            ),
            PiperModel(
                id = "en_GB-alan-medium",
                displayName = "Alan",
                language = "en-GB",
                quality = "Medium",
                gender = "Male",
                modelFileName = "en_GB-alan-medium.onnx",
                modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-alan-medium.tar.bz2",
                dataDirName = "espeak-ng-data",
                sizeBytes = 64_000_000L
            ),
            PiperModel(
                id = "en_GB-northern_english_male-medium",
                displayName = "Northern Male",
                language = "en-GB",
                quality = "Medium",
                gender = "Male",
                modelFileName = "en_GB-northern_english_male-medium.onnx",
                modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-northern_english_male-medium.tar.bz2",
                dataDirName = "espeak-ng-data",
                sizeBytes = 64_000_000L
            ),
            PiperModel(
                id = "en_GB-southern_english_female-medium",
                displayName = "Southern Female",
                language = "en-GB",
                quality = "Medium",
                gender = "Female",
                modelFileName = "en_GB-southern_english_female-medium.onnx",
                modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-southern_english_female_medium.tar.bz2",
                dataDirName = "espeak-ng-data",
                sizeBytes = 76_000_000L
            )
        )
    }

    data class PiperModel(
        val id: String,
        val displayName: String,
        val language: String,
        val quality: String = "Medium",
        val gender: String = "Unknown",
        val modelFileName: String,
        val modelUrl: String,
        val dataDirName: String,
        val sizeBytes: Long
    )

    data class KokoroModel(
        val id: String,
        val displayName: String,
        val language: String,
        val modelUrl: String,
        val dirName: String,
        val modelFileName: String,
        val voicesFileName: String,
        val dataDirName: String,
        val lexiconFileNames: List<String> = emptyList(),
        val dictDirName: String = "",
        val sizeBytes: Long,
        val sampleRate: Int,
        val speakers: List<KokoroSpeaker>
    )

    data class KokoroSpeaker(
        val id: Int,
        val codeName: String,
        val displayName: String,
        val gender: String,
        val language: String
    )

    data class KokoroModelPaths(
        val modelDir: String,
        val modelFileName: String,
        val voicesFileName: String,
        val tokensFile: String,
        val dataDir: String,
        val lexicon: String = "",
        val dictDir: String = ""
    )

    data class ModelPaths(
        val modelDir: String,
        val modelFileName: String,
        val dataDir: String
    )

    private fun modelsBaseDir(): File {
        return File(context.filesDir, MODELS_DIR).apply { mkdirs() }
    }

    fun getModelDir(model: PiperModel): File {
        return File(modelsBaseDir(), "vits-piper-${model.id}")
    }

    fun isModelDownloaded(model: PiperModel): Boolean {
        val modelDir = getModelDir(model)
        val modelFile = File(modelDir, model.modelFileName)
        val tokensFile = File(modelDir, "tokens.txt")
        val dataDir = File(modelDir, model.dataDirName)
        val exists = modelFile.exists() && tokensFile.exists() && dataDir.exists()
        SafeLog.d(TAG, "Piper model installed=${exists}")
        return exists
    }

    fun getModelPaths(model: PiperModel): ModelPaths? {
        if (!isModelDownloaded(model)) return null
        val modelDir = getModelDir(model)
        return ModelPaths(
            modelDir = modelDir.absolutePath,
            modelFileName = model.modelFileName,
            dataDir = File(modelDir, model.dataDirName).absolutePath
        )
    }

    suspend fun downloadModel(
        model: PiperModel,
        onProgress: (Float) -> Unit = {}
    ): Result<ModelPaths> = withContext(Dispatchers.IO) {
        try {
            val baseDir = modelsBaseDir()
            val tarBz2File = File(baseDir, "${model.id}.tar.bz2")

            // Clean up any partial previous Piper download/extraction before retrying.
            tarBz2File.delete()
            getModelDir(model).deleteRecursively()

            SafeLog.i(TAG, "Downloading Piper model")
            downloadFile(model.modelUrl, tarBz2File, model.sizeBytes, onProgress)

            SafeLog.i(TAG, "Piper model download complete")
            SafeLog.i(TAG, "Extracting Piper model")
            extractTarBz2(tarBz2File, baseDir)

            tarBz2File.delete()

            // Log extracted contents for debugging
            val modelDir = getModelDir(model)
            if (modelDir.exists()) {
                SafeLog.d(TAG, "Piper model directory exists after extraction")
            } else {
                SafeLog.e(TAG, "Piper model directory missing after extraction")
            }

            if (!isModelDownloaded(model)) {
                // Do not leave an invalid partial extraction around; the next retry should start clean.
                modelDir.deleteRecursively()
                return@withContext Result.failure(Exception("Model files not found after extraction. Check Logcat for TtsModelManager"))
            }

            SafeLog.i(TAG, "Piper model ready")
            Result.success(getModelPaths(model)!!)
        } catch (e: CancellationException) {
            try { File(modelsBaseDir(), "${model.id}.tar.bz2").delete() } catch (_: Exception) {}
            try { getModelDir(model).deleteRecursively() } catch (_: Exception) {}
            throw e
        } catch (e: Exception) {
            SafeLog.e(TAG, "Failed to download Piper model", e)
            try { File(modelsBaseDir(), "${model.id}.tar.bz2").delete() } catch (_: Exception) {}
            try { getModelDir(model).deleteRecursively() } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    private fun downloadFile(
        urlString: String,
        outputFile: File,
        expectedSize: Long,
        onProgress: (Float) -> Unit
    ) {
        var currentUrl = urlString
        var redirectCount = 0

        while (redirectCount < 10) {
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("User-Agent", "BookLibrary/1.0")

            try {
                connection.connect()
                val responseCode = connection.responseCode
                SafeLog.d(TAG, "Model download HTTP response=$responseCode")

                if (responseCode in 301..308) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location == null) throw Exception("Redirect with no Location header")
                    currentUrl = location
                    redirectCount++
                    continue
                }

                if (responseCode != 200) {
                    connection.disconnect()
                    throw Exception("HTTP $responseCode downloading model")
                }

                val totalSize = connection.contentLengthLong.let {
                    if (it > 0) it else expectedSize
                }

                BufferedInputStream(connection.inputStream, 65536).use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(65536)
                        var totalRead = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            totalRead += read
                            if (totalSize > 0) {
                                onProgress((totalRead.toFloat() / totalSize).coerceAtMost(1f))
                            }
                        }
                        output.flush()
                    }
                }
                connection.disconnect()
                SafeLog.i(TAG, "Model archive downloaded")
                return
            } catch (e: CancellationException) {
                connection.disconnect()
                throw e
            } catch (e: Exception) {
                connection.disconnect()
                throw e
            }
        }
        throw Exception("Too many redirects downloading model")
    }

    private fun extractTarBz2(tarBz2File: File, outputDir: File) {
        SafeLog.i(TAG, "Extracting model archive")

        FileInputStream(tarBz2File).use { fis ->
            BufferedInputStream(fis, 65536).use { bis ->
                BZip2CompressorInputStream(bis).use { bz2 ->
                    TarArchiveInputStream(bz2).use { tar ->
                        var entry = tar.nextEntry
                        var fileCount = 0
                        while (entry != null) {
                            val outFile = File(outputDir, entry.name)

                            // Security: prevent path traversal
                            if (!outFile.canonicalPath.startsWith(outputDir.canonicalPath)) {
                                SafeLog.w(TAG, "Skipping suspicious archive entry")
                                entry = tar.nextEntry
                                continue
                            }

                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    val buffer = ByteArray(65536)
                                    var len: Int
                                    while (tar.read(buffer).also { len = it } != -1) {
                                        fos.write(buffer, 0, len)
                                    }
                                }
                                fileCount++
                            }
                            entry = tar.nextEntry
                        }
                        SafeLog.d(TAG, "Extracted model archive files=$fileCount")
                    }
                }
            }
        }
    }

    fun deleteModel(model: PiperModel) {
        val modelDir = getModelDir(model)
        modelDir.deleteRecursively()
        File(modelsBaseDir(), "${model.id}.tar.bz2").delete()
    }

    // --- Kokoro model management ---

    fun getKokoroModelDir(model: KokoroModel): File {
        return File(modelsBaseDir(), model.dirName)
    }

    fun isKokoroDownloaded(model: KokoroModel): Boolean {
        val dir = getKokoroModelDir(model)
        val modelFile = File(dir, model.modelFileName)
        val voices = File(dir, model.voicesFileName)
        val tokens = File(dir, "tokens.txt")
        val dataDir = File(dir, model.dataDirName)
        val exists = modelFile.exists() && voices.exists() && tokens.exists() && dataDir.exists()
        SafeLog.d(TAG, "Kokoro model installed=${exists}")
        return exists
    }

    fun getKokoroModelPaths(model: KokoroModel): KokoroModelPaths? {
        if (!isKokoroDownloaded(model)) return null
        val dir = getKokoroModelDir(model)
        val lexicon = if (model.lexiconFileNames.isNotEmpty()) {
            model.lexiconFileNames.joinToString(",") { File(dir, it).absolutePath }
        } else ""
        val dictDir = if (model.dictDirName.isNotEmpty()) {
            File(dir, model.dictDirName).absolutePath
        } else ""
        return KokoroModelPaths(
            modelDir = dir.absolutePath,
            modelFileName = model.modelFileName,
            voicesFileName = model.voicesFileName,
            tokensFile = File(dir, "tokens.txt").absolutePath,
            dataDir = File(dir, model.dataDirName).absolutePath,
            lexicon = lexicon,
            dictDir = dictDir
        )
    }

    suspend fun downloadKokoroModel(
        model: KokoroModel,
        onProgress: (Float) -> Unit = {},
        onExtracting: () -> Unit = {}
    ): Result<KokoroModelPaths> = withContext(Dispatchers.IO) {
        try {
            val baseDir = modelsBaseDir()
            val tarBz2File = File(baseDir, "${model.id}.tar.bz2")

            // Clean up any partial previous download/extraction
            tarBz2File.delete()
            getKokoroModelDir(model).deleteRecursively()

            SafeLog.i(TAG, "Downloading Kokoro model")
            downloadFile(model.modelUrl, tarBz2File, model.sizeBytes, onProgress)

            SafeLog.i(TAG, "Kokoro model download complete")
            SafeLog.i(TAG, "Extracting Kokoro model")
            onExtracting()
            extractTarBz2(tarBz2File, baseDir)

            tarBz2File.delete()

            val modelDir = getKokoroModelDir(model)
            if (modelDir.exists()) {
                SafeLog.d(TAG, "Kokoro model directory exists after extraction")
            }

            if (!isKokoroDownloaded(model)) {
                return@withContext Result.failure(Exception("Kokoro model files not found after extraction"))
            }

            SafeLog.i(TAG, "Kokoro model ready")
            Result.success(getKokoroModelPaths(model)!!)
        } catch (e: CancellationException) {
            try { File(modelsBaseDir(), "${model.id}.tar.bz2").delete() } catch (_: Exception) {}
            try { getKokoroModelDir(model).deleteRecursively() } catch (_: Exception) {}
            throw e
        } catch (e: Exception) {
            SafeLog.e(TAG, "Failed to download Kokoro model", e)
            Result.failure(e)
        }
    }

    fun deleteKokoroModel(model: KokoroModel) {
        getKokoroModelDir(model).deleteRecursively()
        // Clean up any leftover partial download
        File(modelsBaseDir(), "${model.id}.tar.bz2").delete()
    }
}
