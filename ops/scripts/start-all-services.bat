@echo off
REM SkyOwl 4 services startup (Windows)
REM Each service starts in a separate window via 'start /B' so they survive parent shell exit.

setlocal

set "PROJECT_ROOT=%~dp0..\.."
set "JAVA_DIR=%PROJECT_ROOT%\java"
set "OTEL_AGENT=%PROJECT_ROOT%\agent\opentelemetry-javaagent.jar"
set "LOG_DIR=%PROJECT_ROOT%\ops\logs"

if not exist "%OTEL_AGENT%" (
    echo [ERROR] OTel agent not found: %OTEL_AGENT%
    exit /b 1
)

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

set "OTEL_ENDPOINT=http://localhost:5081"
set "OO_USER=admin@example.com"
set "OO_PASS=Admin@123456"

REM Base64 encode (avoid newline) via certutil
echo %OO_USER%:%OO_PASS% > "%TEMP%\oo_auth.txt"
certutil -encode "%TEMP%\oo_auth.txt" "%TEMP%\oo_auth.b64" >nul
for /f "tokens=* delims= " %%a in ('type "%TEMP%\oo_auth.b64" ^| findstr /v CERTIFICATE ^| findstr /v END') do set "OO_AUTH=%%a"
del "%TEMP%\oo_auth.txt" "%TEMP%\oo_auth.b64" 2>nul

set "JAVA_OPTS_BASE=-javaagent:\"%OTEL_AGENT%\" -Dotel.exporter.otlp.endpoint=%OTEL_ENDPOINT% -Dotel.exporter.otlp.protocol=grpc -Dotel.exporter.otlp.headers=Authorization=Basic%%20%OO_AUTH%,organization=default -Dotel.metrics.exporter=none -Dotel.traces.exporter=otlp -Dotel.logs.exporter=otlp"

set "JAVA_BIN=java"

echo ==========================================
echo   SkyOwl Microservices (Windows)
echo ==========================================
echo   OTel Endpoint: %OTEL_ENDPOINT%
echo   Log Dir:       %LOG_DIR%
echo ==========================================

REM Use start /B to launch detached in background, redirect stdout/stderr to log
start /B "user-service" cmd /c "%JAVA_BIN% %JAVA_OPTS_BASE% -Dotel.service.name=user-service -Dserver.port=8081 -jar \"%JAVA_DIR%\user-service\target\user-service.jar\" > \"%LOG_DIR%\user-service.log\" 2>&1"
echo [INFO] user-service launched on :8081
timeout /t 1 /nobreak >nul

start /B "order-service" cmd /c "%JAVA_BIN% %JAVA_OPTS_BASE% -Dotel.service.name=order-service -Dserver.port=8082 -jar \"%JAVA_DIR%\order-service\target\order-service.jar\" > \"%LOG_DIR%\order-service.log\" 2>&1"
echo [INFO] order-service launched on :8082
timeout /t 1 /nobreak >nul

start /B "payment-service" cmd /c "%JAVA_BIN% %JAVA_OPTS_BASE% -Dotel.service.name=payment-service -Dserver.port=9083 -jar \"%JAVA_DIR%\payment-service\target\payment-service.jar\" > \"%LOG_DIR%\payment-service.log\" 2>&1"
echo [INFO] payment-service launched on :9083
timeout /t 1 /nobreak >nul

start /B "notification-service" cmd /c "%JAVA_BIN% %JAVA_OPTS_BASE% -Dotel.service.name=notification-service -Dserver.port=8084 -jar \"%JAVA_DIR%\notification-service\target\notification-service.jar\" > \"%LOG_DIR%\notification-service.log\" 2>&1"
echo [INFO] notification-service launched on :8084

echo.
echo ==========================================
echo   All services launched. Wait ~30s.
echo ==========================================
echo.
echo Endpoints:
echo   user-service:        http://localhost:8081
echo   order-service:       http://localhost:8082
echo   payment-service:     http://localhost:9083
echo   notification-service: http://localhost:8084
echo   OpenObserve:         http://localhost:5080
echo.
endlocal
