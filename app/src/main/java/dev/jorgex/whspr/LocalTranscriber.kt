package dev.jorgex.whspr

import java.io.File

class LocalTranscriber {
    fun transcribe(audioFile: File, modelFile: File, language: String): String? {
        if (!modelFile.exists()) return null
        if (!audioFile.exists()) return null
        return runCatching {
            NativeWhisper.transcribe(audioFile.absolutePath, modelFile.absolutePath, language)
        }.getOrNull()
    }
}

object NativeWhisper {
    private val available = runCatching {
        System.loadLibrary("whspr")
    }.isSuccess

    fun transcribe(audioPath: String, modelPath: String, language: String): String? {
        if (!available) return null
        return transcribeNative(audioPath, modelPath, language)?.trim()
    }

    @JvmStatic
    private external fun transcribeNative(audioPath: String, modelPath: String, language: String): String?
}
