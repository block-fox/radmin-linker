# RadminLAN 开发导览

当前开发版本为 0.3.0，目标是 Minecraft 内的 Radmin VPN 网络和用户管理。局域网房间发现、查询和房间屏蔽由独立的 LanWorlds 模组负责。

## 源码与版本

- `src/main/java/dev/radminlan/`：26.3 和 26.2 共用源码，JDK 25。
- `versions/1.21.11/src/main/`：1.21.11 独立源码和资源，JDK 21。
- `versions/<版本>/gradle.properties`：各版本依赖与打包配置。
- `tests/regression/`：三个版本共用的回归程序；通过 Gradle 初始化脚本加载。

修改业务逻辑时同步两套源码；保留 Minecraft API 的版本差异，例如 26.x 的渲染提取与 1.21.11 的直接绘制。中英文资源也需同步。

## 主要模块

| 模块 | 职责 |
| --- | --- |
| `RadminLanClient` | 客户端初始化、后台刷新、共享快照和退出清理 |
| `network/RadminBackend` | 快照、网络和成员操作的异步接口 |
| `network/PipeRadminBackend` | Windows 控制管道读写、状态确认和成员延迟订阅 |
| `network/WindowsCliBackend` | 配置的 CLI 读写回退 |
| `network/FallbackRadminBackend` | 管道优先、CLI 回退和网络退出地址路由 |
| `config/RadminConfig` | 设置、用户屏蔽及缺省网络状态的本地记录 |
| `ui/RadminScreen` | 网络、用户、已屏蔽和设置标签页 |
| `ui/RadminListPreferences` / `RadminListLayout` | 可独立测试的过滤、排序和布局 |
| `ui/RadminBatchOperations` | 串行执行批量请求并记录各项结果 |
| `compat/LanWorldsProvider` | 可选 ObjectShare 联动，按共享快照索引用户虚拟地址 |
| `mixin/MultiplayerScreenMixin` | 多人游戏页入口和返回页面时的原版局域网发现恢复 |

## 行为约定

- 后端请求的 `Boolean.TRUE` 才表示成功；完成但返回 `false` 仍是失败。
- 批量请求串行执行，普通失败继续处理后续项目；仅成功项更新本地状态并取消选中。
- 管道使用网络 GUID，CLI 使用网络名称。退出时传入完整 `Network`，由后端选择标识。
- 服务提供 `Network.enabled` 时以该状态为准；未提供时才使用 `disabledNetworks`。
- 自动刷新更新服务状态并保留操作反馈，手动刷新可以显示自己的进度和结果。
- `snapshot()` 执行完整查询，`latestSnapshot()` 只读取本地订阅视图。客户端每两游戏刻发布实时视图；关闭自动刷新时暂停自动发布，手动查询仍读取最新状态。
- 只读订阅按帧顺序处理在线事件和延迟；新完整查询优先于此前的在线事件，查询期间或之后的事件覆盖查询结果。离线、无效延迟及订阅断开必须清除旧延迟；重复显示同一快照不能延长测量的 60 秒有效期。
- 初始查询以完整消息 34 的状态容器为结束边界；命令响应仍保留独立的批次收集策略。协议和本机客户端逆向依据见 `telemetry-protocol.md`。
- 跨模组联动使用共享快照，按数值匹配 IPv6 字面量，不进行 DNS 查询。

## 验证与打包

README 提供各版本构建和运行命令。先退出开发客户端，再执行构建或切换根项目的游戏版本。

```powershell
# 26.3，使用 JDK 25
./gradlew.bat -I tests/regression.init.gradle uiRegressionCheck --no-configuration-cache

# 26.2，使用 JDK 25，加载版本配置
$profileArgs = Get-Content versions/26.2/gradle.properties |
    Where-Object { $_ -match '^[a-zA-Z_][a-zA-Z0-9_]*=' } |
    ForEach-Object { '-P' + $_.Trim() }
./gradlew.bat @profileArgs -I tests/regression.init.gradle uiRegressionCheck --no-configuration-cache

# 1.21.11，使用 JDK 21
./gradlew.bat -p versions/1.21.11 -I ../../tests/regression.init.gradle uiRegressionCheck --no-configuration-cache
```

缓存齐全时可添加 `--offline`。常规回归使用隔离配置和假后端，不操作真实 Radmin 网络；`nativeTelemetrySmoke` 是额外的本机只读订阅检查。
