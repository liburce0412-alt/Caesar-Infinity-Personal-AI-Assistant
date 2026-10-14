# Caesar cloud free

状态（2026-10-14）：已配置服务器 Key、部署杭州中转并保留数据安装到测试手机。主机 OOM 故障恢复后，手机真实登录下的快速文本免费兜底、GLM-4V-Flash 图片理解均已完整回复，停止生成也已通过。调整启动策略和健康检查频率后，七个服务健康，手机快速文本再次成功；深度模式仍受 GLM-4.7-Flash 上游拥堵影响，长期稳定性不能仅凭当次测试推定。

## 产品行为

| 输入 | 固定模型 | 行为 |
| --- | --- | --- |
| 纯文本 | `glm-4.7-flash` | 默认快速，不开启思考；用户可手动选择深度 |
| 当前轮附图 | `glm-4v-flash` | 每轮一张 JPEG，最多 4 MiB；不带入旧图片或历史上下文 |

快速文本请求遇到上游拥堵 `1305`、上游 5xx 或 8 秒没有首字时，自动使用免费备用模型 `glm-4-flash-250414`。已开始输出后不切换；深度和图片仍固定原模型。服务器最多调用两次，第二次失败即返回错误。客户端通过 `allowTextFallback` 声明支持，仅在快速纯文本发送 true；旧客户端继续原模型，不会收到无法识别的模型事件。标题显示实际模型，备用回答标注“免费兜底”，报告也保存实际模型。

