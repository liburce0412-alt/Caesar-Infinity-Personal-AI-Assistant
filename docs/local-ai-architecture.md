# CampusAI Android 本地 AI 架构

状态：代码实现完成；arm64 MNN 原生桥已编译和链接，并已在 Xiaomi 2410DPN6CC（Android 16、arm64-v8a、约 15 GB RAM）完成已下载模型保留、真实本地推理与飞行模式回归。完整下载中断、抓包、100-token 基准和五分钟温度测试仍是发布门禁。

## 边界

- Android 云端：`PersonalDeepSeekAiEngine → PersonalDeepSeekClient → api.deepseek.com`。用户 Key 由 Android Keystore 加密保存，仅在发起固定域名请求时读取；回复仍映射为统一 `Meta/Status/Delta/Done/Error` 事件。
- 免费云端（尚未部署）：`CaesarCloudFreeEngine → /caesar-cloud/v1/chat → Node 中转 → 智谱`。纯文本 GLM-4.7-Flash，当前附图 GLM-4V-Flash，不接 GLM-OCR；登录鉴权和 Key 均在服务端验证/管理，不设每日次数限制。详见 [Caesar cloud free](caesar-cloud-free.md)。
- 原服务端：`supabase/functions/ai-chat` 和原有五类 SSE 协议保留给 Web/服务端场景，Android 不调用该 Edge Function。
- 本地：`LocalMnnAiEngine → MnnNativeBridge → MNN 3.6.1 CPU → Qwen3.5-2B MNN 4-bit`。不创建任何网络客户端。
- 输出边界：`LocalPromptPolicy` 要求直接给出答案但不依赖自定义标签；`LocalOutputGuard` 会流式放行显式最终段，并把没有标签的普通输出暂存在内存中，生成结束后确认其不含思考过程、系统提示、内部约束或结构化私有 JSON 再分段发送。没有安全答案时返回可恢复错误，不显示原始输出。
- 模板边界：该 MNN 模型的官方 `llm_config.json` 会在生成尾部无条件追加开放的 `<think>`。JNI 在应用 chat template 后，仅把最后一个仍未闭合且尾部只有空白的 generation think 段改写为立即闭合；随后用官方 tokenizer 编码并调用 token-id 入口。历史消息中的完整 think 文本不会被改写，提示词也不会进入日志。
- 统一入口：`AiEngineRouter` 按 `AiProvider`、个人 Key 是否存在、FAST/DEEP、网络和 `LocalModelState` 决定引擎。聊天 UI 只消费 `AiEvent`。
- 生命周期：同一时刻只允许一个本地生成；Kotlin `Mutex` 与 native mutex 双重保护。取消按 token 生效；严重内存压力、删除模型或空闲 5 分钟后释放实例。内存压力中断会返回 `local_memory_pressure` 可恢复错误，保留当前会话的 2B/4B 锁模，不会静默切档。

## 模型和运行时

- 模型：`taobao-mnn/Qwen3.5-2B-MNN`，revision `f3307fcae4c41b63c9a924e0a3de17fd7ad09ae4`，完整大小 `1,386,688,857` bytes。
- MNN：`3.6.1`，commit `d407447ed56c4121a11ccbd266dc184ca1ead0c2`。
- APK 只包含官方 arm64 runtime 与 CampusAI JNI bridge，不含 `.mnn`、权重或 tokenizer。
- 基线：CPU、4 线程、low precision/memory、4096 context、512 max output、thinking off。未启用 GPU/Vulkan/QNN 推理。

## 下载与 Ready 事务

1. WorkManager 使用固定 manifest 生成 Hugging Face revision URL；调用方不能传入 URL。
2. 每个文件写入应用私有 `noBackupFilesDir/models/.<id>-<version>.staging/<file>.part`。
3. 服务端支持 Range 时从现有长度续传；返回完整内容时安全重置该 `.part`。
4. 每个文件完成后核对固定 size 与 SHA-256，再在 staging 中改名。
5. 全部文件再次校验，写入 `.ready.json`，最后同文件系统目录改名到 active 目录。此动作之前永远不报告 Ready。
6. 校验失败删除损坏临时文件；网络失败由 WorkManager 重试；暂停保留 `.part`；进程重启后 WorkManager 和 staging 文件共同恢复进度。
7. 删除或重新下载严格按“取消 WorkManager 与 HTTP Call → 等待 MNN mutex 释放实例 → 删除目录”执行，避免仍有文件句柄时回收模型。

## CampusAI 任务接入

`CampusAiTaskFactory` 提供聊天、首页洞察、今日/周/月总结、结构化建议、课程整理和自然语言时间记录解析。总时长、剩余目标、目标率基点、分类分钟及占比、活跃天数、连续天数、单次最长时长、峰值日期和前后半段日均趋势均由 Kotlin 计算。课程冲突与可确定的自然语言时长同样由确定性代码处理。模型只负责引用这些事实做中文分析并给出带学习对象和时长/时间点的行动；结构化 JSON 被裁剪，本地 context 总字符数和消息数均有上限。聊天 UI 还会移除常见 Markdown 标题/加粗标记，以免原始控制符作为正文展示。

普通聊天与分析任务使用不同负载：`CHAT` 的基础 JSON 不再携带 `learningFacts`，只有问题实际涉及学习、时间、课程或动态时，`AiContextAssembler` 才加入相应裁剪数据。首页每日副文案同样走统一引擎，但仅把可证明的今日摘要交给模型；生成文案还需通过 Kotlin 事实校验后才会缓存和显示。

AI 对话历史使用 Room v5：同一会话 ID 在每轮完成后 `REPLACE` 更新，记录 provider、model、完整消息 JSON、创建与更新时间。历史仅在设备上存在，并与模型权重一样能在同签名 APK 覆盖安装时保留。

时间提示词、趋势口径和视觉能力边界见 `ai-time-prompts-and-vision.md`。

## 当前视觉能力边界

固定模型 manifest 已包含经过 SHA-256 校验的 `visual.mnn` 与 `visual.mnn.weight`，MNN 头文件也提供 `MultimodalPrompt`/`PromptImagePart`。但当前 `MnnNativeBridge` 只暴露文字生成入口，因此应用尚未把图片交给 Qwen；课程表截图仍先由本机 ML Kit OCR 识别，再由 Kotlin 校验和模型整理。未完成 JNI 图片输入、真机准确率与内存测试前，UI 不宣称“Qwen 识图”。

管理员审核、交易风控、账号安全、权限判断和高可靠深度推理没有交给本地模型。
