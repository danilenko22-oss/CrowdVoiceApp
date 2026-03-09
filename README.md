# 🎤 CrowdVoice — Generador de Voz de Multitud para Android

Aplicación Android que convierte texto en audio de multitud simulada.
El usuario escribe una frase y la app genera el efecto de decenas (o cientos) de personas diciéndola al mismo tiempo.

---

## 📁 Estructura del Proyecto

```
CrowdVoiceApp/
├── app/
│   ├── src/main/
│   │   ├── java/com/crowdvoice/app/
│   │   │   ├── MainActivity.kt          ← UI + orquestación
│   │   │   ├── TTSManager.kt            ← Motor TextToSpeech
│   │   │   ├── CrowdAudioGenerator.kt   ← Motor de mezcla de audio
│   │   │   ├── AudioPlayer.kt           ← Reproducción con AudioTrack
│   │   │   └── WavUtils.kt              ← Lectura/escritura WAV
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml ← Diseño de la pantalla
│   │   │   ├── drawable/                ← Fondos y estilos
│   │   │   └── values/                  ← Strings, temas
│   │   └── AndroidManifest.xml
│   └── build.gradle                     ← Dependencias del módulo
├── build.gradle                         ← Configuración raíz
├── settings.gradle
└── gradle.properties
```

---

## ⚙️ Requisitos Previos

