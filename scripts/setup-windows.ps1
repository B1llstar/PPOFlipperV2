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
function Test-GradleCompatibleJdk($javaHomePath) {
    # Reads the JAVA_VERSION out of JAVA_HOME's own "release" file - a plain, stable,
    # machine-readable text file every JDK distribution (OpenJDK/Temurin/Oracle) has shipped at
    # its root since JDK 9. Deliberately NOT parsing `java -version`'s free-text stderr output -
    # that's a real source of the "still doesn't look usable" failure this replaces: PowerShell's
    # `2>&1` merging can hand back ErrorRecord objects rather than plain strings depending on the
    # PS version/host, and different vendors/locales format that line differently. The release
    # file has none of that ambiguity.
    $releaseFile = Join-Path $javaHomePath "release"
    $javaExe = Join-Path $javaHomePath "bin\java.exe"
    Write-Host "    checking candidate JAVA_HOME: $javaHomePath"
    if (-not (Test-Path $javaExe)) {
        Write-Host "        -> no bin\java.exe found here, skipping"
        return $false
    }
    if (-not (Test-Path $releaseFile)) {
        Write-Host "        -> no 'release' file found here (pre-JDK-9 layout?), skipping"
        return $false
    }
    $releaseContent = Get-Content -Path $releaseFile -Raw
    if ($releaseContent -notmatch 'JAVA_VERSION="([^"]+)"') {
        Write-Host "        -> 'release' file exists but has no JAVA_VERSION line - contents:"
        Write-Host "        $releaseContent"
        return $false
    }
    $versionString = $Matches[1]
    # Version strings look like "17.0.20.1" or, pre-JEP-223 (Java 8 and earlier), "1.8.0_432" -
    # take the first numeric component, treating a leading "1." (old scheme) as meaning the NEXT
    # component is the real major version (1.8 -> 8).
    $parts = $versionString -split '[.\-+_]'
    if ($parts[0] -eq '1' -and $parts.Length -gt 1) {
        $major = [int]$parts[1]
    } else {
        $major = [int]$parts[0]
    }
    Write-Host "        -> found JAVA_VERSION=$versionString (major $major)"
    # Gradle 8.2 supports running on JDK 8-20 (the project's own COMPILE toolchain is a separate
    # concern, already auto-provisioned by build.gradle - this check is purely "can Gradle's own
    # launcher start on this JVM at all").
    $compatible = ($major -ge 8 -and $major -le 20)
    if (-not $compatible) {
        Write-Host "        -> major version $major is outside Gradle 8.2's supported launcher range (8-20)"
    }
    return $compatible
}

function Get-JdkMajorVersion($javaHomePath) {
    # Same release-file approach as Test-GradleCompatibleJdk, factored out since the client-launch
    # step below needs the exact major version number (must be 11, the RuneLite/Microbot client's
    # own requirement) rather than Test-GradleCompatibleJdk's broader "8-20, good enough for
    # Gradle's launcher" range check.
    $releaseFile = Join-Path $javaHomePath "release"
    if (-not (Test-Path $releaseFile)) { return $null }
    $releaseContent = Get-Content -Path $releaseFile -Raw
    if ($releaseContent -notmatch 'JAVA_VERSION="([^"]+)"') { return $null }
    $parts = $Matches[1] -split '[.\-+_]'
    if ($parts[0] -eq '1' -and $parts.Length -gt 1) {
        return [int]$parts[1]
    }
    return [int]$parts[0]
}

