param(
    [ValidateSet('LifeEvidence_API35', 'LifeEvidence_API35_16KB')]
    [string]$Name = 'LifeEvidence_API35',
    [string]$SdkRoot = "$env:LOCALAPPDATA/Android/Sdk",
    [ValidateRange(30, 600)][int]$BootTimeoutSeconds = 240,
    [switch]$ShowWindow
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$LogRoot = Join-Path $ProjectRoot 'artifacts/test-results/environment'
New-Item -ItemType Directory -Path $LogRoot -Force | Out-Null
$Adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
$Emulator = Join-Path $SdkRoot 'emulator/emulator.exe'
$Port = if ($Name.EndsWith('_16KB')) { 5582 } else { 5580 }
$Serial = "emulator-$Port"
$ExpectedPageSize = if ($Name.EndsWith('_16KB')) { '16384' } else { '4096' }
$Running = & $Adb devices
if ($LASTEXITCODE -ne 0) { throw '无法读取 adb 设备列表。' }
$AlreadyRunning = $false
foreach ($Line in $Running) {
    if ($Line -match '^(emulator-\d+)\s+(\S+)$') {
        $OtherSerial = $Matches[1]
        $DeviceState = $Matches[2]
        if ($DeviceState -ne 'device' -and $OtherSerial -notin @('emulator-5580', 'emulator-5582')) { continue }
        $NameOutput = @(& $Adb -s $OtherSerial emu avd name 2>&1)
        $AvdName = if ($LASTEXITCODE -eq 0) { ([string]($NameOutput | Select-Object -First 1)).Trim() } else { '' }
        if (-not $AvdName) {
            # The previous list can outlive a shutting-down emulator's console.
            $FreshDevices = & $Adb devices
            if ($LASTEXITCODE -ne 0) { throw '无法重新读取 adb 设备列表。' }
            if (-not ($FreshDevices -match "^$OtherSerial\s")) { continue }
            throw "$OtherSerial 仍在 adb 列表中，但无法读取 AVD 名称（状态 $DeviceState）；请等待其退出或检查设备，不会启动另一个专用实例。"
        }
        if ($AvdName -in @('LifeEvidence_API35', 'LifeEvidence_API35_16KB')) {
            if ($AvdName -ne $Name) { throw "$AvdName 正在运行；先用 stop-emulator.ps1 停止，两个专用实例顺序验证。" }
            if ($OtherSerial -ne $Serial) { throw "$Name 已运行在 $OtherSerial，请先关闭后使用专用端口启动。" }
            $AlreadyRunning = $true
        } elseif ($OtherSerial -eq $Serial) { throw "$Serial 已被 $AvdName 占用；不会停止或改动其他 AVD。" }
    }
}
if (-not $AlreadyRunning) {
    $Running = & $Adb devices
    if ($LASTEXITCODE -ne 0) { throw '无法重新读取 adb 设备列表。' }
    if ($Running -match "^$Serial\s") { throw "$Serial 已被离线设备占用，请先检查 adb devices。" }
    $Arguments = @('-avd', $Name, '-port', "$Port", '-no-snapshot', '-no-boot-anim', '-gpu', 'software', '-cores', '4', '-memory', '4096', '-no-audio')
    if (-not $ShowWindow) { $Arguments += '-no-window' }
    $WindowStyle = if ($ShowWindow) { 'Normal' } else { 'Hidden' }
    $Process = Start-Process -FilePath $Emulator -ArgumentList $Arguments -WindowStyle $WindowStyle -PassThru -RedirectStandardOutput (Join-Path $LogRoot ($Name + '-stdout.log')) -RedirectStandardError (Join-Path $LogRoot ($Name + '-stderr.log'))
    Write-Output "启动 $Name，进程 $($Process.Id)，设备 $Serial。"
}
$Deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
do {
    $Boot = (& $Adb -s $Serial shell getprop sys.boot_completed 2>$null) -join ''
    if ($Boot.Trim() -eq '1') { break }
    if (-not $AlreadyRunning -and $Process.HasExited) { throw "模拟器启动进程已退出，请查看 $LogRoot。" }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $Deadline)
if ($Boot.Trim() -ne '1') { throw "$Name 未在 $BootTimeoutSeconds 秒内启动，请查看 $LogRoot。" }
$Api = ((& $Adb -s $Serial shell getprop ro.build.version.sdk) -join '').Trim()
$Abi = ((& $Adb -s $Serial shell getprop ro.product.cpu.abi) -join '').Trim()
$PageSize = ((& $Adb -s $Serial shell getconf PAGE_SIZE) -join '').Trim()
$CpuCores = ((& $Adb -s $Serial shell getconf _NPROCESSORS_ONLN) -join '').Trim()
$MemoryInfo = (& $Adb -s $Serial shell cat /proc/meminfo) -join "`n"
$MemoryKb = [regex]::Match($MemoryInfo, '(?m)^MemTotal:\s+(\d+)').Groups[1].Value
$Result = [ordered]@{ avd = $Name; serial = $Serial; api = $Api; abi = $Abi; pageSize = [int]$PageSize; cpuCores = [int]$CpuCores; ramConfiguredMb = 4096; memTotalKb = [long]$MemoryKb; checkedAt = (Get-Date).ToString('o') }
$Result | ConvertTo-Json | Tee-Object -FilePath (Join-Path $LogRoot ($Name + '-boot.json'))
if ($Api -ne '35' -or $Abi -ne 'x86_64' -or $PageSize -ne $ExpectedPageSize -or $CpuCores -ne '4') { throw "设备参数与预期不符：API=$Api ABI=$Abi PAGE_SIZE=$PageSize CPU=$CpuCores" }
Write-Output "验证通过；adb -s $Serial 可用于安装 APK。"
