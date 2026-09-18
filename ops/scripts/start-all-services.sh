#!/bin/bash
# 启动 SkyOwl 4 个微服务
# 每个服务单独运行在不同端口，通过 RabbitMQ 串联

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
JAVA_DIR="$PROJECT_ROOT/java"

OTEL_AGENT="$PROJECT_ROOT/agent/opentelemetry-javaagent.jar"
OTEL_ENDPOINT="${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:5081}"
OO_USER="${OPENOBSERVE_USER:-admin@example.com}"
OO_PASS="${OPENOBSERVE_PASSWORD:-Admin@123456}"
ORG_NAME="${OTEL_ORG_NAME:-default}"

if [ ! -f "$OTEL_AGENT" ]; then
    echo "[ERROR] OpenTelemetry Agent 不存在: $OTEL_AGENT"
    exit 1
fi

OO_AUTH=$(echo -n "${OO_USER}:${OO_PASS}" | base64 | tr -d '\n')

# 通用 JVM 参数
JAVA_OPTS_BASE=(
    -javaagent:"$OTEL_AGENT"
    -Dotel.exporter.otlp.endpoint="$OTEL_ENDPOINT"
    -Dotel.exporter.otlp.protocol=grpc
    -Dotel.exporter.otlp.headers="Authorization=Basic%20${OO_AUTH},organization=${ORG_NAME}"
    -Dotel.metrics.exporter=none
    -Dotel.traces.exporter=otlp
    -Dotel.logs.exporter=otlp
)

# 日志目录
LOG_DIR="$SCRIPT_DIR/../logs"
mkdir -p "$LOG_DIR"

start_service() {
    local name=$1
    local jar=$2
    local port=$3
    local service_name=$4

    echo "[INFO] 启动 $name (端口 $port) ..."
    nohup java "${JAVA_OPTS_BASE[@]}" \
        -Dotel.service.name="$service_name" \
        -Dserver.port="$port" \
        -jar "$jar" \
        > "$LOG_DIR/$name.log" 2>&1 &
    echo "[INFO] $name PID: $!"
}

# 检查 JAR
for svc in user-service order-service payment-service notification-service; do
    if [ ! -f "$JAVA_DIR/$svc/target/$svc.jar" ]; then
        echo "[ERROR] JAR 不存在: $JAVA_DIR/$svc/target/$svc.jar"
        echo "请先编译: cd $JAVA_DIR && mvn clean install -DskipTests"
        exit 1
    fi
done

echo "=========================================="
echo "  SkyOwl Microservices 启动"
echo "=========================================="
echo "  OTel Endpoint: $OTEL_ENDPOINT"
echo "  Log Dir:       $LOG_DIR"
echo "=========================================="
echo ""

start_service "user-service"        "$JAVA_DIR/user-service/target/user-service.jar"        8081 "user-service"
sleep 1
start_service "order-service"       "$JAVA_DIR/order-service/target/order-service.jar"      8082 "order-service"
sleep 1
start_service "payment-service"     "$JAVA_DIR/payment-service/target/payment-service.jar"  8083 "payment-service"
sleep 1
start_service "notification-service" "$JAVA_DIR/notification-service/target/notification-service.jar" 8084 "notification-service"

echo ""
echo "=========================================="
echo "  启动完成！等待 30 秒让服务注册到 RabbitMQ..."
echo "=========================================="
echo ""
echo "服务地址："
echo "  用户中心:    http://localhost:8081/api/user/list"
echo "  订单中心:    http://localhost:8082/api/order/list"
echo "  支付中心:    http://localhost:8083/api/payment/list"
echo "  通知中心:    http://localhost:8084/api/notification/list"
echo "  OpenObserve: http://localhost:5080"
echo ""
echo "查看日志:"
echo "  tail -f $LOG_DIR/user-service.log"
echo "  tail -f $LOG_DIR/order-service.log"
echo "  tail -f $LOG_DIR/payment-service.log"
echo "  tail -f $LOG_DIR/notification-service.log"