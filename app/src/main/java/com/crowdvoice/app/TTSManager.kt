package com.crowdvoice.app

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * TTSManager
 *
 * Encapsula el ciclo de vida del motor TextToSpeech de Android.
 *
 * Responsabilidades:
 *  - Inicializar el motor TTS de forma asíncrona.
 *  - Cambiar el idioma (Español / Inglés).
 *  - Sintetizar texto a un archivo WAV temporal.
 *  - Exponer el estado de disponibilidad mediante [isReady].
 *
 * Arquitectura preparada para futura extensión:
 *  - [setEmotion] reservado para cuando se soporte control de emoción (gritando, susurrando, etc.)
 *  - [setVoiceType] reservado para selección de tipo de voz
 */
class TTSManager(private val context: Context) {

    companion object {
        private const val TAG = "TTSManager"
        const val UTTERANCE_ID = "crowd_voice_tts"
    }

    // Motor TTS de Android
    private var tts: TextToSpeech? = null

    // true cuando el motor está inicializado y listo para sintetizar
    var isReady: Boolean = false
        private set

    // Locale actualmente configurado
    private var currentLocale: Locale = Locale.getDefault()

    // ─────────────────────────────────────────────
    // INICIALIZACIÓN
    // ─────────────────────────────────────────────

    /**
     * Inicializa el motor TTS de forma asíncrona con una corrutina suspendida.
     * Devuelve true si la inicialización fue exitosa, false en caso contrario.
     *
     * Debe llamarse desde un contexto de corrutina (ej: lifecycleScope).
     */
    suspend fun initialize(): Boolean = suspendCoroutine { continuation ->
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                Log.d(TAG, "Motor TTS inicializado correctamente")
                continuation.resume(true)
            } else {
                isReady = false
                Log.e(TAG, "Error al inicializar el motor TTS (status=$status)")
                continuation.resume(false)
            }
        }
    }

    // ─────────────────────────────────────────────
    // CONFIGURACIÓN DE IDIOMA
    // ─────────────────────────────────────────────

    /**
     * Cambia el idioma del motor TTS.
     *
     * @param locale Idioma deseado. Actualmente soportado: [Locale.ENGLISH], [Locale("es")]
     * @return true si el idioma fue aceptado por el motor instalado en el dispositivo.
     */
    fun setLanguage(locale: Locale): Boolean {
        if (!isReady) return false
        val result = tts?.setLanguage(locale)
        return when (result) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> {
                currentLocale = locale
                Log.d(TAG, "Idioma configurado: ${locale.displayName}")
                true
            }
            TextToSpeech.LANG_MISSING_DATA -> {
                Log.w(TAG, "Datos de idioma no instalados para: ${locale.displayName}")
                false
            }
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                Log.w(TAG, "Idioma no soportado: ${locale.displayName}")
                false
            }
            else -> false
        }
    }

    // ─────────────────────────────────────────────
    // SÍNTESIS A ARCHIVO
    // ─────────────────────────────────────────────

    /**
     * Sintetiza [text] a un archivo WAV temporal usando el motor TTS.
     *
     * La función es suspendida: espera a que el TTS termine de escribir el archivo
     * antes de devolver el control.
     *
     * @param text   Texto a sintetizar.
     * @param outDir Directorio donde se guardará el archivo temporal.
     * @return       [File] con el WAV generado, o null si falló la síntesis.
     */
    suspend fun synthesizeToFile(text: String, outDir: File): File? {
        if (!isReady) {
            Log.e(TAG, "TTS no está listo, inicializa primero con initialize()")
            return null
        }

        val outputFile = File(outDir, "tts_base_${System.currentTimeMillis()}.wav")

        return suspendCoroutine { continuation ->
            // Listener que nos avisa cuando el TTS termina de escribir el archivo
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS síntesis iniciada: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == UTTERANCE_ID) {
                        Log.d(TAG, "TTS síntesis completada → ${outputFile.absolutePath}")
                        continuation.resume(if (outputFile.exists()) outputFile else null)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "Error en TTS síntesis: $utteranceId")
                    continuation.resume(null)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(TAG, "Error en TTS síntesis: $utteranceId, código=$errorCode")
                    continuation.resumeWithException(
                        RuntimeException("TTS error código $errorCode")
                    )
                }
            })

            // Parámetros de síntesis
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
            }

            val result = tts?.synthesizeToFile(text, params, outputFile, UTTERANCE_ID)
            if (result != TextToSpeech.SUCCESS) {
                Log.e(TAG, "synthesizeToFile devolvió error: $result")
                continuation.resume(null)
            }
        }
    }

    // ─────────────────────────────────────────────
    // EXTENSIONES FUTURAS (arquitectura preparada)
    // ─────────────────────────────────────────────

    /**
     * FUTURO: Control de emoción de la voz.
     * Por ahora no implementado. Se integrará cuando se agregue
     * soporte para "gritando", "susurrando", "normal".
     */
    @Suppress("UNUSED_PARAMETER")
    fun setEmotion(emotion: VoiceEmotion) {
        Log.d(TAG, "setEmotion() aún no implementado: $emotion")
        // TODO: Ajustar pitch/rate del TTS según la emoción
    }

    /**
     * FUTURO: Tipo de voz / tipo de público.
     * Para futura selección de "estadio", "niños", "multitud grande".
     */
    @Suppress("UNUSED_PARAMETER")
    fun setVoiceType(type: VoiceType) {
        Log.d(TAG, "setVoiceType() aún no implementado: $type")
        // TODO: Seleccionar voz del dispositivo según el tipo
    }

    // ─────────────────────────────────────────────
    // CICLO DE VIDA
    // ─────────────────────────────────────────────

    /**
     * Libera los recursos del motor TTS.
     * Llamar en [Activity.onDestroy].
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        Log.d(TAG, "Motor TTS liberado")
    }

    // ─────────────────────────────────────────────
    // ENUMS PARA EXTENSIONES FUTURAS
    // ─────────────────────────────────────────────

    /** Tipos de emoción vocal (para versiones futuras) */
    enum class VoiceEmotion { NORMAL, SHOUTING, WHISPERING }

    /** Tipos de público/voz (para versiones futuras) */
    enum class VoiceType { STADIUM, CHILDREN, LARGE_CROWD, GENERIC }
}
