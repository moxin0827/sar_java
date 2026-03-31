Param(
    [string]$ServerHost = $env:SERVER_HOST,
    [string]$ServerUser = $env:SERVER_USER,
    [string]$RemoteDir  = $env:REMOTE_DIR,
    # ---- LLM API Keys (可通过参数或环境变量传入) ----
    [string]$OpenaiApiKey    = $env:OPENAI_API_KEY,
    [string]$AnthropicApiKey = $env:ANTHROPIC_API_KEY,
    [string]$QwenApiKey      = $env:QWEN_API_KEY,
    [string]$LlmProvider     = $env:LLM_PROVIDER,
    # ---- MySQL (远端容器内使用，可选覆盖) ----
    [string]$MysqlRootPassword = $env:MYSQL_ROOT_PASSWORD,
    [string]$MysqlDatabase     = $env:MYSQL_DATABASE
)

# ---------------------------
# 0) 初始化与环境检查
# ---------------------------
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ServerHost)) { throw "SERVER_HOST not set" }
if ([string]::IsNullOrWhiteSpace($ServerUser)) { throw "SERVER_USER not set" }
if ([string]::IsNullOrWhiteSpace($RemoteDir))  { $RemoteDir = "/opt/sar-javaparser" }

# LLM / MySQL 默认值
if ([string]::IsNullOrWhiteSpace($LlmProvider))       { $LlmProvider = "OPENAI" }
if ([string]::IsNullOrWhiteSpace($MysqlRootPassword))  { $MysqlRootPassword = "sar_javaparser_2026" }
if ([string]::IsNullOrWhiteSpace($MysqlDatabase))      { $MysqlDatabase = "sarpro" }

function Require-Command([string]$cmd) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        throw "Command not found: $cmd"
    }
}
Require-Command "ssh"
Require-Command "scp"
Require-Command "tar"

$RepoRoot = (Resolve-Path "$PSScriptRoot").Path

# ---------------------------
# 1) 编译后端 Jar (Spring Boot)
# ---------------------------
Write-Host "== Build backend jar ==" -ForegroundColor Cyan
$mvnw = Join-Path $RepoRoot "mvnw.cmd"
if (-not (Test-Path $mvnw)) { throw "mvnw.cmd not found at repo root: $RepoRoot" }

& $mvnw -f (Join-Path $RepoRoot "pom.xml") clean package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

$jar = Get-ChildItem -Path (Join-Path $RepoRoot "target") -Filter "*.jar" |
        Where-Object { $_.Name -notmatch "sources|javadoc|original" } |
        Select-Object -First 1

if (-not $jar) { throw "Cannot find jar under $RepoRoot\target" }
Write-Host ("Jar found: " + $jar.FullName) -ForegroundColor Green

# ---------------------------
# 2) 准备暂存区
# ---------------------------
Write-Host "== Stage bundle ==" -ForegroundColor Cyan
$staging = Join-Path ([System.IO.Path]::GetTempPath()) ("sar_javaparser_staging_" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $staging | Out-Null

# 复制 Jar 包
Copy-Item $jar.FullName (Join-Path $staging "app.jar") -Force

# 复制 Dockerfile
$dockerfile = Join-Path $RepoRoot "Dockerfile"
if (-not (Test-Path $dockerfile)) { throw "Missing: $dockerfile" }
Copy-Item $dockerfile (Join-Path $staging "Dockerfile") -Force

# 复制 docker-compose.yml
$compose = Join-Path $RepoRoot "docker-compose.yml"
if (-not (Test-Path $compose)) { throw "Missing: $compose" }
Copy-Item $compose (Join-Path $staging "docker-compose.yml") -Force

# 复制远程部署脚本
$deployRemote = Join-Path $RepoRoot "deploy_remote.sh"
if (-not (Test-Path $deployRemote)) { throw "Missing: $deployRemote" }
Copy-Item $deployRemote (Join-Path $staging "deploy_remote.sh") -Force

# ---------------------------
# 2.1) 生成 .env 文件 (传递 API Key 与数据库配置)
# ---------------------------
Write-Host "== Generate .env ==" -ForegroundColor Cyan

$envLines = @(
    "# ===== LLM Configuration ====="
    "LLM_PROVIDER=$LlmProvider"
    ""
    "# >>> 在此处填写对应的 API Key <<<"
    "OPENAI_API_KEY=$OpenaiApiKey"
    "ANTHROPIC_API_KEY=$AnthropicApiKey"
    "QWEN_API_KEY=$QwenApiKey"
    ""
    "# ===== MySQL Configuration ====="
    "MYSQL_ROOT_PASSWORD=$MysqlRootPassword"
    "MYSQL_DATABASE=$MysqlDatabase"
)
$envLines -join "`n" | Set-Content -Path (Join-Path $staging ".env") -Encoding UTF8 -NoNewline

Write-Host ".env generated (provider=$LlmProvider)" -ForegroundColor Green

# ---------------------------
# 3) 打包 tgz
# ---------------------------
Write-Host "== Pack bundle tgz ==" -ForegroundColor Cyan
$bundleName = "sar_javaparser.tgz"
$bundle = Join-Path ([System.IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString("N") + "_" + $bundleName)

Push-Location $staging
& tar -czf $bundle .
Pop-Location

Write-Host ("Bundle created: " + $bundle) -ForegroundColor Green

# ---------------------------
# 4) 上传并触发远程部署
# ---------------------------
Write-Host "== Upload and remote deploy ==" -ForegroundColor Cyan

$remoteBundlePath = "~/$bundleName"
$sshTarget = "${ServerUser}@${ServerHost}"

# 上传压缩包
& scp -o StrictHostKeyChecking=accept-new $bundle "${sshTarget}:${remoteBundlePath}"
if ($LASTEXITCODE -ne 0) { throw "scp failed" }

# 单独上传 deploy_remote.sh 到远端部署目录，方便后续直接编辑和手动运行
$localDeployScript = Join-Path $RepoRoot "deploy_remote.sh"
$remoteScriptDir = "$RemoteDir/bin"
& ssh -o StrictHostKeyChecking=accept-new $sshTarget "mkdir -p '$remoteScriptDir'"
& scp -o StrictHostKeyChecking=accept-new $localDeployScript "${sshTarget}:${remoteScriptDir}/deploy_remote.sh"
if ($LASTEXITCODE -ne 0) { throw "scp deploy_remote.sh failed" }
& ssh -o StrictHostKeyChecking=accept-new $sshTarget "chmod +x '$remoteScriptDir/deploy_remote.sh'"
Write-Host "deploy_remote.sh uploaded to ${remoteScriptDir}/deploy_remote.sh" -ForegroundColor Green

# 远程执行: 使用刚上传的脚本
$remoteCall = "$remoteScriptDir/deploy_remote.sh '$RemoteDir' '$remoteBundlePath'"

Write-Host "Executing remote deploy..." -ForegroundColor Yellow
& ssh -o StrictHostKeyChecking=accept-new $sshTarget "bash -lc `"$remoteCall`""
if ($LASTEXITCODE -ne 0) { throw "remote deploy failed" }

# ---------------------------
# 5) 清理
# ---------------------------
Write-Host "== Cleanup ==" -ForegroundColor Cyan
Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $bundle -Force -ErrorAction SilentlyContinue

Write-Host "Successfully deployed to $ServerHost" -ForegroundColor Green
Write-Host "Open: http://${ServerHost}:8080"
