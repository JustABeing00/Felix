# Builds Felix with the portable toolchain on D:. Nothing is installed on C:.
# Usage (from D:\Felix\android):
#   .\build-windows.ps1                 # unit tests + debug APK
#   .\build-windows.ps1 lintDebug       # Android Lint
param([string[]]$Tasks = @('testDebugUnitTest', 'assembleDebug'))

$tools = 'D:\felix-tooling'
$env:JAVA_HOME = "$tools\jdk17"
$env:ANDROID_HOME = "$tools\android-sdk"
$env:ANDROID_SDK_ROOT = "$tools\android-sdk"
$env:GRADLE_USER_HOME = "$tools\gradle-home"

Set-Location -LiteralPath $PSScriptRoot
& .\gradlew.bat @Tasks --console=plain
exit $LASTEXITCODE
