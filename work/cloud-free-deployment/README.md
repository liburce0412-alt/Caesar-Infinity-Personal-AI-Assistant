# 3.0 中转更新准备

本目录是中转更新与恢复说明。2026-10-18 已按用户授权完成生产更新，服务 active，回环 healthz 返回 ready=true、glm-4-flash-250414，公网精确路由正确要求登录，activation 仍 HTTP200。目标仅为现有杭州主机上的 `caesar-cloud-free.service` 和其 nginx include，保留 Supabase、activation、环境变量与端口边界。

## 已核对的一致性

- `relay.mjs` 只固定免费文本 `glm-4-flash-250414`、图片 `glm-4v-flash`；纯文本 `deep` 明确拒绝。旧 `allowTextFallback` 字段仅兼容接受并忽略，无 4.7 请求/跨模型回退；历史测试中的 4.7 字面量不是当前路由。
- `tools.mjs` 只投影固定 App 工具、严格验证参数/当前轮回放和终止协议，不在服务器执行工具。部署 bundle 必须包括它，否则新增模块导入失败。
- `server.mjs` 固定监听 `127.0.0.1:8788`；服务仍使用 Node 96 MiB 堆、128/192 MiB memory high/max、无 swap、CPU 100%；nginx 仅公开既有精确路径 `/caesar-cloud/v1/chat`。
- Android 当前版本 3.0.0 / code 14；版本行为见 `docs/releases/v3.0.0.md`；签名 APK 由既有发布工作流生成。

## 安装脚本的事务边界

`deploy/alicloud/install-cloud-free.py` 现在只更新已安装的中转；首次环境变量初始化不再由它完成。`/etc/campusai/cloud-free.env` 必须已存在、普通文件、root 所有、0600，脚本仅 stat，不读、复制、输出或修权限，也不打开 Supabase `.env`。

1. root 专用非阻塞文件锁，阻止两个本脚本同时更新。只接收固定 6 个源码/config 文件的 JSON，不接受 Key、目标路径或任意额外文件。
2. 在 `/etc/campusai/.cloud-free-stage-*` 临时目录写候选。先校验无 npm 依赖的 package、三个 JS 语法、Node 22.16+ 与模块实际导入/固定型号、systemd unit、当前 nginx 和暂存后的完整 nginx 配置树。其他虚拟主机仅保持原 include 引用；阶段结束清理暂存目录。
3. 所有预检通过后，保存唯一 `/etc/campusai/cloud-free.rollback/`（0700），含旧 relay 三/四文件、unit、snippet、site 与原 active/enabled 状态、元数据；单个备份文件0600。没有 env/数据库/账号备份。若恢复目录已存在则拒绝开始新更新，不静默覆盖必要恢复证据。
4. 停止旧 relay 后逐文件原子替换，重新校验 nginx、reload systemd、enable/start relay。只做本机 GET `/healthz`，要求 ready=true 且模型为250414；不发用户请求、不调用智谱。确认 relay active 后仅 reload nginx。
5. 提交阶段异常或可捕获的 INT/TERM/HUP 触发恢复旧文件、原 enable/active 状态及必要 nginx reload。恢复时先验证并读完整备份，防止坏备份先停掉现有服务；新增加而旧版没有的 `tools.mjs` 在恢复时删除。恢复也失败则明确报 `rollback_incomplete` 并保留快照，不谎称已恢复。SIGKILL/断电不能由脚本捕获，使用同一份手工恢复快照。

`/healthz` 只证明当前进程配置就绪与固定型号，不能证明登录、上游工具链或公网入口已通过。真正生产验收继续由主线完成。任何回滚恢复的是旧2.2.2代码，因此若回滚发生，旧4.7路由也可能随旧版恢复；这是恢复可用基线，不得宣称3.0去除4.7已上线。主线应立即报告回滚并停止发布。

## 审查后主线执行顺序

先在本地构造 JSON bundle（仅这6个已审阅文本文件）：

```text
server.mjs                       ← apps/cloud-free/server.mjs
relay.mjs                        ← apps/cloud-free/relay.mjs
tools.mjs                        ← apps/cloud-free/tools.mjs
package.json                     ← apps/cloud-free/package.json
caesar-cloud-free.service         ← deploy/alicloud/caesar-cloud-free.service
caesar-cloud-free.nginx.conf      ← deploy/alicloud/caesar-cloud-free.nginx.conf
```

通过已有安全通道传递脚本与 bundle；以下是主机上的准确入口，不包含连接或密钥操作，本文未执行：

```sh
# 仅暂存预检，不停/启服务、不创建恢复快照。
python3 /reviewed/path/install-cloud-free.py --check < /reviewed/path/cloud-free.bundle.json

# 全部门槛通过后才执行；预检会再次以当前磁盘状态核对。
python3 /reviewed/path/install-cloud-free.py < /reviewed/path/cloud-free.bundle.json

# 若提交后主线公网/生产验收发现回归：使用同一份快照，只有 relay 重启及 nginx reload。
python3 /reviewed/path/install-cloud-free.py --rollback
```

正常停止会等待现有连接结束，服务 `TimeoutStopSec=135`，脚本命令上限150秒；主线执行工具需允许持续运行并分段获取状态，不把30秒工具等待误判为部署超时。更新过程中不要并行更改 nginx 或该 relay。不要执行 `systemctl restart nginx`、`campusai-backend.service`、Docker compose 或 activation 命令。

成功后保留一份恢复快照直到生产和APK发布核验完成；不再额外复制第二份。主线收尾时核对该快照是否还必要，并仅清理这次脚本/bundle/暂存等已确认临时文件。脚本不会自动删除既有恢复目录来强行重试。

## 本地验证

```powershell
python -B scripts/test-cloud-free-installer.py -v
```

测试只使用临时合成主机、假 systemctl/nginx/Node 和假 loopback health，不接服务器或网络。覆盖预检无写入、逐文件中断、重启/健康/reload失败、原 active/enabled 状态恢复、单备份、手工回滚、新模块移除、env仅stat、路径拒绝及恢复失败。Windows测试仅让合成目录继承ACL，避免 restricted token 与 Python 0700 DACL 冲突；生产的 POSIX 0700/0600 逻辑不变，并单独验证环境文件模式。

这组模拟故障测试不替代 Ubuntu 上的 Node/systemd/nginx `--check` 预检、真实 reload、既有业务可用性或生产请求验收。既有 Node 协议回归不因安装脚本变化重复运行；若 relay/tools 再改，由主线统一复测。
