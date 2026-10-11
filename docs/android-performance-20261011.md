# Android 滚动性能修复与真机记录（2026-10-11）

此次修复针对“我的”页面上下滑动卡顿，并解除背景渲染的约 60 fps 限制。保持原有玻璃、折射、流光、触摸反馈、图片分辨率及开屏动画，不改手机刷新率设置或系统无障碍服务。

## 实现

- 年度热图以周提供无障碍摘要，每个已发生的日期保留独立自定义操作；屏幕上的格子、点击与涟漪保持不变。减少滚动时逐个计算格子无障碍边界的开销。
- 节次课表的课程卡片提供完整课程描述和点击动作，清除子文本的重复语义。
- 原始 logo 位图由根部 `BrandLaunchHost` 提供，开屏与各页面复用；列表项被回收、重新出现时不重复解码。
- AUTO/HIGH 背景跟随每次显示刷新，移除原先 16 ms 的请求间隔；向自己的 Surface 提交 120 Hz 偏好，暂停时撤销。LOW 仍保留原有节能节奏。系统仍可按屏幕能力、电量与温度选择实际刷新率。
- 新增非 debuggable、shell-profileable 的 `benchmark` 构建，配置继承 release，使用本地调试签名。它用于测量，不作为发布包。

## 对照条件

基线为 `1a6270d` 的应用源码，加相同 benchmark 构建配置；候选包为此次修复。两者均以 `adb install -r` 覆盖安装，保留应用数据。使用小米 15 Pro、HyperOS 4 / Android 17、1440 × 3200、原有 HIGH / Strba Fluid / 浅色 / ORIGINAL 设置，全部动效开启，系统截图无障碍服务保持开启。

通过手机热点连接无线 ADB；每段 Perfetto 记录 18 秒，包含 FrameTimeline、调度和 gfx/view/input/dalvik。采样期间不录屏。测试为单台设备、有限时长的顺序对照，不是长期热稳定性测试。

### 相同慢拖脚本

上下交替拖动 6 轮，每次 650 ms、间隔 300 ms。以 FrameTimeline 的 `App Deadline Missed` 统计应用超时，不混用厂商 `gfxinfo` 的两套 jank 比例。

| 项目 | 修复前 | 修复后 |
| --- | ---: | ---: |
| “我的”应用超时帧 | 120 / 1010（11.88%） | 9 / 1146（0.79%） |
| “我的”FrameTimeline 时长 p95 / p99 | 20.00 / 21.98 ms | 12.15 / 13.81 ms |
| “我的”语义检查总耗时 | 669.39 ms | 321.59 ms |
| “我的”语义检查 p95 | 8.45 ms | 3.96 ms |
| 背景 GL 绘制回调平均频率 | 59.35 Hz | 120.01 Hz |
| 课表应用超时帧 | 0 / 1060 | 1 / 1066（0.09%） |

FrameTimeline 单帧时长包含渲染流水线等待，不能直接当作帧间隔或用其倒数计算 FPS。慢拖脚本的候选输入事件中位间隔为 11.67 ms，也不是 120 Hz 手指输入；因此另测松手后的惯性滚动。

### 候选包惯性滚动

150 ms 快速滑动，松手等待 2 秒，上下各 3 次。以松手后 50–1800 ms 窗口中真正呈现的 UI 帧计算：通过 `display_frame_token` 关联 SurfaceFlinger，使用相邻正呈现间隔；每次手势分别统计，排除手势之间的静止时间。没有因间隔较长而剔除慢帧。

| 页面 | 呈现间隔样本 | 实际平均 FPS | 呈现间隔 p95 / p99 | 整段应用超时帧 |
| --- | ---: | ---: | ---: | ---: |
| 我的 | 423 | 119.82 | 8.33 / 8.33 ms | 7 / 583（1.20%） |
| 课表 | 404 | 120.11 | 8.33 / 8.33 ms | 0 / 561 |
| 首页 | 240 | 120.10 | 8.33 / 8.33 ms | 0 / 382 |

背景 Surface 的独立呈现时间戳抽样为 126 帧，平均 119.16 fps，p95/p99 间隔约 8.33 ms。该短样本用于确认真实呈现，不是仅凭屏幕 120 Hz 标签或 `onDrawFrame` 次数作结论。

## 验证与边界

- 全量本地单元/Compose 测试：123 个测试类、545 项通过，无失败、错误或跳过；含热图逐日操作、未来日期不可点击、课表详情、logo 跨挂载复用与开屏动画检查。
- `assembleBenchmark`、`lintDebug` 通过；Lint 为 0 错误、77 警告。
- 真机课程卡片可展开详情；首页、时间、树洞、心愿墙、“我的”切换正常，玻璃与原图显示保留。
- 10 次主页签切换的记录仍有 5 / 804 应用超时帧，另外有 1 个 Dropped Frame；局部首次布局/绘制仍会出现尖峰。此轮已改善主要滚动问题，但不能称所有操作、所有数据量或长时间运行都稳定无掉帧。
- 树洞当前仅有少量内容，心愿墙没有可用长列表；尚不覆盖大数据列表、AI 长对话流式生成、持续高温及其他机型。
- 未增加用户可见的测试/预览入口。上述数据来自发布前的非调试候选包；v2.2.1 正式 APK 由既有 Release Android 工作流构建与签名。

原始基线/候选 APK、Perfetto、采样脚本、SQL 与构建日志保留在本机 `D:\DevTools\CampusAI-performance-20261011`，不提交私人截图和 trace。SQL 分别记录拖动与惯性滚动的窗口定义，便于复核。

## 参考

- [Android：Surface 帧率偏好](https://developer.android.com/media/optimize/performance/frame-rate)
- [Compose：无障碍自定义操作](https://developer.android.com/develop/ui/compose/accessibility/semantics)
- [Compose：合并与清除语义](https://developer.android.com/develop/ui/compose/accessibility/merging-clearing)
- [Perfetto：FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline)
