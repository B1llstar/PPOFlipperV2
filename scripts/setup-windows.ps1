#Requires -Version 5.1
<#
.SYNOPSIS
    One-shot Windows setup + launch for BotStar: builds/side-loads the RuneLite
    plugins (mirrors scripts/launch.sh) and, optionally, sets up + starts the
    Python PPO inference worker.

.DESCRIPTION
    Run this from a PowerShell prompt inside a fresh clone of this repo:

        .\scripts\setup-windows.ps1

    What it does, every time, fresh (nothing here is a one-off manual step -
    same philosophy as launch.sh):
      1. Checks for git/Java on PATH (Java is optional - Gradle will
         provision its own JDK 11 toolchain if none is found).
      2. Resolves the latest MicroBot client version and downloads
         microbot-<version>.jar into %USERPROFILE%\microbot-client\ if not
         already cached.
      3. Refreshes the vendor\microbot-hub reference submodule.
      4. Runs .\gradlew.bat build -PmicrobotClientVersion=<version>.
      5. Copies every built *Plugin.jar into
         %USERPROFILE%\.runelite\sideloaded-plugins\ - the REAL sideload
         directory (NOT %USERPROFILE%\.runelite\plugins\, which is a
         different directory RuneLite silently ignores unlisted jars in -
         see README.md's launch.sh section for why).
      6. Launches the client jar.
      7. If -SetupPython is passed, also creates/updates a venv under
         data\.venv, installs data\requirements.txt into it, and (unless
         -SkipInferenceWorker is also passed) starts
         data\ppo\inference_worker.py in a new window.

.PARAMETER SetupPython
    Also set up the Python venv and dependencies for the PPO inference
    worker. Off by default since it's a separate, heavier step (PyTorch,
    etc.) you may not need every single launch.

.PARAMETER SkipInferenceWorker
    With -SetupPython, install dependencies but don't actually start the
    worker process (e.g. you're starting it yourself, or on another
    machine).

.PARAMETER SkipClient
    Skip the RuneLite client build/launch entirely - useful if you only
    want to (re)start the Python inference worker.

.NOTES
    One thing this script CANNOT do for you: the Firestore service account
    JSON (ppoflipperopus-firebase-adminsdk-fbsvc-*.json) is gitignored - a
    real credential, deliberately never committed - so it will NOT be
    present after a fresh git clone. Copy it over separately, from a
    secure channel (not email/chat/a public share), into the repo root
    before running with -SetupPython, or the inference worker will fail
    to authenticate. See data/ppo/inference_worker.py's
    DEFAULT_SERVICE_ACCOUNT_PATH if you need to point it somewhere else
    via -ServiceAccountPath.

.PARAMETER ServiceAccountPath
    Override the Firestore service account JSON path passed to the
    inference worker (--service-account-path). Defaults to whatever the
    worker itself defaults to (a ppoflipperopus-firebase-adminsdk-*.json
    file at the repo root).
#>

[CmdletBinding()]
param(
    [switch]$SetupPython,
    [switch]$SkipInferenceWorker,
    [switch]$SkipClient,
    [string]$ServiceAccountPath
)

$ErrorActionPreference = "Stop"

$RepoDir = Split-Path -Parent $PSScriptRoot
Set-Location $RepoDir

function Write-Step($msg) {
    Write-Host "==> $msg" -ForegroundColor Cyan
}
function Write-Warn2($msg) {
    Write-Host "==> $msg" -ForegroundColor Yellow
}

