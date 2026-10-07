# 课程提醒与课表日期（2026-10-07）

## 实现范围

- 课表以用户确认的教学周及喜鹊儿截图为依据：2026-09-07 为第 1 周周一，2026-10-07 为第 5 周。切换教学周时，星期下的日期同步切换；“全部”不显示猜测日期。
- 课前默认提前 15 分钟提醒一次，上课后安静显示系统倒计时，下课自动收起。点击打开课程，操作按钮结束本次显示。
- 设置按账号保存。缺少周次、真实起止时间或无法解释的日历规则不自动排程；用户可编辑补全。
- AlarmManager 负责下一次课前、上课、下课或日期边界，WorkManager 仅作恢复。支持重启、时区/时间变化、覆盖安装后的重新排程。没有常驻前台服务或每秒唤醒。
- 正式设置页仅提供自动提醒、时间、权限和超级岛样式设置。演示入口仅存在于 debug 探针，由系统 DUMP 权限保护，不进入 release 清单。

## 喜鹊儿核对

手机中的“我的课表”逐周截图覆盖第 1–20 周，课程详情和学校作息表另行取证。共确认 13 种课程安排，包括双周数据库开发实践、第 9–12 周形势与政策 III、第 10–17 周创业概论。

补全既有第 4/5 周的 21 条记录，新增 2 条后半学期课程；对重叠记录拆分周次，避免重复提醒。旧的 50 条不完整记录保留，不参与定时提醒。修改前后课程导出已保留；20 周逐周比较课程名称、星期、起止时间、教师、教室全部一致，旧记录未改变。

学校节次：08:00–08:45、08:50–09:35、09:55–10:40、10:45–11:30、11:35–12:20、14:00–14:45、14:50–15:35、15:55–16:40、16:45–17:30、18:40–19:25、19:30–20:15、20:20–21:05。

## 超级岛实机结论

设备：小米 15 Pro（2410DPN6CC），实机读取为 HyperOS 4.0、Android 17 / API 37、焦点通知协议 3。

采用 HyperOS 协议 3 的原生模板，实机已确认收起的胶囊、小圆圈及点击展开的卡片。展开界面为系统玻璃材质、原生流光描边、清晰的白色课程标题，课前使用淡紫色倒计时，上课使用薄荷绿色。课程、教室、节次、起止时间及“查看课表”按钮由模板提供。胶囊中的计时器由系统刷新；小圆圈包含白色课程图标和进度环，课前及上课阶段最多每 5 分钟更新一次，不唤醒休眠手机。大小形态由系统按当前活动分配，已用两条临时活动验证小圆圈显示及点击展开。

通知栏使用独立的浅色/深色文字与图标，背景根据当前系统明暗设置选择，浅色模式不再显示固定深色卡片。收起的胶囊和小圆圈始终为黑色，因此另外使用白色图标。补齐展开卡片的深色辅助文字颜色，避免时间范围黑字落在深色玻璃上。

明暗切换进行了实际参数实验：`bgInfo.colorBg=#FFFFFF` 和 `bgInfo.picBg` 的纯白位图均不能把原生展开卡片变成浅色，通知栏则可以变浅。完全不传背景时，本机通知栏仍得到深色默认背景，所以最终保留按明暗配置的背景色。[小米常见问题](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2146) 同样说明展开卡片仅支持深色、通知栏支持两种颜色。未给原生卡片添加无效的主题开关；现有原生流光使用系统 `outer_glow`，不声称支持自定义紫白蓝颜色。

### v2.2.0 正式样式

用户要求深色玻璃＋紫白青蓝流光，并保留原有彩色流光。代码已实现 `miui.focus.rv.island.expand` 原生自定义布局，由系统承载卡片、倒计时与点击，流光由卡片内 Android 动画绘制；没有使用 HTML 预览或悬浮窗，也没有把固定 `outer_glow` 当作可调色接口。通知栏保留各自明暗布局。

