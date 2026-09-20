# vex Monitor

基于 **Spring Boot 3.2 + Java 21 + OpenTelemetry Java Agent + OpenObserve** 的零侵入分布式监控示例。

模仿真实分布式系统：4 个独立可启动的微服务，通过 RabbitMQ 串联，使用 MySQL + Redis + OpenObserve，构建类似 Datadog 的开源 APM 监控能力。

---

## 1. 架构总览

```
┌──────────────────────────────────────────────────────────────────────┐
│                          OpenObserve (:5080)                          │
│                     Trace + Log + Metric 接收与展示                  │
└──────────────────────────────────────────────────────────────────────┘
                                  ▲ gRPC/OTLP
                                  │
┌──────────────────────────── Java 业务服务 ────────────────────────────┐
│                                                                       │
│  ┌────────────┐    ┌────────────┐    ┌────────────┐    ┌──────────┐│
│  │user-service│    │order-service│   │payment-    │    │notifica- ││
│  │  :8081     │───▶│   :8082     │──▶│service     │───▶│tion-     ││
│  │            │    │             │   │  :9083     │    │service   ││
│  │ HTTP入口   │    │ 订单+库存   │   │  支付处理  │    │  :8084   ││
│  └─────┬──────┘    └─────┬──────┘    └─────┬──────┘    └────┬─────┘│
│        │                 │                  │                │      │
│        └─────────────────┴──── RabbitMQ ────┴────────────────┘      │
│                              (vex exchange)                       │
└───────────────────────────────────────────────────────────────────────┘
              │                              │
              ▼                              ▼
       ┌────────────┐                ┌────────────┐
       │   MySQL    │                │   Redis    │
       │  :23306    │                │   :6379    │
       │ (共享DB)   │                │  (缓存)    │
       └────────────┘                └────────────┘
```

每服务启动时挂载 `opentelemetry-javaagent.jar`，**业务代码 0 改动**自动采集：
- HTTP/gRPC 入口 span
- JDBC SQL span
- Redis 操作 span
- RabbitMQ 生产/消费 span
- Logback 日志自动关联 trace_id

---

## 2. 目录结构

```
monitor-examples/
├── README.md                  # 本文档
├── ops/                       # 部署与运维
│   ├── docker-compose.yml     # MySQL + Redis + RabbitMQ + OpenObserve
│   ├── scripts/
│   │   ├── start-all-services-ps1.ps1  # 推荐：WMI 真正 detach 启动
│   │   ├── start-all-services.bat      # Windows 原生
│   │   ├── start-all-services.sh       # Git Bash / Linux
│   │   └── simulate-traffic.sh         # 综合业务压测脚本
│   ├── logs/                  # 4 个服务的运行日志
│   └── config/
├── agent/
│   └── opentelemetry-javaagent.jar   # OTel Java Agent 2.4.0
└── java/                      # Maven 多模块项目
    ├── pom.xml
    ├── vex-common/         # 共享 MQ 配置、Event 基类
    ├── user-service/          # 端口 8081
    ├── order-service/         # 端口 8082
    ├── payment-service/       # 端口 9083
    └── notification-service/  # 端口 8084
```

---

## 3. 关键技术决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 监控方案 | **OpenTelemetry Java Agent 2.4.0 + OpenObserve** | SkyWalking 9.x Agent 与 Java 21 不兼容 (`ClassCircularityError`)；OTel Agent 对 Spring Boot 3 + Java 21 完美支持 |
| 日志关联 | **OTel Agent 自动注入 trace_id 到 MDC** | 业务代码 0 改动，通过 `-Dotel.logs.exporter=otlp` 直接上报 OpenObserve |
| 消息总线 | **RabbitMQ 3 management** | 跨服务 trace_id 传递通过 `BaseEvent.traceId` 字段 + MQ Header |
| 端口规划 | 8081/8082/**9083**/8084 | payment-service 改用 9083，避开与现有 iot-test-emqx (8083) 端口冲突 |
| 共享 DB | MySQL 单实例多 schema | 4 服务复用一张 `vex` 库下的多张表 |

---

## 4. 完成的所有事情

### 4.1 中间件搭建 (ops/docker-compose.yml)

- ✅ **OpenObserve** 部署（`public.ecr.aws/zinclabs/openobserve:latest`）
  - 端口 `5080`（Web UI）/ `5081`（gRPC OTLP 接收）
  - 默认账号 `admin@example.com / Admin@123456`
- ✅ **MySQL 8.0** 容器
  - 端口映射 `23306:3306`（注：原计划 13306，被 Windows Hyper-V 占用，改用 23306）
  - 数据库 `vex`，用户 `vex / vex123`
  - 健康检查通过后启动应用
