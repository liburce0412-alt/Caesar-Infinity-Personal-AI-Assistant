# 隔离免费云端工具探测

状态：旧probe五次直接探测和旧relay13次均作为历史保留，旧离线矩阵不变。用户授权的免费文本直用250414、移除4.7及免费深度已完成本地和隔离手机验证；本轮仅2次250414上游均200，summary匹配4条250分钟。14:33:24已恢复非debug benchmark，测试对话与转发清理完成、relay监听0，无当前授权阻塞。不再live调用4.7；生产未变，未提交、推送、部署或发布，整体3.0仍未宣称可发布。

## 已结束的直接探测：probe.mjs

本节仅描述旧直接探测脚本：不启动中转、不连接业务服务器或账号、不读密钥文件、不改 App 或生产配置。现有 Node 24 可直接运行，无新依赖。

默认 `node work/cloud-tools-probe/probe.mjs`（或 `--dry-run`）只输出固定计划，完全不读取环境变量值、不联网。离线固定样例：

```powershell
node work/cloud-tools-probe/probe.mjs --mock
node work/cloud-tools-probe/probe.mjs --dry-run --model=glm-4-flash-250414
node work/cloud-tools-probe/probe.mjs --mock --model=glm-4-flash-250414
node --test --test-isolation=none --test-timeout=10000 work/cloud-tools-probe/probe.test.mjs
```

2026-10-18 本地执行：Node v24.13.1，共72项通过，具体为 `apps/cloud-free/relay.test.mjs` 21项、`apps/cloud-free/tools.test.mjs` 16项、`probe.test.mjs` 35项。默认 dry-run 为0请求，完整 `--mock` 为4次内存模拟请求、0次网络请求；单模型mock最多2次内存请求。单元测试中的“live”分支也只注入合成字符串和内存transport，不调用真实上游；下面另行记录的5次真实请求不计入72项本地测试。

旧直接探测在核实用户既有免费联调授权后，已完成最终的固定250414验证；以下为脚本接口说明，不是再次运行指令。唯一live参数为 `--live --model=glm-4-flash-250414`；省略单模型选项、选择4.7或其他模型都会在读取环境值/请求前拒绝。运行时只从进程环境接收 `ZHIPU_API_KEY`，这是 `apps/cloud-free/server.mjs` 的实际变量名；不读取 `BIGMODEL_API_KEY` 或任何 `.env` 文件。不要把真实 Key 写入命令、脚本、终端输出或报告。缺少变量会在请求前停止。

**旧直接探测的真实请求最终累计5次：首轮2次（4.7为HTTP429 / `provider_1305`，250414为`duplicate_finish`）；第二轮250414为`data_after_finish`，用1次；最终250414工具往返PASS，用2次。** 主线明确增加的协议兼容修正使总数比最初4次计划多1次，没有增加模型、数据或部署范围。旧探测预算已用完，该脚本不再探测4.7、不循环重试、不自动继续请求。脚本不维护跨进程账本，累计数由三个脱敏报告复核；后续独立本机 relay 联调另记账。

最终报告确认：唯一工具 `time_list_records(limit=1)` 经真实模型生成并完成参数校验，脚本只执行1次合成只读操作，返回固定空数组`[]`；第二请求返回12字符自然语言空结果，`stop`和`[DONE]`均通过。工具阶段有1次重复终止，`postFinishDeltaKeys`为`role/content`，固定assistant元数据兼容规则通过。报告没有保存原始回答或参数；这不是对真实个人记录的读取。

