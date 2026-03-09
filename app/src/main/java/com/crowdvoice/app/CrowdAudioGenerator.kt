package com.crowdvoice.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.random.Random

/**
 * CrowdAudioGenerator
 *
 * ============================================================
 * Motor principal del efecto de multitud.
 * ============================================================
 *
 * Recibe un archivo WAV base (generado por TTS) y produce un nuevo WAV
 * donde se mezclan N "voces" simuladas, cada una con:
 *
 *   1. Retardo aleatorio       → 0–300 ms de silencio al inicio
 *   2. Variación de pitch      → re-muestreo simple ±12%
 *      (un pitch más alto = muestras comprimidas, más grave = expandidas)
 *   3. Variación de volumen    → amplitud entre 60% y 100%
 *   4. Variación de velocidad  → factor entre 0.88 y 1.12
 *
 * La mezcla se realiza sumando todas las pistas en un buffer de Long
 * para evitar desbordamiento, y luego normaliza a 16 bits.
 *
 * ──────────────────────────────────────────────────────────────
 * Arquitectura preparada para futuras extensiones:
 *  - [CrowdConfig.audienceType]  → tipo de público (estadio, niños, etc.)
 *  - [CrowdConfig.emotion]       → emoción (gritando, normal, susurrando)
 *  - Método [addAmbientNoise]    → para reverb/ruido ambiental futuro
 * ──────────────────────────────────────────────────────────────
 */
class CrowdAudioGenerator {

    companion object {
        private const val TAG = "CrowdAudioGen"

        // Rango de retardo inicial por voz (en milisegundos)
        private const val MIN_DELAY_MS = 0
        private const val MAX_DELAY_MS = 300

        // Rango de variación de pitch/velocidad (factor de re-muestreo)
        // 1.0 = sin cambio, 0.88 = más lento/grave, 1.12 = más rápido/agudo
        private const val MIN_PITCH_FACTOR = 0.88f
        private const val MAX_PITCH_FACTOR = 1.12f

        // Rango de amplitud por voz (volumen relativo)
        private const val MIN_AMPLITUDE = 0.60f
        private const val MAX_AMPLITUDE = 1.00f
    }

    // ─────────────────────────────────────────────
    // CONFIGURACIÓN DE LA MULTITUD
    // ─────────────────────────────────────────────

    /**
     * Parámetros de generación de la multitud.
     *
     * @param voiceCount     Número de voces a simular (5–100)
     * @param audienceType   FUTURO: tipo de público
     * @param emotion        FUTURO: emoción de la multitud
     */
    data class CrowdConfig(
        val voiceCount: Int = 20,
        // ── Campos preparados para versiones futuras ──
        val audienceType: AudienceType = AudienceType.GENERIC,
        val emotion: Emotion = Emotion.NORMAL
    )

    /** FUTURO: Tipos de público */
    enum class AudienceType { GENERIC, STADIUM, CHILDREN, LARGE_CROWD }

    /** FUTURO: Tipos de emoción */
    enum class Emotion { NORMAL, SHOUTING, WHISPERING }

    // ─────────────────────────────────────────────
    // FUNCIÓN PRINCIPAL
    // ─────────────────────────────────────────────

