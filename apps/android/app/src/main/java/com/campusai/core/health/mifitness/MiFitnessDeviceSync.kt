package com.campusai.core.health.mifitness

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** REQUESTED means Android accepted the broadcast, not that the band finished syncing. */
enum class MiFitnessDeviceSyncStatus {
    REQUESTED,
    APP_UNAVAILABLE,
    APP_STOPPED,
    BLOCKED,
}

class MiFitnessDeviceSync(context: Context) {
    private val context = context.applicationContext

    fun request(): MiFitnessDeviceSyncStatus = try {
        @Suppress("DEPRECATION")
        val app = context.packageManager.getApplicationInfo(PACKAGE_NAME, 0)
        when {
            !app.enabled -> MiFitnessDeviceSyncStatus.APP_UNAVAILABLE
            app.flags and ApplicationInfo.FLAG_STOPPED != 0 -> MiFitnessDeviceSyncStatus.APP_STOPPED
            else -> {
                // The 3.58.0 widget receiver is dynamic. Never force-start an Activity here.
                context.sendBroadcast(Intent(ACTION_REQUEST_SYNC).setPackage(PACKAGE_NAME))
                MiFitnessDeviceSyncStatus.REQUESTED
            }
        }
    } catch (_: PackageManager.NameNotFoundException) {
        MiFitnessDeviceSyncStatus.APP_UNAVAILABLE
    } catch (_: RuntimeException) {
        MiFitnessDeviceSyncStatus.BLOCKED
    }

    fun openApp(): Boolean = try {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
        if (intent == null) false else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    } catch (_: RuntimeException) {
        false
    }

    companion object {
        const val PACKAGE_NAME = "com.mi.health"
        internal const val ACTION_REQUEST_SYNC = "com.mi.health.widget.action.request.sync"
    }
}
