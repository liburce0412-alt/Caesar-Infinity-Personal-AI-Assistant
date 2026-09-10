package com.campusai.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MotionPhotosOff
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.campusai.core.designsystem.SpectraTextField
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campusai.core.designsystem.BrandMark
import com.campusai.core.designsystem.GlassPanel
import com.campusai.core.designsystem.SpectraColors
import com.campusai.core.designsystem.SlideConfirm
import com.campusai.core.designsystem.SpectraPrimaryButton
import com.campusai.core.designsystem.SpectraAlertDialog
import com.campusai.core.designsystem.SpectraDialog
import com.campusai.core.designsystem.SpectraModalBottomSheet
import com.campusai.core.designsystem.SpectraTheme
import com.campusai.core.designsystem.SpectraVisualStyle
import com.campusai.core.designsystem.TelemetryChip
import com.campusai.core.designsystem.Tomorrow
import com.campusai.core.auth.AuthState
import com.campusai.core.model.MotionMode
import com.campusai.core.model.AiMode
import com.campusai.core.model.AiProvider
import com.campusai.core.model.LocalModelState
import com.campusai.core.model.RenderQuality
import com.campusai.core.model.SpectraEnvironment
import com.campusai.core.model.ThemeMode
import com.campusai.core.model.TimeRecord
import com.campusai.core.model.ContributionLevel
import com.campusai.core.model.DailyContribution
import com.campusai.core.model.DailyContributionCalculator
import com.campusai.core.preferences.UserPreferences
import com.campusai.core.preferences.UserPreferencesRepository
import com.campusai.core.localai.LocalMnnAiEngine
import com.campusai.core.localai.LocalModelManifest
import com.campusai.core.localai.LocalModelManager
import com.campusai.core.localai.LocalModelMode
import com.campusai.core.network.CloudProviderException
import com.campusai.core.profile.CampusProfile
import com.campusai.core.profile.ProfileImageKind
import com.campusai.core.profile.ProfileRepository
import com.campusai.core.ai.CloudAiProvider
import com.campusai.core.ai.CloudProviderConnection
import com.campusai.core.ai.CloudProviderModel
import com.campusai.core.automation.ScheduledTaskConfig
import com.campusai.core.automation.ScheduledTaskRunStatus
import com.campusai.core.security.PersonalAiProviderStore
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

