package com.campusai.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import coil.Coil
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.decode.DataSource
import com.campusai.app.*
import com.campusai.core.auth.AuthState
import com.campusai.core.database.CampusDatabase
import com.campusai.core.designsystem.*
import com.campusai.core.health.*
import com.campusai.core.localai.LocalMnnAiEngine
import com.campusai.core.localai.LocalModelManager
import com.campusai.core.model.*
import com.campusai.core.preferences.*
import com.campusai.core.profile.*
import com.campusai.core.ai.*
import com.campusai.core.automation.*
import com.campusai.core.security.ProviderSecretStorage
import com.campusai.core.security.PersonalAiProviderStore
import com.campusai.features.ai.*
import com.campusai.features.community.*
import com.campusai.features.time.TimeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Local filming fixture: shell-only, emulator-only, separate process, never signs in or submits. */
class PromoCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("SDK")) { finish(); return }
        enableEdgeToEdge()
        if (runCatching { androidx.work.WorkManager.getInstance(this) }.isFailure) {
            androidx.work.WorkManager.initialize(this, androidx.work.Configuration.Builder().build())
        }
        val dark = intent.getBooleanExtra("dark", false)
        val motion = !intent.getBooleanExtra("motionOff", false)
        val quality = if (intent.getBooleanExtra("low", false)) RenderQuality.LOW else RenderQuality.HIGH
        val initialScreen = intent.getStringExtra("screen") ?: "home"
        val captureEnvironment = SpectraEnvironment.entries.firstOrNull { it.name == intent.getStringExtra("environment") } ?: SpectraEnvironment.ORIGINAL
        val captureStyle = SpectraVisualStyle.entries.firstOrNull { it.name == intent.getStringExtra("style") } ?: SpectraVisualStyle.FLUID
        val cover = demoCover()
        Coil.setImageLoader(ImageLoader.Builder(this).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                    if (chain.request.data.toString().contains("/promo/")) SuccessResult(BitmapDrawable(resources, cover), chain.request, DataSource.MEMORY)
                    else chain.proceed(chain.request)
            })
        }.build())
        val db = Room.inMemoryDatabaseBuilder(this, CampusDatabase::class.java).build()
        val dao = db.campusDao()
        val preferences = UserPreferencesRepository(this)
        val modelManager = LocalModelManager(this)
        val localEngine = LocalMnnAiEngine(this, modelManager)
        // Filming state is exclusively in memory. No real credentials are read or written.
        val fakeValues = mutableMapOf<String, String>()
        val secrets = PersonalAiProviderStore(object : ProviderSecretStorage {
            override fun read(key: String) = fakeValues[key].orEmpty()
            override fun write(key: String, value: String): Boolean { fakeValues[key] = value; return true }
        })
        if (initialScreen == "automation") {
            secrets.saveCredential(CloudAiProvider.DEEPSEEK, "demo-offline-not-a-real-key-0000")
            secrets.saveSelectedModel(CloudAiProvider.DEEPSEEK, "deepseek-chat")
        }
        val profiles = ProfileRepository()
        val community = CampusViewModel()
        val time = TimeViewModel(dao, this, null)
        val ai = AiViewModel(dao, this, preferences, modelManager, localEngine, secrets)
        val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val records = (0..45).flatMap { day ->
            if (day % 7 == 5) emptyList() else listOf(
                TimeRecord(day * 2, "读完一本好书", "Reading", midnight - day * 86400000L + 9 * 3600000L, midnight - day * 86400000L + 10 * 3600000L, 60, "把喜欢的句子留下"),
                TimeRecord(day * 2 + 1, "专注，推进一点点", "Learning", midnight - day * 86400000L + 14 * 3600000L, midnight - day * 86400000L + 15 * 3600000L, 80, "今天也有新的收获"),
            )
        }
        val health = CaesarHealthUiState(
            miFitnessConfigured = true, miFitnessStatus = MiFitnessUiStatus.SUCCESS,
            miFitnessLastSyncAt = midnight + 9 * 3600000L, availability = HealthAvailability.Available,
            snapshot = HealthSnapshot(setOf("mi_fitness_cloud_cn"), HealthPeriod(midnight, midnight + 86400000L, "today"), midnight + 9 * 3600000L, midnight + 9 * 3600000L, HealthFreshness.FRESH,
                HealthMetrics(steps = 8246, distanceMeters = 5620.0, activeCaloriesKcal = 328.0, activityDurationMinutes = 52, heartRateAverageBpm = 72, sleepMinutes = 462, sleepScore = 91), emptySet(), .95,
                metricValues = mapOf(
                    HealthMetricKey.STEPS to (8246.0 to HealthMetricUnit.COUNT),
                    HealthMetricKey.DISTANCE_METERS to (5620.0 to HealthMetricUnit.METERS),
                    HealthMetricKey.ACTIVE_CALORIES_KCAL to (328.0 to HealthMetricUnit.KILOCALORIES),
                    HealthMetricKey.ACTIVITY_DURATION_MINUTES to (52.0 to HealthMetricUnit.MINUTES),
                    HealthMetricKey.HEART_RATE_AVERAGE_BPM to (72.0 to HealthMetricUnit.BEATS_PER_MINUTE),
                    HealthMetricKey.SLEEP_MINUTES to (462.0 to HealthMetricUnit.MINUTES),
                    HealthMetricKey.SLEEP_SCORE to (91.0 to HealthMetricUnit.SCORE),
                ).mapValues { (_, metric) -> HealthMetricValue(metric.first, metric.second, HealthMetricStatus.AVAILABLE, HealthMetricProvenance("mi_fitness_cloud", "local-demo", "daily")) }),
        )
        lifecycleScope.launch {
            delay(700)
            seedState(profiles, "_state", ProfileState(CampusProfile(displayName = "小夏", coverPath = "promo/cover.png", bio = "慢慢来，每一步都算数", level = 7, experience = 1280, streakDays = 12)))
            if (initialScreen == "daily") seedState(ai, "_state", AiUiState(messages = listOf(
                AiConversationMessage("assistant", "今天已经走了不少路，接下来慢慢来。"),
                AiConversationMessage("assistant", "给自己留一点喝水和休息的时间。"),
                AiConversationMessage("assistant", "晚些时候，再为喜欢的事留点空闲。"),
            )))
            else if (initialScreen != "ai-welcome") seedState(ai, "_state", AiUiState(provider = AiProvider.AUTO, messages = listOf(
                AiConversationMessage("user", "下午有两小时空闲，帮我安排阅读和复习。"),
                AiConversationMessage("assistant", "给下午留一点从容。\n\n先用 50 分钟读完今天的章节，休息 10 分钟；再用 50 分钟复习，把最后 10 分钟留给整理。\n\n一次只专注一件事，就很好。"),
            )))
        }
        setContent {
            val savedPreferences by preferences.preferences.collectAsState(initial = UserPreferences())
            val theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT
            var screen by remember { mutableStateOf(initialScreen) }
            var demoAutomation by remember { mutableStateOf<ScheduledTaskConfig?>(null) }
            var demoMessage by remember { mutableStateOf<String?>(null) }
            var postsScope by remember { mutableStateOf(CommunityFeedScope.MINE) }
            var wishesScope by remember { mutableStateOf(CommunityFeedScope.MINE) }
            val style = captureStyle
            val effects = savedPreferences.glassEffects.copy(
                aurora = savedPreferences.glassEffects.aurora && !intent.getBooleanExtra("noAurora", false),
                meteors = savedPreferences.glassEffects.meteors && !intent.getBooleanExtra("noMeteors", false),
                rimLight = savedPreferences.glassEffects.rimLight && !intent.getBooleanExtra("noRim", false),
                deformation = savedPreferences.glassEffects.deformation && !intent.getBooleanExtra("noDeform", false),
            )
            val tokens = spectraTokensForStyle(style = style).let { it.copy(motion = it.motion.copy(enabled = motion), glassEffects = effects) }
            CampusTheme(theme, captureEnvironment) {
                ProvideSpectraExperience(style) { ProvideSpectraTokens(tokens) {
                    SideEffect { SpectraVisualStyleController.set(style); OpticalGlassRegistry.switchRouteScope("promo:$screen") }
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                    Box(Modifier.fillMaxSize()) {
                        SpectraBackdrop(captureEnvironment, quality, if (motion) MotionMode.ON else MotionMode.OFF)
                        val padding = PaddingValues(top = 30.dp, bottom = 112.dp)
                        val state = CampusRemoteState(
                            postsScope = postsScope, listingsScope = wishesScope,
                            posts = UiState.Data(demoPosts(postsScope)), listings = UiState.Data(demoWishes(wishesScope)), listingsHasSynced = true,
                        )
                        when (screen) {
                            "home", "health" -> HomeScreen(records, "小夏", "", "把今天，过成自己喜欢的样子。", UiState.Empty, {}, { screen = "time" }, { screen = "ai" }, health, {}, {}, padding, savedPreferences.collapsedComponents, { id -> lifecycleScope.launch { preferences.setComponentCollapsed(id, false) } })
                            "time" -> TimeScreen(records, time, { screen = "focus" }, { _, _ -> SnackbarResult.Dismissed }, padding)
                            "focus" -> FocusSessionScreen(50, motion, false, { screen = "time" }, { screen = "time" })
                            "tree" -> CampusScreen(state, true, "self", "小夏", community, {}, padding, { postsScope = it })
                            "wish" -> MarketScreen(state, true, "self", community, {}, {}, padding, { wishesScope = it })
                            "auth" -> AuthScreen(AuthState(), { _, _ -> false }, { _, _, _ -> false }, {}, { screen = "home" })
                            "guide" -> WelcomeGuide { screen = "home" }
                            "components" -> Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
                                Text("组件与内容", style = MaterialTheme.typography.headlineLarge)
                                ComponentSettings(savedPreferences, preferences)
                            }
                            "post-edit" -> PostComposer(false, { screen = "tree" }, { _, _, _, _, _, _ -> screen = "tree" }, initial = demoPosts(CommunityFeedScope.MINE).first())
                            "wish-edit" -> ListingComposer(false, { screen = "wish" }, { _, _, _, _, _, _, _, _ -> screen = "wish" }, initial = demoWishes(CommunityFeedScope.MINE).first())
                            "post-compose" -> PostComposer(false, { screen = "tree" }, { _, _, _, _, _, _ -> screen = "tree" })
                            "wish-compose" -> ListingComposer(false, { screen = "wish" }, { _, _, _, _, _, _, _, _ -> screen = "wish" })
                            "wish-detail" -> ListingDetails(demoWishes(CommunityFeedScope.MINE).first(), true, UiState.Data(listOf(CommunityComment("c", "wish-1", "self", "小夏", "第一步：选好路线。期待出发的那天！", "approved", "2026-09-08T09:00:00Z"))), false, null, { screen = "wish" }, {}, {}, {}, {}, {}, { _, done -> done() })
                            "profile", "automation" -> ProfileScreen(savedPreferences.copy(themeMode = theme, visualStyle = style, environment = captureEnvironment), preferences, records, AuthState(signedIn = true, email = "小夏@example.test"), 0, {}, {}, {}, modelManager, localEngine, secrets, profiles, padding,
                                onListCloudProviderModels = { Result.success(listOf(CloudProviderModel("deepseek-chat"), CloudProviderModel("deepseek-reasoner"))) },
                                healthAutomationConfig = demoAutomation,
                                healthAutomationMessage = demoMessage,
                                onSaveHealthAutomation = { provider, model, interval, consent ->
                                    demoAutomation = ScheduledTaskConfig("promo-only", ScheduledTaskType.HEALTH_CLOUD_STATUS, true, interval, provider, model, consent)
                                    demoMessage = "离线演示：启用状态示例，未进行联网验证"
                                },
                                onDisableHealthAutomation = {
                                    demoAutomation = demoAutomation?.copy(enabled = false)
                                    demoMessage = "离线演示：已切换为停用状态"
                                },
                            )
                            "ai", "ai-welcome", "daily" -> AiScreen(ai, AiContextSnapshot(displayName = "小夏", records = records), motion, style, {}, { screen = "home" })
                        }
                        if (screen in listOf("home", "health", "time", "tree", "wish", "profile", "automation")) {
                            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)) {
                                SpectraDock(when (screen) { "time" -> MainDestination.TIME; "tree" -> MainDestination.CAMPUS; "wish" -> MainDestination.MARKET; "profile" -> MainDestination.PROFILE; else -> MainDestination.HOME }, motion) {
                                    screen = when (it) { MainDestination.HOME -> "home"; MainDestination.TIME -> "time"; MainDestination.CAMPUS -> "tree"; MainDestination.MARKET -> "wish"; MainDestination.PROFILE -> "profile" }
                                }
                            }
                        }
                    } }
                } }
            }
        }
    }

    private fun demoCover(): Bitmap = Bitmap.createBitmap(1200, 700, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, 1200f, 700f, intArrayOf(0xFF153B52.toInt(), 0xFF468C98.toInt(), 0xFFA9DBD1.toInt()), null, Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, 1200f, 700f, paint)
        paint.shader = null; paint.color = 0xFFE8DDAB.toInt(); canvas.drawCircle(850f, 190f, 96f, paint)
        paint.color = 0xFF255F6C.toInt(); canvas.drawOval(-300f, 320f, 1450f, 1500f, paint)
        paint.color = 0xFF123E50.toInt(); canvas.drawOval(220f, 410f, 1900f, 1650f, paint)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> seedState(target: Any, field: String, value: T) {
        val slot = target.javaClass.getDeclaredField(field).apply { isAccessible = true }
        (slot.get(target) as MutableStateFlow<T>).value = value
    }

    private fun demoPosts(scope: CommunityFeedScope) = if (scope == CommunityFeedScope.MINE) listOf(
        CommunityPost("p1", "self", "小夏", "", "今天终于把拖了很久的书读完了。\n原来慢一点，也能到达。", "生活碎片", "", false, 0, false, 1, "2026-09-08T08:20:00Z"),
        CommunityPost("p2", "self", "小夏", "", "傍晚绕操场走了两圈，晚风刚刚好。\n把这一刻，留给自己。", "今日小确幸", "", false, 0, false, 0, "2026-09-07T18:30:00Z"),
    ) else listOf(
        CommunityPost("p3", "other1", "林间", "", "分享一个小习惯：睡前记下今天最开心的一件事。已经坚持了第 21 天。", "一起成长", "", false, 28, false, 6, "2026-09-08T07:20:00Z", true),
        CommunityPost("p4", "other2", "晴天", "", "图书馆靠窗的位置，有今天最好看的云。\n你们今天遇到了什么好事？", "校园日常", "", false, 16, false, 3, "2026-09-08T06:10:00Z", true),
    )

    private fun demoWishes(scope: CommunityFeedScope) = if (scope == CommunityFeedScope.MINE) listOf(
        MarketplaceListing("wish-1", "self", "小夏", "和朋友看一次海边日出", "带上相机，坐最早的一班车。\n想把普通的一天，过得特别一点。", null, "海边", "", "active", "pending", "2026-09-01T09:00:00Z", targetDate = "2026-10-01"),
        MarketplaceListing("wish-2", "self", "小夏", "读完书架上的十二本书", "每读完一本，就留下最喜欢的一句话。", null, "", "", "active", "pending", "2026-09-07T09:00:00Z"),
        MarketplaceListing("wish-3", "self", "小夏", "独自完成一次城市漫步", "原来熟悉的城市，还有这么多惊喜。", null, "", "", "completed", "pending", "2026-08-01T09:00:00Z", completedAt = "2026-09-02T09:00:00Z", completionNote = "走过了 8 公里，带回了一整天的好心情。"),
    ) else listOf(
        MarketplaceListing("wish-4", "other1", "林间", "想找一起晨跑的伙伴", "从每周两次开始，慢慢跑得更远。", null, "学校操场", "", "active", "approved", "2026-09-08T08:00:00Z", true),
        MarketplaceListing("wish-5", "other2", "晴天", "让闲置的书遇见新主人", "一本书的旅程，可以继续。", 1500, "图书馆", "", "active", "approved", "2026-09-07T08:00:00Z", true),
    )
}
