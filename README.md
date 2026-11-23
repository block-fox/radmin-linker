# Radmin Linker

![Radmin Linker](docs/radmin-linker-cover.png)

Fabric 客户端模组，在 Minecraft 内连接和管理 Radmin VPN。支持 Minecraft **26.3、26.2、1.21.11、1.21.1**。

当前版本为 **0.3.0**。更新记录见 [CHANGELOG.md](CHANGELOG.md)。

## 安装与联动

- **只安装 Radmin Linker**：使用网络、用户、已屏蔽用户和设置标签页。需要 Fabric API。传统安装包使用 Windows 上的官方服务。`-core.jar` 内置版可直接连接 Radmin，无需安装官方客户端，见 [核心使用与构建说明](docs/core-integration.md)。
- **只安装 [LanWorlds](https://github.com/block-fox/LanWorlds)**：增强原版局域网房间列表，查看封面、MOTD、地址、人数、延迟并管理房间屏蔽，无需 Radmin VPN。
- **同时安装**：Radmin Linker 为 LanWorlds 中匹配到的 Radmin 用户提供额外信息，以及用户和网络属性操作。

Radmin Linker 1.0.1 是原 RadminLAN 的新版。升级前请移除旧 JAR。首次读取时会从 `config/radminlan.json` 迁移设置到 `config/radminlinker.json`。安装同一 Minecraft 版本的 LanWorlds 1.0.2 可启用房间联动。

## 功能

- 网络和用户支持八种查看方式、排序、过滤和多选；查看网络用户时自动应用网络过滤。
- 批量网络操作逐项显示成功与失败数量，保留失败项供重试；自动刷新保留操作结果提示。
- 顶部直接修改用户名、切换 Radmin 电源；关闭前需确认。
- 通过 Windows 控制管道读取服务、网络、用户与实际延迟，配置 CLI 后可回退查询。
- 使用信号图标显示在线状态，仅悬停图标时显示详细提示。
- 自动刷新开启时，服务推送的实际延迟和离在线事件每两游戏刻同步到界面；完整查询间隔用于更新网络和成员资料。尚未收到的延迟显示未知，旧测量值在 60 秒后过期。
- 自动刷新开关和 1–60 秒刷新间隔；设置自动保存至 `config/radminlinker.json`。
- 列表、设置和属性页支持小窗口滚动裁剪；Mod Menu 提供配置入口。

## 开发

26.2、26.3 共用根源码，使用 JDK 25；1.21.11 和 1.21.1 使用 JDK 21，并共用后端与配置源码。运行客户端：

```powershell
./scripts/run-26.3.ps1 -JavaHome '<JDK 25 路径>' -Online
./scripts/run-26.2.ps1 -JavaHome '<JDK 25 路径>' -Online
./scripts/run-1.21.11.ps1 -JavaHome '<JDK 21 路径>' -Online
./scripts/run-1.21.1.ps1 -JavaHome '<JDK 21 路径>' -Online
```

构建用同版本的 `scripts/build-*.ps1`，产物整理到 `versions/<游戏版本>/build/`。脚本用参数选择版本，不改写根配置；构建或切换版本前请退出开发客户端。离线缓存齐全时可省略 `-Online`。

根配置保留本机 26.3 离线运行库支持；普通检出使用公开依赖：

```powershell
./gradlew.bat classes -Puse_local_runtime=false
```

运行回归检查（按游戏版本选择 JDK；26.2 追加对应 `versions/26.2/gradle.properties` 的 `-P` 参数）：

```powershell
./gradlew.bat -I tests/regression.init.gradle uiRegressionCheck --no-configuration-cache
./gradlew.bat -p versions/1.21.11 -I ../../tests/regression.init.gradle uiRegressionCheck --no-configuration-cache
```

回归覆盖鼠标编号、配置迁移、过滤与查看方式、128 组布局、批量操作部分失败、CLI 退出回退与实时缓存路由、IPv6 联动匹配、在线事件顺序、延迟过期和完整快照边界。安装 Radmin VPN 后可将任务改为 `nativeTelemetrySmoke`，测量首次读取耗时，并验证只查询一次后仍有订阅更新。协议依据见 [docs/telemetry-protocol.md](docs/telemetry-protocol.md)，可选联动协议见 [docs/lanworlds-integration.md](docs/lanworlds-integration.md)，项目结构与维护约定见 [docs/development.md](docs/development.md)。