@Composable
fun ProfileScreen(
    preferences: UserPreferences,
    repository: UserPreferencesRepository,
    records: List<TimeRecord>,
    authState: AuthState,
    unreadMessages: Int,
    onLogin: () -> Unit,
    onSignOut: () -> Unit,
    onOpenMessages: () -> Unit,
    localModelManager: LocalModelManager,
    localAiEngine: LocalMnnAiEngine,
    personalAiProviderStore: PersonalAiProviderStore,
    profileRepository: ProfileRepository,
    contentPadding: PaddingValues,
    dailyTargetSnapshots: Map<LocalDate, Long> = emptyMap(),
    onOpenTimeRecordsForDay: ((LocalDate) -> Unit)? = null,
    miFitnessConfigured: Boolean = false,
    miFitnessSyncing: Boolean = false,
    miFitnessLastSyncAtMillis: Long? = null,
    miFitnessStatus: MiFitnessSettingsStatus = MiFitnessSettingsStatus.IDLE,
    miFitnessFormResetKey: Long = 0L,
    onSaveMiFitnessCredentials: (userId: String, passToken: String) -> Unit = { _, _ -> },
    onRefreshMiFitnessSteps: () -> Unit = {},
    onDeleteMiFitnessCredentials: () -> Unit = {},
    onTestCloudProviderConnection: (suspend (CloudAiProvider, String) -> Result<CloudProviderConnection>)? = null,
    onListCloudProviderModels: (suspend (CloudAiProvider) -> Result<List<CloudProviderModel>>)? = null,
    healthAutomationConfig: ScheduledTaskConfig? = null,
    healthAutomationSaving: Boolean = false,
    healthAutomationNotificationsEnabled: Boolean = true,
    healthAutomationMessage: String? = null,
    healthAutomationMessageIsError: Boolean = false,
    onSaveHealthAutomation: (CloudAiProvider, String, Int, Boolean) -> Unit = { _, _, _, _ -> },
    onDisableHealthAutomation: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val layout = SpectraTheme.layout
    val cardRadius = SpectraTheme.tokens.radii.card.value.roundToInt()
    val profileState by profileRepository.state.collectAsState()
    val profile = profileState.profile
    val totalHours = records.sumOf { it.durationMinutes } / 60
    val level = maxOf(profile.level, (totalHours / 10 + 1).coerceAtMost(99).toInt())
    var sheet by remember { mutableStateOf<ProfileSheet?>(null) }
    val fallbackName = authState.email.substringBefore('@').ifBlank { "Caesar 用户" }
    val achievements = remember(records, profile.streakDays) { buildAchievements(records, profile.streakDays) }

    LaunchedEffect(authState.signedIn, authState.userId) {
        profileRepository.load(
            userId = authState.userId.takeIf { authState.signedIn }.orEmpty(),
            fallbackName = fallbackName,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = layout.pageHorizontalPadding,
            end = layout.pageHorizontalPadding,
            top = contentPadding.calculateTopPadding() + layout.pageTopSpacing,
            bottom = maxOf(contentPadding.calculateBottomPadding(), layout.pageBottomSpacing),
        ),
        verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
    ) {
        item {
            ProfileHero(profile, fallbackName, level, totalHours * 10) {
                if (authState.signedIn) sheet = ProfileSheet.EDIT else onLogin()
            }
        }
        item {
            GlassPanel(Modifier.fillMaxWidth().height(86.dp), radius = cardRadius, shadowed = !SpectraTheme.isFluid) {
                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatCell("$totalHours", "累计小时", Modifier.weight(1f))
                    VerticalRule()
                    StatCell(records.size.toString(), "时间记录", Modifier.weight(1f))
                    VerticalRule()
                    StatCell(profile.streakDays.coerceAtLeast(currentStreak(records)).toString(), "连续天数", Modifier.weight(1f))
                }
            }
        }
        item {
            AnnualContributionCard(
                records = records,
                targetSnapshots = dailyTargetSnapshots,
                onOpenDay = onOpenTimeRecordsForDay,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("成就", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { sheet = ProfileSheet.ACHIEVEMENTS }) { Text("查看全部 ${achievements.count { it.unlocked }}/${achievements.size}") }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(achievements) { AchievementCard(it) }
            }
        }
        item { Text("快捷入口", style = MaterialTheme.typography.titleLarge) }
        item {
            GlassPanel(Modifier.fillMaxWidth(), radius = cardRadius, shadowed = !SpectraTheme.isFluid) {
                Column {
                    SettingLink(Icons.Rounded.Edit, "编辑资料", "头像、名称、简介与背景") { if (authState.signedIn) sheet = ProfileSheet.EDIT else onLogin() }
                    DividerInset()
                    SettingLink(Icons.Rounded.Cloud, "AI 运行方式", aiProviderLabel(preferences.aiProvider)) { sheet = ProfileSheet.AI }
                    DividerInset()
                    SettingLink(
                        Icons.Rounded.Cloud,
                        "Mi Fitness",
                        miFitnessEntrySubtitle(miFitnessConfigured, miFitnessSyncing, miFitnessLastSyncAtMillis, miFitnessStatus),
                    ) { sheet = ProfileSheet.MI_FITNESS }
                    DividerInset()
                    SettingLink(
                        Icons.Rounded.Notifications,
                        "健康自动化",
                        healthAutomationEntrySubtitle(healthAutomationConfig),
                    ) { sheet = ProfileSheet.HEALTH_AUTOMATION }
                    DividerInset()
                    SettingLink(Icons.Rounded.Palette, "组件与内容", "折叠暂时用不上的首页卡片") { sheet = ProfileSheet.COMPONENTS }
                    DividerInset()
                    SettingLink(Icons.Rounded.Palette, "外观与体验", environmentLabel(preferences.environment)) { sheet = ProfileSheet.APPEARANCE }
                    DividerInset()
                    SettingLink(Icons.Rounded.Forum, if (unreadMessages > 0) "消息 · $unreadMessages 条未读" else "消息", onClick = if (authState.signedIn) onOpenMessages else onLogin)
                    DividerInset()
                    if (authState.signedIn) SettingLink(
                        Icons.AutoMirrored.Rounded.Logout,
                        "退出登录",
                        authState.email.ifBlank { "当前账户" },
                        onSignOut,
                    )
                    else SettingLink(Icons.AutoMirrored.Rounded.Login, "登录以同步、聊天和使用 AI", onClick = onLogin)
                }
            }
        }
    }

    sheet?.let { selected ->
        val haptic = LocalHapticFeedback.current
        SpectraModalBottomSheet(
            onDismissRequest = { sheet = null; profileRepository.clearMessage() },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = layout.pageHorizontalPadding,
                    end = layout.pageHorizontalPadding,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
            ) {
                item {
                    Text(
                        when (selected) {
                            ProfileSheet.EDIT -> "编辑资料"
                            ProfileSheet.AI -> "AI 运行方式"
                            ProfileSheet.MI_FITNESS -> "Mi Fitness"
                            ProfileSheet.HEALTH_AUTOMATION -> "健康自动化"
                            ProfileSheet.COMPONENTS -> "组件与内容"
                            ProfileSheet.APPEARANCE -> "外观与体验"
                            ProfileSheet.ACHIEVEMENTS -> "全部成就"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                when (selected) {
                    ProfileSheet.EDIT -> item {
                        ProfileEditor(profileState.profile, profileState.saving, profileState.message, profileState.error, authState.userId, profileRepository)
                    }
                    ProfileSheet.AI -> item {
                        LocalAiSettings(
                            preferences = preferences,
                            repository = repository,
                            manager = localModelManager,
                            engine = localAiEngine,
                            providerStore = personalAiProviderStore,
                            onTestConnection = onTestCloudProviderConnection,
                            onListModels = onListCloudProviderModels,
                        )
                    }
                    ProfileSheet.MI_FITNESS -> item {
                        MiFitnessCloudSettings(
                            configured = miFitnessConfigured,
                            syncing = miFitnessSyncing,
                            lastSyncAtMillis = miFitnessLastSyncAtMillis,
                            status = miFitnessStatus,
                            formResetKey = miFitnessFormResetKey,
                            onSaveCredentials = onSaveMiFitnessCredentials,
                            onRefreshSteps = onRefreshMiFitnessSteps,
                            onDeleteCredentials = onDeleteMiFitnessCredentials,
                        )
                    }
                    ProfileSheet.HEALTH_AUTOMATION -> item {
                        HealthAutomationSettings(
                            config = healthAutomationConfig,
                            saving = healthAutomationSaving,
                            notificationsEnabled = healthAutomationNotificationsEnabled,
                            message = healthAutomationMessage,
                            messageIsError = healthAutomationMessageIsError,
                            providerStore = personalAiProviderStore,
                            onListModels = onListCloudProviderModels,
                            onSave = onSaveHealthAutomation,
                            onDisable = onDisableHealthAutomation,
                        )
                    }
                    ProfileSheet.COMPONENTS -> item { ComponentSettings(preferences, repository) }
                    ProfileSheet.APPEARANCE -> item {
                        AppearanceSettings(preferences, repository) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    }
                    ProfileSheet.ACHIEVEMENTS -> items(achievements.chunked(2)) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { AchievementCard(it, Modifier.weight(1f)) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private enum class ProfileSheet { EDIT, AI, MI_FITNESS, HEALTH_AUTOMATION, APPEARANCE, COMPONENTS, ACHIEVEMENTS }

/** Fixed presentation states keep credentials, responses, and raw errors out of the UI contract. */
enum class MiFitnessSettingsStatus {
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

@Composable
internal fun ProfileHero(profile: CampusProfile, fallbackName: String, level: Int, xp: Long, onEdit: () -> Unit) {
    val coverUrl = profile.coverUrl
    var coverLoaded by remember(coverUrl) { mutableStateOf(false) }
    val hasCover = coverUrl.isNotBlank() && coverLoaded
    val primary = if (hasCover) Color.White else MaterialTheme.colorScheme.onSurface
    val tokens = SpectraTheme.tokens
    val fluid = SpectraTheme.isFluid
    GlassPanel(
        Modifier.fillMaxWidth().height(if (fluid) 224.dp else 208.dp),
        radius = tokens.radii.hero.value.roundToInt(),
        emphasized = true,
        shadowed = false,
        onClick = onEdit,
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(tokens.radii.hero))) {
            if (!hasCover) {
                Box(Modifier.fillMaxSize().background(Color.White.copy(.06f)))
                BrandMark(Modifier.align(Alignment.TopCenter).padding(top = 26.dp).size(100.dp))
            }
            if (coverUrl.isNotBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = "个人背景",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onLoading = { coverLoaded = false },
                    onSuccess = { coverLoaded = true },
                    onError = { coverLoaded = false },
                )
            }
            if (hasCover) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color(0xFF111827).copy(.18f),
                                Color(0xFF111827).copy(.76f),
                            ),
                        ),
                    ),
                )
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(if (fluid) 108.dp else 100.dp)
                    .background(
                        if (hasCover) {
                            Brush.horizontalGradient(listOf(Color.Black.copy(.06f), Color.Black.copy(.18f)))
                        } else if (fluid) {
                            Brush.horizontalGradient(listOf(Color.White.copy(.10f), Color.White.copy(.055f)))
                        } else {
                            Brush.horizontalGradient(
                                listOf(
                                    Color.White.copy(if (hasCover) .30f else .12f),
                                    Color.White.copy(if (hasCover) .20f else .08f),
                                    SpectraColors.Cyan.copy(.08f),
                                    SpectraColors.Violet.copy(.07f),
                                ),
                            )
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(primary.copy(if (fluid) .34f else .20f), style = Stroke(if (fluid) 1.5.dp.toPx() else 1.dp.toPx()))
                        if (!fluid) {
                            drawArc(SpectraColors.Cyan.copy(.82f), 126f, 46f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                            drawArc(SpectraColors.Violet.copy(.72f), 284f, 38f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                            drawArc(SpectraColors.Warm.copy(.62f), 344f, 24f, false, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                        }
                    }
                    BrandMark(Modifier.size(62.dp))
                    if (profile.avatarUrl.isNotBlank()) {
                        AsyncImage(profile.avatarUrl, "头像", Modifier.size(66.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    }
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(profile.displayName.ifBlank { fallbackName }, style = MaterialTheme.typography.titleLarge, color = primary)
                        if (profile.isStaff) { Spacer(Modifier.size(5.dp)); Icon(Icons.Rounded.Shield, "管理员", tint = if (hasCover) Color.White else SpectraColors.Focus, modifier = Modifier.size(18.dp)) }
                    }
                    if (profile.bio.isNotBlank()) Text(profile.bio, maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = primary.copy(.70f))
                    Text("LEVEL $level · ${maxOf(profile.experience.toLong(), xp)} XP", fontFamily = Tomorrow, fontWeight = FontWeight.SemiBold, color = primary.copy(.72f))
                }
                Icon(Icons.Rounded.Edit, "编辑资料", tint = primary)
            }
        }
    }
}

@Composable
private fun ProfileEditor(profile: CampusProfile, saving: Boolean, message: String?, error: String?, userId: String, repository: ProfileRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(profile.id, profile.displayName) { mutableStateOf(profile.displayName) }
    var bio by remember(profile.id, profile.bio) { mutableStateOf(profile.bio) }
    fun upload(uri: Uri, kind: ProfileImageKind) {
        scope.launch {
            val type = context.contentResolver.getType(uri).orEmpty()
            val maximumBytes = if (kind == ProfileImageKind.AVATAR) 5 * 1024 * 1024 else 10 * 1024 * 1024
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        output.write(buffer, 0, read)
                        if (total > maximumBytes) break
                    }
                    output.toByteArray()
                } ?: ByteArray(0)
            }
            repository.uploadImage(userId, kind, bytes, type)
        }
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { uri -> upload(uri, ProfileImageKind.AVATAR) } }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { uri -> upload(uri, ProfileImageKind.COVER) } }
    GlassPanel(Modifier.fillMaxWidth(), radius = 16, emphasized = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SpectraPrimaryButton("更换头像", { avatarPicker.launch("image/*") }, Modifier.weight(1f), enabled = !saving, icon = Icons.Rounded.CameraAlt)
                SpectraPrimaryButton("更换背景", { coverPicker.launch("image/*") }, Modifier.weight(1f), enabled = !saving, icon = Icons.Rounded.Badge)
            }
            SpectraTextField(name, { name = it.take(32) }, Modifier.fillMaxWidth(), label = { Text("账号名称") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            SpectraTextField(bio, { bio = it.take(160) }, Modifier.fillMaxWidth(), label = { Text("个人简介（可选）") }, minLines = 2, shape = RoundedCornerShape(12.dp), supportingText = { Text("${bio.length}/160") })
            message?.let { Text(it, color = SpectraColors.Success, style = MaterialTheme.typography.bodyMedium) }
            error?.let { Text(it, color = SpectraColors.Error, style = MaterialTheme.typography.bodyMedium) }
            SpectraPrimaryButton(if (saving) "正在保存…" else "保存资料", { scope.launch { repository.updateText(userId, name, bio) } }, Modifier.fillMaxWidth(), enabled = !saving && name.trim().length in 2..32, icon = Icons.Rounded.Save)
            Text("图片保存在你的 Supabase 私有目录；替换成功后会清理旧文件。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.58f))
        }
    }
}

@Composable
private fun AppearanceSettings(preferences: UserPreferences, repository: UserPreferencesRepository, onFeedback: () -> Unit) {
    val scope = rememberCoroutineScope()
    GlassPanel(Modifier.fillMaxWidth(), radius = 16, emphasized = true) {
        Column {
            Column(Modifier.padding(16.dp)) {
                Text("界面体系", style = MaterialTheme.typography.titleMedium)
                Text(
                    "经典保留当前信息架构；Strba Fluid 会同步改变全 App 的留白、卡片、导航、转场与环境场。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(.58f),
                )
                Spacer(Modifier.height(12.dp))
                com.campusai.core.designsystem.CaesarSlidingSelector(
                    options = SpectraVisualStyle.entries.map(::visualStyleLabel),
                    selectedIndex = SpectraVisualStyle.entries.indexOf(preferences.visualStyle),
                    onSelected = { index ->
                        SpectraVisualStyle.entries.getOrNull(index)?.let { style ->
                            onFeedback()
                            scope.launch { repository.setVisualStyle(style) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    motionEnabled = preferences.motionMode == MotionMode.ON,
                )
            }
            DividerInset()
            SettingSelector(Icons.Rounded.Palette, "主题", ThemeMode.entries, preferences.themeMode, { themeLabel(it) }) { onFeedback(); scope.launch { repository.setTheme(it) } }
            DividerInset()
            Column(Modifier.padding(16.dp)) {
                Text("SPECTRA 环境", style = MaterialTheme.typography.titleMedium)
                Text("样本会立即改变整页体积色场。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.58f))
                Spacer(Modifier.height(12.dp))
                com.campusai.core.designsystem.CaesarSlidingSelector(
                    options = SpectraEnvironment.entries.map(::environmentSelectorLabel),
                    selectedIndex = SpectraEnvironment.entries.indexOf(preferences.environment),
                    onSelected = { index ->
                        SpectraEnvironment.entries.getOrNull(index)?.let { environment ->
                            onFeedback()
                            scope.launch { repository.setEnvironment(environment) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    motionEnabled = preferences.motionMode == MotionMode.ON,
                )
                Spacer(Modifier.height(10.dp))
                EnvironmentSample(preferences.environment)
            }
            DividerInset()
            SettingSelector(Icons.Rounded.Speed, "渲染质量", RenderQuality.entries, preferences.renderQuality, { qualityLabel(it) }) { onFeedback(); scope.launch { repository.setQuality(it) } }
            DividerInset()
            SettingSwitch(Icons.Rounded.MotionPhotosOff, "动态与折射", preferences.motionMode == MotionMode.ON) { onFeedback(); scope.launch { repository.setMotion(if (it) MotionMode.ON else MotionMode.OFF) } }
            DividerInset()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("玻璃交互", style = MaterialTheme.typography.titleMedium)
                Text("各项可独立选择。极光和流星只在暗色模式下响应触摸；关闭总动效时，所有动画停止。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.65f))
            }
            SettingSwitch(Icons.Rounded.MotionPhotosOff, "玻璃形变", preferences.glassEffects.deformation) { scope.launch { repository.setGlassDeformation(it) } }
            SettingSwitch(Icons.Rounded.Palette, "跟手边框光", preferences.glassEffects.rimLight) { scope.launch { repository.setGlassRimLight(it) } }
            SettingSwitch(Icons.Rounded.Palette, "极光 · 仅暗色", preferences.glassEffects.aurora) { scope.launch { repository.setGlassAurora(it) } }
            SettingSwitch(Icons.Rounded.GraphicEq, "流星 · 仅暗色", preferences.glassEffects.meteors) { scope.launch { repository.setGlassMeteors(it) } }
            DividerInset()
            SettingSwitch(Icons.Rounded.GraphicEq, "计时完成声音", preferences.soundEnabled) { onFeedback(); scope.launch { repository.setSound(it) } }
        }
    }
}

@Composable
private fun EnvironmentSample(environment: SpectraEnvironment) {
    val colors = environmentColors(environment)
    Box(
        Modifier.fillMaxWidth().height(88.dp).background(Brush.horizontalGradient(colors), RoundedCornerShape(44.dp)).border(1.dp, Color.White.copy(.8f), RoundedCornerShape(44.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.White.copy(.54f), Color.Transparent)), RoundedCornerShape(44.dp)))
        Text(environmentLabel(environment), style = MaterialTheme.typography.labelMedium, color = SpectraColors.Ink)
    }
}

@Composable
private fun AnnualContributionCard(
    records: List<TimeRecord>,
    targetSnapshots: Map<LocalDate, Long>,
    onOpenDay: ((LocalDate) -> Unit)?,
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val currentYear = today.year
    val earliestYear = remember(records, zone, currentYear) {
        records.asSequence()
            .filter { it.durationMinutes > 0L && it.endTime > it.startTime }
            .map { Instant.ofEpochMilli(it.endTime).atZone(zone).toLocalDate() }
            .filterNot { it.isAfter(today) }
            .map(LocalDate::getYear)
            .minOrNull()
            ?.coerceAtMost(currentYear)
            ?: currentYear
    }
    val availableYears = remember(earliestYear, currentYear) { (earliestYear..currentYear).toList() }
    var selectedYear by rememberSaveable { mutableStateOf(currentYear) }
    var selectedDay by remember { mutableStateOf<DailyContribution?>(null) }
    LaunchedEffect(availableYears) {
        if (selectedYear !in availableYears) selectedYear = currentYear
    }
    LaunchedEffect(selectedYear) { selectedDay = null }

    val contributions = remember(records, targetSnapshots, selectedYear, zone, today) {
        DailyContributionCalculator.calculate(
            year = selectedYear,
            records = records,
            targetSnapshots = targetSnapshots,
            zoneId = zone,
            today = today,
        )
    }
    val contributionByDate = remember(contributions) { contributions.associateBy(DailyContribution::date) }
    val first = LocalDate.of(selectedYear, 1, 1)
    val last = LocalDate.of(selectedYear, 12, 31)
    val gridStart = first.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    val gridEnd = last.with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
    val weekCount = ChronoUnit.WEEKS.between(gridStart, gridEnd).toInt() + 1
    val weeks = remember(contributionByDate, selectedYear, gridStart, weekCount) {
        List(weekCount) { weekIndex ->
            List(7) { dayIndex ->
                val date = gridStart.plusDays(weekIndex * 7L + dayIndex)
                contributionByDate[date].takeIf { date.year == selectedYear }
            }
        }
    }
    val completedCount = contributions.sumOf(DailyContribution::completedCount)
    val durationMinutes = contributions.sumOf(DailyContribution::durationMinutes)
    val cardRadius = SpectraTheme.tokens.radii.card.value.roundToInt()

    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        radius = cardRadius,
        emphasized = true,
        shadowed = !SpectraTheme.isFluid,
        opticalPriority = 2,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("年度节奏", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${formatContributionDuration(durationMinutes)} · $completedCount 条完成记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(.58f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { selectedYear = (selectedYear - 1).coerceAtLeast(earliestYear) },
                        enabled = selectedYear > earliestYear,
                    ) {
                        Icon(Icons.Rounded.ChevronLeft, "上一年")
                    }
                    Text(
                        selectedYear.toString(),
                        fontFamily = Tomorrow,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.requiredWidth(44.dp),
                    )
                    IconButton(
                        onClick = { selectedYear = (selectedYear + 1).coerceAtMost(currentYear) },
                        enabled = selectedYear < currentYear,
                    ) {
                        Icon(Icons.Rounded.ChevronRight, "下一年")
                    }
                }
            }

            ContributionGrid(
                selectedYear = selectedYear,
                gridStart = gridStart,
                weeks = weeks,
                today = today,
                selectedDate = selectedDay?.date,
                onSelect = { selectedDay = it },
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("较少", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.54f))
                Spacer(Modifier.width(6.dp))
                ContributionLevel.entries.forEach { level ->
                    Box(
                        Modifier
                            .padding(horizontal = 2.dp)
                            .size(10.dp)
                            .background(contributionColor(level), RoundedCornerShape(3.dp)),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text("更多", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.54f))
            }

            selectedDay?.let { day ->
                val percent = ((day.durationMinutes.toDouble() / day.targetMinutes.toDouble()) * 100.0)
                    .roundToInt()
                    .coerceAtLeast(0)
                GlassPanel(Modifier.fillMaxWidth(), radius = 14, shadowed = false) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${day.date.year}年${day.date.monthValue}月${day.date.dayOfMonth}日",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${day.durationMinutes} 分钟 · ${day.completedCount} 条 · 目标完成 $percent%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(.62f),
                            )
                        }
                        if (onOpenDay != null && day.completedCount > 0) {
                            TextButton(onClick = { onOpenDay(day.date) }) { Text("查看记录") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContributionGrid(
    selectedYear: Int,
    gridStart: LocalDate,
    weeks: List<List<DailyContribution?>>,
    today: LocalDate,
    selectedDate: LocalDate?,
    onSelect: (DailyContribution) -> Unit,
) {
    val cellSize = 13.dp
    val gap = 3.dp
    val step = cellSize + gap
    val gridWidth = step * weeks.size - gap
    val scrollState = rememberScrollState()
    val maxScroll = scrollState.maxValue
    LaunchedEffect(selectedYear, maxScroll) {
        // The current year opens near today; past years start at January for predictable review.
        if (selectedYear == today.year) scrollState.scrollTo(maxScroll) else scrollState.scrollTo(0)
    }
    Row(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(top = 20.dp, end = 7.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            listOf("一", "", "三", "", "五", "", "日").forEach { label ->
                Box(Modifier.size(cellSize), contentAlignment = Alignment.Center) {
                    if (label.isNotEmpty()) Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(.46f))
                }
            }
        }
        Column(Modifier.horizontalScroll(scrollState)) {
            Box(Modifier.width(gridWidth).height(20.dp)) {
                (1..12).forEach { month ->
                    val monthStart = LocalDate.of(selectedYear, month, 1)
                    val monthWeek = ChronoUnit.WEEKS.between(
                        gridStart,
                        monthStart.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)),
                    ).toInt()
                    Text(
                        "$month 月",
                        modifier = Modifier.offset(x = step * monthWeek).requiredWidth(30.dp),
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(.50f),
                        maxLines = 1,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                weeks.forEach { week ->
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        week.forEach { day ->
                            if (day == null) {
                                Spacer(Modifier.size(cellSize))
                            } else {
                                val future = day.date.isAfter(today)
                                val selected = day.date == selectedDate
                                val description = if (future) {
                                    "${day.date.monthValue}月${day.date.dayOfMonth}日，未来日期"
                                } else {
                                    "${day.date.monthValue}月${day.date.dayOfMonth}日，${day.durationMinutes}分钟，${day.completedCount}条记录"
                                }
                                var cellModifier = Modifier
                                    .size(cellSize)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (future) SpectraColors.Silver.copy(.16f)
                                        else contributionColor(day.level),
                                    )
                                    .border(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) SpectraColors.Cyan else Color.White.copy(if (future) .18f else .52f),
                                        shape = RoundedCornerShape(3.dp),
                                    )
                                    .semantics { contentDescription = description }
                                if (!future) cellModifier = cellModifier.clickable { onSelect(day) }
                                Box(cellModifier)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun contributionColor(level: ContributionLevel): Color = when (level) {
    ContributionLevel.NONE -> SpectraColors.Silver.copy(.34f)
    ContributionLevel.LOW -> SpectraColors.Cyan.copy(.24f)
    ContributionLevel.MEDIUM -> SpectraColors.Cyan.copy(.44f)
    ContributionLevel.HIGH -> SpectraColors.Focus.copy(.66f)
    ContributionLevel.GOAL -> SpectraColors.Cyan.copy(.92f)
}

private fun formatContributionDuration(minutes: Long): String = when {
    minutes < 60L -> "$minutes 分钟"
    minutes % 60L == 0L -> "${minutes / 60L} 小时"
    else -> "${minutes / 60L} 小时 ${minutes % 60L} 分钟"
}

private data class AchievementUi(val name: String, val description: String, val progress: Int, val target: Int, val colors: List<Color>) { val unlocked get() = progress >= target }

private fun buildAchievements(records: List<TimeRecord>, remoteStreak: Int): List<AchievementUi> {
    val totalMinutes = records.sumOf { it.durationMinutes }.toInt()
    val focusCount = records.count { it.category.contains("专注") || it.durationMinutes >= 25 }
    val categories = records.map { it.category }.filter { it.isNotBlank() }.distinct().size
    val streak = maxOf(remoteStreak, currentStreak(records))
    return listOf(
        AchievementUi("第一束光", "完成第一条时间记录", records.size, 1, listOf(SpectraColors.Cyan, SpectraColors.Focus)),
        AchievementUi("专注起航", "完成一次 25 分钟专注", focusCount, 1, listOf(SpectraColors.Focus, SpectraColors.Violet)),
        AchievementUi("稳定节奏", "连续记录 7 天", streak, 7, listOf(SpectraColors.Warm, SpectraColors.Rose)),
        AchievementUi("深度轨道", "累计投入 10 小时", totalMinutes, 600, listOf(SpectraColors.Violet, SpectraColors.Rose)),
        AchievementUi("时间建筑师", "完成 25 条记录", records.size, 25, listOf(SpectraColors.Cyan, SpectraColors.Warm)),
        AchievementUi("完整光谱", "覆盖 5 个学习分类", categories, 5, listOf(SpectraColors.Cyan, SpectraColors.Violet, SpectraColors.Warm)),
        AchievementUi("百小时节点", "累计投入 100 小时", totalMinutes, 6000, listOf(SpectraColors.Warm, SpectraColors.Violet)),
    )
}

private fun currentStreak(records: List<TimeRecord>): Int {
    val dates = records.map { Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault()).toLocalDate() }.toSet()
    var day = java.time.LocalDate.now()
    if (day !in dates) day = day.minusDays(1)
    var count = 0
    while (day in dates) { count++; day = day.minusDays(1) }
    return count
}

@Composable
private fun AchievementCard(item: AchievementUi, modifier: Modifier = Modifier.width(150.dp)) {
    GlassPanel(modifier.height(132.dp), radius = 16) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            OpticalBadge(item.colors, item.unlocked, item.progress.toFloat() / item.target.coerceAtLeast(1), Modifier.size(48.dp))
            Column {
                Text(item.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(if (item.unlocked) 1f else .55f))
                Text(if (item.unlocked) "已解锁" else "${item.progress.coerceAtMost(item.target)}/${item.target} · ${item.description}", maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.56f))
            }
        }
    }
}

@Composable
private fun OpticalBadge(colors: List<Color>, unlocked: Boolean, progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = center
        val r = size.minDimension * .34f
        val alpha = if (unlocked) 1f else .28f
        drawArc(Brush.sweepGradient(colors.map { it.copy(alpha) }), 48f, 264f, false, topLeft = androidx.compose.ui.geometry.Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(size.minDimension * .11f, cap = StrokeCap.Round))
        val nodes = listOf(310f, 238f, 166f, 94f, 22f)
        nodes.forEachIndexed { index, degrees ->
            val rad = Math.toRadians(degrees.toDouble())
            val x = c.x + kotlin.math.cos(rad).toFloat() * r
            val y = c.y + kotlin.math.sin(rad).toFloat() * r
            drawCircle(colors[index % colors.size].copy(if (progress * 5f >= index + 1) alpha else .16f), size.minDimension * .07f, androidx.compose.ui.geometry.Offset(x, y))
        }
    }
}

@Composable private fun StatCell(value: String, label: String, modifier: Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { Text(value, fontFamily = Tomorrow, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge); Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.58f)) }
@Composable private fun VerticalRule() = Box(Modifier.width(1.dp).height(34.dp).background(SpectraColors.Silver.copy(.62f)))

private fun aiProviderLabel(value: AiProvider) = when (value) {
    AiProvider.AUTO -> "自动 · 本地优先"
    AiProvider.DEEPSEEK -> "DeepSeek · 我的 Key"
    AiProvider.GOOGLE_GEMINI -> "Gemini · 我的 Key"
    AiProvider.CODEX -> "Codex · 私有服务"
    AiProvider.LOCAL -> "本地模型"
}
private fun visualStyleLabel(value: SpectraVisualStyle) = when (value) { SpectraVisualStyle.CLASSIC -> "经典"; SpectraVisualStyle.FLUID -> "Strba Fluid" }
private fun environmentSelectorLabel(value: SpectraEnvironment) = when (value) {
    SpectraEnvironment.ORIGINAL -> "原生"
    SpectraEnvironment.OCEAN -> "海流"
    SpectraEnvironment.ULTRAVIOLET -> "紫外"
    SpectraEnvironment.EMBER -> "余烬"
    SpectraEnvironment.AURORA -> "森屿"
}
private fun environmentLabel(value: SpectraEnvironment) = when (value) {
    SpectraEnvironment.ORIGINAL -> "Original 原生"
    SpectraEnvironment.OCEAN -> "Ocean 海流"
    SpectraEnvironment.ULTRAVIOLET -> "Ultraviolet 紫外"
    SpectraEnvironment.EMBER -> "Ember 余烬"
    SpectraEnvironment.AURORA -> "Aurora 森屿"
}
private fun themeLabel(value: ThemeMode) = when (value) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }
private fun qualityLabel(value: RenderQuality) = when (value) { RenderQuality.AUTO -> "自动"; RenderQuality.LOW -> "低"; RenderQuality.HIGH -> "高" }
private fun environmentColors(value: SpectraEnvironment) = when (value) {
    SpectraEnvironment.ORIGINAL -> listOf(SpectraColors.Cyan, SpectraColors.Violet, SpectraColors.Warm, SpectraColors.Rose)
    SpectraEnvironment.OCEAN -> listOf(Color(0xFF0C8DBF), SpectraColors.Cyan, SpectraColors.Focus)
    SpectraEnvironment.ULTRAVIOLET -> listOf(Color(0xFF5138D7), SpectraColors.Violet, SpectraColors.Rose)
    SpectraEnvironment.EMBER -> listOf(Color(0xFFE04B32), SpectraColors.Warm, Color(0xFFFFC457))
    SpectraEnvironment.AURORA -> listOf(Color(0xFF126B4B), SpectraColors.Aurora, SpectraColors.AuroraLight, Color(0xFFA6D86F))
}

private fun miFitnessEntrySubtitle(
    configured: Boolean,
    syncing: Boolean,
    lastSyncAtMillis: Long?,
    status: MiFitnessSettingsStatus,
): String = when {
    status == MiFitnessSettingsStatus.VALIDATING -> "正在验证并保存"
    status == MiFitnessSettingsStatus.DELETING -> "正在删除本地凭据与缓存"
    syncing || status == MiFitnessSettingsStatus.REFRESHING -> "正在同步今日健康"
    configured && lastSyncAtMillis != null && lastSyncAtMillis > 0L -> "已配置 · 最近刷新 ${formatMiFitnessLastSync(lastSyncAtMillis)}"
    configured -> "已配置 · 尚未刷新"
    else -> "同步今日健康"
}

private fun healthAutomationEntrySubtitle(config: ScheduledTaskConfig?): String =
    if (config?.enabled == true) {
        "每 ${config.intervalMinutes} 分钟 · ${config.provider.displayName}"
    } else {
        "未启用 · 仅前台运行"
    }

internal fun miFitnessStatusText(status: MiFitnessSettingsStatus, configured: Boolean): String = when (status) {
    MiFitnessSettingsStatus.IDLE -> if (configured) "已配置，等待手动刷新。" else "尚未配置。"
    MiFitnessSettingsStatus.VALIDATING -> "正在验证并保存到系统安全存储。"
    MiFitnessSettingsStatus.REFRESHING -> "正在同步 Mi Fitness 今日健康。"
    MiFitnessSettingsStatus.DELETING -> "正在删除本机凭据与健康缓存。"
    MiFitnessSettingsStatus.SUCCESS -> "最近一次操作已完成。"
    MiFitnessSettingsStatus.NO_DATA -> "今天还没有同步到健康数据。"
    MiFitnessSettingsStatus.AUTH_ERROR -> "验证失败，请检查 userId 与 passToken。"
    MiFitnessSettingsStatus.NETWORK_ERROR -> "网络异常，请稍后重试。"
    MiFitnessSettingsStatus.STORAGE_ERROR -> "系统安全存储暂不可用，请稍后重试。"
}

internal fun formatMiFitnessLastSync(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0L) return "尚未刷新"
    return runCatching {
        val time = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        "${time.monthValue}月${time.dayOfMonth}日 ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    }.getOrDefault("尚未刷新")
}

@Composable
private fun MiFitnessCloudSettings(
    configured: Boolean,
    syncing: Boolean,
    lastSyncAtMillis: Long?,
    status: MiFitnessSettingsStatus,
    formResetKey: Long,
    onSaveCredentials: (userId: String, passToken: String) -> Unit,
    onRefreshSteps: () -> Unit,
    onDeleteCredentials: () -> Unit,
) {
    var userId by remember(formResetKey) { mutableStateOf("") }
    var passToken by remember(formResetKey) { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val displayStatus = when (status) {
        MiFitnessSettingsStatus.VALIDATING,
        MiFitnessSettingsStatus.DELETING -> status
        else -> if (syncing) MiFitnessSettingsStatus.REFRESHING else status
    }
    val busy = syncing || displayStatus == MiFitnessSettingsStatus.VALIDATING ||
        displayStatus == MiFitnessSettingsStatus.REFRESHING || displayStatus == MiFitnessSettingsStatus.DELETING
    val canDelete = configured && displayStatus !in setOf(
        MiFitnessSettingsStatus.VALIDATING,
        MiFitnessSettingsStatus.DELETING,
    )
    val deleteColor = if (canDelete) SpectraColors.Error else MaterialTheme.colorScheme.onSurface.copy(.38f)
    val statusColor = when (displayStatus) {
        MiFitnessSettingsStatus.SUCCESS -> SpectraColors.Success
        MiFitnessSettingsStatus.NO_DATA -> SpectraColors.Warm
        MiFitnessSettingsStatus.AUTH_ERROR,
        MiFitnessSettingsStatus.NETWORK_ERROR,
        MiFitnessSettingsStatus.STORAGE_ERROR -> SpectraColors.Error
        else -> MaterialTheme.colorScheme.onSurface.copy(.70f)
    }

    GlassPanel(Modifier.fillMaxWidth(), radius = 16, emphasized = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Cloud, null)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Mi Fitness", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (configured) "凭据已配置" else "尚未配置凭据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(.58f),
                    )
                }
            }
            Text(
                "保存凭据后，只有你主动点击同步时才会更新今日健康。CampusAI 不连接手环，也不需要 Health Connect 权限。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(.66f),
            )
            Text(
                "userId 与 passToken 由系统安全存储加密保存；页面不会回显已保存的凭据或原始错误。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(.66f),
            )
            Text(
                "步数总览与分时趋势会在首页的 Mi Fitness 卡片中显示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(.58f),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("状态", style = MaterialTheme.typography.labelLarge)
                Text(miFitnessStatusText(displayStatus, configured), style = MaterialTheme.typography.bodyMedium, color = statusColor)
                Text(
                    "最近刷新：${formatMiFitnessLastSync(lastSyncAtMillis)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(.58f),
                )
            }
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = SpectraColors.Focus,
                    trackColor = SpectraColors.Silver.copy(.42f),
                )
            }
            HorizontalDivider(color = SpectraColors.Silver.copy(.68f))
            SpectraTextField(
                value = userId,
                onValueChange = { userId = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                label = { Text("userId") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            SpectraTextField(
                value = passToken,
                onValueChange = { passToken = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                label = { Text("passToken") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(12.dp),
            )
            Text(
                "输入只用于这次显式保存；保存成功后，本页会清空输入内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(.58f),
            )
            SpectraPrimaryButton(
                text = if (displayStatus == MiFitnessSettingsStatus.VALIDATING) "正在验证…" else if (configured) "安全更新凭据" else "安全验证并保存",
                onClick = { onSaveCredentials(userId.trim(), passToken) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && userId.isNotBlank() && passToken.isNotBlank(),
                icon = Icons.Rounded.Save,
            )
            if (configured) {
                HorizontalDivider(color = SpectraColors.Silver.copy(.68f))
                SpectraPrimaryButton(
                    text = if (displayStatus == MiFitnessSettingsStatus.REFRESHING) "正在同步…" else "同步今日健康",
                    onClick = onRefreshSteps,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    icon = Icons.Rounded.Cloud,
                )
                TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.align(Alignment.End),
                    enabled = canDelete,
                ) {
                    Icon(Icons.Rounded.Delete, null, tint = deleteColor)
                    Spacer(Modifier.size(6.dp))
                    Text("删除 Mi Fitness 凭据", color = deleteColor)
                }
            }
        }
    }

    if (confirmDelete && configured) SpectraAlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = "删除 Mi Fitness 凭据？",
        message = (if (displayStatus == MiFitnessSettingsStatus.REFRESHING) "当前刷新会先取消。" else "") +
            "将从本机系统安全存储删除 userId、passToken 与已缓存的健康摘要。" +
            "之后需要重新保存，才能同步今日健康。",
        confirmLabel = "确认删除",
        onConfirm = { if (canDelete) { confirmDelete = false; onDeleteCredentials() } },
        dismissLabel = "取消",
        destructive = true,
    )
}

@Composable
private fun HealthAutomationSettings(
    config: ScheduledTaskConfig?,
    saving: Boolean,
    notificationsEnabled: Boolean,
    message: String?,
    messageIsError: Boolean,
    providerStore: PersonalAiProviderStore,
    onListModels: (suspend (CloudAiProvider) -> Result<List<CloudProviderModel>>)? = null,
    onSave: (CloudAiProvider, String, Int, Boolean) -> Unit,
    onDisable: () -> Unit,
) {
    val configuredProviders = CloudAiProvider.entries.filter(providerStore::hasCredential)
    // Runtime status changes (RUNNING -> UPDATED/UNCHANGED) must not reset an
    // unsaved form. Each Provider keeps its own draft model while this sheet is open.
    var provider by remember(config?.id) {
        mutableStateOf(
            config?.provider
                ?: configuredProviders.firstOrNull()
                ?: CloudAiProvider.GOOGLE_GEMINI,
        )
    }
    val modelDrafts = remember(config?.id) {
        mutableStateMapOf<CloudAiProvider, String>().apply {
            CloudAiProvider.entries.forEach { candidate ->
                put(
                    candidate,
                    config?.takeIf { it.provider == candidate }?.modelId
                        ?: providerStore.selectedModel(candidate),
                )
            }
        }
    }
    val modelId = modelDrafts[provider].orEmpty()
    var availableModels by remember(config?.id, provider) {
        mutableStateOf<List<CloudProviderModel>>(emptyList())
    }
    var modelCatalogLoading by remember(config?.id, provider) { mutableStateOf(false) }
    var modelCatalogLoaded by remember(config?.id, provider) { mutableStateOf(false) }
    var modelCatalogError by remember(config?.id, provider) { mutableStateOf<String?>(null) }
    var modelCatalogReload by remember(config?.id, provider) { mutableStateOf(0) }
    var intervalMinutes by remember(config?.id) {
        mutableStateOf(config?.intervalMinutes ?: ScheduledTaskConfig.DEFAULT_INTERVAL_MINUTES)
    }
    var includeHealthSummary by remember(config?.id) {
        mutableStateOf(config?.includeHealthSummary ?: false)
    }
    val active = config?.enabled == true
    val credentialAvailable = providerStore.hasCredential(provider)
    val latestListModels by rememberUpdatedState(onListModels)
    LaunchedEffect(provider, credentialAvailable, modelCatalogReload) {
        val requestedProvider = provider
        availableModels = emptyList()
        modelCatalogError = null
        modelCatalogLoaded = false
        if (!credentialAvailable) {
            modelCatalogLoading = false
            return@LaunchedEffect
        }
        val loader = latestListModels
        if (loader == null) {
            modelCatalogLoading = false
            modelCatalogLoaded = true
            modelCatalogError = "当前版本无法读取实时模型列表。"
            return@LaunchedEffect
        }
        modelCatalogLoading = true
        val catalogResult = loader(requestedProvider)
        currentCoroutineContext().ensureActive()
        catalogResult.fold(
            onSuccess = { models ->
                val catalog = models
                    .filter { requestedProvider.acceptsModelId(it.id) }
                    .distinctBy { requestedProvider.normalizeModelId(it.id) }
                val previousModelId = modelDrafts[requestedProvider].orEmpty()
                val selected = selectHealthAutomationModel(
                    provider = requestedProvider,
                    currentModelId = previousModelId,
                    availableModels = catalog,
                )
                availableModels = catalog
                modelDrafts[requestedProvider] = selected
                modelCatalogLoaded = true
            },
            onFailure = { error ->
                modelCatalogError = healthAutomationCatalogError(error)
                modelCatalogLoaded = true
            },
        )
        modelCatalogLoading = false
    }
    val selectedModelAvailable = isHealthAutomationModelAvailable(provider, modelId, availableModels)
    val statusText = when (config?.lastStatus) {
        ScheduledTaskRunStatus.RUNNING -> "正在检查小米云"
        ScheduledTaskRunStatus.UPDATED -> "最近一次已发现新数据"
        ScheduledTaskRunStatus.UNCHANGED -> "最近一次云端数据未变化"
        ScheduledTaskRunStatus.ERROR -> automationErrorText(config.lastErrorCode)
        ScheduledTaskRunStatus.IDLE, null -> if (active) "等待下一次检查" else "尚未启用"
    }

    GlassPanel(Modifier.fillMaxWidth(), radius = 16, emphasized = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Notifications, null)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Caesar∞ 日常", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (active) "仅在 CampusAI 前台运行" else "定时任务未启用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(.58f),
                    )
                }
            }
            Text(
                "到时间后只读取 Mi Fitness 云端。数据变化时会生成 2–3 条短消息；没有变化时只发一条状态。不会连接手环或拉起 Mi Fitness。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(.66f),
            )
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (config?.lastStatus == ScheduledTaskRunStatus.ERROR) SpectraColors.Error else MaterialTheme.colorScheme.onSurface.copy(.72f),
            )
            if (!notificationsEnabled) {
                Text(
                    "通知权限未开启，消息仍会写入 Caesar∞ 日常。",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpectraColors.Warm,
                )
            }
            HorizontalDivider(color = SpectraColors.Silver.copy(.68f))
            Text("使用的 AI", style = MaterialTheme.typography.labelLarge)
            com.campusai.core.designsystem.CaesarSlidingSelector(
                options = CloudAiProvider.entries.map(CloudAiProvider::displayName),
                selectedIndex = CloudAiProvider.entries.indexOf(provider).coerceAtLeast(0),
                onSelected = { index -> CloudAiProvider.entries.getOrNull(index)?.let { provider = it } },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
            )
            Text(
                if (credentialAvailable) "Key 已安全保存" else "请先在 AI 运行方式中保存这个 Provider 的 Key",
                style = MaterialTheme.typography.bodySmall,
                color = if (credentialAvailable) SpectraColors.Success else SpectraColors.Warm,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("锁定模型", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (
                    credentialAvailable &&
                    !modelCatalogLoading &&
                    modelCatalogLoaded &&
                    modelCatalogError == null
                ) {
                    TextButton(
                        onClick = { modelCatalogReload++ },
                        enabled = !saving,
                    ) { Text("刷新列表") }
                }
            }
            if (
                modelId.isNotBlank() &&
                (!modelCatalogLoaded || modelCatalogError != null || availableModels.isEmpty())
            ) {
                Text(
                    "已保留选择 $modelId，实时列表恢复前不会改动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(.58f),
                )
            }
            when {
                !credentialAvailable -> Text(
                    "保存 ${provider.displayName} Key 后才能读取可用模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpectraColors.Warm,
                )
                modelCatalogLoading -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = SpectraColors.Focus,
                        trackColor = SpectraColors.Silver.copy(.42f),
                    )
                    Text(
                        "正在读取 ${provider.displayName} 实时模型列表…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(.58f),
                    )
                }
                modelCatalogError != null -> {
                    Text(
                        modelCatalogError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = SpectraColors.Error,
                    )
                    TextButton(
                        onClick = { modelCatalogReload++ },
                        enabled = !saving,
                    ) { Text("重新加载") }
                }
                modelCatalogLoaded && availableModels.isEmpty() -> Text(
                    "当前账户没有返回可用于文本对话的模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpectraColors.Warm,
                )
                availableModels.isNotEmpty() -> {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(availableModels, key = CloudProviderModel::id) { model ->
                            val normalizedId = provider.normalizeModelId(model.id)
                            TelemetryChip(
                                text = model.displayName,
                                selected = normalizedId == modelId,
                                onClick = { modelDrafts[provider] = normalizedId },
                                enabled = !saving,
                            )
                        }
                    }
                    val modelHint = when {
                        selectedModelAvailable -> "已锁定 $modelId；启用时会再次实时验证，之后不会静默切换。"
                        modelId.isNotBlank() -> "$modelId 已不在实时列表中，请重新选择。"
                        else -> "请选择一个实时可用模型。"
                    }
                    Text(
                        modelHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selectedModelAvailable) {
                            MaterialTheme.colorScheme.onSurface.copy(.58f)
                        } else {
                            SpectraColors.Warm
                        },
                    )
                }
            }
            Text("检查间隔", style = MaterialTheme.typography.labelLarge)
            com.campusai.core.designsystem.CaesarSlidingSelector(
                options = ScheduledTaskConfig.ALLOWED_INTERVAL_MINUTES.sorted().map { "$it 分钟" },
                selectedIndex = ScheduledTaskConfig.ALLOWED_INTERVAL_MINUTES.sorted().indexOf(intervalMinutes).coerceAtLeast(0),
                onSelected = { index ->
                    ScheduledTaskConfig.ALLOWED_INTERVAL_MINUTES.sorted().getOrNull(index)?.let { intervalMinutes = it }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
            )
            SettingSwitch(
                Icons.Rounded.PrivacyTip,
                "附带必要的今日汇总",
                includeHealthSummary,
                enabled = !saving,
            ) { includeHealthSummary = it }
            Text(
                "只发送日汇总，不发送分钟级数据、设备标识或小米认证信息。自动消息不会被带入之后的云端对话。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(.58f),
            )
            if (saving) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = SpectraColors.Focus,
                    trackColor = SpectraColors.Silver.copy(.42f),
                )
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (messageIsError) SpectraColors.Error else SpectraColors.Success,
                )
            }
            SpectraPrimaryButton(
                text = when {
                    saving -> "正在验证…"
                    active -> "验证并保存设置"
                    else -> "验证并启用"
                },
                onClick = { onSave(provider, modelId.trim(), intervalMinutes, includeHealthSummary) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving && credentialAvailable && selectedModelAvailable && includeHealthSummary,
                icon = Icons.Rounded.Notifications,
            )
            if (active) {
                TextButton(
                    onClick = onDisable,
                    enabled = !saving,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("停用定时任务") }
            }
        }
    }
}

