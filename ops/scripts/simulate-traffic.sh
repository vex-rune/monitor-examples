#!/bin/bash
# vex 4 个微服务综合业务压测脚本
# 覆盖：正常请求 / 错误请求 / 并发请求
# 用途：验证 OpenObserve 中的 trace + log 关联

set -u

USER_URL="http://localhost:8081"
ORDER_URL="http://localhost:8082"
PAYMENT_URL="http://localhost:9083"
NOTIF_URL="http://localhost:8084"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

PIDS=()
TOTAL_REQUESTS=0
TOTAL_SUCCESS=0
TOTAL_FAILED=0

cleanup() {
    if [ ${#PIDS[@]} -gt 0 ]; then
        for pid in "${PIDS[@]}"; do
            kill "$pid" 2>/dev/null || true
        done
        wait 2>/dev/null
    fi
}
trap cleanup EXIT

bar() {
    echo ""
    echo -e "${BLUE}============================================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}============================================================${NC}"
}

# 计算 HTTP 状态码 (curl -s -o /dev/null -w "%{http_code}")
http_status() {
    curl -s -o /dev/null -w "%{http_code}" "$@"
}

record() {
    local name=$1 status=$2
    TOTAL_REQUESTS=$((TOTAL_REQUESTS+1))
    if [[ "$status" =~ ^2 ]]; then
        TOTAL_SUCCESS=$((TOTAL_SUCCESS+1))
        echo -e "  [${GREEN}OK${NC}   $status] $name"
    else
        TOTAL_FAILED=$((TOTAL_FAILED+1))
        echo -e "  [${RED}FAIL${NC} $status] $name"
    fi
}

############################################################
# Part 1. 健康检查
############################################################
bar "Part 0. 健康检查"
for url in "$USER_URL" "$ORDER_URL" "$PAYMENT_URL" "$NOTIF_URL"; do
    code=$(http_status "$url/actuator/health")
    record "GET $url/actuator/health" "$code"
done

############################################################
# Part 1. 正常业务链路 (HTTP -> SQL -> Redis -> MQ -> 多个服务)
############################################################
bar "Part 1. 正常业务链路"

echo "-- 1.1 创建用户 alice99"
RES=$(curl -s -X POST "$USER_URL/api/user/create" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"alice99\",\"email\":\"alice99@test.com\",\"phone\":\"13900000991\"}")
echo "    $RES"
USER_ID=$(echo "$RES" | grep -oP '"userId":\d+' | grep -oP '\d+')
record "user/create -> userId=$USER_ID" "200"

echo "-- 1.2 查询用户列表"
http_status "$USER_URL/api/user/list?limit=5" > /dev/null
record "user/list?limit=5" "$(http_status $USER_URL/api/user/list?limit=5)"

echo "-- 1.3 创建订单 (iPhone 15)"
RES=$(curl -s -X POST "$ORDER_URL/api/order/create" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":$USER_ID,\"productName\":\"iPhone 15\",\"quantity\":1}")
echo "    $RES"
ORDER_NO=$(echo "$RES" | grep -oP '"orderNo":"[^"]+' | cut -d'"' -f4)
record "order/create iPhone 15 -> $ORDER_NO" "200"

echo "-- 1.4 触发支付"
code=$(http_status -X POST "$ORDER_URL/api/order/pay/$ORDER_NO")
record "order/pay/$ORDER_NO" "$code"
sleep 2  # 等 MQ 异步消费

echo "-- 1.5 验证 payment 已写入"
PAYMENT_NO=$(curl -s "$PAYMENT_URL/api/payment/list?limit=1" | grep -oP '"payment_no":"[^"]+' | head -1 | cut -d'"' -f4)
record "payment/list -> $PAYMENT_NO" "$(http_status $PAYMENT_URL/api/payment/list)"

echo "-- 1.6 验证 notification 已写入"
http_status "$NOTIF_URL/api/notification/user/$USER_ID" > /dev/null
NOTIF_COUNT=$(curl -s "$NOTIF_URL/api/notification/user/$USER_ID" | grep -oP '"id":\d+' | wc -l)
echo "    user $USER_ID 当前通知数: $NOTIF_COUNT"
record "notification/user/$USER_ID (count=$NOTIF_COUNT)" "200"

############################################################
# Part 2. 错误请求（产生 error span + 异常日志）
############################################################
bar "Part 2. 错误请求"

echo "-- 2.1 用户名重复 (DuplicateKeyException -> 500)"
code=$(http_status -X POST "$USER_URL/api/user/create" \
    -H "Content-Type: application/json" \
    -d '{"username":"alice","email":"x@x.com","phone":"13900000000"}')
record "user/create duplicate alice -> expect 500" "$code"

echo "-- 2.2 订单指定不存在的 userId (EmptyResult/500)"
code=$(http_status -X POST "$ORDER_URL/api/order/create" \
    -H "Content-Type: application/json" \
    -d '{"userId":999999,"productName":"iPhone 15","quantity":1}')
record "order/create userId=999999 -> expect 500" "$code"

echo "-- 2.3 库存不足 (quantity=99999 -> Out of stock / 500)"
code=$(http_status -X POST "$ORDER_URL/api/order/create" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":$USER_ID,\"productName\":\"MacBook Pro\",\"quantity\":99999}")
record "order/create qty=99999 -> expect 500" "$code"

echo "-- 2.4 支付一个不存在的订单 (EmptyResultDataAccessException / 500)"
code=$(http_status -X POST "$ORDER_URL/api/order/pay/ORD-DOES-NOT-EXIST-XXXX")
record "order/pay fake orderNo -> expect 500" "$code"

echo "-- 2.5 查询不存在的 userId (EmptyResultDataAccessException / 500)"
code=$(http_status "$USER_URL/api/user/9999999")
record "user/9999999 -> expect 500" "$code"

echo "-- 2.6 查询不存在的 orderNo (EmptyResultDataAccessException / 500)"
code=$(http_status "$ORDER_URL/api/order/ORD-FAKE-FAKE-FAKE")
record "order/ORD-FAKE-FAKE-FAKE -> expect 500" "$code"

echo "-- 2.7 缺字段 userId (NullPointerException / 500)"
code=$(http_status -X POST "$ORDER_URL/api/order/create" \
    -H "Content-Type: application/json" \
    -d '{"productName":"AirPods Pro","quantity":1}')
record "order/create missing userId -> expect 500" "$code"

############################################################
# Part 3. 并发请求（验证高并发下 trace_id 跨调用链）
############################################################
bar "Part 3. 并发请求 (20 并发 × 多服务)"

# 预创建 3 个用户
for u in carol01 carol02 carol03; do
    curl -s -X POST "$USER_URL/api/user/create" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$u\",\"email\":\"$u@t.com\",\"phone\":\"13900000\"}" > /dev/null
done

run_concurrent() {
    local idx=$1
    local user_id=$((idx % 5 + 1))
    local products=("iPhone 15" "MacBook Pro" "AirPods Pro" "iPad Mini")
    local product=${products[$((idx % 4))]}
    local qty=$((idx % 3 + 1))

    # 创建订单
    local res=$(curl -s -X POST "$ORDER_URL/api/order/create" \
        -H "Content-Type: application/json" \
        -d "{\"userId\":$user_id,\"productName\":\"$product\",\"quantity\":$qty}")
    local order_no=$(echo "$res" | grep -oP '"orderNo":"[^"]+' | cut -d'"' -f4)

    # 50% 概率支付
    if [ $((idx % 2)) -eq 0 ] && [ -n "$order_no" ]; then
        curl -s -X POST "$ORDER_URL/api/order/pay/$order_no" > /dev/null
    fi

    # 查询
    curl -s "$USER_URL/api/user/$user_id" > /dev/null
    curl -s "$ORDER_URL/api/order/list?limit=5" > /dev/null
    curl -s "$PAYMENT_URL/api/payment/list?limit=5" > /dev/null
    curl -s "$NOTIF_URL/api/notification/list?limit=5" > /dev/null
}

CONCURRENCY=20
echo "  启动 $CONCURRENCY 个并发 worker..."
START=$(date +%s)
for i in $(seq 1 $CONCURRENCY); do
    run_concurrent "$i" &
    PIDS+=($!)
done
for pid in "${PIDS[@]}"; do
    wait "$pid" 2>/dev/null
done
PIDS=()
END=$(date +%s)
echo "  并发完成，耗时 $((END-START))s"

############################################################
# Part 4. 突发流量 (短时间内高并发产生流量尖峰)
############################################################
bar "Part 4. 突发流量 (50 并发 burst)"

burst() {
    local i=$1
    curl -s -X POST "$USER_URL/api/user/create" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"burst_$i\",\"email\":\"b$i@t.com\",\"phone\":\"13900001$i\"}" > /dev/null
}

for i in $(seq 1 50); do
    burst "$i" &
    PIDS+=($!)
    # 控制并发窗口: 同时只有 ~15 个活跃
    if [ $((i % 15)) -eq 0 ]; then
        wait 2>/dev/null
    fi
done
for pid in "${PIDS[@]}"; do
    wait "$pid" 2>/dev/null
done
PIDS=()
echo "  burst 完成"

############################################################
# Part 5. 最终统计
############################################################
bar "Part 5. 最终统计"

echo "统计指标 (来自各服务自身 Counter):"
echo ""
echo "  [user-service]"
curl -s "$USER_URL/api/user/stats" | sed 's/^/    /'
echo ""
echo "  [order-service]"
curl -s "$ORDER_URL/api/order/stats" | sed 's/^/    /'
echo ""
echo "  [payment-service]"
curl -s "$PAYMENT_URL/api/payment/stats" | sed 's/^/    /'
echo ""
echo "  [notification-service]"
curl -s "$NOTIF_URL/api/notification/stats" | sed 's/^/    /'

bar "压测完成"
echo ""
echo "查看数据："
echo "  OpenObserve UI:    http://localhost:5080 (admin@example.com / Admin@123456)"
echo "  SQL 查询 trace:    SELECT service_name, count(*) FROM default GROUP BY service_name"
echo "  SQL 查询错误日志:  SELECT * FROM default WHERE severity='ERROR' ORDER BY _timestamp DESC LIMIT 20"
echo ""
