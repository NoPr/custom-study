# Nacos 配置中心与服务发现

> 对应 Java Demo：[NacosConfigDemo.java](../../../java/base/spring/alibaba/NacosConfigDemo.java) [NacosDiscoveryDemo.java](../../../java/base/spring/alibaba/NacosDiscoveryDemo.java)

---

## 一、配置长轮询时序图

```mermaid
sequenceDiagram
    participant Client as Nacos Client
    participant Server as Nacos Server (2.x)
    participant DB as Config Store

    Client->>Server: GET /config?dataId=xxx&group=xxx&contentMD5=abc
    Note over Server: 对比 MD5，无变化则 hold 连接
    Server-->>Client: HTTP 200 (hold 30s)

    rect rgb(255, 240, 220)
        Note over Server: 管理员通过控制台修改配置
        Server->>DB: 写入新配置
        DB-->>Server: OK
        Server->>Client: 推送事件 (立即返回)
    end

    Client->>Client: 解析新配置 → refresh Environment
    Client->>Client: 重新绑定 @Value / @RefreshScope Bean
```

**关键机制**：
- Nacos 1.x：HTTP 短轮询，客户端每 30s 拉取一次
- Nacos 2.x：gRPC 长连接（基于 HTTP/2），服务端主动推送变更，性能提升 **10 倍**
- MD5 对比：客户端携带 `contentMD5`，服务端对比后决定是否返回新内容

---

## 二、服务注册心跳图

```mermaid
sequenceDiagram
    participant Provider as 服务提供者
    participant Nacos as Nacos Server
    participant Consumer as 服务消费者

    rect rgb(220, 255, 220)
        Note over Provider,Nacos: 注册阶段
        Provider->>Nacos: 1. 注册实例 (ip+port+meta)
        Nacos->>Nacos: 写入注册表
        Nacos-->>Provider: 注册成功
    end

    loop 每 5s
        Provider->>Nacos: 2. 发送心跳 (beat)
        Nacos->>Nacos: 更新 lastHeartbeat 时间戳
    end

    Consumer->>Nacos: 3. 订阅服务 (subscribe)
    Nacos-->>Consumer: 返回实例列表 + 后续推送变更

    Note over Provider: 临时实例 15s 无心跳 → 不健康
    Note over Provider: 临时实例 30s 无心跳 → 剔除

    rect rgb(255, 220, 220)
        Note over Nacos: 永久实例不受心跳检查影响
        Note over Nacos: 需用户主动调用 deregister
    end
```

**临时实例 vs 永久实例**：

| 维度 | 临时实例 (ephemeral) | 永久实例 (persistent) |
|------|---------------------|----------------------|
| CAP | AP (Distro 协议) | CP (Raft 协议) |
| 心跳 | 必需 (5s 间隔) | 不需要 |
| 剔除 | 自动 (15s 不健康, 30s 剔除) | 手动注销 |
| 场景 | 微服务 (K8s Pod) | DNS / CoreDNS |

---

## 三、Namespace / Group / DataId 三级定位图

```mermaid
graph TD
    subgraph "Namespace: 环境隔离 (public / prod / dev)"
        direction LR
        NS1["public (默认)"]
        NS2["prod"]
        NS3["dev"]
    end

    subgraph "Group: 业务分组 (DEFAULT_GROUP / CUSTOM_GROUP)"
        G1["DEFAULT_GROUP"]
        G2["CUSTOM_GROUP"]
    end

    subgraph "DataId: 配置唯一标识"
        D1["application-dev.yml"]
        D2["datasource.yml"]
        D3["sentinel.properties"]
    end

    NS1 --> G1
    NS1 --> G2
    NS2 --> G1
    NS3 --> G1

    G1 --> D1
    G1 --> D3
    G2 --> D2

    D1 -.- K1["唯一 Key: public/DEFAULT_GROUP/application-dev.yml"]
    D2 -.- K2["唯一 Key: public/CUSTOM_GROUP/datasource.yml"]
    D3 -.- K3["唯一 Key: prod/DEFAULT_GROUP/sentinel.properties"]
```

**DataId 命名规则**：
```
${prefix}-${spring.profiles.active}.${file-extension}

示例：
  application-dev.yml
  application-prod.properties
  user-service-dev.yml
```

---

## 四、配置优先级链

```
1. 命令行参数          --server.port=9090         (最高优先级)
2. 环境变量             SERVER_PORT=9090
3. Nacos 配置中心       DataId=application-dev.yml
4. application.yml      classpath 配置文件
5. bootstrap.yml        (Spring Cloud 2020 起已移除)
```

**优先级规则**：外部化配置 > 内部配置，远程 > 本地，精确 > 模糊。