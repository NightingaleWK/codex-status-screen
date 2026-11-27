param([string]$Go)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$distribution = Join-Path $project 'dist'
$stage = Join-Path $project ('build/windows-go-' + [guid]::NewGuid().ToString('N'))
if (!$Go) {
  $Go = (Get-Command go.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
}
$Go = (Resolve-Path -LiteralPath $Go).Path
New-Item -ItemType Directory -Path $stage,$distribution -Force | Out-Null
$exe = Join-Path $stage 'CodexStatusScreen.exe'
$env:GOOS = 'windows'
$env:GOARCH = 'amd64'
$env:CGO_ENABLED = '0'
$env:GOTOOLCHAIN = 'local'
& $Go -C $project build -mod=readonly -trimpath -ldflags '-s -w -H=windowsgui' -o $exe ./collector
if ($LASTEXITCODE -ne 0) { throw 'Go collector build failed' }

$goRoot = & $Go env GOROOT
if ($LASTEXITCODE -ne 0) { throw 'Could not find the Go runtime license' }
$notices = [Collections.Generic.List[string]]::new()
$notices.Add('The Go Programming Language')
$notices.Add((Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $goRoot 'LICENSE')))
$modules = & $Go -C $project list -mod=readonly -deps -f '{{with .Module}}{{if not .Main}}{{.Path}}|{{.Version}}|{{.Dir}}{{end}}{{end}}' ./collector
if ($LASTEXITCODE -ne 0) { throw 'Could not list the bundled modules' }
foreach ($description in ($modules | Where-Object { $_ } | Sort-Object -Unique)) {
  $name, $version, $directory = $description.Split('|', 3)
  $notices.Add("$name $version")
  $texts = @(Get-ChildItem -LiteralPath $directory -File | Where-Object Name -Match '^(LICENSE|COPYING|NOTICE)([.-].*)?$')
  if (!$texts.Count) { throw "No license present for $name" }
  foreach ($license in $texts) {
    $notices.Add((Get-Content -Raw -Encoding UTF8 -LiteralPath $license.FullName))
  }
}
$notices -join ([Environment]::NewLine * 2) | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $stage 'THIRD-PARTY-NOTICES.txt')
Copy-Item -LiteralPath (Join-Path $project 'README.md') -Destination $stage
$zip = Join-Path $distribution 'CodexStatus-Windows-x64.zip'
Compress-Archive -LiteralPath @(Get-ChildItem -LiteralPath $stage -File | Select-Object -ExpandProperty FullName) -DestinationPath $zip -CompressionLevel Optimal -Force
Get-Item -LiteralPath $exe,$zip | Select-Object FullName,Length
