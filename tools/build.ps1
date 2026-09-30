param([string[]]$Tasks = @('assembleRelease'), [switch]$Offline)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$jdkPath = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot'
if (Test-Path -LiteralPath $jdkPath) { $env:JAVA_HOME = $jdkPath; $env:PATH = "$jdkPath\bin;$env:PATH" }
$sdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
if (!(Test-Path -LiteralPath (Join-Path $projectRoot 'local.properties'))) {
    [IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), ('sdk.dir=' + $sdkPath.Replace('\','/') + "`n"))
}
$signPath = Join-Path $projectRoot '.signing'
if (!(Test-Path -LiteralPath (Join-Path $signPath 'life-evidence.jks'))) {
    New-Item -ItemType Directory -Path $signPath -Force | Out-Null
    $passwordBytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($passwordBytes)
    $rng.Dispose()
    $password = [Convert]::ToBase64String($passwordBytes)
    $env:LIFE_SIGN_PASSWORD = $password
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore (Join-Path $signPath 'life-evidence.jks') -storepass:env LIFE_SIGN_PASSWORD -keypass:env LIFE_SIGN_PASSWORD -alias life-evidence -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Life Evidence Personal, O=Personal, C=CN' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed' }
    [IO.File]::WriteAllText((Join-Path $signPath 'signing.properties'), "storePassword=$password`nkeyPassword=$password`n")
    Remove-Item Env:LIFE_SIGN_PASSWORD
}
Push-Location $projectRoot
try {
    $gradleArgs = @('--console=plain', '--no-daemon') + $Tasks
    if ($Offline) { $gradleArgs += '--offline' }
    & (Join-Path $projectRoot 'gradlew.bat') @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally { Pop-Location }