internal fun selectHealthAutomationModel(
    provider: CloudAiProvider,
    currentModelId: String,
    availableModels: List<CloudProviderModel>,
): String {
    val availableIds = availableModels
        .map { provider.normalizeModelId(it.id) }
        .toSet()
    val current = provider.normalizeModelId(currentModelId)
    if (current.isNotBlank()) return current
    return provider.defaultModel(AiMode.FAST).takeIf(availableIds::contains).orEmpty()
}

internal fun isHealthAutomationModelAvailable(
    provider: CloudAiProvider,
    modelId: String,
    availableModels: List<CloudProviderModel>,
): Boolean {
    val selected = provider.normalizeModelId(modelId)
    return selected.isNotBlank() && availableModels.any {
        provider.normalizeModelId(it.id) == selected
    }
}

private fun healthAutomationCatalogError(error: Throwable): String =
    (error as? CloudProviderException)?.message
        ?: "模型列表加载失败，请检查网络或代理后重试。"

private fun automationErrorText(code: String?): String = when (code) {
    "notification_permission_disabled" -> "通知权限未开启，任务仍会继续记录消息"
    "credentials_missing" -> "Mi Fitness 凭据尚未配置"
    "authentication_failed" -> "Mi Fitness 认证已失效"
    "rate_limited" -> "小米云请求暂时受限"
    "task_provider_key_missing" -> "任务锁定的 Provider Key 已不可用"
    "model_unavailable", "model_invalid", "task_model_unavailable", "task_model_invalid", "task_model_mismatch" ->
        "任务锁定的模型当前不可用"
    "provider_response_invalid" -> "AI Provider 返回的模型列表无法识别"
    else -> "最近一次检查没有完成"
}

