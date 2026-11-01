@file:Suppress("unused", "UNUSED_PARAMETER")
package com.google.mediapipe.tasks.genai.llminference

import android.content.Context

class LlmInference : AutoCloseable {
    fun generateResponse(prompt: String): String = TODO()
    override fun close() {}
    class LlmInferenceOptions {
        abstract class Builder {
            abstract fun setModelPath(p: String): Builder
            abstract fun setMaxTokens(n: Int): Builder
            abstract fun build(): LlmInferenceOptions
        }
        companion object { @JvmStatic fun builder(): Builder = TODO() }
    }
    companion object { @JvmStatic fun createFromOptions(ctx: Context, o: LlmInferenceOptions): LlmInference = TODO() }
}
