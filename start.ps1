# Windows start script (start.sh is the Linux/macOS one).
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# Pick up PATH entries added since this shell opened (e.g. a newly installed hermes).
$env:Path = [Environment]::GetEnvironmentVariable('Path', 'User') + ';' + [Environment]::GetEnvironmentVariable('Path', 'Machine')
if (-not $env:HERMES_HOME -and (Test-Path config\owner.json)) {
    $h = (Get-Content config\owner.json -Raw | ConvertFrom-Json).hermes_home
    if ($h) { $env:HERMES_HOME = $h }
}
$env:PYTHONUTF8 = '1'  # server.py reads/writes files without an encoding; Windows would default to cp1252

$python = '.venv\Scripts\python.exe'
if (-not (Test-Path $python)) {
    uv venv .venv --python 3.12
}
uv pip install --python $python -r requirements.txt --quiet
New-Item -ItemType Directory -Force backups, audit | Out-Null  # same as install.sh
if (-not (Test-Path 'web\dist\index.html')) {
    npm --prefix web install
    npm --prefix web run build
}

$port = 8810
Write-Host "Agentic OS: http://127.0.0.1:$port"
& $python server.py --port $port @args
