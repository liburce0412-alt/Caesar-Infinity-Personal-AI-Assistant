# Caesar cloud free

**3.0.0 / versionCode 14。** 生产relay已实际部署免费文本直用`glm-4-flash-250414`和受控文字工具协议，`caesar-cloud-free.service`处于active。4.7优先/回退链和8秒首字切换等待已移除；Android免费深度入口移除，旧免费DEEP状态按FAST处理，图片仍用4V。工具执行继续由App权限、确认策略、按用户轮次幂等和账号边界控制，图片不使用工具。正式手机HTTPS只读工具往返已返回4条250分钟；发布CI成功，正式APK和双下载源已核验。见[3.0工具协议与验证](caesar-3.0-cloud-tools.md)及[总验收清单](caesar-3.0-acceptance.md)。

历史状态（2026-10-14）：已配置服务器Key、部署杭州中转并保留数据安装到测试手机。主机OOM恢复后，手机真实登录下的快速文本免费兜底、GLM-4V-Flash图片理解及停止生成通过；当时深度仍受4.7拥堵影响。以下历史记录保留原结论，不代表当前路由或长期可用性。

## 3.0 行为与当前生产路由

| 输入 | 固定模型 | 行为 |
| --- | --- | --- |
| 纯文本 | `glm-4-flash-250414` | 直接使用，无4.7优先请求或8秒切换等待；免费入口只有快速，旧免费DEEP按FAST处理 |
| 当前轮附图 | `glm-4v-flash` | 每轮一张 JPEG，最多 4 MiB；不带入旧图片或历史上下文 |

文本不再请求4.7，也没有跨模型免费回退或首字切换计时。标题与报告继续保留实际模型，直用250414不标成“免费兜底”。服务端保留输出前`1305`拥堵的一次同模型重试；其他错误明确返回，已开始输出后不重试。Android兼容已保存的免费DEEP状态；绕过新客户端直接提交纯文本`mode=deep`会被中转明确拒绝，不暗中调用4.7。旧`allowTextFallback`字段不再启用跨模型切换。