- 唯一地址：`https://open.bigmodel.cn/api/paas/v4/chat/completions`；不支持地址覆盖，禁止重定向。
- 初始完整矩阵只有`glm-4.7-flash`、`glm-4-flash-250414`，仍可用于离线mock；当前live限定250414，每模型至多一次工具请求及一次结果回答，当前进程至多 **2 次**请求。没有重试、并发、付费模型或自动换模型。
- 每请求总超时 **25 秒**（含读流），`max_tokens=256`、关闭思考；本次单模型最多合计512输出tokens，单响应最多64KiB，最坏约50秒。
- 逻辑工具仅 `time.list_records`，线上名称 `time_list_records`。直接复用当前 `apps/cloud-free/tools.mjs` 的纯 schema/聚合校验，无服务启动副作用。请求使用与现有 relay 一致的 `tool_choice: auto`、`parallel_tool_calls: false`；要求完整合法参数 `{"limit":1}`、单call id、`finish_reason: tool_calls` 和 `[DONE]` 后，唯一“执行”就是返回固定字符串 `[]`。
- 第二轮回传同一 call id、assistant/tool 消息与合成空数组；必须以 `stop` + `[DONE]` 完成、无新工具调用，回答固定中文句“合成数据中没有时间记录。”（容忍空白和句末标点）。这是可复核的小型结果依据检查，不评估开放式语义质量。厂商若拒绝参数、返回其他合法措辞或不同模型别名，会明确失败，不自动放宽或增加请求。
- 第二轮脱敏报告确认包数2、两次`tool_calls` reason、终止后有值字段1次，但后续工具/参数片段均为0；它没有记录字段名，因此`role=assistant`仍只是解释假设。[官方流式响应示例](https://docs.bigmodel.cn/cn/guide/capabilities/streaming)明确给出`finish_reason: stop`伴随`delta: {"role":"assistant","content":""}`，说明固定assistant角色可以是终止元数据；官方样例本身不证明这次250414的具体包内容。
- 探测与本地relay现已一致：只允许固定`role: assistant`元数据和无新载荷的相同`stop`/`tool_calls`重复终止事件。其他非null角色、冲突reason、终止后新正文/参数/思考内容或未知有值字段仍拒绝；完整参数与`[DONE]`要求不变。诊断只增加`postFinishDeltaKeys`，字段名白名单为`role/content/reasoning_content/tool_calls`；不记录字段值或未知字段名。其余诊断仍仅为固定reason枚举计数、重复终止/后续载荷/工具/参数片段/DONE计数。若出现终止后新参数，必须依据协议证据另行评估，不能自动忽略。
- 只输出允许的模型名、协议、阶段、完成原因、计数、白名单字段名、布尔结果或受控错误码/HTTP状态；不输出或保存请求正文、原始响应、自然语言正文、call id、原始参数、用户信息、Key或错误原文。SSE只在内存有界解析。`mock` 明确标记网络请求为0，不能当作真实上游通过。

官方资料： [免费 GLM-4-Flash-250414 与 Function Calling](https://docs.bigmodel.cn/cn/guide/models/free/glm-4-flash-250414)、[4.7-Flash 中国平台发布](https://www.zhipuai.cn/zh/news/148)、[Function Calling 协议](https://docs.bigmodel.cn/cn/guide/capabilities/function-calling)、[当前定价](https://docs.bigmodel.cn/cn/guide/start/pricing)。2026-10-18 已读取250414免费模型说明、官方流式消息与工具流式文档；初次定价页面读取超时，本脚本不配置任何付费模型。

此次250414真实探测通过只证明对应合成只读请求的上游SSE工具往返；不证明4.7、手机、鉴权中转、真实用户数据访问、写入确认、搜索引用、免费回退、深度模式、并发/长期稳定性或部署通过。relay兼容修订只修改本地源码，未部署。

## 本轮独立本机 relay 联调：local-relay.mjs

用户以“那就继续”授权隔离联调，后续明确“4.7是不是没好过，干脆不要”，授权当前免费文本路由改为直接使用 `glm-4-flash-250414`。候选移除4.7优先请求、跨模型回退及8秒首字切换等待；免费深度入口移除，旧免费DEEP按FAST处理。图片仍用 `glm-4v-flash`，本地及个人云端模式不改。新候选已通过本轮定向与隔离实机验证，尚未部署或发布，不再live请求4.7。2026-10-13曾有一次4.7真实成功记录，本轮失败不能改写为从未成功。

- `local-relay.mjs`复用本地relay，仅监听电脑 `127.0.0.1:18789`，通过 `adb reverse tcp:18789 tcp:18789` 接入手机loopback，不对局域网开放、不改生产服务。
- Android仅显式启用的debug构建接收 `-PcaesarCloudTestEndpoint=http://127.0.0.1:18789/caesar-cloud/v1/chat`，值固定为完整URL；覆盖不持久化，不改Supabase配置，benchmark/release无测试override或调试NSC。
- 使用现有真实Supabase `/auth/v1/user` 鉴权及已登录非匿名账号。Key和配置仅从进程环境读取，不写入文档、命令参数或日志。上游固定BigModel地址，新候选仅使用免费250414文本和4V图片；保留同模型拥堵重试，不增加付费模型或日额度。
- 本轮请求与旧五次直接探测分账，脱敏元数据记入 `artifacts/caesar-3.0-local-relay.jsonl`；只记录受控阶段、模型/工具名、请求标识、计数、HTTP状态、错误码与耗时，不记录凭据、用户正文或工具参数/结果正文。
- 结束时停止relay、撤销本轮reverse，并保留数据恢复同签名非debug benchmark；以实际检查为准，不能用构建成功代替手机恢复。

### 已完成的旧路由联调结果

累计真实鉴权7次均200。上游13次与旧probe的5次分开：

| 模型 | 本轮上游次数 | 结果 |
| --- | ---: | --- |
| `glm-4.7-flash` | 7 | 全部失败：2次首字8秒超时、4次429/1305、1次429/1302 |
| `glm-4-flash-250414` | 6 | 全部200，完成课程、初次时间查询与summary复测的各两轮 |

课程回答23条与数据库一致。时间查询最初答255分钟，实际存储90/25/50/85分钟合计250；App追加仅针对返回记录的 `summary={scope: returned_records, recordCount, totalDurationMinutes}`，relay要求直接引用后，手机复测正确回答4条250分钟，回放 `matchesData=true`。证据为 `artifacts/caesar-3.0-device/summary-retest-result.xml`、`summary-retest-result.png` 及结果收据 `artifacts/caesar-3.0-local-relay-result.json`；没有修改存量记录。

路由更改前Android合并659/659（全量及多轮定向，非新全量）、统计20/20、端点27/27、Node72/72、debug/benchmark构建和lint0错误/80警告通过。新增UnusedResources来自显式test配置未选中的默认debug网络XML，正式构建无调试NSC且原签名一致。收据为 `artifacts/caesar-3.0-combined-test-results.json` 与 `artifacts/caesar-3.0-record-summary-node-tests.log`。这些旧结果不替代当前直用250414/移除免费深度的新变更验证。

### 当前设备与收尾

新直用250414通过Node72/72、Android7类65/65；受影响class整体替换后的合并结果665/665，不是新全量。lint0错误/80警告，debug/benchmark构建6分2秒成功，原证书已核。本轮手机仅2次上游，全部glm-4-flash-250414 HTTP200，真实auth2次200，summary matchesData=true，标题显示250414并回答4条250分钟。App两次POST完成6773ms/1403ms，不能当TTFT。新2次不并入旧13次，收据为 `artifacts/caesar-3.0-drop47-result.json`、`caesar-3.0-drop47-test-cases.json`，手机证据为 `artifacts/caesar-3.0-device/drop47-result.xml` / `drop47-result.png`。

语音固定“现在几点”已被系统识别，仅进入EditText草稿，没有自动发送；主线手动发送后确定性回答北京时间14:05:12，与前后手机时间14:05:11/14:05:12吻合。证据为 `speech-second-result.xml`、`speech-transcribed-draft.png`、`speech-manual-send.xml`（均在 `artifacts/caesar-3.0-device/`）。离页取消录音与TTS未验证。

旧自动审批冲突已解决，无当前授权阻塞。此前三条及本轮唯一新测试对话均已删除，当前账号历史空、legacy未触碰。14:33:24 install-r恢复普通非debug benchmark3.0.0/code14，firstInstall仍为2026-08-23 22:17:32；个人页4h10/4记录/5成就保留，当前第6周课表11门（完整23条仅沿用历史核验）。PCrelay关闭、监听0，adb18789 reverse已移除。最终恢复证据为 `drop47-restored-profile.xml`、`drop47-final-screen.xml`、`drop47-package.txt`（均在artifacts/caesar-3.0-device）。临时产物已清理，必要数值和结果证据保留。

隔离成功不代表生产成功。3.0未部署、未发布，耗电按用户要求不测。
