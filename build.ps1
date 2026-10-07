param(
    [string]$Sdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    [string]$Jdk = 'C:\Program Files\Android\Android Studio\jbr',
    [string]$Keystore = (Join-Path $env:USERPROFILE '.android\debug.keystore')
)
$ErrorActionPreference = 'Stop'
$taskSource = $PSScriptRoot
# aapt on this Windows installation cannot open paths containing Chinese characters.
$taskRoot = Join-Path ([System.IO.Path]::GetTempPath()) 'LuxenHarmonica-build'
$taskBuild = Join-Path $taskRoot 'build'
$taskTools = Join-Path $Sdk 'build-tools\36.1.0'
$taskAndroid = Join-Path $Sdk 'platforms\android-35\android.jar'
$taskJava = Join-Path $Jdk 'bin\java.exe'
$taskJavac = Join-Path $Jdk 'bin\javac.exe'
function Run-Checked([string]$TaskProgram, [string[]]$TaskArguments) {
    & $TaskProgram @TaskArguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $TaskProgram" }
}
foreach ($taskDirectory in @($taskBuild, "$taskBuild\classes", "$taskBuild\gen", "$taskBuild\dex", "$taskRoot\assets", "$taskRoot\res", "$taskRoot\src")) {
    New-Item -ItemType Directory -Path $taskDirectory -Force | Out-Null
}
Copy-Item -LiteralPath "$taskSource\AndroidManifest.xml" -Destination "$taskRoot\AndroidManifest.xml" -Force
foreach ($taskFolder in @('assets','res','src')) { Copy-Item -Path "$taskSource\$taskFolder\*" -Destination "$taskRoot\$taskFolder" -Recurse -Force }
Run-Checked "$taskTools\aapt.exe" @('package','-f','-M',"$taskRoot\AndroidManifest.xml",'-S',"$taskRoot\res",'-A',"$taskRoot\assets",'-I',$taskAndroid,'-F',"$taskBuild\unsigned.apk",'-J',"$taskBuild\gen")
$taskSources = @(Get-ChildItem -LiteralPath "$taskRoot\src" -Recurse -Filter '*.java' | ForEach-Object FullName)
Run-Checked $taskJavac (@('-encoding','UTF-8','-source','8','-target','8','-classpath',$taskAndroid,'-d',"$taskBuild\classes") + $taskSources)
$taskClasses = @(Get-ChildItem -LiteralPath "$taskBuild\classes" -Recurse -Filter '*.class' | ForEach-Object FullName)
Run-Checked $taskJava (@('-cp',"$taskTools\lib\d8.jar",'com.android.tools.r8.D8','--lib',$taskAndroid,'--min-api','30','--output',"$taskBuild\dex") + $taskClasses)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskZip = [System.IO.Compression.ZipFile]::Open("$taskBuild\unsigned.apk",[System.IO.Compression.ZipArchiveMode]::Update)
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskZip,"$taskBuild\dex\classes.dex",'classes.dex') | Out-Null
} finally { $taskZip.Dispose() }
Run-Checked "$taskTools\zipalign.exe" @('-f','4',"$taskBuild\unsigned.apk","$taskBuild\aligned.apk")
Run-Checked $taskJava @('-jar',"$taskTools\lib\apksigner.jar",'sign','--ks',$Keystore,'--ks-pass','pass:android','--key-pass','pass:android','--out',"$taskBuild\harmonica-player.apk","$taskBuild\aligned.apk")
Run-Checked $taskJava @('-jar',"$taskTools\lib\apksigner.jar",'verify',"$taskBuild\harmonica-player.apk")
New-Item -ItemType Directory -Path "$taskSource\build" -Force | Out-Null
Copy-Item -LiteralPath "$taskBuild\harmonica-player.apk" -Destination "$taskSource\build\harmonica-player.apk" -Force
Write-Output "APK: $taskSource\build\harmonica-player.apk"
