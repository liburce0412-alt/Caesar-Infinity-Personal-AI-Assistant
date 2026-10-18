# Caesar 3.0 免费云端工具协议

状态：免费文本直用250414、移除4.7及免费深度已完成源码和手机候选验证，图片4V保留。本轮Node72/72、Android65/65、lint与构建通过；手机仅2次250414上游均200，鉴权2次200，标题与4条250分钟回答正确，summary matchesData=true。14:33:24已恢复普通非debug benchmark并撤测试转发，历史已清理、legacy未动、relay已停，无当前授权阻塞。生产未变，未提交、推送、部署或发布；整体验收仍有剩余。

## 协议与执行边界

当前新路由Node72/72、Android7类65/65通过；按受影响class整体替换后的Android合并结果665/665，不是新全量。lint0错误/80警告，debug/benchmark构建6分2秒成功且原证书已核。新路由调用与旧13次分账，见 `artifacts/caesar-3.0-drop47-result.json`、`artifacts/caesar-3.0-drop47-test-cases.json` 与本轮测试/构建日志。

- 客户端通过 `toolProtocol: 1` 与工具名称数组选择能力；中转 `meta` 必须确认同一版本。旧客户端仍使用原聊天/图片协议；新客户端连接旧服务时明确提示协议未开通，不伪装工具成功。
- 中转只构造已有 App 工具的固定 schema：`web.search`、`time.list_records/create_record/edit_record/delete_record/undo_delete`、`course.list`、`memory.propose`。不透传客户端自定义 schema，不开放本地记忆读取、健康详细工具、私聊发送、公开发布、任意 HTTP、SQL、Shell、厂商付费搜索或 MCP。
- 工具实际执行仍由 App 的 `CaesarAgentEngine` / Registry / Repository 负责权限、参数、确认、持久幂等、取消与账号隔离。中转只返回请求，不执行手机或服务器动作。新协议本身不会替代这些检查。
- `ProviderToolCallEnvelope` 保留真实 call id / wire name / 参数；只回传当前最后一条 user 后的完整 call/result 对。旧轮工具结果、健康敏感消息及其他 Provider 的思考/签名不回传。下一轮输出修复可保留本轮已执行结果，但不重新投影工具。
- 文本历史仍最多 12 条/12,000 字；工具回放最多 12 对，每条结果 8,000 字、合计 32,000 字，参数最多 4,096 字。超限明确停止，不截断 JSON 后继续执行。单条模型回复最多一个调用，未知/并行/不完整/错结束标记调用不会交给 App。
- 结构化context的12,000字符硬上限不变：仅对已知的课程、重叠提示、帖子及近期记录明细按完整JSON条目控制预算，记录Total/Included/Omitted计数；不裁剪未知字段、任务事实或确认参数。可选明细整体目标10,000字符，为当轮明确同意的类型化健康摘要留出空间；必要内容仍超限则在读取token或网络访问前明确拒绝。`course.list`继续返回原有`data`数组，完整保留已包含课程的字段，数组预算7,500字符并提供覆盖计数，以满足8,000字符工具回放上限。
- `time.list_records`保留原有`{ok, data: [...]}`，追加App计算的`summary`：`scope=returned_records`、`recordCount`、`totalDurationMinutes`。合计只覆盖limit后的本次返回记录，直接相加已存`durationMinutes`，不重算或改写历史，不代表全部历史、某年贡献或其他context统计。relay要求模型引用该summary；原有owner/本机旧记录读取条件不变，本地回归及4条250分钟的手机复测均通过。
- 按用户2026-10-18的新授权，免费文本直接使用 `glm-4-flash-250414`，移除4.7优先请求、跨模型回退及8秒首字切换等待。Android移除免费深度入口，旧免费DEEP按FAST处理；直接发送纯文本deep协议会被明确拒绝。保留输出前1305的一次同模型重试、4V单张图片、本地和个人云端模式，不增加付费服务或每日限额。新路由候选已通过本轮定向与隔离实机验证，不再live请求4.7。
- 未启用未经当前两模型核实的厂商 `tool_stream` 扩展；标准 SSE 中一次完整或分段的 `tool_calls` 都可解析。完整参数、正确 finish_reason 和流结束均确认后，客户端才发出一次 `ToolCallRequested`。
- 允许无新载荷且相同 reason 的重复终止事件，固定 `delta.role: assistant` 作为角色元数据；其他非 null 角色、冲突 reason、终止后的正文/思考/工具参数或未知有值字段继续拒绝。此兼容修订不放宽完整参数与 `[DONE]` 校验，也不触发额外工具执行。
- 服务端每次请求加入真实 `Asia/Shanghai` 时钟。图片只包含时钟、当前问题和当前 JPEG，不附工具、私有上下文、健康摘要或历史图片。
- 自由文本记忆不按关键词猜测是否安全：所有云端请求移除 `confirmedMemories`、禁止 `memory.list` 投影与回放，中转也剥离旧客户端附带的记忆上下文。记忆建议只回传建议 ID 与未确认状态。记忆所有数据库读取与修改按捕获的 owner 精确限定；旧行在未发布的 10→11 迁移中保留为 `local_user`，不自动归属已登录账号。本地记忆读取结果与相关回复沿用敏感历史标记，后续切换云端不自动回传。

