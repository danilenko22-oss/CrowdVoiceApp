package com.crowdvoice.app

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WavUtils
 *
 * Utilidades para leer y escribir archivos WAV (PCM de 16 bits).
 * Soporta lectura del encabezado WAV para extraer metadatos (sampleRate, canales, bits)
 * y lectura/escritura del buffer de muestras PCM crudas (ShortArray).
 *
 * Formato WAV esperado:
 *  - PCM sin compresión (audioFormat = 1)
 *  - 16 bits por muestra
 *  - 1 canal (mono) — el TTS de Android genera mono por defecto
 */
object WavUtils {

    // ─────────────────────────────────────────────
    // DATA CLASS para el contenido completo de un WAV
    // ─────────────────────────────────────────────
    data class WavData(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val samples: ShortArray        // muestras PCM 16-bit
    )

    // ─────────────────────────────────────────────
    // LECTURA
    // ─────────────────────────────────────────────

    /**
     * Lee un archivo WAV y devuelve [WavData] con los metadatos y muestras PCM.
     * Lanza [IllegalArgumentException] si el formato no es PCM-16 válido.
     */
    fun readWav(file: File): WavData {
        FileInputStream(file).use { fis ->
            val header = ByteArray(44)
            fis.read(header)

            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF chunk
            val riff = String(header, 0, 4)
            require(riff == "RIFF") { "No es un archivo WAV válido (RIFF faltante)" }

            buf.position(8)
            val wave = String(header, 8, 4)
            require(wave == "WAVE") { "No es un archivo WAV válido (WAVE faltante)" }

            // fmt sub-chunk
            buf.position(16)
            val fmtSize     = buf.int          // posición 16 → debe ser 16
            val audioFormat = buf.short.toInt() // posición 20 → 1 = PCM
            val channels    = buf.short.toInt() // posición 22
            val sampleRate  = buf.int           // posición 24
            /* byteRate */    buf.int
            /* blockAlign */  buf.short
            val bitsPerSample = buf.short.toInt() // posición 34

            require(audioFormat == 1) { "Solo se soporta PCM sin compresión (audioFormat=$audioFormat)" }
            require(bitsPerSample == 16) { "Solo se soportan 16 bits por muestra" }

            // data sub-chunk (puede que haya sub-chunks extra entre fmt y data)
            // Buscamos el marcador "data"
            val dataSize = findDataChunk(fis)
            val dataBytes = ByteArray(dataSize)
            fis.read(dataBytes)

            // Convertir bytes → ShortArray (little-endian)
            val samples = ShortArray(dataSize / 2)
            val dataBuf = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in samples.indices) {
                samples[i] = dataBuf.short
            }

            return WavData(sampleRate, channels, bitsPerSample, samples)
        }
    }

    /**
     * Avanza el [FileInputStream] hasta encontrar el sub-chunk "data"
     * y devuelve su tamaño en bytes.
     */
    private fun findDataChunk(fis: FileInputStream): Int {
        val idBuf   = ByteArray(4)
        val sizeBuf = ByteArray(4)
        while (true) {
            val read = fis.read(idBuf)
            if (read < 4) throw IllegalStateException("Sub-chunk 'data' no encontrado en el WAV")
            fis.read(sizeBuf)
            val chunkId   = String(idBuf)
            val chunkSize = ByteBuffer.wrap(sizeBuf).order(ByteOrder.LITTLE_ENDIAN).int
            if (chunkId == "data") return chunkSize
            // Saltar este sub-chunk desconocido
            fis.skip(chunkSize.toLong())
        }
    }

    // ─────────────────────────────────────────────
    // ESCRITURA
    // ─────────────────────────────────────────────

    /**
     * Escribe un [WavData] en [file] como PCM-16 WAV con encabezado estándar de 44 bytes.
     */
    fun writeWav(file: File, wavData: WavData) {
        val dataSize    = wavData.samples.size * 2   // 2 bytes por muestra
        val fileSize    = 36 + dataSize
        val byteRate    = wavData.sampleRate * wavData.channels * (wavData.bitsPerSample / 8)
        val blockAlign  = wavData.channels * (wavData.bitsPerSample / 8)

        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF
            header.put("RIFF".toByteArray())
            header.putInt(fileSize)
            header.put("WAVE".toByteArray())

            // fmt
            header.put("fmt ".toByteArray())
            header.putInt(16)                           // tamaño del sub-chunk fmt
            header.putShort(1)                          // PCM = 1
            header.putShort(wavData.channels.toShort())
            header.putInt(wavData.sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(wavData.bitsPerSample.toShort())

            // data
            header.put("data".toByteArray())
            header.putInt(dataSize)

            fos.write(header.array())

            // Muestras PCM
            val dataBuf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in wavData.samples) {
                dataBuf.putShort(sample)
            }
            fos.write(dataBuf.array())
        }
    }
}
