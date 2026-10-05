# 应用内更新与双下载源

## 使用

从 v2.1.1 开始支持应用内更新。首次安装该版本后，后续正式版可通过「我的 → 应用更新」下载；进入主界面也会检查新版并展示版本说明。自动检查成功后缓存 24 小时，手动检查不受限制。选择“稍后”后，同一版本不重复弹窗，手动入口仍可更新。

默认从杭州服务器下载，支持切换 GitHub；下载失败会自动尝试另一来源。下载期间可切换应用内页面，但请保持应用运行，进程被系统结束后需重新下载。下载结束后校验大小、SHA-256、包名、版本及签名，再打开系统安装界面。首次需要允许本应用安装 APK；系统安装确认不会被绕过。覆盖更新保留本机数据。

v2.1.0 及更早版本没有检查更新功能，必须先手动覆盖安装 v2.1.1。

## 发布

1. 更新 `apps/android/app/build.gradle.kts`：递增 versionCode，并填写新的 versionName。
2. 添加 `docs/releases/vX.Y.Z.md`。`<!-- updater -->` 后面的正文会显示在更新弹窗。
3. 提交并推送，创建对应 `vX.Y.Z` 标签触发 Release Android。保留现有签名密钥。
4. 工作流构建、检查、签名 APK，从实际 `output-metadata.json` 提取版本信息，生成 SHA256SUMS.txt 和 update.json，一起发布 GitHub 正式 Release。
5. 服务器每 15 分钟检查公开的最新正式版；校验 APK 后才原子替换 `/updates/latest.json`。若需立即生效，可执行 `systemctl start caesar-release-mirror.service`。

服务器元数据：`https://campusai.campus3ai.xyz/updates/latest.json`。
GitHub 元数据：`https://github.com/liburce0412-alt/Caesar-Infinity-Personal-AI-Assistant/releases/latest/download/update.json`。
下载 URL 包含固定版本号，避免元数据指向版本 A、实际下载到版本 B。

若主源故障，客户端会尝试 GitHub 元数据；若两个检查源都不可用，手动检查显示可重试错误，自动检查不阻断应用。撤回版本需停止镜像定时器并移除错误 Release，修复后使用更高 versionCode；镜像脚本拒绝版本回退及同一版本码替换内容。

## 服务器部署与容量

将 `deploy/alicloud/` 下的 `install-release-mirror.py`、`sync-app-releases.py`、`caesar-release-mirror.service` 和 `.timer` 放在同一目录，由 root 运行安装脚本。脚本仅添加当前 campusai 虚拟主机的静态更新路由，验证 nginx 配置后 reload；不重启 Supabase 或其他站点。原配置备份在 `/opt/campusai/deploy/nginx-before-app-updates.conf`。

镜像程序运行于 www-data，脚本存于 `/usr/local/lib/caesar-updater`，写入范围限制为 `/var/www/campusai-updates`，内存上限 96 MB、CPU 上限 20%，平时不常驻。它读取公开 GitHub Release，不需要把服务器 SSH 密钥交给 GitHub。

服务器保留最近 **3** 个版本。以 v2.1.0 的 43,797,714 字节为例，2 个版本约 83.5 MiB，3 个约 125.3 MiB，多留一个约增加 41.8 MiB；同步期间还需一个新包的临时空间。实机磁盘检查剩余约 26 GB，可容纳此策略。旧 GitHub Release 不自动删除，旧版本可人工下载；Android 默认不允许直接降级。

用户提供的阿里云概览显示峰值公网带宽 **200 Mbps**；峰值不代表持续速度，实际还受网络与并发影响。该截图没有显示月流量包，**月流量额度/计费仍待套餐详情确认**。静态 APK 下载公开可访问，邀请码限制账号注册而不是下载链接。

## 诊断

`systemctl status caesar-release-mirror.timer` 查看计划；`journalctl -u caesar-release-mirror.service` 查看镜像结果。损坏、超长、版本回退、摘要不符的更新会保留上一份 latest.json。清理只处理该更新目录中的标准版本目录和已知生成文件，遇到额外文件或符号链接会跳过。
