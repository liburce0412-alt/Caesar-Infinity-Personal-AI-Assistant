# 审计修复记录 · 2026-09-22

对应 [原始审计](code-audit-2026-09-22.md)。本文记录源码修复及本地验证；数据库迁移、后台部署和真机安装尚未执行。

## 修复对应关系

| 项目 | 实施内容 | 关键验证 |
| --- | --- | --- |
| A1 账号隔离 | Room v9：课程哈希、课程/时间记录 clientId 均改为账号内唯一；查询带 userId；访客行认领采用 UPDATE OR IGNORE；软删除课程可重新导入 | v8→v9 真实 Room 迁移；两账号同哈希/同 clientId；访客冲突 |
| A2 同步竞态 | 事务内重读并比较完整快照；成功回包只为新编辑合并同步元数据并保留 pending；失败不覆盖已变化的行；删除/撤销使用相同保护；Worker 请求绑定启动时会话 | 编辑/删除/撤销与成功、失败回包交错测试 |
| A3 消息分页/已读 | 最新 200 条倒序取回后正序展示；时间+ID 向前分页；列表显示消息后才推进已读；失败、关闭会话、仅发送消息均不确认未获取内容；评论按游标读完 | 201 条消息、读取失败、同时间戳水位、不回退、非成员/匿名拒绝 |
| A4 同步分页 | 时间记录和课程均按 updated_at + id 循环读取；允许服务端返回小于请求 limit 的页；中断后从头幂等重试 | 1001 条同时间戳、服务端每页 137 条、删除标记、半途失败 |
| A5 冲突恢复 | 本地修改另存为带“同步冲突副本”名称的独立记录，再接收远端；新副本继续同步；远端已删除时保留本地有效副本；删除失败可重新基于远端版本提交；无 remoteId 的删除先确认远端是否已收到上次上传 | 副本保留最新正文/满足远端哈希长度；删除版本恢复；课程详情增加二次确认删除 |
| A6 ICS | 转换 UTC/TZID 至设备时区；保留具体日期、重复/排除规则；单次事件不再变成每周；全天、跨午夜、未知时区、无结束时间进入待确认草稿 | UTC、纽约→上海跨日、隔周/次数/BYDAY/EXDATE、异常事件 |
| A7 OCR | 严格匹配完整星期表头；只从真实时间轴读取时间；缺少时间轴则时间留空；所有 OCR 草稿需核对确认 | 课程名误匹配、无时间轴、真实 08:35–09:20 节次、预览表单 |
| A8 时间统计 | 统一按有效记录完成日统计，自然周从周一开始；日/月统计和连续天数使用日期运算；AI 上下文同步完成日口径；SQL 按完成时间及逐条分钟数汇总 | 跨午夜、月初/周一、DST、零时长/未来记录；SQL 午夜和时长测试 |
| R1 职责拆分 | 导入弹窗、ICS 解析、OCR 几何、时间统计独立；两处 AI 时间重叠计算共用函数并标明周次待核实 | 编译与既有 AI/Compose 回归 |
| R2 时间轴 | 每条记录使用带稳定 ID 的 LazyColumn items，避免一个 Column 一次创建所有记录 | 编译、Lint；未声称真机帧率提升幅度 |
| R3 管理台 | 页面懒加载；RPC 输入/返回值类型及关键读取结果运行时校验；操作/字段映射抽离；300ms 搜索节流及 AbortSignal；页面按 kind 重置状态 | 管理台单元测试、生产构建、手机/桌面浏览器检查 |
| R4 部署入口 | 标明 Legacy hosted；显式填写并核对环境变量中的项目 ref；使用独立 GitHub environment | 本地 YAML/diff 检查；未更改线上环境配置 |
| R5 Edge 输入 | 抽出输入校验，先检查 message 是非空对象，再读取 role/content；畸形输入走 400 | Node 离线运行真实校验模块 |

## 明确的行为与限制

