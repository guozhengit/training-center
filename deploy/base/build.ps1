# ============================================================
# 构建 Training Center 基础镜像 (JDK 17 + Maven + Python + pytest)
#
# 用法 (PowerShell):
#   .\deploy\base\build.ps1            # 默认版本
#   $env:MAVEN_VERSION = "3.9.9"; .\deploy\base\build.ps1
#
# 必须在工作区根目录 (jiupai, 含 training-center/ 与 output/) 下执行
# ============================================================
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$workspaceRoot = Resolve-Path (Join-Path $scriptDir "..\..\..")
Set-Location $workspaceRoot

$imageName = if ($env:IMAGE_NAME) { $env:IMAGE_NAME } else { "training-center-base:17" }
$mavenVersion = if ($env:MAVEN_VERSION) { $env:MAVEN_VERSION } else { "3.9.9" }

Write-Host "==> 构建基础镜像: $imageName"
Write-Host "==> Maven 版本:   $mavenVersion"
Write-Host "==> 工作区根目录: $workspaceRoot"

docker build `
    -f training-center/deploy/base/Dockerfile `
    -t $imageName `
    --build-arg "MAVEN_VERSION=$mavenVersion" `
    .

Write-Host "==> 完成: docker images | Select-String training-center-base"
