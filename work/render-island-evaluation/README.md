# Xiaomi 15 Pro 渲染与超级岛补验

供主线执行；编写者未操作设备、构建或部署。保留应用数据，不卸载、不清数据、不运行 connectedAndroidTest。不改系统时间、温控、刷新率或电池状态，不主动加热；耗电不测。所有设备操作由同一个主线操作者串行完成。

## Debug 渲染接线探针

入口只存在于 `src/debug`，独立 `:spectra_probe` 进程、`android.permission.DUMP` 保护，并同时要求固定 action 和 `explicitOptIn=true`。release/benchmark 没有该 Activity。复用正式 `SpectraBackdrop` 的一个 Compose 帧循环、真实 `SpectraSurfaceView`、实际 shader/FBO、玻璃和 orb；不增加 GL 请求循环、不加载账号/偏好或访问网络。

debug 反射只把该 Surface 的热/省电输入改成固定合成值，再调用已有预算入口。实际系统值单独读回，合成输入不低于真实值；发现真实 SEVERE 及以上立即结束。未更改或假装收到 Android 热回调。帧压力阶段只在该调试进程主线程每约 40 ms 有限等待 24 ms，共 4.5 秒，不忙等、不加 GPU 负载；记录真实渲染回调标记和 FBO 尺寸。阶段总时长 64.5 秒，包含设置/读回在内 70 秒硬截止。离开页面立即终止，不留定时任务；屏幕保持标记只对该 Activity 生效。

主线构建可复用现有命令；新增定向类：`com.campusai.SpectraRenderProbeContractTest`（testDebug）。既有 `com.campusai.SpectraRenderPolicyTest` 继续验证策略边界。以下假设 `$adb` 与 `$serial` 已由主线确认：

```powershell
$package = 'com.aistudio.campusai.ywtpzx'
& $adb -s $serial shell am start -W -n "$package/com.campusai.debug.SpectraRenderProbeActivity" -a com.campusai.debug.RUN_SPECTRA_PROBE --ez explicitOptIn true
```

约 66 秒后自动返回；操作者保持进程前台，不切换页面或锁屏。可按两个小于 60 秒的等待段安排其他只读工作。完成后：

```powershell
& $adb -s $serial shell run-as $package cat files/spectra-render-probe.json
& $adb -s $serial logcat -d -v threadtime -s CaesarRenderProbe:I CaesarRenderBudget:D
```

保存报告到 `artifacts/caesar-3.0-completion/`。不要用截图命令切到其他 Activity；`exec-out screencap -p` 本身不应影响前台。可在 `synthetic_severe` / `high_synthetic_critical` 阶段截图玻璃与 orb 是否继续可见。检查 JSON：

| 阶段 | 末尾预算预期 | 证据性质 |
|---|---|---|
| baseline | FULL | 当前调试场景实际条件 |
| synthetic_moderate / synthetic_severe | BALANCED / ECONOMY | 合成热输入，真实渲染接线 |
| thermal_input_removed | 逐级 ECONOMY → BALANCED → FULL | 移除合成输入后的实际健康 GL/显示反馈 |
| synthetic_saver / saver_input_removed | BALANCED → FULL | 合成省电输入及真实恢复反馈 |
| high_synthetic_severe / high_synthetic_critical / high_input_removed | FULL → ECONOMY → FULL | HIGH 合成边界接线 |
| actual_process_frame_pressure / frame_pressure_removed | FULL → BALANCED → ECONOMY，再逐级 FULL | 有限等待造成的真实本进程帧压力及恢复 |

`outcome=completed` 仅表示跑完，不能单独判通过。每阶段应检查 `expectedFinalBudgetObserved`、时间边界、`observedTransitions` 和实际系统值；真实省电/温度或恢复期帧压力可能使预期无法达到，应保留真实失败或受限结果，不改预期或反复重试挑好结果。`glAfter.sceneFramebufferReady=true`，纹理尺寸应与实际 `width/height` 乘预算比例一致（正式实现取整）；`glCompletionAdvanced=true` 表示本阶段确有真实 GL 回调。`sceneScale` 应对应 FULL=.50、BALANCED=.42、ECONOMY=.34。

