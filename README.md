# RadminLAN

Fabric 客户端模组，在 Minecraft 内管理 Windows Radmin VPN 的网络和用户。支持 Minecraft **26.3、26.2、1.21.11**。

当前开发版本：**0.3.0**。更新记录见 [CHANGELOG.md](CHANGELOG.md)。

## 安装与联动

- **只安装 RadminLAN**：使用网络、用户、已屏蔽用户和设置标签页。需要 Fabric API，以及 Windows 上已安装并运行的 Radmin VPN 服务。
- **只安装 [LanWorlds](https://github.com/block-fox/LanWorlds)**：增强原版局域网房间列表，查看封面、MOTD、地址、人数、延迟并管理房间屏蔽，无需 Radmin VPN。
- **同时安装**：RadminLAN 为 LanWorlds 中匹配到的 Radmin 用户提供额外信息，以及用户和网络属性操作。

从 0.3.0 起，局域网房间功能移至独立仓库 LanWorlds。联动需要 RadminLAN **0.3.0 或更新版本**与 LanWorlds **0.1.0 或更新版本**，两个包必须对应相同的 Minecraft 版本。旧房间屏蔽规则和查询设置会在 LanWorlds 首次启动时从 `config/radminlan.json` 迁移，原文件保留。

## 功能

- 网络和用户支持八种查看方式、排序、过滤和多选；查看网络用户时自动应用网络过滤。
- 顶部直接修改用户名、切换 Radmin 电源；关闭前需确认。
- 通过 Windows 控制管道读取服务、网络、用户与实际延迟，配置 CLI 后可回退查询。
- 使用信号图标显示在线状态，仅悬停图标时显示详细提示。
- 自动刷新开关和 1–60 秒刷新间隔；设置自动保存至 `config/radminlan.json`。
- 列表、设置和属性页支持小窗口滚动裁剪；Mod Menu 提供配置入口。

## 开发

26.2、26.3 共用根源码，使用 JDK 25；1.21.11 使用独立源码和 JDK 21。运行客户端：

```powershell
./scripts/run-26.3.ps1 -JavaHome '<JDK 25 路径>' -Online
./scripts/run-26.2.ps1 -JavaHome '<JDK 25 路径>' -Online
./scripts/run-1.21.11.ps1 -JavaHome '<JDK 21 路径>' -Online
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

回归覆盖鼠标编号、配置迁移、过滤与查看方式、128 组布局、在线状态和延迟协议。安装 Radmin VPN 后可将任务改为 `nativeTelemetrySmoke`，进行只读订阅检查。可选联动协议见 [docs/lanworlds-integration.md](docs/lanworlds-integration.md)。