    /**
     * Genera el audio de multitud a partir de [baseTtsFile].
     *
     * Esta función es suspendida y se ejecuta en [Dispatchers.Default]
     * para no bloquear el hilo principal.
     *
     * @param baseTtsFile  Archivo WAV con la voz TTS base.
     * @param config       Configuración de la multitud.
     * @param outputFile   Archivo WAV donde se escribirá el resultado.
     */
    suspend fun generate(
        baseTtsFile: File,
        config: CrowdConfig,
        outputFile: File
    ): Boolean = withContext(Dispatchers.Default) {
        try {
            Log.d(TAG, "Iniciando generación: ${config.voiceCount} voces")

            // 1. Leer WAV base
            val baseWav = WavUtils.readWav(baseTtsFile)
            Log.d(TAG, "WAV base leído: ${baseWav.samples.size} muestras, ${baseWav.sampleRate} Hz")

            val sampleRate = baseWav.sampleRate

            // Muestras de silencio equivalentes a MAX_DELAY_MS
            val maxDelaySamples = msToSamples(MAX_DELAY_MS, sampleRate)

            // 2. Generar cada "voz" de la multitud y acumular en buffer largo
            // El buffer tiene espacio para el audio más largo posible + el retardo máximo
            val maxVoiceLength = estimateMaxVoiceLength(baseWav.samples.size, config.voiceCount)
            val mixBuffer = LongArray(maxDelaySamples + maxVoiceLength + sampleRate)

            for (i in 0 until config.voiceCount) {
                val voice = generateSingleVoice(baseWav.samples, sampleRate)
                accumulateVoice(mixBuffer, voice)
                Log.v(TAG, "Voz $i generada (${voice.samples.size} muestras, delay=${voice.delayMs}ms)")
            }

            // 3. Normalizar y convertir de Long a Short (16-bit PCM)
            val finalSamples = normalizeBuffer(mixBuffer, config.voiceCount)

            // 4. FUTURO: aquí se agregaría ruido ambiental / reverb
            // addAmbientNoise(finalSamples, config.audienceType)

            // 5. Escribir WAV resultante
            val resultWav = WavUtils.WavData(
                sampleRate    = sampleRate,
                channels      = 1,
                bitsPerSample = 16,
                samples       = finalSamples
            )
            WavUtils.writeWav(outputFile, resultWav)

            Log.d(TAG, "Audio de multitud generado → ${outputFile.absolutePath}")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Error generando audio de multitud", e)
            false
        }
    }

    // ─────────────────────────────────────────────
    // GENERACIÓN DE UNA VOZ INDIVIDUAL
    // ─────────────────────────────────────────────

    /**
     * Contenedor de una voz procesada lista para mezclar.
     */
    private data class ProcessedVoice(
        val samples: ShortArray,    // muestras PCM después de pitch shift
        val delayMs: Int,           // retardo de inicio en ms
        val delaySamples: Int,      // retardo en número de muestras
        val amplitude: Float        // factor de volumen aplicado
    )