## 官方证据与待验项

2026-10-18 查阅：

- [GLM-4-Flash-250414 官方说明](https://docs.bigmodel.cn/cn/guide/models/free/glm-4-flash-250414)明确列出 Function Calling。
- [官方 GLM-4.7 系列说明](https://docs.z.ai/guides/llm/glm-4.7)列出 Flash 与 Function Call；[中国平台官方发布说明](https://www.zhipuai.cn/zh/news/148)确认 4.7-Flash 在 BigModel 免费提供。中国旧 4.7 专页本次读取超时，不能把国际文档或开源能力当作杭州真实调用验收。
- [官方工具调用协议](https://docs.bigmodel.cn/cn/guide/capabilities/function-calling)与[工具流式说明](https://docs.bigmodel.cn/cn/guide/capabilities/stream-tool)描述 `tools`、`tool_calls`、call id 与 `role: tool` 结果回传；平台不会替客户端执行函数。
- [官方流式消息样例](https://docs.bigmodel.cn/cn/guide/capabilities/streaming)的终止包包含 `delta: {"role":"assistant","content":""}`，是固定 assistant 角色可作为空终止元数据的依据。

必须继续验证：直用250414与旧免费DEEP兼容的新候选、Android工具链的确认/拒绝/撤销/账号切换、真实搜索引用、停止与断网恢复，以及生产部署后的完整链路。移除4.7后不再将其live往返列为验收项；2026-10-13一次真实4.7成功仍保留在[历史记录](caesar-cloud-free.md)，不能改写为从未成功。本轮隔离结果不推定长期稳定性。既有 `time.edit/delete/undo` 与 `course.delete` 保留DAO原子所有者限定（当前owner或local_user），无可写行明确拒绝；新幂等键含版本与捕获owner，旧动作历史保留而不被新请求复用。

## 路由更改前的本地结果

`node --test --test-isolation=none --test-timeout=12000 apps/cloud-free/relay.test.mjs apps/cloud-free/tools.test.mjs work/cloud-tools-probe/probe.test.mjs`：**72 项通过**，具体为 `relay.test.mjs` 21 项、`tools.test.mjs` 16 项、`probe.test.mjs` 35 项。测试均使用模拟上游，覆盖原聊天/图片/限流/回退/取消、协议握手、固定白名单、分片回放、无效/并行/未完整调用拒绝、首工具片段后的不切换、取消时上游中止、旧记忆上下文剥离，以及重复终止/assistant 角色元数据兼容、终止后载荷拒绝、请求上限与诊断脱敏。默认 Node 子进程隔离被环境以 `spawn EPERM` 拒绝，改用无子进程模式后通过。这72项不包含下面的真实上游请求。

`CaesarCloudFreeToolsTest` 新增 9 项 Android 本地用例；执行结果由主线统一记录。`git diff --check` 通过。

`CaesarAppToolsOwnerBoundaryTest` 新增 6 项真实 Room 用例，覆盖跨账号编辑/删除/恢复拒绝、课程删除权限、本机旧记录兼容、缺失记录拒绝、同账号重试不重复写入、不同账号独立幂等、旧动作保留与记忆工具所有者边界。另补充 `CaesarMemoryStoreTest` 跨账号/批量删除测试、`AiHistoryMigrationTest` 旧记忆保留断言、`CaesarAgentProjectionBoundaryTest` 本地记忆授权与敏感标记、`CloudProviderCoreTest` 各个人云端记忆回放剥离；这些 Android 检查的执行结果同样以主线记录为准。

课程context修复新增7项回归：`CaesarCloudContextBudgetTest` 5项与OwnerBoundary的2项真实Room/Agent用例覆盖上下文预算、必需字段、课程数组兼容、账号范围及大结果回放。该批定向60/60通过；后续summary修复补3项真实Room用例，覆盖250分钟、limit2＝115/2、空结果0、账号与本机旧记录边界及源实体不变。

路由更改前端点27/27、统计20/20（OwnerBoundary11＋FreeTools9）、Android合并659/659（全量＋多轮定向，非新全量）、Node72/72及debug/benchmark构建通过，lint0错误/80警告；正式构建无调试NSC、空override及原签名已核验。收据为 `artifacts/caesar-3.0-combined-test-results.json` 与 `artifacts/caesar-3.0-record-summary-node-tests.log`。这些结果不替代本次用户授权直用250414/移除免费深度的新变更验证。

## Android设备重试（2026-10-18）

设备在全部 **73条原始课程仍保留** 时重试默认Caesar cloud free“查询课程”，原“对话依据过长，请减少所选内容。”错误消失，显示 **“免费云端尚未开通当前工具协议，请稍后重试。”**。截图及XML保存在 `artifacts/caesar-3.0-device/course-retry-result.*`。该次只验证长度修复及旧服务缺能力时的真实错误提示，没有完成工具执行/回放；后来的隔离本机relay结果另记如下，旧生产relay阻塞仍未解除。

用户授权的课程清理已完成：50条错误安排由原生UI删除，历史核对保留23条的12个字段不变。最终恢复后的当前第6周仍11门，个人页4h10/4记录/5成就保留；本轮未再次导出完整23条。课程清理不是云工具删除验收。14:33:24已恢复同签名非debug benchmark；53张新UI归档和159张历史PNG恢复的既有证据保留。

## 旧直接探测的真实上游结果（2026-10-18，共5次）

主线核实既有免费联调授权后，从本机隔离脚本直连固定官方 BigModel endpoint；没有访问真实用户记录、账号或网页，也没有经过生产 relay 或 Android App。

| 模型 | 累计真实请求 | 结果 |
| --- | ---: | --- |
| `glm-4.7-flash` | 1 | HTTP 429 / `provider_1305`，未取得完整工具请求；工具能力未通过本次验收 |
| `glm-4-flash-250414` | 4 | 前两次在重复终止兼容校验处停止；修订后最后2次请求完成工具→合成结果→自然语言回答 |

最终250414报告 `artifacts/caesar-3.0-cloud-live-250414-final.json` 为 `ok: true`：第一请求完整生成唯一 `time_list_records` 调用，参数按固定 `limit=1` 校验通过；脚本只执行一次合成只读工具并回传固定 `[]`。第二请求以 `stop` 和 `[DONE]` 结束，12字符回答通过固定空结果句校验。工具阶段记录一次无新载荷的重复 `tool_calls` reason，后续delta白名单字段为 `role/content`；正文、参数和call id没有写入报告。

前两轮脱敏报告分别为 `artifacts/caesar-3.0-cloud-live-probe.json`（2次，`1305`/`duplicate_finish`）与 `artifacts/caesar-3.0-cloud-live-250414.json`（1次，`data_after_finish`）。最终往返再用2次，**旧脚本累计5次**；相比最初4次计划，主线明确增加1次用于实际协议兼容修正。该直接探测已结束，不再发起该脚本的live请求；不包含下述另行授权的本机relay联调。

这个结果只证明250414在本次合成只读场景中的真实SSE工具往返；不证明4.7、真实记录访问、写入确认、搜索引用、免费回退、深度模式、Android集成、长期稳定性或生产部署通过。探测边界与复核方式见 [隔离探测说明](../work/cloud-tools-probe/README.md)。

## 新本机relay的真实手机联调（2026-10-18，上游13次）

用户授权的隔离联调通过本机127.0.0.1:18789、adb reverse及debug固定URL接入，复用真实现有Supabase鉴权。累计鉴权7次均200；上游13次独立于旧probe五次：4.7七次全失败（2次8秒首字超时、4次429/1305、1次429/1302），250414六次均200。日志 `artifacts/caesar-3.0-local-relay.jsonl`仅含脱敏元数据，不含凭据、正文或工具参数/结果正文。这些为移除4.7之前的路由证据，当前新候选不再live调用4.7。

`course.list`完整两轮回答23条与数据库一致。`time.list_records`首次虽完成执行/回放却答255分钟；真实90/25/50/85分钟合计250。App增加summary且relay要求引用后，手机复测已正确回答4条250分钟，回放 `matchesData=true`；证据为 `artifacts/caesar-3.0-device/summary-retest-result.xml`、`summary-retest-result.png` 及 `artifacts/caesar-3.0-local-relay-result.json`。未改存量记录，summary只代表返回片段。

语音“现在几点”识别只进入EditText草稿，未自动发送；手动发送后回答北京时间14:05:12与前后手机秒吻合。`speech-second-result.xml`、`speech-transcribed-draft.png`、`speech-manual-send.xml`均在 `artifacts/caesar-3.0-device/`；离页取消与TTS尚未验证。

新直用250414手机验收仅2次上游，全部glm-4-flash-250414 HTTP200，真实鉴权2次200；time.list_records回放summary matchesData=true，手机标题250414、回答4条250分钟。App POST完成耗时6773ms/1403ms，不是TTFT。见 `artifacts/caesar-3.0-device/drop47-result.xml`、`drop47-result.png` 及 `artifacts/caesar-3.0-drop47-result.json`；与上述旧13次分账。

旧审批冲突已解决，无当前授权阻塞。本轮唯一新测试对话已清理，当前账号历史空、legacy未动。14:33:24已保留数据恢复普通非debug benchmark3.0.0/code14，firstInstall为2026-08-23 22:17:32不变；PCrelay关闭、监听0，adb18789 reverse已撤。恢复证据见artifacts/caesar-3.0-device下的drop47-restored-profile.xml、drop47-final-screen.xml、drop47-package.txt。

以上为较早轮次记录。2026-10-18 本轮最终收尾已将包含 `apps/cloud-free/tools.mjs` 的新中转部署到生产，服务 active、回环 ready=true/model250414，公网未登录请求按预期401。正式端到端和发布状态以 [3.0 验收记录](caesar-3.0-acceptance.md) 为准；耗电不测。


本轮追加真实手机联调：搜索两轮、合成图片识别、一次经授权的健康摘要及记录读取共7次上游 HTTP200，全部使用250414或4V。TTS起停和语音离页停止通过；真实健康同步返回无指标，已修复为不上传空摘要、保留草稿并给出本地说明。四条本轮测试对话已删除，用户历史与正式记录保持。临时relay/转发在恢复正式包时撤销。