| Herramienta           | Versión mínima | Descarga |
|-----------------------|---------------|----------|
| Android Studio        | Hedgehog (2023.1.1) o superior | [developer.android.com/studio](https://developer.android.com/studio) |
| JDK                   | 17            | Incluido en Android Studio |
| Android SDK           | API 34 (Android 14) | Se instala desde Android Studio |
| Dispositivo / Emulador | Android 7.0+ (API 24) | — |

---

## 🚀 Cómo Compilar el Proyecto

### Opción A — Android Studio (recomendado)

1. **Clona o descarga** el proyecto en tu equipo:
   ```bash
   git clone https://github.com/tu-usuario/CrowdVoiceApp.git
   # o descomprime el ZIP descargado
   ```

2. **Abre Android Studio** → `File → Open` → selecciona la carpeta `CrowdVoiceApp`.

3. **Sincroniza Gradle**: Android Studio lo hará automáticamente.
   Si no, haz clic en `File → Sync Project with Gradle Files`.

4. **Instala el SDK** si se te solicita:
   - Ve a `Tools → SDK Manager`.
   - Marca **Android 14.0 (API 34)** y haz clic en `Apply`.

5. **Genera el APK de debug**:
   - Menú: `Build → Build Bundle(s) / APK(s) → Build APK(s)`
   - El APK se genera en:
     ```
     app/build/outputs/apk/debug/app-debug.apk
     ```

6. **Genera el APK de release** (firmado):
   - Menú: `Build → Generate Signed Bundle / APK`
   - Sigue el asistente para crear o usar un keystore.

### Opción B — Línea de comandos (Gradle)

```bash
# Entrar al directorio del proyecto
cd CrowdVoiceApp

# macOS / Linux
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug

# El APK queda en:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 📲 Cómo Instalar el APK en un Dispositivo Android

### Método 1 — Instalación directa desde Android Studio

1. Conecta tu teléfono por USB.
2. Activa **Opciones de Desarrollador** en tu teléfono:
   - `Ajustes → Acerca del teléfono → Número de compilación` (tocar 7 veces).
3. Habilita **Depuración USB** en las Opciones de Desarrollador.
4. En Android Studio, selecciona tu dispositivo en el menú desplegable (barra superior).
5. Pulsa el botón ▶ **Run** (o `Shift + F10`).

### Método 2 — ADB por línea de comandos

```bash
# Verificar que el dispositivo está conectado
adb devices

# Instalar el APK
adb install app/build/outputs/apk/debug/app-debug.apk

# Si ya estaba instalada una versión anterior
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Método 3 — Instalación manual en el teléfono

1. Copia el archivo `app-debug.apk` al teléfono (por USB, correo, Google Drive…).
2. En el teléfono, activa **"Instalar apps de fuentes desconocidas"**:
   - `Ajustes → Seguridad → Fuentes desconocidas` (Android 7-8)
   - `Ajustes → Aplicaciones → Instalar apps desconocidas` (Android 9+)
3. Abre el APK desde el explorador de archivos y sigue el asistente de instalación.

---

## 🎙️ Cómo Usar la App

1. **Escribe** una palabra o frase en el campo de texto (ej: "¡Campeón!").
2. **Elige el idioma**: Español o English.
3. **Ajusta el slider** para seleccionar cuántas voces tendrá la multitud (5–100).
4. Pulsa **🎙 GENERAR AUDIO DE MULTITUD**.
   - La app convierte el texto a voz (TTS) y mezcla N copias con variaciones aleatorias.
5. Pulsa **▶ Reproducir** para escuchar el resultado.
6. Pulsa **💾 Guardar WAV** para exportar el audio a `Música/CrowdVoice/`.

> ⚠️ **Nota**: Dispositivos sin el paquete de voz en español instalado usarán el idioma del sistema por defecto. Para instalar voces adicionales: `Ajustes → Accesibilidad → Texto a voz → Motor de voz`.

---

## 🧠 Cómo Funciona el Efecto de Multitud

```
Texto
  │
  ▼
[TTS Engine] ──→ tts_base.wav (voz limpia)
                        │
          ┌─────────────┴─────────────┐
          │   Para cada voz (1..N):   │
          │   ─ pitch_factor = rand(0.88..1.12)  │
          │   ─ amplitude    = rand(0.60..1.00)  │
          │   ─ delay_ms     = rand(0..300ms)    │
          │   ─ resample(base, factor)           │
          └─────────────┬─────────────┘
                        │ Suma en buffer Long
                        ▼
              [Normalización ÷ (N × 0.65)]
                        │
                        ▼
                  crowd_output.wav
```

**Re-muestreo de pitch**: Se usa interpolación lineal sobre las muestras PCM. Un factor > 1.0 comprime el audio (más agudo y rápido), un factor < 1.0 lo expande (más grave y lento). Esto simula variaciones naturales de voz entre personas.

**Mezcla con acumulador Long**: Sumar directamente valores `Short` causaría desbordamiento. Se usa un `LongArray` como acumulador y se normaliza al final.

---

## 🔧 Solución de Problemas Comunes

| Problema | Causa probable | Solución |
|----------|---------------|----------|
| "Error al inicializar motor de voz" | TTS no instalado | Instala `Google Text-to-Speech` desde Play Store |
| "Idioma no disponible" | Paquete de voz faltante | Descarga la voz en `Ajustes → Accesibilidad → TTS` |
| El audio suena distorsionado | Demasiadas voces (100) | Reduce a 20–50 voces |
| APK no se instala | "Fuentes desconocidas" desactivado | Ver instrucciones de instalación manual |
| Build falla con "SDK not found" | SDK no instalado | `Tools → SDK Manager` → instalar API 34 |

---

## 🗺️ Roadmap — Funciones Futuras

La arquitectura ya tiene los puntos de extensión preparados (marcados con `TODO v2` en el código):

- [ ] **Tipos de público**: estadio, niños, multitud grande (enum `AudienceType`)
- [ ] **Control de emoción**: gritando, normal, susurrando (enum `Emotion`)
- [ ] **Aplausos y gritos**: generación procedural de sonidos de multitud
- [ ] **Reverb / eco**: simulación de espacio acústico (estadio, sala, etc.)
- [ ] **Exportar a MP3**: compresión del WAV final
- [ ] **Separación pitch/velocidad**: algoritmo PSOLA o Phase Vocoder
- [ ] **TTS en la nube**: voces de mayor calidad via API (Google Cloud TTS, AWS Polly)
- [ ] **Widget de onda**: visualización de la forma de onda del audio generado

---

## 📄 Licencia

MIT License — Libre para uso personal y comercial.