# Gradle 8.2 (this repo's wrapper version) cannot itself RUN under a JDK newer than ~20 - it
# fails at startup with "Unsupported class file major version <N>" before build.gradle's own
# toolchain config (which auto-provisions whatever JDK the actual COMPILE step needs) ever gets a
# chance to matter. This is a separate concern from that toolchain: a fresh Windows machine with
# a modern system JDK (25+ is common as of 2026) will hit this immediately, with nothing already
# on disk for the script to fall back to (the ~/.gradle/jdks toolchain cache only gets populated
# AFTER a successful Gradle run - a chicken-and-egg problem the first run on a new machine hits
# head-on). Confirmed the actual failure mode live: "Could not open cp_settings generic cache...
# unsupported class file major version 69" (69 = Java 25) when JAVA_HOME/PATH pointed at a
# too-new JDK.
function Get-CompatibleJavaHome {
    $CacheDir = Join-Path $env:USERPROFILE "microbot-client\jdk-17"
    $CachedJavaExe = Join-Path $CacheDir "bin\java.exe"

    function Test-GradleCompatible($javaExePath) {
        if (-not (Test-Path $javaExePath)) { return $false }
        try {
            $verOutput = & $javaExePath -version 2>&1 | Out-String
            if ($verOutput -match 'version "(\d+)') {
                $major = [int]$Matches[1]
                # Gradle 8.2 supports running on JDK 8-20 (project compile toolchain is separate,
                # handled by build.gradle - this check is purely "can Gradle's own launcher start").
                return ($major -ge 8 -and $major -le 20)
            }
        } catch { }
        return $false
    }

    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-GradleCompatible $candidate) {
            return $env:JAVA_HOME
        }
        Write-Warn2 "JAVA_HOME ($env:JAVA_HOME) is not compatible with Gradle 8.2's own launcher (too new/too old) - looking for an alternative."
    }

    $systemJava = Get-Command java -ErrorAction SilentlyContinue
    if ($systemJava -and (Test-GradleCompatible $systemJava.Source)) {
        return (Split-Path -Parent (Split-Path -Parent $systemJava.Source))
    }

    if (Test-GradleCompatible $CachedJavaExe) {
        Write-Step "Using previously-downloaded portable JDK 17 at $CacheDir"
        return $CacheDir
    }

    Write-Step "No Gradle-compatible JDK found (need JDK 8-20 to run Gradle itself) - downloading a portable JDK 17"
    $TmpZip = Join-Path $env:TEMP "temurin17-windows-x64.zip"
    # Eclipse Temurin's own API resolves "latest" within a feature version - pinning the major
    # version (17) rather than an exact patch release so this doesn't need updating by hand.
    $DownloadUrl = "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
    Invoke-WebRequest -Uri $DownloadUrl -OutFile $TmpZip -UseBasicParsing
    New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null
    $ExtractTmp = Join-Path $env:TEMP "temurin17-extract"
    if (Test-Path $ExtractTmp) { Remove-Item -Recurse -Force $ExtractTmp }
    Expand-Archive -Path $TmpZip -DestinationPath $ExtractTmp -Force
    # Adoptium's zip contains one top-level "jdk-17.x.x+y" folder - flatten it into $CacheDir so
    # the path stays stable across whatever exact patch version got resolved.
    $ExtractedRoot = Get-ChildItem -Path $ExtractTmp -Directory | Select-Object -First 1
    Copy-Item -Path (Join-Path $ExtractedRoot.FullName "*") -Destination $CacheDir -Recurse -Force
    Remove-Item -Recurse -Force $ExtractTmp, $TmpZip -ErrorAction SilentlyContinue

    if (-not (Test-GradleCompatible $CachedJavaExe)) {
        Write-Error "Downloaded a JDK to $CacheDir but it still doesn't look usable - check $CacheDir manually."
    }
    return $CacheDir
}

# ---------------------------------------------------------------------------
# 1. RuneLite client: build + sideload + launch
# ---------------------------------------------------------------------------
if (-not $SkipClient) {

    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        Write-Error "git is not on PATH. Install Git for Windows (https://git-scm.com/download/win) and re-run."
    }

    $ClientDir = if ($env:MICROBOT_CLIENT_DIR) { $env:MICROBOT_CLIENT_DIR } else { Join-Path $env:USERPROFILE "microbot-client" }
    $RuneLitePluginsDir = Join-Path $env:USERPROFILE ".runelite\sideloaded-plugins"
    $VersionEndpoint = "https://microbot.cloud/api/version/client"

    New-Item -ItemType Directory -Force -Path $ClientDir | Out-Null
    New-Item -ItemType Directory -Force -Path $RuneLitePluginsDir | Out-Null

    Write-Step "Resolving latest MicroBot client version"
    $Version = $null
    try {
        $Version = (Invoke-WebRequest -Uri $VersionEndpoint -UseBasicParsing -TimeoutSec 15).Content.Trim()
    } catch {
        Write-Warn2 "Could not reach $VersionEndpoint; checking for a cached client jar"
    }
    if ([string]::IsNullOrWhiteSpace($Version)) {
        $cached = Get-ChildItem -Path $ClientDir -Filter "microbot-*.jar" -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($cached) {
            $Version = ($cached.BaseName -replace '^microbot-', '')
            Write-Warn2 "Using cached version $Version"
        } else {
            Write-Error "No network and no cached client jar found in $ClientDir. Aborting."
        }
    }

    $ClientJar = Join-Path $ClientDir "microbot-$Version.jar"
    if (-not (Test-Path $ClientJar)) {
        Write-Step "Downloading microbot-$Version.jar"
        $TmpJar = "$ClientJar.tmp"
        Invoke-WebRequest -Uri "https://github.com/chsami/Microbot/releases/download/$Version/microbot-$Version.jar" `
            -OutFile $TmpJar -UseBasicParsing
        Move-Item -Force $TmpJar $ClientJar
    } else {
        Write-Step "microbot-$Version.jar already cached, skipping download"
    }

    Write-Step "Refreshing vendor/microbot-hub reference submodule"
    try {
        git submodule update --remote --merge vendor/microbot-hub
    } catch {
        Write-Warn2 "Could not refresh vendor/microbot-hub (offline?); using existing checkout"
    }

    Write-Step "Checking for a JDK Gradle's own launcher can run on"
    $GradleJavaHome = Get-CompatibleJavaHome
    Write-Host "    using JAVA_HOME=$GradleJavaHome for the Gradle build step"

    Write-Step "Building plugins against microbot $Version"
    $OldJavaHome = $env:JAVA_HOME
    try {
        $env:JAVA_HOME = $GradleJavaHome
        & .\gradlew.bat build "-PmicrobotClientVersion=$Version" --console=plain
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Gradle build failed (exit code $LASTEXITCODE)."
        }
    } finally {
        $env:JAVA_HOME = $OldJavaHome
    }

    Write-Step "Side-loading built plugin jars into $RuneLitePluginsDir"
    Get-ChildItem -Path (Join-Path $RepoDir "plugins") -Recurse -Filter "*Plugin.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\build\\libs\\' -and $_.Name -notmatch '-sources\.jar$' } |
        ForEach-Object {
            Copy-Item -Force $_.FullName (Join-Path $RuneLitePluginsDir $_.Name)
            Write-Host "    -> $($_.Name)"
        }

    Write-Step "Launching MicroBot client $Version"
    $JavaBin = $null
    $systemJava = Get-Command java -ErrorAction SilentlyContinue
    if ($systemJava) {
        $verOutput = & $systemJava.Source -version 2>&1 | Out-String
        if ($verOutput -match '"11') {
            $JavaBin = $systemJava.Source
        }
    }
    if (-not $JavaBin) {
        $toolchainJava = Get-ChildItem -Path (Join-Path $env:USERPROFILE ".gradle\jdks") -Recurse -Filter "java.exe" -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match "jdk-11" } | Select-Object -First 1
        if ($toolchainJava) {
            $JavaBin = $toolchainJava.FullName
        }
    }
    if (-not $JavaBin) {
        Write-Error "No JDK 11 found on PATH or in %USERPROFILE%\.gradle\jdks. Run '.\gradlew.bat build' once first to let Gradle provision one."
    }

    Write-Host "    using java: $JavaBin"
    Start-Process -FilePath $JavaBin -ArgumentList "-ea", "-Xmx2g", "-jar", "`"$ClientJar`""
}