不接入 GLM-OCR，不配置付费兜底，不设每日个人次数或总次数限制。保留上游并发保护和具体错误提示。模型免费状态以[智谱官方定价](https://docs.bigmodel.cn/cn/guide/start/pricing)为准，免费服务不等于厂商永远不会调整政策。备用模型较旧，复杂问题可手动选择深度，但仍受 4.7 上游可用性影响。

新安装默认选择 Caesar cloud free；已有明确选择保持不变，已完成引导但缺少旧配置的用户仍保留 AUTO。AI 切换使用完整名称的纵向单选弹层；快速/深度与 Provider 分开选择。图片使用独立视觉模型，深度开关只影响纯文本。

该入口第一版提供对话和图片理解，不执行 App 工具、不联网搜索。原有本地、DeepSeek、Gemini、Codex 及其工具能力继续保留。首页问候不使用免费云端后台生成。

## 请求与隐私边界

`Android CaesarCloudFreeEngine → 现有后端 HTTPS /caesar-cloud/v1/chat → Node 中转 → 智谱固定域名`

- 手机携带当前 Caesar 登录会话；中转调用现有 Supabase `/auth/v1/user` 验证真实用户，拒绝无效或匿名会话。登录所有者变化会阻止客户端继续发送。
- 智谱 Key 仅由服务器环境文件提供，不进入 APK、个人 Key 存储或客户端配置。中转不记录正文、凭据、用户标识或上游错误原文，不持久化对话；智谱自身留存政策另行适用。
- 纯文本最多带入最近 12 条、合计 12,000 字符的用户/助手消息。过滤历史健康敏感轮次和工具消息；结构化依据同样受 12,000 字符限制，仅当次显式勾选才附带类型化今日健康摘要。
- 图片沿用本机旋转校正、缩放与 JPEG 重编码流程，仅发送当前新附的一张；文件路径和 Base64 不写入云端会话存储。图片问题最多 2,000 字符，视觉请求不带健康摘要或文本历史。保留 `requiresLocal` 硬边界。
- 快速输出默认 512 tokens，最多 2,048；深度启用思考，思考和输出共享至少 8,192 tokens。视觉输出最多 1,024 tokens。达到上限或中断时提示未完成，不伪装成功。
- 上游流实时转为 `meta/status/delta/done/error` SSE。快速请求 60 秒、深度 120 秒超时；停止生成或连接断开会取消上游。收到部分答案后不会自动重试。
- 未启用文本兜底的请求遇到模型拥堵 `1305` 时，等待 1 秒并使用相同模型、请求和总超时自动重试一次。账号限流、余额/权益、授权异常、内容拒绝分别提示，不盲目重试或切换。日志只保留随机请求编号、固定模型、错误码、HTTP 状态与耗时，不包含错误原文或用户内容。

## 服务端部署准备

当前阿里云部署没有运行 Supabase Edge Functions，因此复用登录服务，单独运行无 npm 依赖的 Node 中转；原 `supabase/functions/ai-chat` 不变。

1. 确认服务器有 Node **22.16+**，核对 `command -v node`；杭州现有环境与模板使用 `/usr/local/bin/node`，其他环境不一致时先调整服务文件。中转 JS 堆上限 96 MiB，服务内存高水位 128 MiB、硬上限 192 MiB、禁用 swap，CPU 最多一核，避免挤占同机业务。
2. 将 `apps/cloud-free/` 的 `server.mjs`、`relay.mjs`、`package.json` 部署到 `/opt/campusai/cloud-free/`，目录与源码由管理员拥有，对服务用户可读。
3. 在服务器受保护的 `/etc/campusai/cloud-free.env` 配置以下变量。目录仅管理员可访问，环境文件权限 `0600`；实际 Key 不进入 Git 或聊天消息。

```dotenv
CAESAR_AUTH_URL=http://127.0.0.1:8000
SUPABASE_ANON_KEY=<现有后端的公开 anon Key>
ZHIPU_API_KEY=<智谱 API Key>
CAESAR_CLOUD_CONCURRENCY=1
```

`CAESAR_AUTH_URL` 指向同一实例的可访问认证网关；杭州当前使用 Envoy，监听 `127.0.0.1:8000`。不能使用 service-role Key 代替 anon Key。认证请求最多等待 15 秒，超时明确返回 `authentication_unavailable`，不调用模型，也不绕过登录校验。并发默认 1 是本项目的保守初值，不代表智谱账户的官方限制；根据控制台实际并发权益调整为 1–8。仅运行一个进程共享队列，不启用 Node cluster。队列最多 4 个，等待最多 8 秒，超出返回 429；无日额度计数器。

4. 安装 [`caesar-cloud-free.service`](../deploy/alicloud/caesar-cloud-free.service)，检查路径和环境文件后启动。服务只监听 `127.0.0.1:8788`，无需开放公网端口。`GET /healthz` 只检查配置存在，不证明 Key、鉴权或上游可用。
5. 将 [`caesar-cloud-free.nginx.conf`](../deploy/alicloud/caesar-cloud-free.nginx.conf) 包含到现有 HTTPS `server` 块，先 `nginx -t` 再重载。保留原有路由。中转路径关闭响应缓冲、缓存与 gzip，请求体上限 6 MiB。
6. 使用真实测试账号在手机验证文字、深度、图片、停止、断网和退出登录；记录首字延迟和完整响应时间，再决定是否对外开放。不能用本地模拟测试推定国内真实延迟。

本轮已按上述边界安装服务、检查并重载 Nginx。`install-cloud-free.py` 接收五个已审阅文件组成的 JSON 包，重复安装保留现有 Key；旧 Nginx 配置仅保留一份受保护的恢复副本。客户端纳入 [v2.2.2](releases/v2.2.2.md)，versionCode 13。

## 本地验证

```powershell
node --test --test-timeout=12000 apps/cloud-free/relay.test.mjs
.\gradlew.bat :apps:android:app:testDebugUnitTest --tests com.campusai.core.network.CaesarCloudFreeEngineTest --tests com.campusai.features.ai.AiProviderPickerTest --tests com.campusai.AiEngineRouterTest --tests com.campusai.features.ai.CaesarTurnAssemblyTest :apps:android:app:lintDebug :apps:android:app:assembleDebug
```

中转测试使用本地 HTTP 与模拟认证/上游，覆盖模型固定、拒绝 OCR/任意模型覆盖、登录校验、并发排队、断流、上游失败、图片边界与取消。Android 验证原有选择保留、当前图片范围、本地内容隔离、SSE 完整性以及浅色/深色大字体弹层选择。真实服务、实际费用与手机验收须单独完成。

2026-10-13 本地结果：中转 11 项、Android 36 项相关测试通过；`lintDebug` 与 `assembleDebug` 通过。系统 1.5 倍字体下另行重跑弹层测试通过，并检查了浅色、深色渲染图与滚动选中行为。截图位于本机 `artifacts/cloud-free-picker-light.png` 和 `artifacts/cloud-free-picker-dark.png`，不是手机截图。

免费兜底及认证错误区分修复后中转 21 项测试通过，覆盖相同模型一次重试、连续拥堵退出、账号错误不重试、停止等待后不重试、首字截止切换、首字后不切换，以及深度/图片/旧客户端保持原路由。Android 云端引擎 9 项、图片存储 2 项测试通过；`lintDebug` 与 benchmark 构建通过。图片路径统一使用 canonical 基准目录，避免 Android `/data/user/0` 与 `/data/data` 别名混用造成导入失败，同时保留目录边界检查。

## 本次线上与手机记录

- 10 月 13 日中转已在 `127.0.0.1:8788` 运行，现有 HTTPS 路由已接通；Key 环境文件权限为 `0600`。当时主站与同机激活服务检查正常；10 月 14 日二者所在主机出现连接超时，不能沿用此前健康结论。
- 手机现安装 `versionCode=12`、`versionName=2.2.2-cloud-fallback-local`，签名保持一致，使用 `install -r` 保留数据，首次安装时间仍为 2026-08-23。原有 DeepSeek 选择在首次覆盖安装后保留，随后在手机手动选中 Caesar cloud free；纵向模型选择已在实机操作。
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