`completedFrameAtNanos` 和 `frameCpuDurationNanos` 是提交回调标记/墙钟耗时，不是 GPU 时间、完整回调计数或屏幕帧率。此报告不替代先前 FrameTimeline 配对性能样本，也不证明真实过热事件、真实省电系统回调或节电收益。截图是这一固定调试场景的实机材料证据；正式各页面/主题视觉仍须主线实看。

## 复用真实 AI 通知入口

`CourseReminderProbeActivity` 的 `preview-ai-island` 调用正式 `AiIslandNotifications.publish`，仍受既有账号、通知权限、小米集成开关、协议和 Shizuku/原生 lease 守卫约束。先确认应用没有正在生成/录音/朗读；预览和真实任务共用通知 ID，不能重叠。不要调用 `configure`、`demo`、`request-island-access` 或 `test-island-recovery`；最后一个会杀进程。探针还会生成既有 `course-reminder-report.json`（包含课程），只保留本次必要证据，结束按既有清理流程移除。

```powershell
& $adb -s $serial shell am start -W -n "$package/com.campusai.debug.CourseReminderProbeActivity" --es operation preview-ai-island --es mode RESPONDING
$aiKeys = @(& $adb -s $serial shell cmd notification list | Where-Object { $_ -match '^\d+\|com\.aistudio\.campusai\.ywtpzx\|742001\|caesar-ai-status\|' })
if ($aiKeys.Count -eq 1) {
    $aiKey = $aiKeys[0].Trim()
    & $adb -s $serial shell "cmd notification get '$aiKey'"
}
```

若 ROM 不支持 `cmd notification list/get`，先读 `cmd notification help`，不要猜写入子命令。只取本应用目标通知，避免保存其他应用正文。命令依据 [AOSP NotificationShellCmd](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/core/java/com/android/server/notification/NotificationShellCmd.java)。

通过标准分开记录：

1. **真实展示/展开**：顶部原生岛实际可见并能展开，截图保留表情、通用状态和查看/停止按钮；仅有 `miui.focus.param` JSON 或普通通知不能判原生岛通过。正式通知不包含对话正文。Shizuku 不可用时普通通知降级应可用，但原生岛为受限。既有原生 lease 会短暂调整 XMSF/chain3 后恢复，沿用既有授权和守卫，不能因验收额外扩大系统权限。
2. **查看**：实际点击“查看回复”，确认进入 AI 页，未误开课表或产生新用户消息。不要用 `am start ACTION_OPEN` 冒充按钮投递。
3. **停止按钮链路**：新建 RESPONDING 预览并实际点击“停止生成”，目标通知应消失；这只验证真实系统按钮 → PendingIntent → Receiver → 撤销，不能证明有实际任务被取消。
4. **实际任务停止**：主线在真实已有回答上启动本机 TTS，再从展开岛/通知实际点击“停止朗读”；确认声音和活动状态停止、无后续自动重播。实际生成取消另用已授权的真实生成流程，确认流停止且后续迟到 chunk 不再更新，不使用预览伪装真实生成。
5. **过期**：只发一次 RESPONDING 预览，不做清理或第二次发布；约 120 秒后本应用目标 key 应自然消失（分段等待，每次小于 60 秒）。再单发 COMPLETE，3 秒后应自然消失。不要启动真实活动状态续期或改变系统时钟。正常运行中的真实活跃状态每 50 秒续期，不能把其超过 120 秒仍存在误判泄漏。

```powershell
# 完成态短超时。不要与正在运行的 120 秒测试重叠。
& $adb -s $serial shell am start -W -n "$package/com.campusai.debug.CourseReminderProbeActivity" --es operation preview-ai-island --es mode COMPLETE

# 所有观察结束后的显式清理；提前执行会使“自然过期”结论无效。
& $adb -s $serial shell am start -W -n "$package/com.campusai.debug.CourseReminderProbeActivity" --es operation cancel-ai-preview
```

跨账号、旧动作重放和排队撤销已有 `AiIslandNotificationsTest` 的本地守卫证据；本次不为补测试而退出用户账号。未经真实切号/旧动作设备操作，不写成跨账号实机通过。原生通知静态 artwork 不等于持续动画或 GL 渲染。

最终报告分别标注：真实系统通知 UI、真实任务取消、系统自然到期、合成热/省电接线、真实本进程帧压力、真实传感器事件未触发。Android 热等级定义见 [PowerManager](https://developer.android.com/reference/android/os/PowerManager)；禁止通过 `thermalservice override-status`、电池伪装或加热来补齐证明。