不接入GLM-OCR、不配置付费兜底、不设每日个人次数或总次数限制。保留上游并发保护和具体错误提示。模型免费状态以[智谱官方定价](https://docs.bigmodel.cn/cn/guide/start/pricing)为准，免费服务不等于厂商永远不会调整政策。复杂任务可使用用户已配置的其他Provider；本次不改变本地或个人云端模式。

新安装默认选择Caesar cloud free；已有明确Provider选择保持不变，已完成引导但缺少旧配置的用户仍保留AUTO。AI切换保留完整名称的纵向单选弹层；只有免费云端移除深度选项，其他Provider保留原有模式。图片使用独立视觉模型。

生产relay支持固定白名单受控文字工具：时间记录读取、新建、编辑、软删除与撤销，课程读取，待确认记忆提议及只读搜索。工具实际执行仍由App守卫控制；中转不直接执行工具。原有本地、DeepSeek、Gemini、Codex及其工具能力继续保留。首页问候不使用免费云端后台生成。

## 请求与隐私边界

`Android CaesarCloudFreeEngine → 现有后端 HTTPS /caesar-cloud/v1/chat → Node 中转 → 智谱固定域名`

- 手机携带当前 Caesar 登录会话；中转调用现有 Supabase `/auth/v1/user` 验证真实用户，拒绝无效或匿名会话。登录所有者变化会阻止客户端继续发送。
- 智谱 Key 仅由服务器环境文件提供，不进入 APK、个人 Key 存储或客户端配置。中转不记录正文、凭据、用户标识或上游错误原文，不持久化对话；智谱自身留存政策另行适用。
- 纯文本最多带入最近 12 条、合计 12,000 字符的用户/助手消息。过滤历史健康敏感轮次和工具消息；结构化依据同样受 12,000 字符限制，仅当次显式勾选才附带类型化今日健康摘要。
- 图片沿用本机旋转校正、缩放与 JPEG 重编码流程，仅发送当前新附的一张；文件路径和 Base64 不写入云端会话存储。图片问题最多 2,000 字符，视觉请求不带健康摘要或文本历史。保留 `requiresLocal` 硬边界。
- 文本输出默认512 tokens，最多2,048；视觉输出最多1,024 tokens。免费路线不再启用深度思考，达到上限或中断时提示未完成，不伪装成功。
- 上游流实时转为`meta/status/delta/done/error` SSE，请求总超时60秒；停止生成或连接断开会取消上游，收到部分答案后不重试。移除的是4.7的8秒首字切换等待，不是登录或并发队列的时限。
- 输出前遇到模型拥堵`1305`时，等待1秒并使用相同模型、请求和总超时重试一次。账号限流、余额/权益、授权异常、内容拒绝分别提示，不盲目重试或切换。日志只保留随机请求编号、固定模型、错误码、HTTP状态与耗时，不包含错误原文或用户内容。

## 更新已有服务端部署

当前阿里云部署没有运行 Supabase Edge Functions，因此复用登录服务，单独运行无 npm 依赖的 Node 中转；原 `supabase/functions/ai-chat` 不变。

[`install-cloud-free.py`](../deploy/alicloud/install-cloud-free.py) 用于更新**已有**relay部署，不负责首次安装或提供密钥。必须已存在普通文件 `/etc/campusai/cloud-free.env`，所有者为root、权限严格为`0600`；缺失或权限不符就拒绝。安装器仅检查文件元数据，不读取、复制、打印或修改环境文件中的Key；服务通过原有systemd环境配置继承鉴权与上游凭据，预检子进程使用不含调用端凭据的最小环境。

1. 保留现有Node **22.16+**与`/usr/local/bin/node`路径、已有服务和nginx站点。受审部署包只包含`server.mjs`、`relay.mjs`、`tools.mjs`、`package.json`、`caesar-cloud-free.service`与`caesar-cloud-free.nginx.conf`；以JSON从stdin传入安装器，不包含密钥。
2. `--check`只暂存并验证JS模块、systemd与完整nginx候选配置；无参数模式在授权后事务更新，保留root受保护恢复快照`/etc/campusai/cloud-free.rollback`。失败时恢复原服务；`--rollback`恢复该快照。安装器不调用Provider、不重启Supabase或独立激活服务。
3. 服务仍只监听`127.0.0.1:8788`，公网入口为既有HTTPS `POST /caesar-cloud/v1/chat`；nginx关闭响应缓冲、缓存与gzip，请求体上限6MiB。JS堆96MiB，服务内存高水位128MiB、硬上限192MiB、禁用swap、CPU最多一核。

既有`CAESAR_AUTH_URL`指向同实例认证网关，杭州为`127.0.0.1:8000`；服务沿用已有anon Key和智谱Key，不能用service-role Key代替anon Key。认证最多等待15秒，失败不调用模型。并发默认1、可配置1–8，单进程队列最多4个、等待最多8秒，超出返回429；无每日额度计数器。`GET /healthz`仅证明配置就绪与固定模型，不证明真实鉴权或上游可用；最终生产请求检查单独记录，不再发起4.7 live验证。

## 部署前的隔离验证记录（2026-10-18）

直用250414已完成Node72/72、Android7类65/65；按受影响class整体替换后的合并结果为665/665（不是新全量）。lint0错误/80警告，debug/benchmark构建6分2秒成功，原证书已核。独立手机验收仅2次上游，均为250414 HTTP200，鉴权2次200；标题显示250414，time.list_records回放matchesData=true，回答4条250分钟。App两次POST完成耗时6773ms/1403ms，不是TTFT。结果见 `artifacts/caesar-3.0-drop47-result.json`、`caesar-3.0-drop47-test-cases.json` 与 `artifacts/caesar-3.0-device/drop47-result.xml/png`；本轮2次与旧13次分账。

移除4.7之前，Android合并659项（全量加多轮定向）、Node72项、lint0错误/80警告及debug/benchmark构建通过；这些结果不能替代新路由的定向测试。隔离手机summary复测已正确返回4条250分钟，回放`matchesData=true`，证据为 `artifacts/caesar-3.0-device/summary-retest-result.*`。此前隔离relay累计鉴权7次200、上游13次：4.7七次全失败（2次8秒超时、4次1305、1次1302），250414六次均200。与旧直接probe五次分开统计。

该阶段语音“现在几点”只进入草稿，手动发送后回答北京时间14:05:12，离页取消和TTS当时尚未验。旧审批冲突已解决；该阶段测试对话均已删除、legacy未动。14:33:24曾install-r恢复普通非debug benchmark3.0.0/code14，firstInstall仍为2026-08-23 22:17:32；个人页4h10/4记录/5成就保留，课表第6周11门。随后关闭隔离relay并撤销18789转发。证据为 `drop47-restored-profile.xml`、`drop47-final-screen.xml`、`drop47-package.txt`（均在artifacts/caesar-3.0-device）。这是部署前检查点，后续验收与发布结果以[总验收记录](caesar-3.0-acceptance.md)为准，当前生产部署状态见本文开头。

```powershell
node --test --test-timeout=12000 apps/cloud-free/relay.test.mjs
.\gradlew.bat :apps:android:app:testDebugUnitTest --tests com.campusai.core.network.CaesarCloudFreeEngineTest --tests com.campusai.features.ai.AiProviderPickerTest --tests com.campusai.AiEngineRouterTest --tests com.campusai.features.ai.CaesarTurnAssemblyTest :apps:android:app:lintDebug :apps:android:app:assembleDebug
```

中转测试使用本地 HTTP 与模拟认证/上游，覆盖模型固定、拒绝 OCR/任意模型覆盖、登录校验、并发排队、断流、上游失败、图片边界与取消。Android 验证原有选择保留、当前图片范围、本地内容隔离、SSE 完整性以及浅色/深色大字体弹层选择。真实服务、实际费用与手机验收须单独完成。

2026-10-13 本地结果：中转 11 项、Android 36 项相关测试通过；`lintDebug` 与 `assembleDebug` 通过。系统 1.5 倍字体下另行重跑弹层测试通过，并检查了浅色、深色渲染图与滚动选中行为。截图位于本机 `artifacts/cloud-free-picker-light.png` 和 `artifacts/cloud-free-picker-dark.png`，不是手机截图。

免费兜底及认证错误区分修复后中转 21 项测试通过，覆盖相同模型一次重试、连续拥堵退出、账号错误不重试、停止等待后不重试、首字截止切换、首字后不切换，以及深度/图片/旧客户端保持原路由。Android 云端引擎 9 项、图片存储 2 项测试通过；`lintDebug` 与 benchmark 构建通过。图片路径统一使用 canonical 基准目录，避免 Android `/data/user/0` 与 `/data/data` 别名混用造成导入失败，同时保留目录边界检查。

## 历史线上与手机记录（2026-10-13 至 14）

- 10 月 13 日中转已在 `127.0.0.1:8788` 运行，现有 HTTPS 路由已接通；Key 环境文件权限为 `0600`。当时主站与同机激活服务检查正常；10 月 14 日二者所在主机出现连接超时，不能沿用此前健康结论。
- 当时手机安装 `versionCode=12`、`versionName=2.2.2-cloud-fallback-local`，签名保持一致，使用 `install -r` 保留数据，首次安装时间仍为 2026-08-23。原有DeepSeek选择在首次覆盖安装后保留，随后在手机手动选中Caesar cloud free；这不是当前3.0包状态。
- GLM-4V-Flash 从生产服务器识别合成红方块/蓝圆形成功，首字约 775 ms、完成约 995 ms；这是服务器直连测试，尚不代表手机图片端到端通过。
- GLM-4.7-Flash 多次返回 HTTP 429 / `1305`，并出现 25 秒和 45 秒诊断超时；手机实际请求也曾出现 429/502。
- 诊断时另测 GLM-4-Flash-250414，首字约 228 ms、完成约 249 ms；用户随后明确允许免费兜底，已部署相应服务端。GLM-4.5-Flash 官方说明已路由至 4.7，不能视为独立备用服务。
- 新部署代码使用真实智谱上游、独立合成鉴权的一次验证中，GLM-4.7-Flash 恢复回答，首字约 2,694 ms、完整完成约 2,750 ms；这证明拥堵是间歇性的，不构成稳定性保证，也不替代手机真实登录验证。
- 手机真实登录请求 `17+25=` 已通过免费兜底完整返回 `17 + 25 = 42`，标题显示 `GLM-4-Flash · 免费兜底`，状态为“回复已完成”；服务端对应记录为首字等待超时后切换。证据位于本机忽略目录 `artifacts/cloud-free-20261013/device-text-result.png`。
- v12 已修复手机图片导入失败。10 月 14 日主机恢复后，手机附加合成测试图并发送，标题显示 `GLM-4V-Flash · 免费`、状态“回复已完成”，正确回答左侧红色正方形、右侧蓝色圆形；证据 `artifacts/cloud-free-20261013/device-vision-recovery.png`。这是当前图片端到端成功，不再仅是服务器直连测试。
- 10 月 14 日手机文本复验 `23+19=` 完整返回 `23+19=42`，显示免费兜底，服务端对应首字截止切换；证据 `artifacts/cloud-free-20261013/device-text-recovery.png`。
- 在手机确实出现“停止生成”按钮时点击停止，随后显示“已停止生成”“已停止本次生成。”，发送按钮恢复；证据 `artifacts/cloud-free-20261013/device-cancel-recovery.png`。此前快速回复在找到停止按钮前已结束的一轮没有计作取消成功。
- 手动深度模式实际保持 GLM-4.7-Flash；一轮请求两次收到上游 `1305`，约 1.54 秒后明确显示模型拥堵，不伪装完成，也未擅自降级。测试结束已恢复快速模式。
- 最终运行配置下手机再次发送 `75+25=`，免费兜底完整返回 `100`；证据 `artifacts/cloud-free-20261013/device-final-text.png`。单次功能验证不是模型推理质量、长期稳定性或并发性能保证。断网及退出登录的手机专项测试本轮未执行，相关鉴权与中断边界仅有本地自动化覆盖。

### 2026-10-14 主机故障排查

热点无线 ADB 已连接，手机测试版 v12 与原首次安装时间复核一致。阿里云控制台显示实例仍运行，但 CPU 长时间接近 100%，内存上升并伴随持续磁盘读取。救援控制台出现 `Out of memory: Killed process ... (beam.smp)`，以及 journald、resolved 和发布镜像服务反复启动失败；这是主机级故障，不能归因于智谱模型拥堵。具体内存占用来源和资源限制的实际生效状态仍待主机恢复后核对。

正常重启在 09:03:25 完成，用户完成安全验证。七个容器实际仍归属 `campusai.slice`，high 800 MiB、max 900 MiB；重建 auth/envoy 后确认临时软保留值已回到 0。此前 `docker update --memory-reservation 0` 并未清除旧值，因此没有将该命令成功退出当作撤回成功。独立激活服务返回 200，主站外部请求返回 200、约 0.14 秒；启动约 47 分钟后七个容器健康，主机可用内存约 575 MiB，近期内存/IO 压力为零。

内核 OOM 快照记录 105 个 `runc`、32 个 `runc:[2:INIT]`，合计 RSS 约 354 MiB。运行时进程积压是已确认的重要内存来源；密集健康检查可能加重压力，最初触发原因尚未完全定论。正常探测从大多数服务的每 5 秒改为每 30 秒，启动阶段保持每 5 秒探测。重启策略改为 `on-failure:5`，由既有 systemd 流程按序冷启动，避免 Docker 抢先并发启动导致数据库未就绪时其他服务失败。保留七个服务、镜像、密钥、卷、认证路由和原有资源上限。

配置已部署并通过完整后端服务重启验证：七个容器均读回 `on-failure:5`、health interval 30s、start interval 5s、reservation 0，状态 healthy。主站、激活服务均返回 200；主机可用内存约 598 MiB，`runc` 数量为 0，本次启动以来 core hard-limit/OOM/OOM-kill 均为 0。新配置尚未额外执行一次整机重启。受保护的 Compose 恢复副本保留，候选配置文件已清理；手机自建测试图片和 UI 转储已清理，成功截图保留在忽略目录。该记录采集时尚未推送或发布，后续用户已授权以 v2.2.2 推送源码并公开发布签名 APK。
