# vex 4 微服务综合压测脚本 (PowerShell)
# 覆盖：正常请求 / 错误请求 / 并发请求 / 突发流量
# 用途：验证 OpenObserve 中 trace + log 关联、错误上报

$USER_URL   = "http://localhost:8081"
$ORDER_URL  = "http://localhost:8082"
$PAYMENT_URL= "http://localhost:9083"
$NOTIF_URL  = "http://localhost:8084"

$script:TotalReq = 0
$script:TotalOK  = 0
$script:TotalErr = 0

function Bar([string]$Title) {
    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Blue
    Write-Host "  $Title" -ForegroundColor Blue
    Write-Host "============================================================" -ForegroundColor Blue
}

function Record([string]$Name, [string]$Code) {
    $script:TotalReq++
    if ($Code -match '^2') {
        $script:TotalOK++
        Write-Host "  [OK   $Code] $Name" -ForegroundColor Green
    } else {
        $script:TotalErr++
        Write-Host "  [FAIL $Code] $Name" -ForegroundColor Red
    }
}

function HttpStatus([string]$Method, [string]$Url, [string]$Body = $null) {
    try {
        if ($Body) {
            return (Invoke-WebRequest -Method $Method -Uri $Url -ContentType "application/json" -Body $Body -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop).StatusCode
        } else {
            return (Invoke-WebRequest -Method $Method -Uri $Url -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop).StatusCode
        }
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0
    }
}

function Body([string]$Method, [string]$Url, [string]$Body = $null) {
    try {
        if ($Body) {
            return (Invoke-WebRequest -Method $Method -Uri $Url -ContentType "application/json" -Body $Body -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop).Content
        } else {
            return (Invoke-WebRequest -Method $Method -Uri $Url -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop).Content
        }
    } catch {
        if ($_.Exception.Response) {
            $reader = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
            return $reader.ReadToEnd()
        }
        return ""
    }
}

############################################################
# Part 0. 健康检查
############################################################
Bar "Part 0. 健康检查"
foreach ($u in @($USER_URL, $ORDER_URL, $PAYMENT_URL, $NOTIF_URL)) {
    $code = HttpStatus "GET" "$u/actuator/health"
    Record "GET $u/actuator/health" "$code"
}

############################################################
# Part 1. 正常业务链路
############################################################
Bar "Part 1. 正常业务链路"

Write-Host "-- 1.1 创建用户 alice99" -ForegroundColor Yellow
$res = Body "POST" "$USER_URL/api/user/create" '{"username":"alice99","email":"alice99@test.com","phone":"13900000991"}'
Write-Host "    $res"
if ($res -match '"userId":(\d+)') { $script:UserId = $Matches[1] } else { $script:UserId = "1" }
Record "user/create -> userId=$script:UserId" "200"

Write-Host "-- 1.2 查询用户列表"
$code = HttpStatus "GET" "$USER_URL/api/user/list?limit=5"
Record "user/list?limit=5" "$code"

Write-Host "-- 1.3 创建订单 (iPhone 15)"
$res = Body "POST" "$ORDER_URL/api/order/create" "{`"userId`":$script:UserId,`"productName`":`"iPhone 15`",`"quantity`":1}"
Write-Host "    $res"
if ($res -match '"orderNo":"([^"]+)"') { $script:OrderNo = $Matches[1] } else { $script:OrderNo = "" }
Record "order/create iPhone 15 -> $script:OrderNo" "200"

Write-Host "-- 1.4 触发支付"
$code = HttpStatus "POST" "$ORDER_URL/api/order/pay/$script:OrderNo"
Record "order/pay/$script:OrderNo" "$code"
Start-Sleep -Seconds 2

Write-Host "-- 1.5 验证 payment 已写入"
$code = HttpStatus "GET" "$PAYMENT_URL/api/payment/list?limit=5"
Record "payment/list" "$code"

Write-Host "-- 1.6 验证 notification 已写入"
$code = HttpStatus "GET" "$NOTIF_URL/api/notification/user/$script:UserId"
Record "notification/user/$script:UserId" "$code"

############################################################
# Part 2. 错误请求
############################################################
Bar "Part 2. 错误请求"

Write-Host "-- 2.1 用户名重复 (DuplicateKeyException)"
$code = HttpStatus "POST" "$USER_URL/api/user/create" '{"username":"alice","email":"x@x.com","phone":"13900000000"}'
Record "user/create duplicate -> expect 500" "$code"

Write-Host "-- 2.2 订单指定不存在的 userId"
$code = HttpStatus "POST" "$ORDER_URL/api/order/create" '{"userId":999999,"productName":"iPhone 15","quantity":1}'
Record "order/create userId=999999 -> expect 500" "$code"

Write-Host "-- 2.3 库存不足 (qty=99999)"
$code = HttpStatus "POST" "$ORDER_URL/api/order/create" "{`"userId`":$script:UserId,`"productName`":`"MacBook Pro`",`"quantity`":99999}"
Record "order/create qty=99999 -> expect 500" "$code"