@Composable
private fun LocalAiSettings(
    preferences: UserPreferences,
    repository: UserPreferencesRepository,
    manager: LocalModelManager,
    engine: LocalMnnAiEngine,
    providerStore: PersonalAiProviderStore,
    onTestConnection: (suspend (CloudAiProvider, String) -> Result<CloudProviderConnection>)?,
    onListModels: (suspend (CloudAiProvider) -> Result<List<CloudProviderModel>>)? = null,
) {
    val scope = rememberCoroutineScope()
    val modelStates by manager.states.collectAsState()
    val selectedModel by manager.selection.collectAsState()
    var confirmMobile by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<LocalModelMode?>(null) }
    var confirmRedownload by remember { mutableStateOf<LocalModelMode?>(null) }
    var cloudProvider by rememberSaveable {
        mutableStateOf(CloudAiProvider.from(preferences.aiProvider) ?: CloudAiProvider.DEEPSEEK)
    }
    var personalKey by remember { mutableStateOf("") }
    var personalKeyVisible by remember { mutableStateOf(false) }
    var personalKeyMessage by remember { mutableStateOf<String?>(null) }
    var personalKeyMessageIsError by remember { mutableStateOf(false) }
    var providerRevision by remember { mutableStateOf(0) }
    var catalogReload by remember { mutableStateOf(0) }
    var modelId by remember(cloudProvider, providerRevision) {
        mutableStateOf(providerStore.selectedModel(cloudProvider))
    }
    var baseUrl by remember(cloudProvider, providerRevision) {
        mutableStateOf(providerStore.baseUrl(cloudProvider))
    }
    var availableModels by remember(cloudProvider) { mutableStateOf<List<CloudProviderModel>>(emptyList()) }
    var loadingModels by remember(cloudProvider) { mutableStateOf(false) }
    var modelCatalogError by remember(cloudProvider) { mutableStateOf<String?>(null) }
    var testingConnection by remember { mutableStateOf(false) }
    val providerConfiguration = remember(cloudProvider, providerRevision) {
        providerStore.configuration(cloudProvider)
    }
    LaunchedEffect(preferences.aiProvider) {
        CloudAiProvider.from(preferences.aiProvider)?.let { selected ->
            if (selected != cloudProvider) {
                cloudProvider = selected
                personalKey = ""
                personalKeyMessage = null
                availableModels = emptyList()
            }
        }
    }
    val latestListModels by rememberUpdatedState(onListModels)
    LaunchedEffect(
        cloudProvider,
        providerConfiguration.hasCredential,
        providerConfiguration.baseUrl,
        catalogReload,
    ) {
        availableModels = emptyList()
        modelCatalogError = null
        if (!providerConfiguration.hasCredential) {
            loadingModels = false
            return@LaunchedEffect
        }
        val listModels = latestListModels ?: return@LaunchedEffect
        loadingModels = true
        listModels(cloudProvider).fold(
            onSuccess = { models ->
                availableModels = models
                if (modelId.isBlank()) {
                    modelId = cloudProvider.defaultModel(AiMode.FAST)
                        .takeIf { expected -> models.any { it.id == expected } }
                        ?: models.firstOrNull()?.id.orEmpty()
                }
            },
            onFailure = { error ->
                modelCatalogError = (error as? CloudProviderException)?.message
                    ?: "模型列表加载失败，请检查连接后重试。"
            },
        )
        loadingModels = false
    }
    val localModes = listOf(LocalModelMode.FAST, LocalModelMode.QUALITY)
    val largestDownload = localModes.maxOf { manager.manifestFor(it.modelId).totalBytes }
    GlassPanel(Modifier.fillMaxWidth(), radius = 16, emphasized = true) {
        Column {
            SettingSelector(Icons.Rounded.Cloud, "AI 运行方式", AiProvider.entries, preferences.aiProvider, {
                when (it) {
                    AiProvider.AUTO -> "自动"
                    AiProvider.DEEPSEEK -> "DeepSeek"
                    AiProvider.GOOGLE_GEMINI -> "Gemini"
                    AiProvider.CODEX -> "Codex"
                    AiProvider.LOCAL -> "本地模型"
                }
            }) { scope.launch { repository.setAiProvider(it) } }
            Text(
                when (preferences.aiProvider) {
                    AiProvider.AUTO -> "优先使用当前本地档位；本地不可用时只展示已配置的云端选项，绝不会自动上传对话。"
                    AiProvider.DEEPSEEK -> "固定使用你自己的 DeepSeek Key，不会自动切换本地，也不会调用任何平台共享额度。"
                    AiProvider.GOOGLE_GEMINI -> "固定使用你自己的 Google AI Studio Key，不会自动切换本地，也不会调用其他云端 Provider。"
                    AiProvider.CODEX -> "固定直连你配置的 Codex OpenAI Compatible 服务；对话不经过 Supabase 或额外后端。"
                    AiProvider.LOCAL -> "提示词、学习统计、课程表与回复仅在本机处理；本地失败不会静默调用云端。"
                },
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(.66f),
            )
            DividerInset()
            SettingSelector(
                Icons.Rounded.Speed,
                "本地模型档位",
                localModes,
                selectedModel.mode,
                ::localModelModeLabel,
                manager::selectMode,
            )
            Text(
                "新会话默认 ${localModelModeLabel(selectedModel.mode)} · ${localModelStatusText(selectedModel.state)}",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (selectedModel.state is LocalModelState.Error || selectedModel.state is LocalModelState.Incompatible) SpectraColors.Error else MaterialTheme.colorScheme.onSurface.copy(.70f),
            )
            Text(
                "这里只决定新会话第一次发消息时锁定 FAST 2B 或 DEEP 4B；不会自动下载、切换当前会话，也不会暂停另一模型的下载。",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 5.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(.60f),
            )
            DividerInset()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingSelector(
                    icon = Icons.Rounded.Cloud,
                    label = "云端 Provider 配置",
                    values = CloudAiProvider.entries,
                    selected = cloudProvider,
                    text = { it.displayName },
                ) { selected ->
                    cloudProvider = selected
                    personalKey = ""
                    personalKeyMessage = null
                    personalKeyMessageIsError = false
                    availableModels = emptyList()
                }
                SpectraTextField(
                    value = baseUrl,
                    onValueChange = {
                        if (cloudProvider.baseUrlConfigurable) {
                            baseUrl = it
                            personalKeyMessage = null
                            personalKeyMessageIsError = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL") },
                    placeholder = { Text(cloudProvider.defaultBaseUrl) },
                    supportingText = {
                        Text(
                            if (cloudProvider.baseUrlConfigurable) {
                                "仅允许 HTTPS；接口自动使用 /models 与 /chat/completions。"
                            } else {
                                "${cloudProvider.displayName} 官方地址已固定。"
                            },
                        )
                    },
                    readOnly = !cloudProvider.baseUrlConfigurable,
                    singleLine = true,
                )
                if (cloudProvider.baseUrlConfigurable) {
                    TextButton(
                        onClick = {
                            val originChanged = runCatching {
                                cloudProvider.resolveBaseUrl(baseUrl) != providerConfiguration.baseUrl
                            }.getOrDefault(false)
                            providerStore.saveBaseUrl(cloudProvider, baseUrl).fold(
                                onSuccess = {
                                    personalKey = ""
                                    availableModels = emptyList()
                                    providerRevision++
                                    catalogReload++
                                    personalKeyMessageIsError = false
                                    personalKeyMessage = if (originChanged) {
                                        "Base URL 已保存。为避免把旧 Key 发往新地址，原 Key 已删除，请重新输入。"
                                    } else {
                                        "Base URL 已保存。"
                                    }
                                },
                                onFailure = { error ->
                                    personalKeyMessageIsError = true
                                    personalKeyMessage = error.message ?: "Base URL 无效。"
                                },
                            )
                        },
                        enabled = baseUrl.isNotBlank() && !testingConnection,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("保存 Base URL") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Key, null)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${cloudProvider.displayName} 个人 Key", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (providerConfiguration.hasCredential) providerConfiguration.maskedCredential else "尚未保存",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(.58f),
                        )
                    }
                }
                Text(
                    when (cloudProvider) {
                        CloudAiProvider.DEEPSEEK -> "Key 使用 Android Keystore 加密保存，不参与备份；生成时只发送到 api.deepseek.com，不经过 Supabase。"
                        CloudAiProvider.GOOGLE_GEMINI -> "Key 使用 Android Keystore 加密保存，不参与备份；生成时只发送到 generativelanguage.googleapis.com，不经过 Supabase。"
                        CloudAiProvider.CODEX -> "Key 使用 Android Keystore 加密保存，不参与备份；对话只发送到上方 Base URL，不经过 Supabase。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(.66f),
                )
                SpectraTextField(
                    value = personalKey,
                    onValueChange = { personalKey = it; personalKeyMessage = null; personalKeyMessageIsError = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (providerConfiguration.hasCredential) "粘贴新 Key 以替换" else "${cloudProvider.displayName} API Key") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                    visualTransformation = if (personalKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { personalKeyVisible = !personalKeyVisible }) {
                            Icon(if (personalKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (personalKeyVisible) "隐藏 Key" else "显示 Key")
                        }
                    },
                )
                personalKeyMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (personalKeyMessageIsError) SpectraColors.Error else SpectraColors.Success,
                    )
                }
                SpectraPrimaryButton(
                    text = if (providerConfiguration.hasCredential) "安全替换 Key" else "安全保存 Key",
                    onClick = {
                        providerStore.saveCredential(cloudProvider, personalKey).fold(
                            onSuccess = {
                                personalKey = ""
                                providerRevision++
                                catalogReload++
                                personalKeyMessageIsError = false
                                personalKeyMessage = "已加密保存。下次 ${cloudProvider.displayName} 生成将使用这个 Key。"
                            },
                            onFailure = {
                                personalKeyMessageIsError = true
                                personalKeyMessage = it.message ?: "Key 保存失败，请重试。"
                            },
                        )
                    },
                    icon = Icons.Rounded.Save,
                    enabled = personalKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )

                SpectraTextField(
                    value = modelId,
                    onValueChange = { modelId = it; personalKeyMessage = null; personalKeyMessageIsError = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("模型 ID（留空使用默认）") },
                    placeholder = { Text(cloudProvider.defaultModel(AiMode.FAST)) },
                    singleLine = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            providerStore.saveSelectedModel(cloudProvider, modelId).fold(
                                onSuccess = {
                                    providerRevision++
                                    personalKeyMessageIsError = false
                                    personalKeyMessage = "模型选择已保存。"
                                },
                                onFailure = {
                                    personalKeyMessageIsError = true
                                    personalKeyMessage = it.message ?: "模型 ID 无效。"
                                },
                            )
                        },
                    ) { Text("保存模型") }
                    if (onTestConnection != null) {
                        TextButton(
                            onClick = {
                                testingConnection = true
                                personalKeyMessage = null
                                scope.launch {
                                    onTestConnection(cloudProvider, modelId).fold(
                                        onSuccess = { connection ->
                                            availableModels = connection.models
                                            modelId = connection.selectedModelId
                                            providerStore.saveSelectedModel(cloudProvider, connection.selectedModelId)
                                            providerRevision++
                                            personalKeyMessageIsError = false
                                            personalKeyMessage = if (connection.chatVerified && connection.toolCallsVerified) {
                                                "连接正常 · 已依次验证模型列表、OK 对话与工具调用 · ${connection.latencyMs} ms"
                                            } else if (connection.chatVerified) {
                                                "连接正常 · 已依次验证模型列表与 OK 对话 · ${connection.latencyMs} ms"
                                            } else {
                                                "连接正常 · ${connection.latencyMs} ms · ${connection.models.size} 个可用模型"
                                            }
                                        },
                                        onFailure = { error ->
                                            personalKeyMessageIsError = true
                                            personalKeyMessage = error.message ?: "连接测试失败。"
                                        },
                                    )
                                    testingConnection = false
                                }
                            },
                            enabled = providerConfiguration.hasCredential && !testingConnection,
                        ) { Text(if (testingConnection) "测试中…" else "测试连接") }
                    }
                }
                if (loadingModels) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在从 /models 获取可用模型…", style = MaterialTheme.typography.bodySmall)
                }
                modelCatalogError?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = SpectraColors.Error)
                    TextButton(onClick = { catalogReload++ }) { Text("重试加载模型") }
                }
                if (availableModels.isNotEmpty()) {
                    Text("可用模型", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(availableModels, key = CloudProviderModel::id) { model ->
                            GlassPanel(
                                modifier = Modifier.height(38.dp),
                                radius = 19,
                                emphasized = model.id == modelId,
                                shadowed = false,
                                onClick = {
                                    providerStore.saveSelectedModel(cloudProvider, model.id).onSuccess {
                                        modelId = model.id
                                        providerRevision++
                                        personalKeyMessageIsError = false
                                        personalKeyMessage = "已选择 ${model.displayName}。"
                                    }
                                },
                            ) {
                                Box(Modifier.padding(horizontal = 12.dp).fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(model.displayName, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                if (providerConfiguration.hasCredential) TextButton(
                    onClick = {
                        if (providerStore.deleteCredential(cloudProvider)) {
                            personalKey = ""
                            providerRevision++
                            availableModels = emptyList()
                            personalKeyMessageIsError = false
                            personalKeyMessage = "${cloudProvider.displayName} Key 已从本机删除。"
                        } else {
                            personalKeyMessageIsError = true
                            personalKeyMessage = "删除失败，请重试。"
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) { Text("删除 ${cloudProvider.displayName} Key", color = SpectraColors.Error) }
            }
            DividerInset()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("本机模型", style = MaterialTheme.typography.titleMedium)
                Text(
                    "两套模型独立下载、暂停、校验和删除，可以同时保留。下载完成不会自动改变新会话默认档位。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(.62f),
                )
                localModes.forEach { mode ->
                    val manifest = manager.manifestFor(mode.modelId)
                    val state = modelStates[mode.modelId] ?: LocalModelState.Checking
                    LocalModelDownloadCard(
                        mode = mode,
                        state = state,
                        manifest = manifest,
                        occupiedBytes = manager.runtimeFor(mode.modelId).storage.occupiedBytes(),
                        selected = selectedModel.mode == mode,
                        onPrimaryAction = {
                            when (state) {
                                is LocalModelState.Downloading -> manager.pause(mode)
                                is LocalModelState.Paused -> manager.resume(mode, preferences.localModelWifiOnly)
                                else -> manager.download(mode, preferences.localModelWifiOnly)
                            }
                        },
                        onRedownload = { confirmRedownload = mode },
                        onDelete = { confirmDelete = mode },
                    )
                }
            }
            DividerInset()
            SettingSwitch(Icons.Rounded.Download, "仅 Wi-Fi 下载", preferences.localModelWifiOnly) { enabled ->
                if (enabled) scope.launch { repository.setLocalModelWifiOnly(true) } else confirmMobile = true
            }
            DividerInset()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("隐私、许可证与来源", style = MaterialTheme.typography.titleMedium)
                Text("本地推理不发送提示词或回复；下载器只接受内置固定清单，模型文件只作为数据读取。", style = MaterialTheme.typography.bodyMedium)
                Text("Qwen3.5-2B / 4B · Apache-2.0 · MNN official 4-bit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.58f))
            }
        }
    }
    if (confirmMobile) SpectraAlertDialog(
        onDismissRequest = { confirmMobile = false },
        title = "允许移动网络下载？",
        message = "单个模型最大约 ${formatDownloadBytes(largestDownload)}，可能产生较大流量费用。确认后仅改变下载网络限制。",
        confirmLabel = "确认允许",
        onConfirm = { confirmMobile = false; scope.launch { repository.setLocalModelWifiOnly(false) } },
        dismissLabel = "保持仅 Wi-Fi",
    )
    confirmDelete?.let { targetMode -> SpectraDialog(onDismissRequest = { confirmDelete = null }) {
        val targetManifest = manager.manifestFor(targetMode.modelId)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("删除 ${localModelModeLabel(targetMode)}", style = MaterialTheme.typography.titleLarge)
            Text("只删除 ${targetManifest.displayName}，释放约 ${formatBytes(manager.runtimeFor(targetMode.modelId).storage.occupiedBytes())}；不会删除或暂停另一模型的下载。当前本地生成会先停止，聊天报告保留。", style = MaterialTheme.typography.bodyMedium)
            SlideConfirm("滑动删除模型", onConfirm = {
                confirmDelete = null
                scope.launch {
                    manager.deleteModel(targetMode) { engine.releaseAndWait() }
                }
            })
            TextButton(onClick = { confirmDelete = null }, modifier = Modifier.align(Alignment.End)) { Text("取消") }
        }
    } }
    confirmRedownload?.let { targetMode -> SpectraDialog(onDismissRequest = { confirmRedownload = null }) {
        val targetManifest = manager.manifestFor(targetMode.modelId)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("重新下载 ${localModelModeLabel(targetMode)}", style = MaterialTheme.typography.titleLarge)
            Text("只重下 ${targetManifest.displayName}；不会删除或暂停另一模型的下载。当前本地生成会先停止，然后按现有网络限制重新下载和校验。", style = MaterialTheme.typography.bodyMedium)
            SlideConfirm("滑动重新下载", onConfirm = {
                confirmRedownload = null
                scope.launch {
                    if (manager.deleteModel(targetMode) { engine.releaseAndWait() }) {
                        manager.download(targetMode, preferences.localModelWifiOnly)
                    }
                }
            })
            TextButton(onClick = { confirmRedownload = null }, modifier = Modifier.align(Alignment.End)) { Text("取消") }
        }
    } }
}

@Composable
private fun LocalModelDownloadCard(
    mode: LocalModelMode,
    state: LocalModelState,
    manifest: LocalModelManifest,
    occupiedBytes: Long,
    selected: Boolean,
    onPrimaryAction: () -> Unit,
    onRedownload: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        radius = 18,
        emphasized = selected,
        shadowed = false,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PhoneAndroid, null)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(localModelModeLabel(mode), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${manifest.displayName} · ${manifest.quantization}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(.62f),
                    )
                }
                if (selected) Text(
                    "新会话默认",
                    style = MaterialTheme.typography.labelMedium,
                    color = SpectraColors.Focus,
                )
            }
            Text(
                if (mode == LocalModelMode.FAST) "更快、更省电，适合日常对话与简单工具。" else "更强推理与多模态，适合复杂任务。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(.66f),
            )
            Text(
                "下载 ${formatDownloadBytes(manifest.totalBytes)} · 需预留 ${formatDownloadBytes(manifest.totalBytes + manifest.safetyMarginBytes)} · 当前占用 ${formatBytes(occupiedBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(.56f),
            )
            Text(
                localModelStatusText(state),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is LocalModelState.Error || state is LocalModelState.Incompatible) SpectraColors.Error else MaterialTheme.colorScheme.onSurface.copy(.72f),
            )
            (state as? LocalModelState.Downloading)?.let { downloading ->
                LinearProgressIndicator(
                    progress = { downloading.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = SpectraColors.Focus,
                    trackColor = SpectraColors.Silver.copy(.42f),
                )
            }
            localModelActionLabel(state)?.let { label ->
                SpectraPrimaryButton(
                    text = label,
                    onClick = onPrimaryAction,
                    icon = Icons.Rounded.Download,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val canDelete = occupiedBytes > 0L && state != LocalModelState.Checking &&
                state != LocalModelState.Verifying && state != LocalModelState.Loading
            if (state == LocalModelState.Ready || canDelete) {
                Row(Modifier.align(Alignment.End)) {
                    if (state == LocalModelState.Ready) {
                        TextButton(onClick = onRedownload) { Text("重新下载") }
                    }
                    if (canDelete) {
                        TextButton(onClick = onDelete) {
                            Icon(Icons.Rounded.Delete, null, tint = SpectraColors.Error)
                            Spacer(Modifier.size(6.dp))
                            Text(if (state == LocalModelState.Ready) "删除模型" else "删除下载", color = SpectraColors.Error)
                        }
                    }
                }
            }
        }
    }
}

