# 小米运动健康后台同步入口调查

日期：2026-10-20。对象为用户手机实际安装的 `com.mi.health` 3.58.0（358000）。通过原热点 ADB 连接，用手机 MT MCP 0.2.0 的只读 APK 工具分析；只读运行状态核对使用 `dumpsys activity broadcasts com.mi.health`。

## 结论

找到一个无需打开 Activity 的同步请求入口：广播 action `com.mi.health.widget.action.request.sync`，发送目标包为 `com.mi.health`。

调查阶段证据覆盖 APK 调用链和手机接收器注册状态。随后用户授权接入，当前源码已实现手动请求及可配置的定时请求，详见[健康自动化与超级岛设置](health-automation-and-island-settings.md)。请求发出、云端读取和手环同步完成仍是不同的状态，不把它们混同。

## 调用链

1. `MiuiFunctionComponent.initStepProvider()` 在系统步数提供器受支持且有效时注册动态广播接收器。
2. Android 13 以上使用 `registerReceiver(receiver, filter, 2)`，即 `Context.RECEIVER_EXPORTED`。该重载未传入发送方权限；接收分支未见调用方签名检查。
3. 收到 `com.mi.health.widget.action.request.sync` 后，`MiuiFunctionComponent$initStepProvider$broadcastReceiver$1.onReceive()` 调用 `FitnessSyncer.triggerDataSyncByWidget(true)`。
4. `FitnessDataSyncManager` 通过小米应用内部 Binder 调用 `FitnessSyncRemoteImpl.triggerDataSyncByWidget(true)`。
5. 手环已连接且未在同步时，设置 `manualForceSync(true)`，再调用 `DeviceContact.syncDataByWidget()`。
6. `DeviceContactImpl.syncDataByWidget()` 进入其私有 `syncData(WearableDeviceModel, boolean, DeviceSyncCallback)`，通过 `SyncObservers` 执行设备数据同步；这是设备同步流程，不只是读取 Caesar 的云端缓存。

`triggerDataSync()` 与 `triggerDataSyncByWidget()` 都进入该设备同步体系，后者使用小组件回调。仍需确认它与设备页手动按钮在目标手环上的指标范围、完成事件及云上传延迟是否完全一致。

## 只读运行证据

手机的 `com.mi.health:device` 进程已注册上述 action；保存的相应 `BroadcastFilter` 记录未列出 `requiredPermission`。这印证入口在当前手机运行状态中存在，但不等于已经投递、执行或完成同步。

证据目录：`artifacts/mi-fitness-sync-entry-20261020/`。

| 证据 | 位置 |
| --- | --- |
| APK 身份和 MT 工作区信息 | `workspace-current.json` |
| 注册条件、action、导出标志 | `MiuiFunctionComponent.smali:1090` |
| 广播接收并调用同步 | `MiuiStepReceiver.smali:187` |
| 内部同步分支 | `FitnessSyncRemoteImpl.smali:896` |
| 小组件入口到共享设备同步方法 | `syncDataByWidget.smali:49`、`DeviceContactSyncData.smali` |
| 实际已注册的接收器 | `registered-sync-receiver.txt` |
| Binder 签名门槛 | `DefaultBinderPoolWrapper.smali:161`、`Validator.smali:137` |

Android 文档确认常量值 2 表示可接收其他应用广播：[Context.RECEIVER_EXPORTED](https://developer.android.com/reference/android/content/Context#RECEIVER_EXPORTED)。

## 接入方式与边界

普通应用使用以下调用形态；调查阶段未执行，后续验证另记：

```kotlin
context.sendBroadcast(
    Intent("com.mi.health.widget.action.request.sync")
        .setPackage("com.mi.health")
)
```

- 入口为动态接收器，依赖小米运动健康相应进程存活和注册条件成立。被强制停止后，单独发广播不能保证唤醒它。
- 无绑定设备、未连接或正在同步时，内部逻辑会跳过设备同步或改走服务端同步；发出请求不代表已获取手环最新数据。
- 当前证据不要求无障碍、Root 或界面跳转，但普通 Caesar UID 的实际投递及系统后台策略仍待验证。
- 本 action 是当前 APK 的内部入口，未发现公开兼容承诺；后续接入需保留失败提示和手动打开小米的选项。
- 仅在用户点击时发送一次，再以有界方式更新既有云端摘要；不能把 `sendBroadcast()` 返回或本地读取时间当成手环同步完成时间。
- 不应直接使用导出的 `DeviceManagerService`/`DefaultRemoteService` Binder：其 `queryBinder()` 会经过 `Validator.isPrivilegedPackage()`，普通第三方签名不满足内部签名门槛。广播路径由小米进程内部调用这些 Binder。

## 调查阶段范围

没有修改或重打包小米 APK，没有安装测试包、触发设备同步、读取健康记录或账号凭据，也没有修改 Caesar 应用源码、推送或部署。证据保留于上述目录。

MT 已确认临时 APK 工作区 `kas32vak` 删除成功（`workspace-cleanup.json`）。本地临时截图已删除并确认不存在。停止 MT 服务和清理手机临时 UI 转储的命令曾被自动审批整体拒绝，理由为“最新用户消息明确表示暂时不要操作手机，未重新授权本次设备操作”；未绕过拒绝。随后用户表示“我关了已经”，MT 服务按用户已关闭记录。用户再次明确默认允许本项目手机操作后，已删除 `/data/local/tmp/campusai-mt-inspect.xml`，并用 `ls` 确认文件不存在。
