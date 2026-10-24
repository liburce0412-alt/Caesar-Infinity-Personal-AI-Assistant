# 应用内更新与双下载源

## 3.1.0 发布

当前正式版为 `3.1.0 / versionCode 15`，见 [发布说明](releases/v3.1.0.md)及[验收记录](caesar-3.1-acceptance.md)。GitHub 正式签名包与国内镜像均已发布，并从公网下载校验一致。

仅在签名一致时保留数据覆盖安装，禁止卸载或清数据。数据库 11 为既有 AI 历史补上本机所有者与分页索引，旧内容保留在“本机旧记录”，不自动归属当前登录账号；新诊断记录应用存储上限，历史聊天不随诊断清理。免费文字不再使用4.7或跨模型兜底；图片仍用4V，不接入GLM-OCR、不增加付费路线或每日次数限制。

| 版本 | versionCode | 对照 |
| --- | --- | --- |
| 3.1.0 | 15 | 当前正式版；超级岛、健康自动化、课表与审核 |
| 3.0.0 | 14 | 历史发布；免费受控工具、账号隔离与历史分页 |
| 2.2.2 | 13 | 历史发布；免费云端与旧文字兜底路线 |

## 使用

从 v2.1.1 开始支持应用内更新。当前入口为「我的 → 设置 → 应用更新」；进入主界面也会检查新版并展示版本说明。自动检查成功后缓存 24 小时，手动检查不受限制。选择“稍后”后，同一版本不重复弹窗，手动入口仍可更新。

默认从杭州服务器下载，支持切换 GitHub；下载失败会自动尝试另一来源。下载期间可切换应用内页面，但请保持应用运行，进程被系统结束后需重新下载。下载结束后校验大小、SHA-256、包名、版本及签名，再打开系统安装界面。首次需要允许本应用安装 APK；系统安装确认不会被绕过。覆盖更新保留本机数据。

v2.1.0 及更早版本没有检查更新功能，必须先手动覆盖安装 v2.1.1。

## 发布

1. 更新 `apps/android/app/build.gradle.kts`：递增 versionCode，并填写新的 versionName。
2. 添加 `docs/releases/vX.Y.Z.md`。`<!-- updater -->` 后面的正文会显示在更新弹窗。同时核对 README 及维护中的语言版本：版本、功能文字、入口路径、图标必须与当前应用一致；前端变化时重新抓取真实界面截图，使用演示数据并逐张检查，不把旧截图继续标作当前版本。
3. 提交并推送，创建对应 `vX.Y.Z` 标签触发 Release Android。保留现有签名密钥。
4. 工作流构建、检查、签名 APK，从实际 `output-metadata.json` 提取版本信息，生成 SHA256SUMS.txt 和 update.json，一起发布 GitHub 正式 Release。
5. 人工发布默认从 GitHub 下载正式 APK 与 `update.json`，核验版本、包名、签名、大小和 SHA-256 后，通过已授权 SSH 连接直传国内服务器的私有暂存路径。使用服务器原镜像脚本的 `validate` 与 `verified` 再核验，才移动到精确版本目录并交给 www-data。不得直接覆盖 `latest.json`。
6. 运行 `caesar-release-mirror.service`：它重新获取 GitHub 正式清单、校验已到位 APK 后原子切换 `/updates/latest.json`。若有本轮慢速下载正在运行，先停止该镜像任务，完成校验后仅清理该版本的 `download.part`。每 15 分钟的定时同步保持启用，作为遗漏发布的兜底。最后从公网读取清单并下载验证国内 APK。

服务器元数据：`https://campusai.campus3ai.xyz/updates/latest.json`。
GitHub 元数据：`https://github.com/liburce0412-alt/Caesar-Infinity-Personal-AI-Assistant/releases/latest/download/update.json`。
下载 URL 包含固定版本号，避免元数据指向版本 A、实际下载到版本 B。

若主源故障，客户端会尝试 GitHub 元数据；若两个检查源都不可用，手动检查显示可重试错误，自动检查不阻断应用。撤回版本需停止镜像定时器并移除错误 Release，修复后使用更高 versionCode；镜像脚本拒绝版本回退及同一版本码替换内容。

## 服务器部署与容量

将 `deploy/alicloud/` 下的 `install-release-mirror.py`、`sync-app-releases.py`、`caesar-release-mirror.service` 和 `.timer` 放在同一目录，由 root 运行安装脚本。脚本仅添加当前 campusai 虚拟主机的静态更新路由，验证 nginx 配置后 reload；不重启 Supabase 或其他站点。原配置备份在 `/opt/campusai/deploy/nginx-before-app-updates.conf`。

镜像程序运行于 www-data，脚本存于 `/usr/local/lib/caesar-updater`，写入范围限制为 `/var/www/campusai-updates`，内存上限 96 MB、CPU 上限 20%，平时不常驻。它读取公开 GitHub Release，经官方资源 API 跳转 CDN，不需要把服务器 SSH 密钥交给 GitHub。下载任务最长允许 30 分钟，跨境链路慢时不会影响现有版本下载。

服务器保留最近 **3** 个版本。以 v2.1.0 的 43,797,714 字节为例，2 个版本约 83.5 MiB，3 个约 125.3 MiB，多留一个约增加 41.8 MiB；同步期间还需一个新包的临时空间。实机磁盘检查剩余约 26 GB，可容纳此策略。旧 GitHub Release 不自动删除，旧版本可人工下载；Android 默认不允许直接降级。

用户提供的阿里云概览显示峰值公网带宽 **200 Mbps**；峰值不代表持续速度，实际还受网络与并发影响。该截图没有显示月流量包，**月流量额度/计费仍待套餐详情确认**。静态 APK 下载公开可访问，邀请码限制账号注册而不是下载链接。

## 诊断

`systemctl status caesar-release-mirror.timer` 查看计划；`journalctl -u caesar-release-mirror.service` 查看镜像结果。损坏、超长、版本回退、摘要不符的更新会保留上一份 latest.json。清理只处理该更新目录中的标准版本目录和已知生成文件，遇到额外文件或符号链接会跳过。
