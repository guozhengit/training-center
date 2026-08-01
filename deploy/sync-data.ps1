<#
====================================================================
Training Center 数据同步脚本 (Windows 本机 → Linux 服务器)

用途:
  output/coding-ai-exam 与 output/interview 不在 git 仓库内,
  而是构建应用镜像时烘焙进镜像。本地改动(尤其是 `training import`
  导入的题目内容)必须同步到服务器后才能生效。

用法 (在本地开发机 PowerShell 执行):
  .\deploy\sync-data.ps1 -Server user@1.2.3.4
  .\deploy\sync-data.ps1 -Server user@1.2.3.4 -Port 22022 -RemoteBase /home/docker

依赖:
  - OpenSSH 客户端 (Win10/11 自带 ssh/scp; 旧系统可装 Git Bash 的 ssh/scp)
  - 服务器已配置公钥登录 (ssh user@host 免密可连)

同步完成后在服务器上执行重建:
  ./training-center/deploy/linux/update.sh
====================================================================
#>
param(
    [Parameter(Mandatory = $true, HelpMessage = "服务器地址, 如 user@1.2.3.4")]
    [string]$Server,

    [int]$Port = 22,

    [string]$RemoteBase = "/home/docker"
)

$ErrorActionPreference = "Stop"

# 工作区根目录: <repo>/deploy -> <repo> -> jiupainews 根目录
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$outDir   = Join-Path $repoRoot "output"

if (-not (Test-Path $outDir)) {
    throw "未找到工作区 output 目录: $outDir"
}

$dirs = @("coding-ai-exam", "interview")

foreach ($name in $dirs) {
    $src = Join-Path $outDir $name
    if (-not (Test-Path $src)) {
        Write-Host "[skip] 本地缺少目录: $src" -ForegroundColor Yellow
        continue
    }

    Write-Host "==> mkdir -p $RemoteBase/output/$name" -ForegroundColor Cyan
    & ssh -p $Port $Server "mkdir -p '$RemoteBase/output/$name'"
    if ($LASTEXITCODE -ne 0) { throw "ssh 失败, 请检查服务器地址与公钥登录" }

    Write-Host "==> 同步 $name ($((Get-ChildItem $src -Recurse -File).Count) 个文件) ..." -ForegroundColor Cyan
    & scp -r -P $Port -o StrictHostKeyChecking=accept-new "$src" "${Server}:$RemoteBase/output/"
    if ($LASTEXITCODE -ne 0) { throw "scp 同步 $name 失败" }
}

Write-Host ""
Write-Host "同步完成。下一步 (在服务器上):" -ForegroundColor Green
Write-Host "  ./training-center/deploy/linux/update.sh" -ForegroundColor Green
Write-Host "如同时修改了 config/ (如导入索引), 先 git pull 再 update:" -ForegroundColor Green
Write-Host "  cd training-center && git pull && ./deploy/linux/update.sh" -ForegroundColor Green