- ✅ **Redis 7** 容器，端口 `6379`
- ✅ **RabbitMQ 3-management** 容器，端口 `5672 / 15672`
- ✅ 数据卷持久化（`ops_mysql-data` 等）

### 4.2 Java 微服务实现 (java/)

共 5 个 Maven 模块：

#### vex-common (共享)
- ✅ `BaseEvent` — 所有 MQ 事件的基类（携带 `traceId`、`sourceService`）
- ✅ `UserCreatedEvent / OrderCreatedEvent / OrderPayingEvent / PaymentResultEvent`
- ✅ `MessageBusConfig` — 4 个 exchange / queue / binding 统一定义
- ✅ `EventPublisher` — 自动注入 `__TypeId__` 消息头解决 Jackson 多态反序列化
- ✅ `schema.sql` — 5 张表 (users/orders/payments/products/notifications) + 测试数据

#### user-service (端口 8081)
- ✅ `POST /api/user/create` — 插入 MySQL → 缓存 Redis → 发 `UserCreatedEvent`
- ✅ `GET /api/user/list` / `/api/user/{id}` / `/api/user/stats`
- ✅ Micrometer Counter：`create_total / create_failed_total / publish_total`

#### order-service (端口 8082)
- ✅ `POST /api/order/create` — 验证用户 → 检查库存 → 插入 orders → 扣库存 → 发 `OrderCreatedEvent`
- ✅ `POST /api/order/pay/{orderNo}` — 发 `OrderPayingEvent` 给 payment-service
- ✅ `@RabbitListener` 监听 `PaymentResultEvent` 自动更新订单状态为 PAID/PAY_FAILED
- ✅ 4 个 Micrometer Counter + 1 个 Timer

#### payment-service (端口 9083)
- ✅ `@RabbitListener` 消费 `OrderPayingEvent`
- ✅ 写入 payments 表 → 模拟 200ms 支付延迟 → 发 `PaymentResultEvent`
- ✅ `GET /api/payment/list` / `/{paymentNo}` / `/stats`

#### notification-service (端口 8084)
- ✅ `@RabbitListener` 监听统一队列，**多态处理 4 种 EventType**
- ✅ 写入 notifications 表
- ✅ `GET /api/notification/list` / `/user/{userId}` / `/stats`

### 4.3 OpenTelemetry 零侵入接入

- ✅ **OTel Java Agent 2.4.0** 下载到 `agent/opentelemetry-javaagent.jar`
- ✅ 通过 `-javaagent:...` 挂载，**业务代码 0 改动**
- ✅ OTel 自动拦截的组件：
  - HTTP Server/Client (Spring Web MVC)
  - JDBC (`com.mysql.cj.jdbc.Driver`)
  - Redis (Lettuce/Jedis)
  - RabbitMQ (Spring AMQP)
  - Logback（自动注入 trace_id 到 MDC）
- ✅ JVM 启动参数统一配置：
  ```bash
  -Dotel.exporter.otlp.endpoint=http://localhost:5081
  -Dotel.exporter.otlp.protocol=grpc
  -Dotel.exporter.otlp.headers="Authorization=Basic <base64>,organization=default"
  -Dotel.metrics.exporter=none
  -Dotel.traces.exporter=otlp
  -Dotel.logs.exporter=otlp
  -Dotel.service.name=<service>
  ```

### 4.4 trace_id 跨服务传递

两条链路打通：

1. **HTTP 跨服务** — OTel Agent 自动通过 W3C Trace Context Header 透传
2. **MQ 跨服务** — `EventPublisher` 自动设置 `__TypeId__` 消息头；消费者从 `BaseEvent.traceId` 字段读取后写入 MDC

### 4.5 启动脚本 (ops/scripts/)

- ✅ `start-all-services.sh` — Git Bash / Linux，使用 `nohup ... &`
- ✅ `start-all-services.bat` — Windows 原生批处理
- ✅ **`start-all-services-ps1.ps1` (推荐)** — 使用 WMI `Win32_Process.Create` 真正 detach 进程
  - 解决了 PowerShell 父进程退出导致子 Java 进程被终止的问题
  - 解决了 bash `nohup` 子进程被 sandbox 回收的问题

### 4.6 综合压测脚本 (simulate-traffic.sh / .ps1)

5 大场景共 87+ 次调用：