# ---------------------------------------------------------------------------
# 2. Python PPO inference worker (optional)
# ---------------------------------------------------------------------------
if ($SetupPython) {
    Write-Step "Setting up Python environment for the PPO inference worker"

    $PythonCmd = Get-Command python -ErrorAction SilentlyContinue
    if (-not $PythonCmd) {
        $PythonCmd = Get-Command py -ErrorAction SilentlyContinue
    }
    if (-not $PythonCmd) {
        Write-Error "No 'python' or 'py' found on PATH. Install Python 3.10+ from https://www.python.org/downloads/windows/ (check 'Add python.exe to PATH' during install) and re-run."
    }

    $VenvDir = Join-Path $RepoDir "data\.venv"
    if (-not (Test-Path $VenvDir)) {
        Write-Step "Creating virtualenv at $VenvDir"
        & $PythonCmd.Source -m venv $VenvDir
    }

    $VenvPython = Join-Path $VenvDir "Scripts\python.exe"

    Write-Step "Installing data\requirements.txt (this includes PyTorch - can take a while)"
    & $VenvPython -m pip install --upgrade pip
    & $VenvPython -m pip install -r (Join-Path $RepoDir "data\requirements.txt")

    $ServiceAccountGlob = Get-ChildItem -Path $RepoDir -Filter "*firebase-adminsdk*.json" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $ServiceAccountPath -and -not $ServiceAccountGlob) {
        Write-Warn2 "No Firestore service account JSON found at the repo root (it's gitignored, so a fresh clone never has it)."
        Write-Warn2 "Copy your ppoflipperopus-firebase-adminsdk-*.json into '$RepoDir' from a secure channel before starting the worker,"
        Write-Warn2 "or pass -ServiceAccountPath <path> to point at it wherever you put it."
    }

    if (-not $SkipInferenceWorker) {
        $WorkerArgs = @("ppo\inference_worker.py")
        if ($ServiceAccountPath) {
            $WorkerArgs += @("--service-account-path", $ServiceAccountPath)
        }
        Write-Step "Starting inference_worker.py in a new window"
        Start-Process -FilePath $VenvPython -ArgumentList $WorkerArgs -WorkingDirectory (Join-Path $RepoDir "data")
    } else {
        Write-Step "Skipping inference worker start (-SkipInferenceWorker) - dependencies are installed."
    }
}

Write-Host ""
Write-Step "Done."
if (-not $SetupPython) {
    Write-Warn2 "Note: this only started the RuneLite client. The PPO model won't respond to any decision ticks"
    Write-Warn2 "until the Python inference worker is also running - re-run with -SetupPython to set that up too."
}
