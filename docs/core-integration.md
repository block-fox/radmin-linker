# 在 Minecraft 使用 Radmin Linker Core

内置版安装包以 `-core.jar` 结尾。Windows 启动时会检查官方 Radmin VPN 的控制管道，能连接就使用官方服务和已有身份，否则使用内置核心。Linux 和 macOS 默认使用内置核心。也可以在 **Radmin → 设置 → VPN 后端** 中手动选择，重启游戏后生效。

安装 Fabric API 和对应游戏版本的内置版后，打开 Radmin 界面，点击电源按钮。首次连接会为独立核心注册一个设备身份。连接成功后可在网络页创建或加入网络、查看成员、启用或停用网络、退出网络。要进入游戏，在原版多人游戏中填写同网络成员的 `26.x.x.x:端口`。内置核心通过 Radmin 对端通道转发原版游戏的 TCP 连接和状态查询。网络管理员可移除成员，结果以 Radmin 的确认应答为准。内置模式默认使用游戏玩家名，可在管理界面修改昵称。在线修改昵称或切换模组标记时，内置核心会通过当前连接提交新名称。离线保存的昵称会在下次连接时提交。本机昵称显示在管理界面顶部，远端用户列表不会列出本机。其他用户可看到设置中启用的模组标记。

同一个电脑用户下，CLI 和模组默认使用同一个核心配置目录 `.radminlan/core`，但一个身份同一时刻只能运行一个会话。在游戏中连接前，先退出使用默认配置目录的 CLI。CLI 可用 `--profile` 指定另一个身份。

[官方客户端功能对照](core-compatibility.md)记录了各功能的覆盖范围。

## 构建内置版

在私有核心仓库用 JDK 21 构建 CLI JAR，然后传入其绝对路径。以 26.3 为例：

```powershell
$env:RADMIN_CORE_JAR = '<Radmin Linker Core JAR 的绝对路径>'
./scripts/build-26.3.ps1 -JavaHome '<JDK 25 路径>'
```

也可给 Gradle 传 `-PradminCoreJar=<绝对路径>`。未设置核心 JAR 时构建传统 Windows 版。本仓库保留游戏适配层，独立 CLI、协议兼容层、身份和对端通道的源码保存在私有核心仓库。核心 JAR 内含其 OpenRad MIT 许可证。

桌面内置版转发游戏发出的 IPv4 TCP 连接和状态查询。独立 CLI 支持 Windows Wintun、Linux TUN 和 macOS utun，可让系统应用使用 Radmin 虚拟 IPv4。桌面程序可任选系统 VPN 或游戏内转发，安装包中的游戏内转发无需管理员权限。

Android 和 iOS 各有独立的系统 VPN 应用，移动设备上的网络管理位于对应应用。Android 开发版 APK 已构建，并在 Android 14 模拟器验证了 VPN 服务。iOS 应用和 Packet Tunnel 已通过模拟器与未签名的 iPhone arm64 构建。真机安装仍需要为应用和扩展配置 Apple 签名。Fabric 管理界面适用于 Minecraft Java 版。内置版会将 Minecraft 通过「对局域网开放」明确发布的端口提供给启用网络内的已认证成员，并通过对端连接广播房间。关闭世界后停止接受连接。其他本机服务应使用系统 VPN。

本机用两个独立测试身份验证了官方协调服务登录、创建和密码加入临时私网、双向 IPv4、通过加密对端通道的 Minecraft 状态查询和 Ping。测试网络已删除。三个 Fabric 目标通过构建、回归和连接钩子加载检查。独立核心的 CI 验证了桌面虚拟网卡双向收发、Android 14 的 VPN 服务生命周期以及 iOS 双目标编译。