| Part | 场景 | 覆盖接口 | 异常类型 |
|------|------|---------|---------|
| 0 | 健康检查 | 4 个 actuator/health | — |
| 1 | 正常业务链路 | user→order→payment→notification | — |
| 2 | 错误请求（7 种） | 重复用户名、不存在 userId、库存超限、伪 orderNo、伪 userId、缺字段、负数 quantity | DuplicateKeyException / EmptyResultDataAccessException / OutOfStock / NullPointer |
| 3 | 并发请求 | 20 worker 同时打 4 服务（PowerShell 用 Runspace Pool） | — |
| 4 | 突发流量 | 50 burst create user | — |

### 4.7 OpenObserve 数据验证

实际验证结果：

| 指标 | 数量 |
|------|------|
| Trace 总数 | 2,297,927+ spans |
| Log 总数 | 1,594,267+ 条 |
| notification-service spans | 1,593,538 |
| order-service spans | 141+ |
| payment-service spans | 115+ |
| user-service spans | 105+ |

每条 ERROR/WARN 业务日志都自动关联 trace_id：
```
service_name: user-service
body:        "User already exists: username=alice"
trace_id:    25c3724e443c36773da634cdd1fa6999
```

---

## 5. 启动指南

### 5.1 一次性环境准备

```bash
# 1. 启动中间件
cd ops
docker compose up -d

# 2. 等待 MySQL 就绪后初始化 schema（首次）
sleep 30
docker exec -i vex-mysql mysql -uvex -pvex123 vex \
  < ../java/vex-common/src/main/resources/schema.sql

# 3. 编译 Java 项目
cd ../java
mvn clean install -DskipTests
```

### 5.2 启动 4 个服务

**Windows (推荐)**：
```powershell
powershell -ExecutionPolicy Bypass -File ../ops/scripts/start-all-services-ps1.ps1
```

**Git Bash**：
```bash
bash ../ops/scripts/start-all-services.sh
```

### 5.3 验证

```bash
# 健康检查
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:9083/actuator/health
curl http://localhost:8084/actuator/health

# 业务调用
curl -X POST http://localhost:8081/api/user/create \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"a@a.com","phone":"13900000001"}'

# 触发完整链路
bash ops/scripts/simulate-traffic.sh
```

### 5.4 查看监控数据

打开 **http://localhost:5080** (admin@example.com / Admin@123456)

**SQL 查询示例**：

```sql
-- 各服务 trace 数
SELECT service_name, count(*) as cnt
FROM default
GROUP BY service_name
ORDER BY cnt DESC

-- 最近 ERROR 日志
SELECT service_name, body, trace_id, _timestamp
FROM default
WHERE severity = 'ERROR'
ORDER BY _timestamp DESC
LIMIT 20

-- 业务 WARN 日志（排除启动错误）
SELECT service_name, body, trace_id
FROM default
WHERE severity IN ('WARN','ERROR')
  AND body NOT LIKE '%HikariPool%'
ORDER BY _timestamp DESC
LIMIT 20

-- 慢 SQL（找出耗时 >100ms 的数据库调用）
SELECT service_name, body, duration
FROM default
WHERE body LIKE '%SELECT%FROM%'
ORDER BY duration DESC
LIMIT 10
```

---

## 6. 端口分配

| 服务 | 端口 | 备注 |
|------|------|------|
| user-service | **8081** | — |
| order-service | **8082** | — |
| payment-service | **9083** | 原 8083 被占用，改 9083 |
| notification-service | **8084** | — |
| MySQL | **23306** | 原 13306 被 Hyper-V 占用 |
| Redis | 6379 | — |
| RabbitMQ | 5672 / 15672 | AMQP / 管理 UI |
| OpenObserve | 5080 / 5081 | Web / OTLP gRPC |

---

## 7. 故障排查备忘

| 现象 | 原因 | 解决 |
|------|------|------|
| `ClassNotFoundException: YWRtaW5...` | PowerShell 拼接 `-D` 时引号被吞 | 用字符串拼接而不是数组 |
| 服务启动后立即退出 | bash `nohup &` 在 sandbox 中被回收 | 用 WMI `Win32_Process.Create` detach |
| MySQL 连接被拒 | MySQL 容器端口未映射到 host | 删除旧容器用 `docker run -p 23306:3306` |
| payment-service 启动失败 | 8083 被 iot-test-emqx 占用 | 改用 9083 |
| trace_id 在日志里为空 | 默认 logback 没配 MDC | 修改 logback-spring.xml 加 `%mdc{trace_id:-}` |
| Lombok 编译失败 | Lombok 1.18.x 与 JDK21 + maven-compiler-plugin 3.13 不兼容 | 放弃 Lombok 手写 getter/setter |
