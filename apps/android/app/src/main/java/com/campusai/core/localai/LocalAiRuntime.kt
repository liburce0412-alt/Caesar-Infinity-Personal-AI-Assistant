package com.campusai.core.localai

import android.content.Context

/** One native model owner for chat and automation; the engine releases idle and pressured memory. */
class LocalAiRuntime private constructor(context: Context) {
    val manager = LocalModelManager(context.applicationContext)
    val engine = LocalMnnAiEngine(context.applicationContext, manager)

    companion object {
        @Volatile private var instance: LocalAiRuntime? = null

        /** Initialize on the main thread because LocalModelManager observes WorkManager LiveData. */
        fun get(context: Context): LocalAiRuntime = instance ?: synchronized(this) {
            instance ?: LocalAiRuntime(context.applicationContext).also { instance = it }
        }
    }
}