internal fun localModelModeLabel(mode: LocalModelMode): String = when (mode) {
    LocalModelMode.FAST -> "FAST · 2B"
    LocalModelMode.QUALITY -> "DEEP · 4B"
}

internal fun localModelActionLabel(state: LocalModelState): String? = when (state) {
    LocalModelState.NotDownloaded -> "下载模型"
    is LocalModelState.Downloading -> "暂停下载"
    is LocalModelState.Paused -> "继续下载"
    is LocalModelState.Error -> "重试下载"
    LocalModelState.Ready, LocalModelState.Checking, LocalModelState.Verifying,
    LocalModelState.Loading, is LocalModelState.Incompatible -> null
}

internal fun localModelStatusText(state: LocalModelState): String = when (state) {
    LocalModelState.NotDownloaded -> "未下载 · 不会自动下载"
    LocalModelState.Checking -> "正在检查模型文件"
    is LocalModelState.Downloading -> "下载中 ${(state.progress * 100).toInt()}% · ${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}"
    is LocalModelState.Paused -> "已暂停 · ${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}"
    LocalModelState.Verifying -> "正在逐文件执行 SHA-256 校验"
    LocalModelState.Ready -> "Ready · 可完全离线使用"
    LocalModelState.Loading -> "正在从应用私有目录加载"
    is LocalModelState.Error -> "${state.message}（${state.code}）"
    is LocalModelState.Incompatible -> state.reason
}

