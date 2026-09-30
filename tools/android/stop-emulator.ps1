param(
    [ValidateSet('LifeEvidence_API35', 'LifeEvidence_API35_16KB')]
    [string]$Name = 'LifeEvidence_API35',
    [string]$SdkRoot = "$env:LOCALAPPDATA/Android/Sdk",
    [ValidateRange(1, 180)][int]$StopTimeoutSeconds = 60
)
$ErrorActionPreference = 'Stop'
$Adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
$Port = if ($Name.EndsWith('_16KB')) { 5582 } else { 5580 }
$Serial = "emulator-$Port"
$Running = & $Adb devices
if ($LASTEXITCODE -ne 0) { throw '无法读取 adb 设备列表。' }
if (-not ($Running -match "^$Serial\s")) { Write-Output "$Name 当前未在专用端口运行。"; exit 0 }
$NameOutput = @(& $Adb -s $Serial emu avd name 2>&1)
$ActualName = if ($LASTEXITCODE -eq 0) { ([string]($NameOutput | Select-Object -First 1)).Trim() } else { '' }
if (-not $ActualName) {
    $Running = & $Adb devices
    if ($LASTEXITCODE -ne 0) { throw '无法重新读取 adb 设备列表。' }
    if (-not ($Running -match "^$Serial\s")) { Write-Output "$Name 已退出。"; exit 0 }
    throw "$Serial 仍在 adb 列表中，但无法核对 AVD 名称；不会发送停止命令。"
}
if ($ActualName -ne $Name) { throw "$Serial 属于 $ActualName，不会停止其他 AVD。" }
& $Adb -s $Serial emu kill
if ($LASTEXITCODE -ne 0) { throw "停止 $Name 失败。" }
$Deadline = (Get-Date).AddSeconds($StopTimeoutSeconds)
do {
    $Running = & $Adb devices
    if ($LASTEXITCODE -ne 0) { throw '停止后无法读取 adb 设备列表，尚不能确认设备已退出。' }
    if (-not ($Running -match "^$Serial\s")) { Write-Output "$Name 已退出，$Serial 已从 adb 列表消失。"; exit 0 }
    Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $Deadline)
throw "已向 $Name 发送停止命令，但 $Serial 在 $StopTimeoutSeconds 秒后仍未从 adb 列表消失；请勿立即启动另一台专用模拟器。"
