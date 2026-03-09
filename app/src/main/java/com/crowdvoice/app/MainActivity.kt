package com.crowdvoice.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.crowdvoice.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * MainActivity
 *
 * Pantalla única de la aplicación CrowdVoice.
 *
 * Flujo principal:
 *  1. Usuario escribe texto → elige idioma → ajusta cantidad de voces
 *  2. Pulsa "Generar" → TTS sintetiza → CrowdAudioGenerator mezcla N voces
 *  3. Pulsa "Reproducir" → AudioPlayer reproduce el WAV generado
 *  4. Pulsa "Guardar" → copia el WAV a la carpeta Music del dispositivo
 *
 * Manejo de estado:
 *  - [UiState] controla qué botones están habilitados en cada momento.
 *  - Corrutinas de [lifecycleScope] manejan todas las operaciones asíncronas.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // ─── View Binding ───────────────────────────────────────
    private lateinit var binding: ActivityMainBinding

    // ─── Componentes de negocio ─────────────────────────────
    private val ttsManager       = TTSManager(this)
    private val crowdGenerator   = CrowdAudioGenerator()
    private val audioPlayer      = AudioPlayer()

    // ─── Estado ─────────────────────────────────────────────
    private var generatedWavFile: File? = null   // archivo de salida de la multitud
    private var playbackJob: Job? = null         // corrutina de reproducción activa

    // Idioma seleccionado actualmente
    private var selectedLocale: Locale = Locale("es", "ES")

    // ─────────────────────────────────────────────────────────
    // ESTADOS DE LA UI
    // ─────────────────────────────────────────────────────────

    /** Controla qué controles están habilitados según el estado actual */
    private sealed class UiState {
        object Initializing : UiState()     // TTS se está iniciando
        object Ready        : UiState()     // listo para generar
        object Generating   : UiState()     // generando audio de multitud
        object Generated    : UiState()     // audio generado, listo para reproducir/guardar
        object Playing      : UiState()     // reproduciendo
    }

    // ─────────────────────────────────────────────────────────
    // PERMISO DE ESCRITURA (Android ≤ 9)
    // ─────────────────────────────────────────────────────────

    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) saveAudioFile()
        else showToast("Permiso de almacenamiento denegado")
    }

    // ─────────────────────────────────────────────────────────
    // CICLO DE VIDA
    // ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLanguageSpinner()
        setupCrowdSizeSlider()
        setupButtons()
        initializeTts()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer.stop()
        ttsManager.shutdown()
    }

    // ─────────────────────────────────────────────────────────
    // INICIALIZACIÓN TTS
    // ─────────────────────────────────────────────────────────

    private fun initializeTts() {
        setUiState(UiState.Initializing)
        lifecycleScope.launch {
            val ok = ttsManager.initialize()
            if (ok) {
                ttsManager.setLanguage(selectedLocale)
                setUiState(UiState.Ready)
                Log.d(TAG, "TTS listo")
            } else {
                showToast("Error: No se pudo inicializar el motor de voz.\nVerifica que el TTS esté instalado en el dispositivo.")
                setUiState(UiState.Ready)   // dejamos los botones activos para reintentar
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // CONFIGURACIÓN DE CONTROLES
    // ─────────────────────────────────────────────────────────

    private fun setupLanguageSpinner() {
        val languages = listOf("Español", "English")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLanguage.adapter = adapter

        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                selectedLocale = when (pos) {
                    0    -> Locale("es", "ES")
                    else -> Locale.US
                }
                val ok = ttsManager.setLanguage(selectedLocale)
                if (!ok) {
                    showToast("Idioma no disponible en este dispositivo.\nPuede faltar el paquete de voz.")
                }
                Log.d(TAG, "Idioma cambiado a: ${selectedLocale.displayName}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupCrowdSizeSlider() {
        // Rango: 5–100 voces, valor por defecto: 20
        binding.seekBarCrowdSize.apply {
            max      = 95   // 95 + 5 = rango real 5–100
            progress = 15   // 15 + 5 = 20 voces por defecto
        }

        updateCrowdSizeLabel()

        binding.seekBarCrowdSize.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    updateCrowdSizeLabel()
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            }
        )
    }

    private fun getCrowdSize(): Int = binding.seekBarCrowdSize.progress + 5

    private fun updateCrowdSizeLabel() {
        binding.tvCrowdSizeValue.text = "${getCrowdSize()} personas"
    }

    private fun setupButtons() {
        binding.btnGenerate.setOnClickListener { onGenerateClicked() }
        binding.btnPlay.setOnClickListener    { onPlayClicked()     }
        binding.btnSave.setOnClickListener    { onSaveClicked()     }
    }

    // ─────────────────────────────────────────────────────────
    // MANEJO DE BOTONES
    // ─────────────────────────────────────────────────────────

    private fun onGenerateClicked() {
        val text = binding.etPhrase.text.toString().trim()

        if (text.isBlank()) {
            showToast("Escribe una palabra o frase primero")
            return
        }

        if (!ttsManager.isReady) {
            showToast("El motor de voz aún no está listo, espera un momento")
            return
        }

        lifecycleScope.launch {
            generateCrowdAudio(text)
        }
    }

    private fun onPlayClicked() {
        val wav = generatedWavFile ?: run {
            showToast("Primero genera el audio")
            return
        }

        if (audioPlayer.isPlaying) {
            // Si ya reproduce → detener
            audioPlayer.stop()
            setUiState(UiState.Generated)
            return
        }

        setUiState(UiState.Playing)
        binding.btnPlay.text = "⏹ Detener"

        playbackJob = lifecycleScope.launch {
            audioPlayer.play(wav)
            // Cuando termina la reproducción, volver al estado Generated
            runOnUiThread {
                setUiState(UiState.Generated)
                binding.btnPlay.text = "▶ Reproducir"
            }
        }
    }

    private fun onSaveClicked() {
        if (generatedWavFile == null) {
            showToast("Primero genera el audio")
            return
        }

        // En Android 10+ no se necesita el permiso de escritura para MediaStore
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }

        saveAudioFile()
    }

    // ─────────────────────────────────────────────────────────
    // GENERACIÓN DE AUDIO DE MULTITUD
    // ─────────────────────────────────────────────────────────

    private suspend fun generateCrowdAudio(text: String) {
        setUiState(UiState.Generating)
        showProgress(true, "Sintetizando voz...")

        val tempDir = cacheDir

        // 1. Síntesis TTS → archivo WAV base
        val ttsFile = ttsManager.synthesizeToFile(text, tempDir)
        if (ttsFile == null) {
            showProgress(false)
            showToast("Error al sintetizar el texto.\n¿Está instalado el motor TTS?")
            setUiState(UiState.Ready)
            return
        }

        showProgress(true, "Generando multitud (${getCrowdSize()} voces)...")

        // 2. Configuración de la multitud
        val config = CrowdAudioGenerator.CrowdConfig(
            voiceCount = getCrowdSize()
            // audienceType y emotion se configurarán en versiones futuras
        )

        // 3. Archivo de salida en caché
        val outputWav = File(tempDir, "crowd_output_${System.currentTimeMillis()}.wav")

        // 4. Generar mezcla
        val success = crowdGenerator.generate(ttsFile, config, outputWav)

        // Limpiar TTS base temporal
        ttsFile.delete()

        showProgress(false)

        if (success && outputWav.exists()) {
            generatedWavFile = outputWav
            setUiState(UiState.Generated)
            showToast("✓ Audio de multitud generado (${getCrowdSize()} voces)")
            Log.d(TAG, "Generado en: ${outputWav.absolutePath} (${outputWav.length()} bytes)")
        } else {
            setUiState(UiState.Ready)
            showToast("Error al generar el audio de multitud")
        }
    }

    // ─────────────────────────────────────────────────────────
    // GUARDAR AUDIO
    // ─────────────────────────────────────────────────────────

    private fun saveAudioFile() {
        val sourceFile = generatedWavFile ?: return
        val phrase     = binding.etPhrase.text.toString().trim()
            .replace(" ", "_")
            .take(30)
            .replace("[^a-zA-Z0-9_]".toRegex(), "")
            .ifBlank { "crowd_voice" }

        val fileName = "CrowdVoice_${phrase}_${System.currentTimeMillis()}.wav"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ → MediaStore API (sin permiso de almacenamiento)
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MUSIC + "/CrowdVoice")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }

                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("No se pudo crear el archivo en MediaStore")

                resolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().copyTo(out)
                }

                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)

            } else {
                // Android 9 y anteriores → acceso directo al sistema de archivos
                @Suppress("DEPRECATION")
                val musicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "CrowdVoice"
                )
                musicDir.mkdirs()
                val destFile = File(musicDir, fileName)
                sourceFile.copyTo(destFile, overwrite = true)

                // Notificar al escáner de medios
                MediaScannerConnection.scanFile(this, arrayOf(destFile.absolutePath), null, null)
            }

            showToast("✓ Guardado en Música/CrowdVoice/$fileName")
            Log.d(TAG, "Archivo guardado: $fileName")

        } catch (e: Exception) {
            Log.e(TAG, "Error al guardar el archivo", e)
            showToast("Error al guardar el archivo: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────
    // MANEJO DE ESTADOS DE UI
    // ─────────────────────────────────────────────────────────

    private fun setUiState(state: UiState) {
        runOnUiThread {
            when (state) {
                UiState.Initializing -> {
                    binding.btnGenerate.isEnabled = false
                    binding.btnPlay.isEnabled     = false
                    binding.btnSave.isEnabled     = false
                    binding.tvStatus.text         = "Iniciando motor de voz..."
                }
                UiState.Ready -> {
                    binding.btnGenerate.isEnabled = true
                    binding.btnPlay.isEnabled     = false
                    binding.btnSave.isEnabled     = false
                    binding.tvStatus.text         = "Listo. Escribe una frase y pulsa Generar."
                }
                UiState.Generating -> {
                    binding.btnGenerate.isEnabled = false
                    binding.btnPlay.isEnabled     = false
                    binding.btnSave.isEnabled     = false
                }
                UiState.Generated -> {
                    binding.btnGenerate.isEnabled = true
                    binding.btnPlay.isEnabled     = true
                    binding.btnSave.isEnabled     = true
                    binding.btnPlay.text          = "▶ Reproducir"
                    binding.tvStatus.text         = "Audio listo. Pulsa reproducir o guardar."
                }
                UiState.Playing -> {
                    binding.btnGenerate.isEnabled = false
                    binding.btnPlay.isEnabled     = true
                    binding.btnSave.isEnabled     = false
                    binding.tvStatus.text         = "Reproduciendo..."
                }
            }
        }
    }

    private fun showProgress(visible: Boolean, message: String = "") {
        runOnUiThread {
            binding.progressBar.visibility = if (visible) View.VISIBLE else View.GONE
            if (message.isNotBlank()) binding.tvStatus.text = message
        }
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
}