function Get-PortableJdk([int]$MajorVersion) {
    # Downloads and caches a portable Eclipse Temurin JDK of the exact requested major version
    # into %USERPROFILE%\microbot-client\jdk-<MajorVersion>, independent of whatever Gradle's own
    # toolchain support did or didn't provision - this repo's build.gradle DOES declare the
    # foojay-resolver-convention plugin (auto-download should be on), and build.gradle DOES target
    # JDK 11 specifically, but a real run still left %USERPROFILE%\.gradle\jdks completely empty
    # after a successful build - root cause not conclusively pinned down (a GRADLE_USER_HOME
    # override, an auto-download setting, or something else entirely), so rather than keep
    # debugging Gradle's own toolchain resolution on a machine this can't directly inspect, this
    # function is a self-contained fallback this script fully controls end to end.
    $CacheDir = Join-Path $env:USERPROFILE "microbot-client\jdk-$MajorVersion"
    if (Test-Path $CacheDir) {
        $existingMajor = Get-JdkMajorVersion $CacheDir
        if ($existingMajor -eq $MajorVersion) {
            Write-Step "Using previously-downloaded portable JDK $MajorVersion at $CacheDir"
            return $CacheDir
        }
        Write-Warn2 "A previous download exists at $CacheDir but reports major version $existingMajor, not $MajorVersion - removing it and re-downloading."
        Remove-Item -Recurse -Force $CacheDir -ErrorAction SilentlyContinue
    }

    Write-Step "Downloading a portable JDK $MajorVersion (Eclipse Temurin)"
    $TmpZip = Join-Path $env:TEMP "temurin$MajorVersion-windows-x64.zip"
    if (Test-Path $TmpZip) { Remove-Item -Force $TmpZip -ErrorAction SilentlyContinue }
    # Eclipse Temurin's own API resolves "latest" within a feature version - pinning only the
    # major version so this doesn't need updating by hand as patch releases come out. Confirmed
    # live (2026-09-04) for major version 17: resolves to a ~190MB
    # OpenJDK17U-jdk_x64_windows_hotspot_<version>.zip via a 307 redirect to GitHub releases,
    # whose single top-level directory is named "jdk-<version>" (e.g. "jdk-17.0.20.1+1") - the
    # same shape is expected for other major versions.
    $DownloadUrl = "https://api.adoptium.net/v3/binary/latest/$MajorVersion/ga/windows/x64/jdk/hotspot/normal/eclipse"
    Write-Host "    downloading from $DownloadUrl (this is a real JDK, 150-200MB - can take a few minutes)"
    Invoke-WebRequest -Uri $DownloadUrl -OutFile $TmpZip -UseBasicParsing
    $zipSizeMb = [math]::Round((Get-Item $TmpZip).Length / 1MB, 1)
    Write-Host "    downloaded $zipSizeMb MB"
    if ($zipSizeMb -lt 50) {
        Write-Error "Downloaded file is only $zipSizeMb MB - too small to be a real JDK zip (expected 150-200MB). The download likely failed or returned an error page instead of the archive (does JDK $MajorVersion exist as a Temurin GA release?). Check $TmpZip manually."
    }

    $ExtractTmp = Join-Path $env:TEMP "temurin$MajorVersion-extract"
    if (Test-Path $ExtractTmp) { Remove-Item -Recurse -Force $ExtractTmp }
    New-Item -ItemType Directory -Force -Path $ExtractTmp | Out-Null
    Write-Host "    extracting to $ExtractTmp"
    Expand-Archive -Path $TmpZip -DestinationPath $ExtractTmp -Force

    $ExtractedDirs = Get-ChildItem -Path $ExtractTmp -Directory
    Write-Host "    top-level entries after extraction: $($ExtractedDirs.Name -join ', ')"
    if ($ExtractedDirs.Count -eq 0) {
        Write-Error "Extracted $TmpZip but found no top-level directory inside $ExtractTmp - the zip's layout may have changed. Check it manually."
    }
    $ExtractedRoot = $ExtractedDirs | Select-Object -First 1
    Write-Host "    using extracted root: $($ExtractedRoot.FullName)"

    New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null
    # Robocopy, not Copy-Item: a JDK is thousands of small files, and Copy-Item -Recurse on
    # Windows has known cases of returning before every file handle is actually released,
    # especially over a slow/virus-scanned disk - which would make the very next
    # Get-JdkMajorVersion call race against a still-finishing copy. Robocopy blocks until
    # genuinely done. /E copies subdirectories including empty ones; /NFL /NDL /NJH /NJS quiet
    # its normally-very-verbose per-file output.
    Write-Host "    copying into $CacheDir (robocopy, this can take a minute for a full JDK)"
    robocopy $ExtractedRoot.FullName $CacheDir /E /NFL /NDL /NJH /NJS | Out-Null
    # Robocopy's exit codes are a bitmask where 0-7 all mean success (some combination of
    # "files copied"/"extra files"/"mismatched") - only 8+ is a real failure, unlike every other
    # Windows CLI tool's plain 0-success convention.
    if ($LASTEXITCODE -ge 8) {
        Write-Error "robocopy failed copying $($ExtractedRoot.FullName) to $CacheDir (exit code $LASTEXITCODE)."
    }
    Remove-Item -Recurse -Force $ExtractTmp, $TmpZip -ErrorAction SilentlyContinue

    $finalMajor = Get-JdkMajorVersion $CacheDir
    if ($finalMajor -ne $MajorVersion) {
        Write-Error "Downloaded and extracted a JDK to $CacheDir but its major version reads as $finalMajor, not the requested $MajorVersion - inspect $CacheDir\release manually."
    }
    return $CacheDir
}