    /**
     * Genera una sola "voz de la multitud" a partir de las muestras base.
     *
     * Aplica:
     *  - Pitch/velocidad aleatorio (re-muestreo por interpolación lineal)
     *  - Amplitud aleatoria
     *  - Retardo aleatorio
     */
    private fun generateSingleVoice(
        baseSamples: ShortArray,
        sampleRate: Int
    ): ProcessedVoice {

        // Factor de re-muestreo: < 1.0 → más lento/grave, > 1.0 → más rápido/agudo
        val pitchFactor = Random.nextFloat() * (MAX_PITCH_FACTOR - MIN_PITCH_FACTOR) + MIN_PITCH_FACTOR
        val amplitude   = Random.nextFloat() * (MAX_AMPLITUDE - MIN_AMPLITUDE) + MIN_AMPLITUDE
        val delayMs     = Random.nextInt(MIN_DELAY_MS, MAX_DELAY_MS + 1)
        val delaySamples = msToSamples(delayMs, sampleRate)

        // Re-muestreo por interpolación lineal
        val resampledSamples = resample(baseSamples, pitchFactor)

        // Aplicar amplitud
        val amplifiedSamples = ShortArray(resampledSamples.size) { i ->
            (resampledSamples[i] * amplitude).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

        return ProcessedVoice(
            samples      = amplifiedSamples,
            delayMs      = delayMs,
            delaySamples = delaySamples,
            amplitude    = amplitude
        )
    }

    // ─────────────────────────────────────────────
    // RE-MUESTREO (PITCH / VELOCIDAD)
    // ─────────────────────────────────────────────

    /**
     * Re-muestrea [input] por el [factor] dado usando interpolación lineal.
     *
     * factor > 1.0 → el audio resultante es más corto (pitch más alto / más rápido)
     * factor < 1.0 → el audio resultante es más largo (pitch más bajo / más lento)
     *
     * Este método cambia pitch y velocidad simultáneamente (time-domain resampling).
     * Para una separación real de pitch y velocidad se requeriría un algoritmo
     * PSOLA o phase vocoder (complejidad adicional, preparado para v2).
     */
    private fun resample(input: ShortArray, factor: Float): ShortArray {
        if (factor == 1.0f) return input.copyOf()

        val outputSize = (input.size / factor).toInt()
        val output = ShortArray(outputSize)

        for (i in 0 until outputSize) {
            val srcPos = i * factor
            val srcIdx = srcPos.toInt()
            val frac   = srcPos - srcIdx

            val s0 = input.getOrElse(srcIdx)     { 0 }.toFloat()
            val s1 = input.getOrElse(srcIdx + 1) { 0 }.toFloat()

            // Interpolación lineal entre muestra srcIdx y srcIdx+1
            val interpolated = s0 + frac * (s1 - s0)
            output[i] = interpolated.toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

        return output
    }

    // ─────────────────────────────────────────────
    // MEZCLA (ACUMULACIÓN EN BUFFER)
    // ─────────────────────────────────────────────

    /**
     * Suma las muestras de [voice] en [mixBuffer] con el retardo correspondiente.
     * Se usa Long para el acumulador y así evitar desbordamiento de 16 bits.
     */
    private fun accumulateVoice(mixBuffer: LongArray, voice: ProcessedVoice) {
        val offset = voice.delaySamples
        val limit  = min(offset + voice.samples.size, mixBuffer.size)
        for (i in offset until limit) {
            mixBuffer[i] += voice.samples[i - offset].toLong()
        }
    }

    // ─────────────────────────────────────────────
    // NORMALIZACIÓN
    // ─────────────────────────────────────────────

    /**
     * Normaliza el [mixBuffer] (suma de N voces) al rango de 16 bits.
     *
     * Estrategia: dividir por (voiceCount * NORMALIZATION_FACTOR) para que
     * la mezcla suene densa pero sin recorte.
     * El factor 0.65 fue elegido empíricamente para dejar headroom suficiente
     * considerando que las voces están desplazadas en tiempo (no todas al unísono).
     */
    private fun normalizeBuffer(mixBuffer: LongArray, voiceCount: Int): ShortArray {
        // Encontrar el último índice con datos reales
        var lastNonZero = mixBuffer.size - 1
        while (lastNonZero > 0 && mixBuffer[lastNonZero] == 0L) lastNonZero--
        val length = lastNonZero + 1

        // Factor de normalización: más voces → divisor mayor para evitar clipping
        val divisor = (voiceCount * 0.65).toFloat().coerceAtLeast(1f)

        return ShortArray(length) { i ->
            (mixBuffer[i] / divisor)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    // ─────────────────────────────────────────────
    // UTILIDADES
    // ─────────────────────────────────────────────

    /** Convierte milisegundos a número de muestras dado el [sampleRate] */
    private fun msToSamples(ms: Int, sampleRate: Int): Int =
        (ms * sampleRate / 1000.0).toInt()

    /** Estima el largo máximo de una voz re-muestreada */
    private fun estimateMaxVoiceLength(baseSamples: Int, voiceCount: Int): Int =
        // Con el factor mínimo de pitch (0.88), la salida es baseSamples/0.88 ≈ baseSamples*1.14
        (baseSamples * (1.0 / MIN_PITCH_FACTOR) + 1).toInt()

    // ─────────────────────────────────────────────
    // EXTENSIONES FUTURAS (stubs preparados)
    // ─────────────────────────────────────────────

    /**
     * FUTURO: Añade ruido ambiental / reverberación según el tipo de público.
     * Por ahora no implementado.
     *
     * @param samples      Buffer de muestras a procesar in-place
     * @param audienceType Tipo de público seleccionado
     */
    @Suppress("UNUSED_PARAMETER")
    private fun addAmbientNoise(samples: ShortArray, audienceType: AudienceType) {
        // TODO v2: Aplicar reverb de sala / estadio
        // TODO v2: Añadir murmullos de fondo o aplausos de base
    }

    /**
     * FUTURO: Aplica modificadores de emoción al buffer de mezcla.
     * Ej: SHOUTING → satura ligeramente, WHISPERING → reduce amplitud.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun applyEmotionModifiers(samples: ShortArray, emotion: Emotion) {
        // TODO v2: Filtros pasa-altos para SHOUTING, pasa-bajos para WHISPERING
    }
}
