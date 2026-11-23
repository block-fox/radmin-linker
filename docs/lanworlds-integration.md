# 可选 Radmin 联动协议 v1

LanWorlds 与 Radmin Linker 不编译依赖对方的类。Radmin Linker 0.3.0 在 Fabric Loader `ObjectShare` 注册 `radminlan:room-provider-v1`，退出时移除。

值类型为 `Function<String, Map<String, Object>>`。输入为房间主机 IP，不带端口；只匹配服务 READY 快照中用户的虚拟 IPv4/IPv6 地址。未匹配或服务不可用返回空 Map。提供方按快照缓存查询，避免每帧遍历成员。

IPv6 按数值规范化，压缩、展开、前导零、大小写、方括号和嵌入 IPv4 尾段的等价写法使用相同查询缓存。地址匹配不进行 DNS 查询；带端口或不同作用域后缀的输入不会当作无后缀地址匹配。

| 字段 | 类型 | 内容 |
| --- | --- | --- |
| name | String | 用户名 |
| virtualIp / virtualIpv6 | String | 虚拟地址 |
| nodeId | String | 无符号十进制节点 ID，未知为空 |
| publicIp | String | 外部地址，未提供时为空 |
| connectionType / version | String | 连接方式、客户端版本 |
| state | String | online / offline / unknown |
| latencyMs | Long | 延迟，负数表示未知 |
| networks | String | 已加入的网络名称 |
| actions | List&lt;Map&lt;String, Object&gt;&gt; | 属性操作 |

每个 action 包含本地化的 `label: String` 与 `open: Consumer<Screen>`。在 Minecraft 客户端线程调用 `open`，传入父页面；Radmin Linker 打开自己的用户或网络属性页面，关闭后返回该父页面。

仅使用 JDK 类型和共享的 Minecraft `Screen` 类型；协议变更使用新的 ObjectShare key。LanWorlds 容忍 provider 缺失、返回空结果、格式错误及运行时异常，不因此阻断普通局域网信息。缺少 provider 时隐藏整个 Radmin 信息分组，操作区域显示安装提示；provider 存在但没有匹配用户时显示未匹配提示。