function Get-CompatibleJavaHome {
    if ($env:JAVA_HOME) {
        if (Test-GradleCompatibleJdk $env:JAVA_HOME) {
            return $env:JAVA_HOME
        }
    } else {
        Write-Host "    JAVA_HOME is not set"
    }

    $systemJava = Get-Command java -ErrorAction SilentlyContinue
    if ($systemJava) {
        $systemJavaHome = Split-Path -Parent (Split-Path -Parent $systemJava.Source)
        if (Test-GradleCompatibleJdk $systemJavaHome) {
            return $systemJavaHome
        }
    } else {
        Write-Host "    no 'java' found on PATH"
    }

    Write-Step "No Gradle-compatible JDK found (need JDK 8-20 to run Gradle's own launcher)"
    return Get-PortableJdk 17
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

    Write-Step "Looking for a JDK 11 to launch the RuneLite/Microbot client with"
    # The client itself (unlike Gradle's launcher, which just needs 8-20 - see
    # Get-CompatibleJavaHome above) specifically wants JDK 11, matching launch.sh's own
    # `"$JAVA_BIN" -version 2>&1 | grep -q '"11'` check on macOS/Linux.
    #
    # Deliberately does NOT rely on Gradle's own toolchain cache (%USERPROFILE%\.gradle\jdks) even
    # though build.gradle targets JDK 11 and should provision one there via the declared
    # foojay-resolver-convention plugin - confirmed live that a real successful build still left
    # that directory completely empty (root cause not conclusively pinned down: possibly a
    # GRADLE_USER_HOME override, an auto-download setting, or something else specific to that
    # machine). Rather than keep debugging Gradle's own toolchain resolution on a machine this
    # can't directly inspect, this uses Get-PortableJdk (the same self-contained
    # download-and-cache mechanism already built for the Gradle-launcher JDK) as a fallback this
    # script fully controls end to end, independent of whatever Gradle itself did or didn't do.
    $JavaBin = $null

    $systemJava = Get-Command java -ErrorAction SilentlyContinue
    if ($systemJava) {
        $systemMajor = Get-JdkMajorVersion (Split-Path -Parent (Split-Path -Parent $systemJava.Source))
        Write-Host "    system java major version: $systemMajor"
        if ($systemMajor -eq 11) {
            $JavaBin = $systemJava.Source
        }
    }

    if (-not $JavaBin) {
        $PortableJdk11Home = Get-PortableJdk 11
        $JavaBin = Join-Path $PortableJdk11Home "bin\java.exe"
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
