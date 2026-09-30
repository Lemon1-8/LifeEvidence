param(
    [ValidateSet('emulator-5580', 'emulator-5582')]
    [string]$Serial = 'emulator-5580',
    [string]$SdkRoot = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$OutputDirectory = '',
    [ValidateRange(10, 180)][int]$ResultTimeoutSeconds = 60
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $ProjectRoot 'artifacts/test-results/ime' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$Adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
$AppPackage = 'app.shenghuoyouju'
$ExpectedIme = 'com.osfans.trime/.ime.core.TrimeInputMethodService'
$Corpus = Get-Content -LiteralPath (Join-Path $ProjectRoot 'app/src/main/assets/corpus.json') -Raw -Encoding utf8 | ConvertFrom-Json
$CorpusTitles = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$Corpus.documents | ForEach-Object { [void]$CorpusTitles.Add($_.title) }
$Outcome = [ordered]@{ passed = $false; scope = 'real-ime-composition-commit-and-visible-results'; serial = $Serial; package = $AppPackage; inputMethod = ''; startedAt = (Get-Date).ToString('o') }

function Invoke-Device([string[]]$AdbArgs) {
    $Result = & $Adb -s $Serial @AdbArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb 命令失败：$($AdbArgs -join ' ')`n$($Result -join "`n")" }
    return ($Result -join "`n").Trim()
}

function Read-DeviceUi([string]$Name) {
    Invoke-Device @('shell', 'uiautomator', 'dump', '/sdcard/life-ime-test.xml') | Out-Null
    $LocalPath = Join-Path $OutputDirectory ($Name + '.xml')
    Invoke-Device @('pull', '/sdcard/life-ime-test.xml', $LocalPath) | Out-Null
    return [xml](Get-Content -LiteralPath $LocalPath -Raw -Encoding utf8)
}

function Save-Evidence([string]$Name) {
    $Ui = Read-DeviceUi $Name
    Invoke-Device @('shell', 'screencap', '-p', '/sdcard/life-ime-test.png') | Out-Null
    Invoke-Device @('pull', '/sdcard/life-ime-test.png', (Join-Path $OutputDirectory ($Name + '.png'))) | Out-Null
    Invoke-Device @('shell', 'dumpsys', 'input_method') | Set-Content -LiteralPath (Join-Path $OutputDirectory ($Name + '-input-method.txt')) -Encoding utf8
    return $Ui
}

function Find-AppField([xml]$Ui) {
    return $Ui.SelectSingleNode("//*[@package='$AppPackage' and @class='android.widget.EditText']")
}

function Tap-Node($Node) {
    if (-not $Node) { throw '没有找到可操作的 UI 节点。' }
    $Rect = [regex]::Match($Node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $Rect.Success) { throw "节点没有有效坐标：$($Node.OuterXml)" }
    $X = [int](([int]$Rect.Groups[1].Value + [int]$Rect.Groups[3].Value) / 2)
    $Y = [int](([int]$Rect.Groups[2].Value + [int]$Rect.Groups[4].Value) / 2)
    Invoke-Device @('shell', 'input', 'tap', "$X", "$Y") | Out-Null
}

