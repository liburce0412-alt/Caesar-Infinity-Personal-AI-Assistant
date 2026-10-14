package com.campusai.features.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Only the app-created immutable PendingIntent can reach this non-exported receiver. */
class AiIslandActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AiIslandNotifications.handleStop(context, intent)
    }
}
