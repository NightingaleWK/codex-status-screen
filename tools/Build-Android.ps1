param(
  [string]$Sdk,
  [string]$Jdk,
  [string]$Zxing
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$toolchain = Join-Path $env:LOCALAPPDATA 'CodexStatusScreen/toolchain'
if (!$Sdk) { $Sdk = Join-Path $toolchain 'android-sdk' }
if (!$Jdk) { $Jdk = Join-Path $toolchain 'jdk-21' }
if (!$Zxing) { $Zxing = Join-Path $toolchain 'zxing-core-3.5.4.jar' }
$tools = Join-Path $Sdk 'build-tools/35.0.0'
$platform = Join-Path $Sdk 'platforms/android-35/android.jar'
$android = Join-Path $project 'android'
$build = Join-Path $project ('build/android-' + [guid]::NewGuid().ToString('N'))
$distribution = Join-Path $project 'dist'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
$generated = Join-Path $build 'generated'
$res = Join-Path $build 'res'
$raw = Join-Path $res 'raw'
$output = Join-Path $distribution 'CodexStatus-Android.apk'
New-Item -ItemType Directory -Path $build,$classes,$dex,$generated,$distribution -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $android 'res') -Destination $res -Recurse
New-Item -ItemType Directory -Path $raw -Force | Out-Null

function Run-Tool([string]$program, [string[]]$arguments) {
  & $program @arguments
  if ($LASTEXITCODE -ne 0) { throw "$program exited with $LASTEXITCODE" }
}

$env:JAVA_HOME = $Jdk
$compiled = Join-Path $build 'resources.zip'
$base = Join-Path $build 'unsigned.apk'
$aligned = Join-Path $build 'aligned.apk'
$jar = Join-Path $build 'compiled.jar'
Run-Tool 'node.exe' @((Join-Path $PSScriptRoot 'generate-chime.mjs'),(Join-Path $raw 'chime.wav'))
Run-Tool 'node.exe' @((Join-Path $PSScriptRoot 'generate-chime.mjs'),(Join-Path $raw 'disconnect.wav'),'offline')
Run-Tool (Join-Path $tools 'aapt2.exe') @('compile','--dir',$res,'-o',$compiled)
Run-Tool (Join-Path $tools 'aapt2.exe') @('link','-o',$base,'-I',$platform,'--manifest',
  (Join-Path $android 'AndroidManifest.xml'),'--java',$generated,$compiled)
$sources = @(Get-ChildItem -Path (Join-Path $android 'src'), $generated -Recurse -Filter '*.java' |
  Select-Object -ExpandProperty FullName)
Run-Tool (Join-Path $Jdk 'bin/javac.exe') (@('-encoding','UTF-8','--release','8','-classpath',
  "$platform;$Zxing",'-d',$classes) + $sources)
Run-Tool (Join-Path $Jdk 'bin/jar.exe') @('--create','--file',$jar,'-C',$classes,'.')
Run-Tool (Join-Path $tools 'd8.bat') @('--min-api','26','--lib',$platform,'--output',$dex,$jar,$Zxing)
Push-Location $dex
try {
  $dexFiles = @(Get-ChildItem -Filter 'classes*.dex' | Select-Object -ExpandProperty Name)
  Run-Tool (Join-Path $tools 'aapt.exe') (@('add',$base) + $dexFiles)
} finally { Pop-Location }
Run-Tool (Join-Path $tools 'zipalign.exe') @('-f','4',$base,$aligned)

$keyDir = Join-Path $env:LOCALAPPDATA 'CodexStatusScreen/signing'
$key = Join-Path $keyDir 'codex-status.p12'
$passFile = Join-Path $keyDir 'password.txt'
New-Item -ItemType Directory -Path $keyDir -Force | Out-Null
if (!(Test-Path -LiteralPath $passFile)) {
  $random = New-Object byte[] 32
  $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
  try { $generator.GetBytes($random) } finally { $generator.Dispose() }
  [IO.File]::WriteAllText($passFile, [Convert]::ToBase64String($random))
}
$env:CODEX_STATUS_SIGN_PASS = [IO.File]::ReadAllText($passFile).Trim()
try {
  if (!(Test-Path -LiteralPath $key)) {
    Run-Tool (Join-Path $Jdk 'bin/keytool.exe') @('-genkeypair','-storetype','PKCS12','-keystore',$key,
      '-alias','codex-status','-keyalg','RSA','-keysize','2048','-validity','3650',
      '-dname','CN=Codex Status Screen Local Release','-storepass:env','CODEX_STATUS_SIGN_PASS')
  }
  Run-Tool (Join-Path $tools 'apksigner.bat') @('sign','--ks',$key,'--ks-key-alias','codex-status',
    '--ks-pass','env:CODEX_STATUS_SIGN_PASS','--out',$output,$aligned)
  Run-Tool (Join-Path $tools 'apksigner.bat') @('verify','--verbose',$output)
} finally {
  Remove-Item Env:CODEX_STATUS_SIGN_PASS -ErrorAction SilentlyContinue
}
Get-Item -LiteralPath $output | Select-Object FullName,Length
