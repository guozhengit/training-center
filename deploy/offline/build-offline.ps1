<#
.SYNOPSIS
    Docker Hub 不可达时的一键离线构建：抽取前端产物 -> 构建镜像 -> 导出归档。

.DESCRIPTION
    标准 Dockerfile 的 Stage 1 需要 node:20-alpine，在 registry-1.docker.io
    不可达时会失败。本脚本改为从已有镜像里抽取前端静态资源，再走
    deploy/offline/Dockerfile.offline 构建，最后导出两份归档：
      <workspace>/training-center-<Tag>.tar.gz       镜像
      <workspace>/training-center-src-<Tag>.tar.gz  部署脚本

    依赖：Docker Desktop（BuildKit）、系统自带 .NET（用于 zip/gzip，无需 Python）。

.PARAMETER Tag
    新镜像版本号，例如 20260915。同时决定归档文件名。

.PARAMETER FromImage
    抽取前端产物所用的已有镜像，默认 training-center:latest。

.PARAMETER SkipExtract
    跳过抽取步骤（_fe/dist 已存在时使用）。

.EXAMPLE
    .\deploy\offline\build-offline.ps1 -Tag 20260915
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Tag,

    [string]$FromImage = 'training-center:latest',

    [switch]$SkipExtract
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

# 定位工作区根: 本脚本位于 <workspace>\training-center\deploy\offline\
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoDir   = Split-Path -Parent (Split-Path -Parent $ScriptDir)
$Workspace = Split-Path -Parent $RepoDir

$FeDir   = Join-Path $Workspace '_fe'
$DistDir = Join-Path $FeDir 'dist'
$JarPath = Join-Path $FeDir 'app.jar'
$ImageArchive = Join-Path $Workspace "training-center-$Tag.tar.gz"
$SrcArchive   = Join-Path $Workspace "training-center-src-$Tag.tar.gz"

function Say($msg, $color = 'Cyan') { Write-Host "[build] $msg" -ForegroundColor $color }

# ---------------- 1. 抽取前端产物 ----------------
if ($SkipExtract -and (Test-Path (Join-Path $DistDir 'index.html'))) {
    Say "跳过抽取, 复用已有 $DistDir"
}
else {
    Say "从 $FromImage 抽取前端静态资源"
    New-Item -ItemType Directory -Force -Path $FeDir | Out-Null
    if (Test-Path $DistDir) { Remove-Item -Recurse -Force $DistDir }
    New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

    docker rm -f tc-fe-x 2>$null | Out-Null
    docker create --name tc-fe-x $FromImage true | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "无法从 $FromImage 创建容器" }
    docker cp "tc-fe-x:/app/training-web.jar" $JarPath | Out-Null
    docker rm -f tc-fe-x | Out-Null
    if (-not (Test-Path $JarPath)) { throw "抽取 jar 失败: $JarPath" }

    $prefix = 'BOOT-INF/classes/static/'
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $count = 0
        foreach ($e in $zip.Entries) {
            if (-not $e.FullName.StartsWith($prefix)) { continue }
            $rel = $e.FullName.Substring($prefix.Length)
            if ([string]::IsNullOrEmpty($rel)) { continue }
            $target = Join-Path $DistDir ($rel -replace '/', '\')
            $parent = Split-Path -Parent $target
            if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $target, $true)
            $count++
        }
    }
    finally { $zip.Dispose() }

    if (-not (Test-Path (Join-Path $DistDir 'index.html'))) { throw "抽取结果里没有 index.html, 请检查 $FromImage" }
    Say "抽取完成: $count 个文件 -> $DistDir" 'Green'
}

# ---------------- 2. 构建镜像 ----------------
Say "构建镜像 training-center:$Tag (3~5 分钟)"
$env:DOCKER_BUILDKIT = '1'
Push-Location $Workspace
try {
    docker build -f training-center/deploy/offline/Dockerfile.offline `
        --build-arg FE_DIST=_fe/dist `
        --build-arg VCS_REF=$Tag `
        -t "training-center:$Tag" . 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'docker build 失败' }
}
finally { Pop-Location }

# ---------------- 3. 导出镜像归档 ----------------
Say "导出镜像归档 (约 400MB, 压缩需 1~2 分钟)"
$tmpTar = Join-Path $FeDir 'img.tar'
docker save -o $tmpTar "training-center:$Tag"
if ($LASTEXITCODE -ne 0) { throw 'docker save 失败' }

$src = [System.IO.File]::OpenRead($tmpTar)
$dst = [System.IO.File]::Create($ImageArchive)
$gz  = New-Object System.IO.Compression.GzipStream($dst, [System.IO.Compression.CompressionLevel]::Optimal)
try { $src.CopyTo($gz, 8MB) }
finally { $gz.Dispose(); $dst.Dispose(); $src.Dispose() }
Remove-Item -Force $tmpTar

$imgHash = (Get-FileHash $ImageArchive -Algorithm SHA256).Hash.ToLower()
$imgSize = (Get-Item $ImageArchive).Length

# ---------------- 4. 打包部署脚本 ----------------
Say "打包部署脚本"
if (Test-Path $SrcArchive) { Remove-Item -Force $SrcArchive }
Push-Location $Workspace
try { tar -czf $SrcArchive training-center/deploy }
finally { Pop-Location }
$srcHash = (Get-FileHash $SrcArchive -Algorithm SHA256).Hash.ToLower()

# ---------------- 5. 摘要 ----------------
Write-Host ''
Write-Host '=== 构建完成 ===' -ForegroundColor Green
Write-Host ("镜像       training-center:{0}" -f $Tag)
Write-Host ("镜像归档   {0}  ({1:N1} MB)" -f (Split-Path $ImageArchive -Leaf), ($imgSize / 1MB))
Write-Host ("           sha256 {0}" -f $imgHash)
Write-Host ("脚本归档   {0}  ({1:N1} KB)" -f (Split-Path $SrcArchive -Leaf), ((Get-Item $SrcArchive).Length / 1KB))
Write-Host ("           sha256 {0}" -f $srcHash)
Write-Host ''
Write-Host '下一步: 更新 first-deploy.sh 与 DEPLOY-GUIDE.html 里的文件名和校验值, 然后上传 --' -ForegroundColor White
$nextCmd = '.\deploy\offline\push-to-server.ps1 -Server root@<服务器IP> -ImageArchive "' + $ImageArchive + '"'
Write-Host $nextCmd -ForegroundColor Yellow
