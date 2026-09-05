<#
.SYNOPSIS
    Brings up (live-tails) one of PPOFlipperStar's or nmz-debug's log files, or RuneLite's own
    shared client.log. Windows equivalent of scripts/show-logs.sh - same options, same file
    locations under %USERPROFILE%\.runelite (RuneLite uses that same directory layout on every
    platform).

.PARAMETER Log
    Which log to tail: "ppo" (PPOFlipperStar decide log), "nmz" (nmz-debug disconnect log), or
    "client" (RuneLite's shared client.log, everything from every plugin). Omit for an
    interactive menu.

.EXAMPLE
    .\scripts\show-logs.ps1 ppo

.EXAMPLE
    .\scripts\show-logs.ps1
    (prompts interactively)
#>

[CmdletBinding()]
param(
    [ValidateSet("ppo", "ppoflipperstar", "flipper", "nmz", "nmzdebug", "client", "runelite")]
    [string]$Log
)

$ErrorActionPreference = "Stop"

$RuneLiteDir = if ($env:RUNELITE_DIR) { $env:RUNELITE_DIR } else { Join-Path $env:USERPROFILE ".runelite" }
$PpoLog = Join-Path $RuneLiteDir "ppoflipperstar-decide.log"
$NmzLog = Join-Path $RuneLiteDir "nmzdebug-disconnect.log"
$ClientLog = Join-Path $RuneLiteDir "logs\client.log"

function Show-TailedLog([string]$Path, [string]$Label) {
    if (-not (Test-Path $Path)) {
        Write-Host "==> $Label not found yet at $Path"
        Write-Host "    (it's created the first time the plugin actually logs something - e.g. after Execute is clicked, or a decide tick/disconnect happens)"
        exit 1
    }
    Write-Host "==> Tailing $Label ($Path) - Ctrl+C to stop"
    Write-Host ""
    Get-Content -Path $Path -Tail 40 -Wait
}

function Invoke-Choice([string]$Choice) {
    switch ($Choice) {
        { $_ -in "ppo", "ppoflipperstar", "flipper" } { Show-TailedLog $PpoLog "PPOFlipperStar decide log" }
        { $_ -in "nmz", "nmzdebug" } { Show-TailedLog $NmzLog "nmz-debug disconnect log" }
        { $_ -in "client", "runelite" } { Show-TailedLog $ClientLog "RuneLite client.log (all plugins)" }
        default { Write-Error "Unknown log '$Choice'. Valid options: ppo, nmz, client" }
    }
}

if ($Log) {
    Invoke-Choice $Log
    return
}

Write-Host "Which log do you want to tail?"
Write-Host "  1) PPOFlipperStar decide log  ($PpoLog)"
Write-Host "  2) nmz-debug disconnect log   ($NmzLog)"
Write-Host "  3) RuneLite client.log        ($ClientLog, everything - all plugins)"
$choice = Read-Host "Enter 1, 2, or 3"

switch ($choice) {
    "1" { Invoke-Choice "ppo" }
    "2" { Invoke-Choice "nmz" }
    "3" { Invoke-Choice "client" }
    default { Write-Error "Not a valid choice." }
}
