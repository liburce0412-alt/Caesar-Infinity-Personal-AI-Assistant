# Caesar 3.0：Mobile-Agent 小范围验证

核对日期：2026-10-18，北京时间。结论：**值得复用其“观察 → 有限动作 → 重新观察”和 GUI / 工具路径选择思路；3.0 暂不把完整 Mobile-Agent 或 ToolCUA 接入默认聊天。** 自家记录、课程等能力继续走已有 App 工具。外部应用的 GUI 操作保留为独立授权后的补充能力，不进入当前生产路径。

## 官方资料核对

| 来源 | 本次确认 | 对 Caesar 的含义 |
| --- | --- | --- |
| [MobileAgent 仓库](https://github.com/X-PLUG/MobileAgent) / [v3.5](https://github.com/X-PLUG/MobileAgent/tree/main/Mobile-Agent-v3.5) | 手机样例需要 Python、ADB、ADB Keyboard 和模型服务；动作坐标默认归一到 0–1000。 | 是独立执行方案，不能直接当 Android SDK 嵌入；不会自动给现有免费 GLM 增加搜索。 |
| [官方手机入口代码](https://github.com/X-PLUG/MobileAgent/blob/main/Mobile-Agent-v3.5/mobile_use/run_gui_owl_1_5_for_mobile.py) | 循环读取截图、请求模型、解析动作再执行；有步数上限，`terminate` / `answer` 会结束循环。应用名称解析另有模型配置。 | 不能把模型结束语当成功凭据；不能悄悄启用额外模型或付费解析器。未直接运行该脚本。 |
| [论文 arXiv:2602.16855](https://arxiv.org/abs/2602.16855) | 介绍 GUI-Owl 1.5 与跨平台 GUI、工具 / MCP 能力及研究评测。 | 论文分数不是 Caesar 目标设备、账号和工作流的验收结果。 |
| [ToolCUA](https://github.com/X-PLUG/ToolCUA) | 研究 GUI 动作与高级工具的路径选择；官方部署示例使用 8B 模型、vLLM / GPU 和桌面评测环境。 | 可借鉴工具优先和回读验证；不在现有 2 核约 2GB 代理服务器部署模型，也不把桌面评测代码包装成手机 SDK。 |
| [阿里云 gui-plus](https://help.aliyun.com/zh/model-studio/gui-plus) | 当前基础别名能力表不支持 Function Calling；`gui-plus-2026-02-26` 快照支持。两者都不支持内置联网搜索，且有输入 / 输出计费。 | 需按确切版本核验能力；本轮没有调用、获取密钥、开通计费或切换 Caesar 模型。 |

## 已执行的隔离实验

交付目录：[work/mobile-agent-evaluation](../work/mobile-agent-evaluation)。只依赖本机现有 Python 标准库，没有安装官方依赖或下载权重。运行：

```powershell
python -B work/mobile-agent-evaluation/evaluate.py
```

- [evaluate.py](../work/mobile-agent-evaluation/evaluate.py) 读取固定动作字符串，识别官方 `mobile_use` / `arguments` 形状和归一坐标；只提供有限的合成页面状态，不连接桌面、浏览器或手机。
- [fixtures.json](../work/mobile-agent-evaluation/fixtures.json) 含 17 个合成轨迹。内容与账号均为虚构，不读取真实用户数据、截图、健康详情或凭证。
- [results.json](../work/mobile-agent-evaluation/results.json) 保存实际运行的逐步状态及读回结果。**17 / 17 通过，模型 / 设备 / 网络调用均为 0。**
- SQLite 使用单次隔离目录；动作提交后执行查询验证持久状态，逐项连接关闭后移除临时数据库。结果确认临时目录已不存在。

另保留了通过官方 Git 远端确定的固定提交 **`11cea575561fb7800b5fb6b6cafa56f7a91de11f`** 的[手机入口代码](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/mobile_use/run_gui_owl_1_5_for_mobile.py)，来源记录和 MIT 许可证位于 `upstream/`。[replay_upstream.py](../work/mobile-agent-evaluation/replay_upstream.py) 通过 AST 只加载该文件中已审阅的两个纯函数 `parse_action` / `rescale_coordinates`，不会导入上游模块、依赖或执行 `main`。它用本地固定轨迹重放官方解析器，并核对动作名称属于官方实际分派分支，验证两种屏幕坐标缩放及一个缺失动作块的错误输入。结果写入 `official_protocol_replay`：这覆盖了本实验使用的官方动作协议形状与坐标规则，**仍不覆盖 Mobile-Agent 的视觉理解、规划、真实执行器或整体效果**。自家 `time.create_record` 是本实验的工具路径对照，不计入官方 `mobile_use` 协议样例。

| 实验 | 实际结果 |
| --- | --- |
| 工具完成“新增离线学习计划” | 经精确参数确认后一次写入，回读一条预期记录；固定工具名与当前 `CaesarAppTools` 源码对齐。 |
| GUI 完成同类合成笔记任务 | 打开合成页面、填草稿、确认保存三次动作；保存后回读一条笔记。仅用于比较动作边界，不是性能或模型成功率比较。 |
| 重复工具动作 | 同一账号 / 参数只写一条记录。 |
| 首步停止 / 中途停止 / 步数上限 | 后续动作不执行；中途的草稿不被提交。默认上限 12 步，测试用更小上限验证及时停止。 |
| 缺少确认 / 参数改变 / 账号变化 | 拒绝写入；GUI 草稿也必须匹配已确认内容与账号。 |
| 模型直接宣称完成 | 数据库没有目标记录，结果为 `unverified_claim`。 |
| 越界坐标 / 未观察到目标 / 任意 shell 动作 / 多动作歧义 | 拒绝执行。 |
| 页面文字含动作指令 / 要求读取健康工具 | 页面文本保持数据身份；不进入动作解析。健康工具不在本实验的允许集合内。 |

实验中的 App 工具适配器是合成 SQLite 实现，**没有执行 Android 的 `CaesarAppTools` Kotlin 代码**。实际 App 工具的权限、确认、持久幂等、取消及账号隔离仍由现有原生执行链和它的测试负责；本脚本不能替代那些验证，也不会向 APK 注册新工具。

## 可以复用的部分与剩余边界

可复用的是受限动作描述、坐标检查、明确的观察来源、独立终态验证及停止预算。若以后接入外部应用，主执行权继续归 Caesar 原生确认与取消机制；目标包名、账号、当前页面和具体写入内容必须与授权相符。截图 / 网页中的文字不是用户授权，模型的“完成”不是验收证据。

本次完成了资料核对、固定上游版本的有限协议重放和自写边界实验。真实视觉定位、模型规划、外部 App 成功率、延迟 / 费用以及实际设备执行仍**未验证**。当前没有获授权的 GUI 模型端点 / 费用额度；手机调试权限本身不等于付费模型调用或向其上传截图的许可。若要求进一步的真实模型实验，需要明确模型端点、数据范围及调用预算后再开展，不能用这 17 个离线用例把该项标成完整模型接入通过。

尚无生产接入、服务部署、模型切换、APK / 标签发布。本项不改变 3.0 的完整验收门槛。