private fun formatDownloadBytes(bytes: Long): String = "%.2f GB".format(bytes.toDouble() / 1_000_000_000.0)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes.toDouble() / (1024L * 1024L * 1024L))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes.toDouble() / (1024L * 1024L))
    else -> "%.1f KB".format(bytes.toDouble() / 1024L)
}

@Composable
private fun StatPanel(value: String, label: String, modifier: Modifier) {
    GlassPanel(modifier.height(92.dp), radius = 16) { Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) { Text(value, fontFamily = Tomorrow, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge); Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.58f)) } }
}

@Composable
private fun <T> SettingSelector(icon: ImageVector, label: String, values: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null); Spacer(Modifier.size(12.dp)); Text(label, style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(12.dp))
        com.campusai.core.designsystem.CaesarSlidingSelector(
            options = values.map(text),
            selectedIndex = values.indexOf(selected).coerceAtLeast(0),
            onSelected = { index -> values.getOrNull(index)?.let(onSelect) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingSwitch(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null)
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked, onChecked, enabled = enabled)
    }
}

@Composable
private fun SettingLink(icon: ImageVector, label: String, subtitle: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.56f)) }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(.45f))
    }
}

@Composable private fun DividerInset() = HorizontalDivider(Modifier.padding(start = 52.dp), color = SpectraColors.Silver.copy(.68f))
