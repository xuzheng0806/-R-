# 桌面联调：编译 core + harness，跑 SelfTest 与 Harness（需 JDK 8+）
param([string]$Jdk = "$PSScriptRoot\..\..\参考_LuaJ\tools\jdk8u512-b09\bin")
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$W = Resolve-Path "$PSScriptRoot\.."
Set-Location $W
$out = 'build\harness-classes'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null
$src = (Get-ChildItem -Recurse core\src\main\java, desktop-harness\src -Filter *.java).FullName
& "$Jdk\javac.exe" -encoding UTF-8 -nowarn -cp core\libs\luaj.jar -d $out $src
if ($LASTEXITCODE -ne 0) { exit 1 }
$cp = "$out;core\libs\luaj.jar"
& "$Jdk\java.exe" '-Dfile.encoding=UTF-8' -cp $cp com.warship.droid.harness.SelfTest app\src\main\assets
$a = $LASTEXITCODE
$pngs = (Get-ChildItem "$W\..\wb_probe*.png").FullName
& "$Jdk\java.exe" '-Dfile.encoding=UTF-8' -cp $cp com.warship.droid.harness.Harness app\src\main\assets $pngs
if ($a -ne 0 -or $LASTEXITCODE -ne 0) { exit 1 }