try {
    $Boot = Invoke-Device @('shell', 'getprop', 'sys.boot_completed')
    if ($Boot -ne '1') { throw '设备尚未完成启动；脚本不会自行启动或切换模拟器。' }
    $InstalledPath = Invoke-Device @('shell', 'pm', 'path', $AppPackage)
    if (-not $InstalledPath.StartsWith('package:')) { throw '请先安装真实 App 的 x86_64 APK。' }
    $Ime = Invoke-Device @('shell', 'settings', 'get', 'secure', 'default_input_method')
    $Outcome.inputMethod = $Ime
    if ($Ime -ne $ExpectedIme) { throw "当前输入法不是已配置的同文：$Ime。脚本不会替换输入法。" }
    Invoke-Device @('shell', 'am', 'force-stop', $AppPackage) | Out-Null
    Invoke-Device @('shell', 'am', 'start', '-n', "$AppPackage/.MainActivity") | Out-Null
    $Deadline = (Get-Date).AddSeconds(45)
    do {
        $Ui = Read-DeviceUi 'ready'
        $Field = Find-AppField $Ui
        if ($Field) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $Deadline)
    if (-not $Field) { throw '启动后未找到真实 App 的搜索输入框。' }
    if ($Field.text) {
        $Clear = $Ui.SelectSingleNode("//*[@package='$AppPackage' and @content-desc='清空搜索']")
        Tap-Node $Clear
        $Ui = Read-DeviceUi 'ready'
        $Field = Find-AppField $Ui
    }
    Tap-Node $Field
    Save-Evidence '01-before-composition' | Out-Null
    Invoke-Device @('shell', 'input', 'text', 'shebao') | Out-Null
    Start-Sleep -Seconds 2
    $ComposingUi = Save-Evidence '02-composing'
    $ComposingField = Find-AppField $ComposingUi
    if (-not $ComposingField) { throw '组词阶段搜索输入框消失。' }
    $CompositionMessage = $ComposingUi.SelectSingleNode("//*[@package='$AppPackage' and contains(@text,'输入完成后，继续查找相关原文')]")
    $Progress = $ComposingUi.SelectSingleNode("//*[@package='$AppPackage' and (contains(@text,'正在理解这个问题') or contains(@text,'正在整理更多相关原文'))]")
    $Outcome.composingFieldText = [string]$ComposingField.text
    $Outcome.appCompositionMessage = [bool]$CompositionMessage
    $Outcome.semanticProgressDuringComposition = [bool]$Progress
    if ($Progress) { throw '未提交候选词时出现语义搜索进度提示。' }
    if ($CompositionMessage) { $Outcome.compositionEvidence = 'app-composing-span-message' }
    elseif ([string]::IsNullOrEmpty($ComposingField.text)) { $Outcome.compositionEvidence = 'uncommitted-preedit-retained-by-real-ime' }
    else { throw "无法确认仍在组词：输入框为 $($ComposingField.text)，没有 composition 提示。" }
    $Outcome.inferenceCallCounterAvailable = $false
    Invoke-Device @('shell', 'input', 'keyevent', '62') | Out-Null
    $CommittedUi = Save-Evidence '03-after-commit'
    $CommittedField = Find-AppField $CommittedUi
    if (-not $CommittedField -or $CommittedField.text -ne '社保') { throw "首个候选词提交后不是社保：$($CommittedField.text)；已保存真实现场，不做自动纠正。" }
    $Outcome.committedText = [string]$CommittedField.text
    $Deadline = (Get-Date).AddSeconds($ResultTimeoutSeconds)
    $Completed = $null
    $PreviousTitles = ''
    $StableTitles = $false
    do {
        $Ui = Read-DeviceUi 'result-poll'
        $Failure = $Ui.SelectSingleNode("//*[@package='$AppPackage' and contains(@text,'语义搜索暂不可用')]")
        if ($Failure) { throw $Failure.text }
        $Completed = $Ui.SelectSingleNode("//*[@package='$AppPackage' and contains(@text,'找到 ') and contains(@text,'份相关原文')]")
        # Some Compose versions draw the status line without exposing it to UI Automator.
        # Match actual corpus titles in visible result cards and require a stable repeat.
        $ResultTitles = @($Ui.SelectNodes("//*[@package='$AppPackage' and @text!='']") |
            ForEach-Object { [string]$_.text } | Where-Object { $CorpusTitles.Contains($_) } | Select-Object -Unique)
        $CurrentTitles = $ResultTitles -join "`n"
        $Pending = $Ui.SelectSingleNode("//*[@package='$AppPackage' and (contains(@text,'正在理解这个问题') or contains(@text,'正在整理更多相关原文'))]")
        $StableTitles = $ResultTitles.Count -gt 0 -and $CurrentTitles -ceq $PreviousTitles -and -not $Pending
        if (($Completed -and $ResultTitles.Count -gt 0) -or $StableTitles) { break }
        $PreviousTitles = $CurrentTitles
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $Deadline)
    if (-not $Completed -and -not $StableTitles) { throw "提交社保后，$ResultTimeoutSeconds 秒内未看到稳定的相关原文结果。" }
    $Outcome.completionLabel = if ($Completed) { [string]$Completed.text } else { '' }
    $Outcome.semanticCompletionVerified = [bool]$Completed
    $Outcome.resultEvidence = if ($Completed) { 'completion-label-and-corpus-titles' } else { 'stable-visible-corpus-titles-status-label-not-accessible' }
    $Outcome.visibleResultTitles = $ResultTitles
    $ImeState = Invoke-Device @('shell', 'dumpsys', 'input_method')
    if ($ImeState -match 'mInputShown=true') { Invoke-Device @('shell', 'input', 'keyevent', '4') | Out-Null }
    Save-Evidence '04-final-results' | Out-Null
    $Outcome.passed = $true
} catch {
    $Outcome.error = $_.Exception.Message
    try { Save-Evidence 'failure' | Out-Null } catch { $Outcome.captureError = $_.Exception.Message }
} finally {
    try {
        $AppPid = Invoke-Device @('shell', 'pidof', $AppPackage)
        if ($AppPid -match '^\d+$') {
            Invoke-Device @('logcat', '-d', '-v', 'threadtime', '--pid', $AppPid, '-t', '2000') | Set-Content -LiteralPath (Join-Path $OutputDirectory 'app-logcat.txt') -Encoding utf8
        }
    } catch { $Outcome.logCaptureError = $_.Exception.Message }
    $Outcome.finishedAt = (Get-Date).ToString('o')
    $Outcome | ConvertTo-Json -Depth 5 | Tee-Object -FilePath (Join-Path $OutputDirectory 'result.json')
}
if (-not $Outcome.passed) { throw "真实中文输入法验证失败；请查看 $OutputDirectory/result.json。" }
