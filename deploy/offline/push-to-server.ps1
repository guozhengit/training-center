<#
.SYNOPSIS
    把本地构建好的镜像归档 + 源码归档 + 一键部署脚本传到服务器, 并可选直接触发部署。

.DESCRIPTION
    配合 deploy/offline/first-deploy.sh 使用。
    服务器只做 docker load + 启动, 不装 JDK/Maven/Node/Python, 也不访问 GitHub。

.PARAMETER Server
    服务器登录串, 例如 root@1.2.3.4

.PARAMETER Port
    SSH 端口, 默认 22

.PARAMETER RemoteDir
    服务器接收目录, 默认 /home/docker

.PARAMETER UploadOnly
    只上传, 不触发远端部署 (之后自己 ssh 上去跑)

.EXAMPLE
    .\deploy\offline\push-to-server.ps1 -Server root@1.2.3.4

.EXAMPLE
    .\deploy\offline\push-to-server.ps1 -Server root@1.2.3.4 -Port 2222 -UploadOnly
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Server,

    [int]$Port = 22,

    [string]$RemoteDir = '/home/docker',

    # 镜像归档; 不传则自动选取工作区根目录下最新的 training-center-<日期>.tar.gz
    [string]$ImageArchive = '',

    # 源码归档; 不传则自动选取最新的 training-center-src-<日期>.tar.gz
    [string]$SrcArchive = '',

    # 服务器对外端口, 仅用于结束时打印访问地址
    [int]$TrainingPort = 9091,

    [switch]$UploadOnly
)

$ErrorActionPreference = 'Stop'

# 定位工作区根: 本脚本位于 <workspace>\training-center\deploy\offline\
$ScriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoDir    = Split-Path -Parent (Split-Path -Parent $ScriptDir)
$Workspace  = Split-Path -Parent $RepoDir

function Resolve-Latest {
    param([string]$Pattern, [string]$Fallback)
    if ($Fallback) { return $Fallback }
    $hit = Get-ChildItem -Path $Workspace -Filter $Pattern -File -ErrorAction SilentlyContinue |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $hit) { throw "工作区未找到匹配的归档: $Pattern (可用 -ImageArchive / -SrcArchive 指定)" }
    return $hit.FullName
}

$ImageArchive = Resolve-Latest -Pattern 'training-center-2*.tar.gz'   -Fallback $ImageArchive
$SrcArchive   = Resolve-Latest -Pattern 'training-center-src-*.tar.gz' -Fallback $SrcArchive
$DeployScript = Join-Path $ScriptDir  'first-deploy.sh'

Write-Host ''
Write-Host '=== 待上传产物 ===' -ForegroundColor Cyan
foreach ($f in @($ImageArchive, $SrcArchive, $DeployScript)) {
    if (Test-Path $f) {
        $size = '{0:N1} MB' -f ((Get-Item $f).Length / 1MB)
        Write-Host ('  [OK] {0}  ({1})' -f (Split-Path $f -Leaf), $size) -ForegroundColor Green
    }
    else {
        Write-Host ('  [缺失] {0}' -f $f) -ForegroundColor Red
        throw "缺少文件: $f"
    }
}
Write-Host ''

# ---------------- 1. 确保远端目录存在 + 归档完整 ----------------
Write-Host '=== 远端预检 ===' -ForegroundColor Cyan
ssh -p $Port $Server "mkdir -p $RemoteDir && df -h $RemoteDir | tail -1"
if ($LASTEXITCODE -ne 0) { throw 'SSH 连接失败, 请确认地址/端口/密钥' }
Write-Host ''

# ---------------- 2. 上传 ----------------
Write-Host '=== 上传 (360MB, 视带宽数分钟) ===' -ForegroundColor Cyan
scp -P $Port $DeployScript "$Server`:$RemoteDir/first-deploy.sh"
if ($LASTEXITCODE -ne 0) { throw '上传 first-deploy.sh 失败' }

scp -P $Port $SrcArchive "$Server`:$RemoteDir/"
if ($LASTEXITCODE -ne 0) { throw '上传源码归档失败' }

scp -P $Port $ImageArchive "$Server`:$RemoteDir/"
if ($LASTEXITCODE -ne 0) { throw '上传镜像归档失败' }

Write-Host '上传完成' -ForegroundColor Green
Write-Host ''

if ($UploadOnly) {
    Write-Host '=== 已按 -UploadOnly 跳过部署, 手动执行: ===' -ForegroundColor Yellow
    Write-Host "  ssh -p $Port $Server"
    Write-Host "  bash $RemoteDir/first-deploy.sh"
    return
}

# ---------------- 3. 触发远端部署 ----------------
Write-Host '=== 触发远端一键部署 ===' -ForegroundColor Cyan
ssh -p $Port -t $Server "cd $RemoteDir && bash first-deploy.sh"

Write-Host ''
Write-Host ('访问: http://<服务器IP>:9090/') -ForegroundColor Green
Write-Host '如需查看日志: ssh 上去执行 docker logs --tail 100 training-center' -ForegroundColor Gray
