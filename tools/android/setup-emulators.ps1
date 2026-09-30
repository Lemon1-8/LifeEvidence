param(
    [string]$SdkRoot = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$ProxyHost = '',
    [int]$ProxyPort = 7897,
    [switch]$SkipInstall
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$LogRoot = Join-Path $ProjectRoot 'artifacts/test-results/environment'
New-Item -ItemType Directory -Path $LogRoot -Force | Out-Null
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin/java.exe'))) {
    throw '请通过 -JavaHome 指定已安装的 JDK 17 或更高版本。'
}
$env:JAVA_HOME = $JavaHome
$env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
$SdkManager = Join-Path $SdkRoot 'cmdline-tools/latest/bin/sdkmanager.bat'
$AvdManager = Join-Path $SdkRoot 'cmdline-tools/latest/bin/avdmanager.bat'
$ProxyArgs = if ($ProxyHost) { @('--proxy=http', "--proxy_host=$ProxyHost", "--proxy_port=$ProxyPort") } else { @() }
$Devices = @(
    @{ Name = 'LifeEvidence_API35'; Package = 'system-images;android-35;google_apis;x86_64' },
    @{ Name = 'LifeEvidence_API35_16KB'; Package = 'system-images;android-35;google_apis_ps16k;x86_64' }
)
if (-not $SkipInstall) {
    & $SdkManager "--sdk_root=$SdkRoot" @ProxyArgs 'cmdline-tools;latest' 2>&1 | Tee-Object -FilePath (Join-Path $LogRoot 'sdk-tools-install.log')
    if ($LASTEXITCODE -ne 0) { throw "命令行工具安装失败：$LASTEXITCODE" }
    & $SdkManager "--sdk_root=$SdkRoot" @ProxyArgs $Devices[0].Package $Devices[1].Package 2>&1 | Tee-Object -FilePath (Join-Path $LogRoot 'sdk-images-install.log')
    if ($LASTEXITCODE -ne 0) { throw "系统镜像安装失败：$LASTEXITCODE" }
}
$AvdRoot = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $env:USERPROFILE '.android/avd' }
foreach ($Device in $Devices) {
    $ConfigPath = Join-Path $AvdRoot ($Device.Name + '.avd/config.ini')
    if (-not (Test-Path -LiteralPath $ConfigPath)) {
        'no' | & $AvdManager create avd --name $Device.Name --package $Device.Package --device 'pixel_7' 2>&1 | Tee-Object -FilePath (Join-Path $LogRoot ($Device.Name + '-create.log'))
        if ($LASTEXITCODE -ne 0) { throw "创建 $($Device.Name) 失败：$LASTEXITCODE" }
    }
    $Config = Get-Content -LiteralPath $ConfigPath -Raw
    $ExpectedImage = $Device.Package.Replace(';', '/').TrimEnd('/')
    $ImageLine = [regex]::Match($Config, '(?m)^image\.sysdir\.1=(.+)$').Groups[1].Value.Trim().Replace('\', '/').TrimEnd('/')
    if (-not $ImageLine.EndsWith($ExpectedImage)) { throw "$($Device.Name) 使用了非预期镜像：$ImageLine；请人工检查，不覆盖现有 AVD。" }
    foreach ($Pair in @(@('hw.cpu.ncore', '4'), @('hw.ramSize', '4096'), @('hw.keyboard', 'yes'))) {
        $Pattern = '(?m)^' + [regex]::Escape($Pair[0]) + '=.*$'
        $Line = $Pair[0] + '=' + $Pair[1]
        if ([regex]::IsMatch($Config, $Pattern)) { $Config = [regex]::Replace($Config, $Pattern, $Line) }
        else { $Config = $Config.TrimEnd() + "`n$Line`n" }
    }
    [IO.File]::WriteAllText($ConfigPath, $Config, [Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath $ConfigPath -Destination (Join-Path $LogRoot ($Device.Name + '-config.ini'))
    Write-Output "$($Device.Name)：4 核 / 4096 MB；$ImageLine"
}
& $SdkManager --list_installed 2>&1 | Out-File -FilePath (Join-Path $LogRoot 'sdk-installed.txt') -Encoding utf8