- 冲突采用“保留双方”策略。副本会出现在列表且会参与统计；用户可编辑/删除时间记录，或在课程详情二次确认删除课程。原本地删除与较新远端内容冲突时采用远端内容，不反复自动删除。
- 访客数据只在同步时认领到当前账号；与当前账号唯一约束冲突的访客行仍留在本地，不覆盖账号数据。
- ICS 的课程表仍是星期概览，不是完整日历重复规则引擎。复杂规则保留在日期/周次文本中并必须核对；跨午夜或全天事件需要手动拆分/填写。没有悄悄近似为每周或自动编造时长。
- Android 使用设备时区；后台统计明确沿用产品的 Asia/Shanghai 报表时区。不同报表时区下的日界线差异不是同一时区内的口径混用。
- AI ViewModel 健康/图片协调器和 Profile 所有设置区域没有做整体迁移。这些属于后续大规模结构整理，本次只拆出与已确认缺陷直接相关的职责。

## 发布前顺序

1. 自托管环境先按现有备份/迁移流程审查并应用 `20260922065331_message_read_watermark.sql` 和 `20260922070550_completed_time_statistics.sql`。
2. 再发布新的 Android 包。新客户端调用 `mark_conversation_read_through`；未迁移时已读确认会失败，消息读取仍可用，不会退回“全部标为现在已读”。旧无水位 RPC 的执行权限被撤销，旧客户端不再推进已读。
3. 若维护历史托管环境，需要配置 GitHub `legacy-hosted-supabase` 环境及 `LEGACY_SUPABASE_PROJECT_REF`，并在界面填写匹配的 ref。此入口不用于阿里云自托管。

## 验证方式

- Android：`gradlew.bat :apps:android:app:testDebugUnitTest :apps:android:app:lintDebug :apps:android:app:assembleDebug -Proborazzi.test.record=true --console=plain`。
- 管理台：`npm --prefix apps/admin test`、`npm --prefix apps/admin run build`。
- SQL：`node scripts/test-audit-migrations.mjs`。使用隔离的 PGlite PostgreSQL/WASM 和最小业务表夹具执行真实迁移与查询；不等同于完整自托管栈/线上 RLS 验收。
- Edge 校验：`node --experimental-strip-types --test supabase/functions/ai-chat/request.test.ts`；未启动 Deno Edge Runtime 或调用上游模型。
- 浏览器：本机 Chrome 临时无登录上下文，空后端配置、独立本地端口；320px/1280px 总览和手机菜单导航。初始默认 Playwright 浏览器缺失，之后显式使用现有 Chrome；既有导航测试的模糊选择器也已修正。
- Supabase 当前 CLI 的 npm Windows 包未提供可用二进制，因此使用已验证可运行的 CLI 2.39.2 执行 `migration new` 创建两个文件。已核对当前官方迁移及 PostgREST 游标查询文档；没有使用该 CLI 连接远端。

## 最终结果

- Android：429 个测试，0 失败、0 错误、0 跳过；96 个测试套件。包含 Room v8→v9 验证；调整索引后的首轮因旧 schema 测试资产未重新合并而失败，重新合并生成资产后完整通过。
- Lint：0 error / 53 warning；本次修改文件没有对应 warning。Kotlin/C++ 仍有既有弃用和第三方未使用参数警告。
- 管理台：7/7 单元测试、3/3 浏览器检查、生产构建通过。主入口 JS 373.81 kB（gzip 116.22 kB），原为 655.53 kB（gzip 194.75 kB）；依赖及页面独立分包，无大于 500 kB 的单包警告。这是构建体积变化，不代表已测量首屏网络耗时。
- SQL 迁移夹具测试、Edge 输入 2/2、YAML 解析、git diff --check 通过。
- 已检查课程导入核对表单截图；无真机安装、长列表帧率或生产数据库验收。
- 候选 APK：`artifacts/campusai-audit-fixed-debug.apk`，54,403,733 bytes。
- SHA-256：`DC93D72B6E35F9108D95EA1254CC8C53CB09CA7499EF6B25FA90A1B3D7020113`（源构建与候选文件一致）。
- 机器可读结果：`artifacts/audit-validation/results.json`。