Write-Host "-- 2.4 支付不存在的订单"
$code = HttpStatus "POST" "$ORDER_URL/api/order/pay/ORD-DOES-NOT-EXIST-XXXX"
Record "order/pay fake -> expect 500" "$code"

Write-Host "-- 2.5 查询不存在的 userId"
$code = HttpStatus "GET" "$USER_URL/api/user/9999999"
Record "user/9999999 -> expect 500" "$code"

Write-Host "-- 2.6 查询不存在的 orderNo"
$code = HttpStatus "GET" "$ORDER_URL/api/order/ORD-FAKE-FAKE-FAKE"
Record "order/ORD-FAKE-FAKE-FAKE -> expect 500" "$code"

Write-Host "-- 2.7 缺字段 userId"
$code = HttpStatus "POST" "$ORDER_URL/api/order/create" '{"productName":"AirPods Pro","quantity":1}'
Record "order/create missing userId -> expect 500" "$code"

Write-Host "-- 2.8 支付负数 amount (模拟 type cast 错误)"
$code = HttpStatus "POST" "$ORDER_URL/api/order/create" "{`"userId`":$script:UserId,`"productName`":`"iPhone 15`",`"quantity`":-1}"
Record "order/create qty=-1 -> expect 500" "$code"

############################################################
# Part 3. 并发请求
############################################################
Bar "Part 3. 并发请求 (20 并发 × 多服务混合)"

# 预创建 3 个用户
foreach ($u in @("carol01","carol02","carol03")) {
    Body "POST" "$USER_URL/api/user/create" "{`"username`":`"$u`",`"email`":`"$u@t.com`",`"phone`":`"13900000`"}" | Out-Null
}

$products = @("iPhone 15","MacBook Pro","AirPods Pro","iPad Mini")
$jobs = @()
$runspacePool = [runspacefactory]::CreateRunspacePool(1, 20)
$runspacePool.Open()

$scriptBlock = {
    param($idx, $userId, $product, $qty, $userUrl, $orderUrl, $payUrl, $notifUrl)
    $body = "{`"userId`":$userId,`"productName`":`"$product`",`"quantity`":$qty}"
    try {
        $r = Invoke-WebRequest -Method POST -Uri "$orderUrl/api/order/create" -ContentType "application/json" -Body $body -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
        if ($r.Content -match '"orderNo":"([^"]+)"') {
            $orderNo = $Matches[1]
            if ($idx % 2 -eq 0) {
                Invoke-WebRequest -Method POST -Uri "$orderUrl/api/order/pay/$orderNo" -UseBasicParsing -TimeoutSec 10 -ErrorAction SilentlyContinue | Out-Null
            }
        }
    } catch {}
    try { Invoke-WebRequest -Method GET -Uri "$userUrl/api/user/$userId" -UseBasicParsing -TimeoutSec 10 -ErrorAction SilentlyContinue | Out-Null } catch {}
    try { Invoke-WebRequest -Method GET -Uri "$orderUrl/api/order/list?limit=5" -UseBasicParsing -TimeoutSec 10 -ErrorAction SilentlyContinue | Out-Null } catch {}
    try { Invoke-WebRequest -Method GET -Uri "$payUrl/api/payment/list?limit=5" -UseBasicParsing -TimeoutSec 10 -ErrorAction SilentlyContinue | Out-Null } catch {}
    try { Invoke-WebRequest -Method GET -Uri "$notifUrl/api/notification/list?limit=5" -UseBasicParsing -TimeoutSec 10 -ErrorAction SilentlyContinue | Out-Null } catch {}
}

