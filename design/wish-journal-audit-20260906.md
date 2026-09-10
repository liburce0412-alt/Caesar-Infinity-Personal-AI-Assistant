# 心愿体验与全项目检查 · 2026-09-06

## 本轮交付

- 树洞与心愿的纯文字卡片使用横向布局；带图卡片为正方形。纯文字心愿在网格中占满一行。列表摘要截断后可进入详情读全文。
- 去掉内容卡片和详情的“公开”“在售”等标签；创建时仍可主动选择“分享给其他人”。编辑保留原分享范围与记录时间。
- 本人内容支持修改文字、替换或移除图片、删除；删除需二次确认，并在服务端校验所有者。删除以逻辑删除保留历史关联，页面与评论读取同时隐藏。
- 心愿有独立评论区，本人也可留言。私有心愿的评论只对本人可见。
- 自动展示记录时间；可选“希望实现的日子”，支持清除日期。日期提示为温和的倒计时/当天/日期已过三种文案。
- “实现了，留个纪念”保存完成时间、感受与可选照片。回望卡只选择本人至少 30 天前的真实记录，按天轮换；没有旧记录时不制造占位回忆。
- 原有外观、环境、玻璃交互和深色星光保留。

## 审计发现与处理

| 范围 | 发现与结果 |
| --- | --- |
| Android 社区/心愿 | 修复文字卡仍在双列网格、自己的联系按钮被禁用且缺评论区、缺编辑删除入口的问题；新增操作错误在弹窗内展示，避免失败提示被遮挡。 |
| Android 会话状态 | 原请求可在退出登录或快速切换页面后回填。现将请求绑定到可取消的会话任务，切换账号清空状态；评论与消息切换会取消旧读取，发送返回仅更新对应会话。重复操作在忙碌期间拦截。 |
| 时间/课程同步 | 拉取明确附带当前 user_id 条件，避免账号切换中的请求取得另一个账号的记录后按旧账号落库。保留现有离线同步模型和版本冲突处理。 |
| Supabase | 新增评论 RLS、本人编辑/删除/完成 RPC；匿名调用禁止，私有内容拒绝陌生人读取与写入。删除后的收藏/联系入口也拒绝访问。直接列更新与编辑 RPC 均将变更的已分享内容重新送审。 |
| 管理台数据真实性 | 原先已配置环境在缺数据时可能使用示例计数；静态增长率、趋势与队列标为实时。现缺数据显示“—”并显示请求错误，静态趋势只在未配置预览中作为示例出现。 |
| 管理台交互/缓存 | 刷新按钮执行真实刷新，公告入口跳转到公告页面；退出或切换账号清除查询缓存。心愿管理去掉商品称谓。 |
| 测试配置 | 原 npm test 会把 Playwright 用例交给 Vitest 导致失败。拆开两类测试入口，新增已配置管理台不得展示虚构实时数据的测试。 |
| 代码组织 | 心愿详情、日期选择、内容管理、完成留念拆到 WishJournalUi.kt；日期文案与真实旧记录筛选放到 WishTime.kt，复用现有创建表单作为编辑表单。 |
| AI/安全存储/健康/本地数据库 | 检查模块入口、异常处理与相关全量测试结果；保留现有 Android Keystore、失败拒绝保存密钥、本地模型和云端路由。健康设备真实链路、本地模型长时运行未在这轮重新验收。 |

全项目检查包括 Android 全量测试与 Lint、管理台类型检查/构建/测试，以及数据库迁移、RLS 和 RPC 验证；关键链路另做代码阅读。大型 AI、个人页、首页等文件仍可按功能继续拆分，但本轮没有为减少行数改动其产品行为。现有依赖升级、兼容性提示等 Lint warning 单独保留，没有盲目升级整个技术栈。

## 验证与发布证据

- `artifacts/wish-journal-validation.log`：Android 全量 379 项通过，Lint 无 error，APK 构建成功。
- `artifacts/wish-journal-final-ui-tests.log`：追加账号切换取消测试与 4 项心愿 UI 用例通过；覆盖横向/方形几何、本人评论、编辑保持日期与隐私、删除确认。
- `artifacts/wish-journal-device-fix-validation.log`：手机底部安全间距修正后的相关测试、Lint 与最终 APK 构建。
- `artifacts/wish-journal-cards.png`：深色布局渲染证据，图卡使用不可加载的测试媒体验证几何；不代表真实上传照片效果。
- `artifacts/wish-admin-unit.log` / `wish-admin-build.log` / `wish-admin-visual.log`：2 项单元测试、构建、10 项浏览器用例通过。管理台源代码改动尚未发布线上。
- `artifacts/wish-journal-db-test.log`：隔离 PostgreSQL/WASM 中 66 项检查通过。
- 线上迁移 `20260906070905_wish_journal_management` 已应用；实际事务验证本人编辑/删除/评论/完成、日期与创建时间保持、陌生人越权拦截，测试数据全部回滚。
- 安全 advisor 未返回 ERROR；保留已有可调用 SECURITY DEFINER RPC 提示和未启用泄露密码检查的提醒。本轮验证了新增 RPC 的授权边界，未更改 Auth 配置。参考：[RPC advisor](https://supabase.com/docs/guides/database/database-linter?lint=0029_authenticated_security_definer_function_executable)、[密码保护](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection)。

最终安装、哈希与清理清单另存于 artifacts，清理只针对本轮旧包、临时备份和可重新生成的构建文件。源代码、签名、密钥配置、现有用户内容、正式历史发布包与验证记录保留。

### 最终交付结果

- 15:22 已向手机 `3cc5349b` 覆盖安装最终 APK，启动成功；手机内包与 `artifacts/campusai-wish-journal-debug.apk` 的 SHA-256 均为 `56400B166658BE7DDEE5393D3D9D245AA2EAD884086DFC5C165B79136CFE0B09`。
- 原安装时间及用户数据目录 inode 保持不变；检查时 AndroidRuntime 未返回崩溃记录。详见 `artifacts/wish-journal-final-install.json`。这不替代所有功能的长期实机验收。
- 清理清单已记录到 `artifacts/wish-cleanup-preview.json`，但删除执行被自动审批审查以 `blocked by policy` 拦截，未给出更具体原因。因此本轮未实际删除这些旧包、备份和缓存，不能计为已释放空间。
