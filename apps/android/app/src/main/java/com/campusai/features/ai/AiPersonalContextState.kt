package com.campusai.features.ai

import com.campusai.core.health.HealthAvailability
import com.campusai.core.health.HealthSnapshot

data class CaesarHealthUiState(
    val loading: Boolean = false,
    val miFitnessConfigured: Boolean = false,
    val miFitnessSyncing: Boolean = false,
    val miFitnessStatus: MiFitnessUiStatus = MiFitnessUiStatus.IDLE,
    val miFitnessLastSyncAt: Long? = null,
    val miFitnessFormResetKey: Long = 0L,
    val availability: HealthAvailability? = null,
    val grantedPermissionCount: Int = 0,
    val requiredPermissionCount: Int = 0,
    val snapshot: HealthSnapshot? = null,
    val healthError: String? = null,
    val actionMessage: String? = null,
)

enum class MiFitnessUiStatus {
    IDLE,
    VALIDATING,
    REFRESHING,
    DELETING,
    SUCCESS,
    NO_DATA,
    AUTH_ERROR,
    NETWORK_ERROR,
    STORAGE_ERROR,
}

data class CaesarMemoryUiItem(
    val id: String,
    val type: String,
    val content: String,
    val confirmed: Boolean,
    val expiresAt: Long?,
)