手机随后重新连接。修复联网规则 guard 对本机已知 `AUROGON_MATCH` 标记的识别后，新 APK 的标准彩色模板实测获得 `nativeLease:true`，原生胶囊成功显示；发布后确认恢复为 `chain:disabled`、`com.xiaomi.xmsf:allow`。该修复没有取消对未知标记、读取失败或既存 `OEM_DENY_3_MATCH` 规则的拒绝。此前列出的展开卡片、小圆圈等截图仍是旧实现的历史证据，本轮标准模板复测不替代所有旧场景的重新验收。

紫白青蓝自定义卡片在同一手机上出现 `onInflateSuccess` 后 `onAuthFailed`，未获得原生超级岛展示许可。当前系统插件的独立名单 `config_canShowCustomFocusPackages` 不包含本应用；Shizuku 所取得的离线标准模板许可不覆盖该自定义布局名单。因此不能把本地 RemoteViews 渲染成功或 `nativeLease:true` 当作自定义卡片已经可用的证明。

正式代码通过 `supportedAppearance` 检查当前系统资源中的应用许可；未获许可、资源不存在或读取失败时，发送阶段自动使用标准彩色模板，设置页隐藏无效的自定义样式选择。仅当名单明确包含本应用时，设置才提供两种样式并按账号保存。当前这台手机实际使用深色玻璃与原生彩色流光，不宣称已交付紫白青蓝原生卡片，也不改动系统名单。

品牌资源已交换用途：珠白 Logo 用于桌面图标，黑银 Logo 用于开屏与应用内品牌。新开屏约 2050ms：黑白两条原生 Bézier 光带先分离、再交织收束成黑银 Logo，高低错落的原创尖角 `CAESAR∞` 路径逐笔签出，随后扫光并淡出首页。开屏与首页共用现有 SpectraBackdrop，跟随用户主题连续运行，仅首页内容在最后 300ms 渐入。热启动、通知/分享深链与关闭系统动画时跳过；播放上限 2200ms，等待系统启动窗口就绪的兜底仍为 1100ms。最终 debug 构建和 lint 已通过（0 错误、77 警告）；本轮 41 项相关测试通过后，新增共享背景断言及 11 项开屏/外部跳转测试也通过。实机录屏 `caesar-220-launch.mp4` 确认光带、签名、背景衔接及首页恢复；修正系统启动 Logo 裁切与首页玻璃轮廓过早露出。正式 release 构建由发布工作流另行验证。

未申请小米开发者认证。独立发送模板会被系统鉴权拒绝，`canShowFocus=true` 也不能代表接入成功。经用户授权使用 Shizuku 后，发布期间短暂调整 XMSF 联网规则，让当前系统采用离线模板处理路径；发布后立即恢复。只支持已验证的初始状态：chain3 关闭且 XMSF 允许联网，遇到其他配置不修改并回退普通通知。独立的 2 秒恢复看守和退出处理覆盖客户端异常退出；实机已在持有临时规则时结束应用进程，确认规则恢复为 `chain:disabled`、`com.xiaomi.xmsf:allow`。没有永久关闭小米推送或改动系统白名单。

原 Shizuku 13.5.4 在本机因 `IProcessObserver.onProcessStarted` 接口变化崩溃。已从官方 GitHub 下载 13.6.0，确认新旧 APK 签名相同后保留数据覆盖更新；原 APK 已备份。新版本服务运行稳定，CampusAI 已获授权。Shizuku 失效时保留普通通知；重启后需保证 Shizuku 服务重新启动。该方案依赖当前系统行为，不能视为小米官方兼容承诺。

正式设置移除 Android promoted ongoing 实验选项，正常保存时关闭该路径。小米超级岛不可用时使用普通系统通知。

参考资料仅用于接口研究，未复制参考工程实现：

