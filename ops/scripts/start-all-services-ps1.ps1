$PROJECT_ROOT = "C:\workspace\monitor-examples"
$JAVA_DIR = Join-Path $PROJECT_ROOT "java"
$OTEL_AGENT = Join-Path $PROJECT_ROOT "agent\opentelemetry-javaagent.jar"
$LOG_DIR = Join-Path $PROJECT_ROOT "ops\logs"

if (!(Test-Path $LOG_DIR)) { New-Item -ItemType Directory -Force -Path $LOG_DIR | Out-Null }

$authPlain = "admin@example.com:Admin@123456"
$authBytes = [System.Text.Encoding]::UTF8.GetBytes($authPlain)
$authB64 = [Convert]::ToBase64String($authBytes)

function Start-DetachedService {
    param([string]$Name, [string]$JarPath, [string]$Port, [string]$ServiceName)
    $logFile = Join-Path $LOG_DIR "$Name.log"

    $cmdLine = "java " +
        "-javaagent:`"$OTEL_AGENT`" " +
        "-Dotel.exporter.otlp.endpoint=http://localhost:5081 " +
        "-Dotel.exporter.otlp.protocol=grpc " +
        "-Dotel.exporter.otlp.headers=`"Authorization=Basic $authB64,organization=default`" " +
        "-Dotel.metrics.exporter=none " +
        "-Dotel.traces.exporter=otlp " +
        "-Dotel.logs.exporter=otlp " +
        "-Dotel.service.name=$ServiceName " +
        "-Dserver.port=$Port " +
        "-jar `"$JarPath`" > `"$logFile`" 2>&1"

    Write-Host "[INFO] Starting $Name on :$Port"

    # Use WMI to spawn a fully detached process
    $wmi = ([wmiclass]"\\.\root\cimv2:Win32_Process")
    $result = $wmi.Create($cmdLine, $PROJECT_ROOT, $null)
    if ($result.ReturnValue -eq 0) {
        Write-Host "  PID: $($result.ProcessId)"
    } else {
        Write-Host "  FAILED: ReturnValue=$($result.ReturnValue)"
    }
}

Start-DetachedService -Name "user-service"        -JarPath (Join-Path $JAVA_DIR "user-service\target\user-service.jar")        -Port "8081" -ServiceName "user-service"
Start-Sleep -Seconds 2
Start-DetachedService -Name "order-service"       -JarPath (Join-Path $JAVA_DIR "order-service\target\order-service.jar")      -Port "8082" -ServiceName "order-service"
Start-Sleep -Seconds 2
Start-DetachedService -Name "payment-service"     -JarPath (Join-Path $JAVA_DIR "payment-service\target\payment-service.jar")  -Port "9083" -ServiceName "payment-service"
Start-Sleep -Seconds 2
Start-DetachedService -Name "notification-service" -JarPath (Join-Path $JAVA_DIR "notification-service\target\notification-service.jar") -Port "8084" -ServiceName "notification-service"

Write-Host ""
Write-Host "All services spawned via WMI. Waiting 30s..."
Start-Sleep -Seconds 30

Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in 8081,8082,8083,8084,9083 } | Format-Table LocalPort,OwningProcess -AutoSize
