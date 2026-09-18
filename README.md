# SkyOwl Monitor

> SpringBoot + Apache SkyWalking APM 学习示例项目

## 项目结构

```
skywalking-monitor-examples/
├── java/                      # Spring Boot 应用
│   ├── src/main/java/
│   ├── src/main/resources/
│   └── pom.xml
├── ops/                       # 部署配置
│   ├── docker-compose.yml
│   ├── config/                # SkyWalking 配置
│   │   ├── README.md         # UI/OAP 配置说明
│   │   └── oap/              # OAP 配置目录
│   └── scripts/
├── agent/                     # SkyWalking Agent
│   └── skywalking-agent.jar
└── README.md
```

## 快速启动

### 1. 启动 SkyWalking

```powershell
cd ops
docker compose up -d
```

### 2. 启动 Java 应用

```powershell
cd java
mvn spring-boot:run
```

## 访问地址

| 服务 | 地址 |
|------|------|
| Spring Boot | http://localhost:8080 |
| SkyWalking UI | http://127.0.0.1:8088 |

## SkyWalking UI 配置

详细配置说明请查看 [ops/config/README.md](ops/config/README.md)

### 主要配置项

| 变量 | 说明 |
|------|------|
| SW_OAP_ADDRESS | OAP 服务地址 |
| SW_TIMEOUT | 请求超时时间 |
| SW_MAX_LOGIN_DATE | 登录有效期 |

## 测试接口

| 接口 | 说明 |
|------|------|
| GET /api/hello | Hello World |
| GET /api/newVoiceSession | AI语音会话演示 |
| POST /api/voice/session | 创建语音会话 |
| POST /api/voice/message | 发送消息 |
| DELETE /api/voice/session/{id} | 结束会话 |

## 技术栈

- Java 21
- Spring Boot 3.2.5
- Apache SkyWalking 9.4.0