- [Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)
- [小米焦点通知接入](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132)
- [Maling-Island](https://github.com/SiberiaApp/Maling-Island)
- [xiaoaiisland](https://github.com/Mercury000/xiaoaiisland)
- [HyperIsland 模板说明](https://github.com/1812z/HyperIsland)
- [Shizuku 官方 13.6.0](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0)
- [Shizuku API](https://github.com/RikkaApps/Shizuku-API)

## 验证与证据

构建及验证命令：

```powershell
./gradlew.bat :apps:android:app:assembleDebug :apps:android:app:testDebugUnitTest --tests '*CourseReminder*' --tests '*CourseTimetableUiTest' :apps:android:app:lintDebug -PcampusBuildDir=C:/Users/28219/.gradle/campusai-notifications-build --project-cache-dir D:/DevTools/CampusAI-notifications/gradle-project-cache --console=plain
```

`C:/Users/28219/.gradle/campusai-notifications-build` 是指向 D 盘构建目录的 junction，用于规避 KSP 在 C 盘源码、D 盘生成代码之间的路径限制。原有默认构建目录保持兼容。

证据根目录：`D:/DevTools/CampusAI-notifications`。包括 `xique-evidence` 的 20 周截图、课程详情、`confirmed-courses.json`、`course-reconciliation.json`、修改前后课程导出、实机截图、构建日志。原安装 APK 保留用于恢复；覆盖安装保留原应用数据。

测试覆盖周次/单双周、日期与学期映射、无效及模糊规则、一次性课程、时区边界、通知阶段/计时、账号隔离和课表交互。实机测试发现 HyperOS 在通知 timeout 时触发 deleteIntent，因此通知只在下课时过期，课前至上课转换由闹钟负责。

构建及测试证据位于 `island-background-verify.log`、`island-progress-build.log` 和 `test-summary.json`。20 项相关测试通过，覆盖日期、通知、原生卡片计时一致性及按钮目标；lint 为 0 错误、72 警告，包含项目已有提示及 KTX/同步持久化建议。最后补充课前进度刷新后，重新构建成功。签名兼容的 debug APK 通过 `adb install -r` 覆盖安装，版本号保持 2.1.2 / 6。交付 APK 为证据目录中的 `CampusAI-2.1.2-course-reminders-debug.apk`。

关键实机截图：`native-shade-final.png`（浅色通知栏）、`native-expanded-final.png`（原生深色玻璃展开）、`native-small-final.png`（最终白色图标与进度环）、`native-small-open.png`（小圆圈点击展开）、`native-image-background-attempt.png`（纯白位图背景仍得到深色展开卡片）。截图使用临时示例课程，不是正在上课的证明。

实机已确认：第 5 周/第 6 周日期与课程变化；普通通知清晰显示；原生超级岛收起/展开及按钮跳转；`am kill` 后进程消失，闹钟重新启动进程并切到无声上课卡片；到期通知列表中演示项归零。演示课程的查看按钮打开课程提醒设置，真实课程跳到课表中的课程。当前手机自动提醒开启、提前 15 分钟、小米超级岛开启、Android 实时通知关闭。新安装应用默认不自行打开提醒或 Shizuku 接入。

课前渠道为 HIGH、系统通知提示音，上课渠道为 LOW、无声；原生模板仅首次课前显示允许自动展开，倒计时更新不反复展开。实际是否响铃、横幅或锁屏显示仍受系统、勿扰和主题设置影响，不把通知渠道配置视为听到铃声的证据。

当前自定义锁屏先前未显示普通课程通知，通知记录本身为 PUBLIC，应用锁屏通知、实时动态和悬浮通知开关均已确认开启。最后检查时手机已切到其他应用，未打断当前操作去再次锁屏。未更改锁屏凭据或主题，锁屏外观仍未完成验收。

清理临时参考仓库的命令被执行策略拒绝，两个参考目录暂留，未尝试绕过。证据、原 APK 恢复备份和 D 盘可复用构建缓存保留。

尚不把重启后实际触发、整夜休眠、后续系统升级和真实上课日长期运行视为已完成验收。
