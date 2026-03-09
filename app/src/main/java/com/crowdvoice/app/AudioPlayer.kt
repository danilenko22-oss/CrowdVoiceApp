package com.crowdvoice.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AudioPlayer
 *
 * Reproduce un archivo WAV usando [AudioTrack] de Android.
 *
 * Se elige AudioTrack sobre MediaPlayer porque:
 *  - Permite control fino sobre los datos PCM.
 *  - Es más adecuado para audio generado programáticamente.
 *  - Facilita la integración futura de efectos en tiempo real.
 *
 * Uso:
 * ```kotlin
 * audioPlayer.play(wavFile)       // reproducir
 * audioPlayer.stop()              // detener
 * audioPlayer.isPlaying           // estado
 * ```
 *
 * Arquitectura preparada para futura extensión:
 *  - [setVolume] para control de volumen master
 *  - [seekTo] para reproducción desde posición arbitraria
 */
class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
        // Tamaño de chunk para escritura en AudioTrack (en muestras)
        private const val WRITE_CHUNK_SIZE = 4096
    }

    // Track de reproducción activo
    private var audioTrack: AudioTrack? = null

    // Estado de reproducción
    var isPlaying: Boolean = false
        private set

    // Callback notificado cuando la reproducción termina
    var onPlaybackComplete: (() -> Unit)? = null

    // ─────────────────────────────────────────────
    // REPRODUCCIÓN
    // ─────────────────────────────────────────────

    /**
     * Reproduce el archivo WAV [wavFile] de forma asíncrona.
     *
     * La función es suspendida; se ejecuta en [Dispatchers.IO] y retorna
     * cuando la reproducción ha terminado o se llamó [stop].
     *
     * @param wavFile Archivo WAV a reproducir (PCM-16, mono o estéreo)
     */
    suspend fun play(wavFile: File) = withContext(Dispatchers.IO) {
        if (isPlaying) {
            stop()
        }

        try {
            val wavData = WavUtils.readWav(wavFile)
            Log.d(TAG, "Reproduciendo: ${wavFile.name} — ${wavData.samples.size} muestras @ ${wavData.sampleRate} Hz")

            // Configurar AudioTrack
            val channelConfig = if (wavData.channels == 1)
                AudioFormat.CHANNEL_OUT_MONO
            else
                AudioFormat.CHANNEL_OUT_STEREO

            val minBufferSize = AudioTrack.getMinBufferSize(
                wavData.sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(wavData.sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 4)   // buffer generoso para estabilidad
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            track.play()
            isPlaying = true

            // Convertir ShortArray → ByteArray para escritura en chunks
            val byteData = shortArrayToByteArray(wavData.samples)

            var offset = 0
            val chunkBytes = WRITE_CHUNK_SIZE * 2   // 2 bytes por muestra Short

            while (offset < byteData.size && isPlaying) {
                val end   = minOf(offset + chunkBytes, byteData.size)
                val count = end - offset

                val written = track.write(byteData, offset, count)
                if (written < 0) {
                    Log.e(TAG, "Error en AudioTrack.write: $written")
                    break
                }

                offset += written
            }

            // Esperar a que el buffer del hardware termine de sonar
            if (isPlaying) track.stop()

            track.release()
            audioTrack = null
            isPlaying = false

            Log.d(TAG, "Reproducción completada")
            withContext(Dispatchers.Main) { onPlaybackComplete?.invoke() }

        } catch (e: Exception) {
            Log.e(TAG, "Error en reproducción", e)
            isPlaying = false
        }
    }

    // ─────────────────────────────────────────────
    // DETENCIÓN
    // ─────────────────────────────────────────────

    /**
     * Detiene la reproducción en curso.
     * Seguro de llamar aunque no haya reproducción activa.
     */
    fun stop() {
        isPlaying = false
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error al detener AudioTrack", e)
        } finally {
            audioTrack = null
        }
        Log.d(TAG, "Reproducción detenida")
    }

    // ─────────────────────────────────────────────
    // UTILIDADES
    // ─────────────────────────────────────────────

    /**
     * Convierte un [ShortArray] PCM-16 a [ByteArray] en orden little-endian,
     * formato requerido por [AudioTrack.write].
     */
    private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val s = shorts[i].toInt()
            bytes[i * 2]     = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    // ─────────────────────────────────────────────
    // EXTENSIONES FUTURAS
    // ─────────────────────────────────────────────

    /**
     * FUTURO: Ajustar volumen master de reproducción.
     * @param level Nivel entre 0.0 (silencio) y 1.0 (máximo)
     */
    fun setVolume(level: Float) {
        val safeLevel = level.coerceIn(0f, 1f)
        audioTrack?.setVolume(safeLevel)
    }

    /**
     * FUTURO: Avanzar a una posición específica (en milisegundos).
     * Requiere cambiar el modo a MODE_STATIC para acceso aleatorio.
     */
    @Suppress("UNUSED_PARAMETER")
    fun seekTo(positionMs: Int) {
        // TODO v2: Implementar seek con AudioTrack en modo estático
        Log.d(TAG, "seekTo() aún no implementado")
    }
}
