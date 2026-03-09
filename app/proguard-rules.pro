# proguard-rules.pro
# Reglas de ProGuard para CrowdVoice

# Mantener todas las clases del paquete principal (necesario para TTS callbacks)
-keep class com.crowdvoice.app.** { *; }

# Mantener UtteranceProgressListener (callbacks de TTS)
-keep class android.speech.tts.** { *; }