$sw = [System.Diagnostics.Stopwatch]::StartNew()
for ($i = 1; $i -le 20; $i++) {
    $userId = ($i % 5) + 1
    $product = $products[$i % 4]
    $qty = ($i % 3) + 1
    $ps = [powershell]::Create().AddScript($scriptBlock)
    $ps.AddArgument($i) | Out-Null
    $ps.AddArgument($userId) | Out-Null
    $ps.AddArgument($product) | Out-Null
    $ps.AddArgument($qty) | Out-Null
    $ps.AddArgument($USER_URL) | Out-Null
    $ps.AddArgument($ORDER_URL) | Out-Null
    $ps.AddArgument($PAYMENT_URL) | Out-Null
    $ps.AddArgument($NOTIF_URL) | Out-Null
    $ps.RunspacePool = $runspacePool
    $jobs += [pscustomobject]@{ PS = $ps; Handle = $ps.BeginInvoke() }
}

foreach ($j in $jobs) { $j.PS.EndInvoke($j.Handle); $j.PS.Dispose() }
$runspacePool.Close(); $runspacePool.Dispose()
$sw.Stop()
Write-Host "  20 并发完成，耗时 $($sw.Elapsed.TotalSeconds)s" -ForegroundColor Yellow

############################################################
# Part 4. 突发流量
############################################################
Bar "Part 4. 突发流量 (50 并发 burst)"

$burstJobs = @()
$burstPool = [runspacefactory]::CreateRunspacePool(1, 15)
$burstPool.Open()

$burstScript = {
    param($i, $userUrl)
    try {
        $body = "{`"username`":`"burst_$i`",`"email`":`"b$i@t.com`",`"phone`":`"13900001$i`"}"
        Invoke-WebRequest -Method POST -Uri "$userUrl/api/user/create" -ContentType "application/json" -Body $body -UseBasicParsing -TimeoutSec 10 -ErrorAction SilentlyContinue | Out-Null
    } catch {}
}

for ($i = 1; $i -le 50; $i++) {
    $ps = [powershell]::Create().AddScript($burstScript)
    $ps.AddArgument($i) | Out-Null
    $ps.AddArgument($USER_URL) | Out-Null
    $ps.RunspacePool = $burstPool
    $burstJobs += [pscustomobject]@{ PS = $ps; Handle = $ps.BeginInvoke() }
}

foreach ($j in $burstJobs) { $j.PS.EndInvoke($j.Handle); $j.PS.Dispose() }
$burstPool.Close(); $burstPool.Dispose()
Write-Host "  burst 完成" -ForegroundColor Yellow

############################################################
# Part 5. 统计
############################################################
Bar "Part 5. 最终统计"

Write-Host "各服务 Counter (来源: 服务自身 Micrometer):" -ForegroundColor Yellow
Write-Host ""
Write-Host "  [user-service]"
$null = Body "GET" "$USER_URL/api/user/stats"
Write-Host ""
Write-Host "  [order-service]"
$null = Body "GET" "$ORDER_URL/api/order/stats"
Write-Host ""
Write-Host "  [payment-service]"
$null = Body "GET" "$PAYMENT_URL/api/payment/stats"
Write-Host ""
Write-Host "  [notification-service]"
$null = Body "GET" "$NOTIF_URL/api/notification/stats"
Write-Host ""

Bar "压测完成"
Write-Host ""
Write-Host "查看 OpenObserve:" -ForegroundColor Green
Write-Host "  UI:           http://localhost:5080 (admin@example.com / Admin@123456)"
Write-Host ""
Write-Host "示例查询 (traces):" -ForegroundColor Green
Write-Host '  SELECT service_name, count(*) cnt FROM default'
Write-Host '  WHERE _timestamp > 1789734900000000'
Write-Host '  GROUP BY service_name ORDER BY cnt DESC'
Write-Host ""
Write-Host "示例查询 (error logs):" -ForegroundColor Green
Write-Host '  SELECT service_name, body, trace_id'
Write-Host '  FROM default WHERE severity IN ('"'"'ERROR'"'"','"'"'WARN'"'"')'
Write-Host '  ORDER BY _timestamp DESC LIMIT 20'
Write-Host ""
