<#
.SYNOPSIS
    Chinar Voice Assistant — Bootstrap and Management Script (Windows)

.DESCRIPTION
    Manages the Chinar voice assistant lifecycle on Windows.

.PARAMETER Action
    The action to perform: check, start, stop, status, logs, install, doctor

.EXAMPLE
    .\chinar-daemon.ps1 check
    .\chinar-daemon.ps1 start
    .\chinar-daemon.ps1 stop
#>

param(
    [Parameter(Position=0)]
    [ValidateSet("check", "start", "stop", "status", "logs", "install", "doctor", "help")]
    [string]$Action = "help"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$PidFile = Join-Path $ProjectRoot "data\chinar.pid"
$LogFile = Join-Path $ProjectRoot "logs\chinar.log"
$VenvPath = Join-Path $ProjectRoot ".venv"
$PythonExe = "python"

# --- Helpers ---

function Write-Status($icon, $message) {
    Write-Host "$icon $message"
}

function Find-Python {
    # Try venv first
    $venvPython = Join-Path $VenvPath "Scripts\python.exe"
    if (Test-Path $venvPython) {
        return $venvPython
    }
    # Try system python
    $sysPython = Get-Command python -ErrorAction SilentlyContinue
    if ($sysPython) {
        return $sysPython.Source
    }
    return $null
}

function Test-ChinarRunning {
    if (-not (Test-Path $PidFile)) { return $false }
    $pid = Get-Content $PidFile -ErrorAction SilentlyContinue
    if (-not $pid) { return $false }
    $proc = Get-Process -Id $pid -ErrorAction SilentlyContinue
    return ($null -ne $proc)
}

# --- Actions ---

function Invoke-Check {
    Write-Status "🔍" "Checking Chinar prerequisites..."
    Write-Host ""

    # Python
    $py = Find-Python
    if ($py) {
        $ver = & $py --version 2>&1
        Write-Status "✅" "Python: $ver ($py)"
    } else {
        Write-Status "❌" "Python not found"
        return
    }

    # Virtual environment
    if (Test-Path $VenvPath) {
        Write-Status "✅" "Virtual environment: $VenvPath"
    } else {
        Write-Status "⚠️" "No virtual environment (run: $Action install)"
    }

    # Config
    $configPath = Join-Path $ProjectRoot "config\config.toml"
    if (Test-Path $configPath) {
        Write-Status "✅" "Config: $configPath"
    } else {
        Write-Status "⚠️" "Config not found — using defaults"
    }

    # STT Model
    $modelPath = Join-Path $ProjectRoot "models\stt\vosk-model-small-en-us-0.15"
    if (Test-Path $modelPath) {
        Write-Status "✅" "STT model: found"
    } else {
        Write-Status "⚠️" "STT model not found — voice input will be unavailable"
        Write-Host "      Download from: https://alphacephei.com/vosk/models"
    }

    # Ollama
    $ollama = Get-Command ollama -ErrorAction SilentlyContinue
    if ($ollama) {
        Write-Status "✅" "Ollama: available"
    } else {
        Write-Status "ℹ️" "Ollama not installed — LLM features disabled (deterministic rules still work)"
    }

    Write-Host ""
    Write-Status "✅" "Check complete"
}

function Invoke-Start {
    if (Test-ChinarRunning) {
        Write-Status "⚠️" "Chinar is already running"
        return
    }

    $py = Find-Python
    if (-not $py) {
        Write-Status "❌" "Python not found"
        return
    }

    # Ensure directories
    New-Item -ItemType Directory -Force -Path (Join-Path $ProjectRoot "logs") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $ProjectRoot "data") | Out-Null

    $mainPy = Join-Path $ProjectRoot "main.py"
    if (-not (Test-Path $mainPy)) {
        Write-Status "❌" "main.py not found at $mainPy"
        return
    }

    Write-Status "🚀" "Starting Chinar..."
    & $py $mainPy
}

function Invoke-Stop {
    if (-not (Test-ChinarRunning)) {
        Write-Status "ℹ️" "Chinar is not running"
        # Clean up stale PID file
        if (Test-Path $PidFile) { Remove-Item $PidFile -Force }
        return
    }

    $pid = Get-Content $PidFile
    Write-Status "🛑" "Stopping Chinar (PID: $pid)..."
    Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
    Write-Status "✅" "Chinar stopped"
}

function Invoke-Status {
    if (Test-ChinarRunning) {
        $pid = Get-Content $PidFile
        Write-Status "🟢" "Chinar is running (PID: $pid)"
    } else {
        Write-Status "🔴" "Chinar is not running"
    }
}

function Invoke-Logs {
    if (Test-Path $LogFile) {
        Get-Content $LogFile -Tail 50
    } else {
        Write-Status "ℹ️" "No log file found at $LogFile"
    }
}

function Invoke-Install {
    Write-Status "📦" "Installing Chinar..."

    $py = Find-Python
    if (-not $py) {
        Write-Status "❌" "Python not found — install Python 3.11+ first"
        return
    }

    # Create venv if needed
    if (-not (Test-Path $VenvPath)) {
        Write-Status "📦" "Creating virtual environment..."
        & $py -m venv $VenvPath
    }

    $venvPip = Join-Path $VenvPath "Scripts\pip.exe"
    Write-Status "📦" "Installing dependencies..."
    & $venvPip install -e ".$([char]91)dev$([char]93)" 2>&1

    # Create directories
    New-Item -ItemType Directory -Force -Path (Join-Path $ProjectRoot "logs") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $ProjectRoot "data") | Out-Null

    # Copy example config if no config exists
    $configPath = Join-Path $ProjectRoot "config\config.toml"
    $exampleConfig = Join-Path $ProjectRoot "config\config.example.toml"
    if ((-not (Test-Path $configPath)) -and (Test-Path $exampleConfig)) {
        Copy-Item $exampleConfig $configPath
        Write-Status "📄" "Created config/config.toml from example"
    }

    Write-Status "✅" "Installation complete"
    Write-Host ""
    Write-Host "Next steps:"
    Write-Host "  1. Download a Vosk model — see models/README.md"
    Write-Host "  2. Optionally install Ollama for LLM features"
    Write-Host "  3. Run: .\chinar-daemon.ps1 start"
}

function Invoke-Doctor {
    $py = Find-Python
    if (-not $py) {
        Write-Status "❌" "Python not found"
        return
    }
    $mainPy = Join-Path $ProjectRoot "main.py"
    & $py $mainPy --check
}

function Show-Help {
    Write-Host ""
    Write-Host "Chinar Voice Assistant — Management Script"
    Write-Host "==========================================="
    Write-Host ""
    Write-Host "Usage: .\chinar-daemon.ps1 <action>"
    Write-Host ""
    Write-Host "Actions:"
    Write-Host "  check    — Verify prerequisites"
    Write-Host "  start    — Start Chinar (interactive)"
    Write-Host "  stop     — Stop a running instance"
    Write-Host "  status   — Check if Chinar is running"
    Write-Host "  logs     — Show recent log entries"
    Write-Host "  install  — Set up virtual env and install dependencies"
    Write-Host "  doctor   — Run diagnostic checks"
    Write-Host "  help     — Show this help message"
    Write-Host ""
}

# --- Dispatch ---

switch ($Action) {
    "check"   { Invoke-Check }
    "start"   { Invoke-Start }
    "stop"    { Invoke-Stop }
    "status"  { Invoke-Status }
    "logs"    { Invoke-Logs }
    "install" { Invoke-Install }
    "doctor"  { Invoke-Doctor }
    "help"    { Show-Help }
}
