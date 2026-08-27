[CmdletBinding()]
param(
    [string]$DevOpsHost = "192.168.14.21",
    [string]$DevOpsUser = "devops",
    [int]$Port = 22,
    [string]$IdentityFile = "$HOME\.ssh\neuroplan_k8s",
    [string]$ArchivePath = "",
    [string]$DeployScriptPath = "",
    [string]$RemoteDirectory = "/home/devops/releases",
    [switch]$Deploy
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($ArchivePath)) {
    $ArchivePath = Join-Path -Path $PSScriptRoot -ChildPath "neuroplan-login-mvp-0.6.0-bundle.zip"
}
if ([string]::IsNullOrWhiteSpace($DeployScriptPath)) {
    $DeployScriptPath = Join-Path -Path $PSScriptRoot -ChildPath "deploy-neuroplan-release.sh"
}

if ($ArchivePath -match '[<>]') {
    throw "ArchivePath contains a placeholder character (< or >). Use the actual archive filename, for example: neuroplan-login-mvp-0.6.0-bundle.zip"
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed (exit=$LASTEXITCODE): $Command"
    }
}

foreach ($command in @("ssh.exe", "scp.exe")) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "$command was not found. Install the Windows OpenSSH Client first."
    }
}

$archive = (Resolve-Path -LiteralPath $ArchivePath).Path
$deployScript = (Resolve-Path -LiteralPath $DeployScriptPath).Path
$archiveName = Split-Path -Leaf $archive
$deployScriptName = Split-Path -Leaf $deployScript
$remoteTarget = "${DevOpsUser}@${DevOpsHost}"
$remoteArchive = "$RemoteDirectory/$archiveName"
$remoteDeployScript = "$RemoteDirectory/$deployScriptName"

$sshOptions = @("-p", "$Port")
$scpOptions = @("-P", "$Port")
if ($IdentityFile -and (Test-Path -LiteralPath $IdentityFile)) {
    $identity = (Resolve-Path -LiteralPath $IdentityFile).Path
    $sshOptions += @("-i", $identity)
    $scpOptions += @("-i", $identity)
} elseif ($IdentityFile) {
    Write-Warning "SSH key not found: $IdentityFile (password authentication will be used)"
}

$localHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()

Write-Host "===== NeuroPlan 0.6.0 transfer ====="
Write-Host "Local archive : $archive"
Write-Host "SHA256        : $localHash"
Write-Host "Remote target : ${remoteTarget}:$RemoteDirectory"

Invoke-Native -Command "ssh.exe" -Arguments ($sshOptions + @(
    $remoteTarget,
    "mkdir -p '$RemoteDirectory' && chmod 700 '$RemoteDirectory'"
))

Invoke-Native -Command "scp.exe" -Arguments ($scpOptions + @(
    $archive,
    $deployScript,
    "${remoteTarget}:$RemoteDirectory/"
))

$remoteHashLine = & ssh.exe @sshOptions $remoteTarget "sha256sum '$remoteArchive'"
if ($LASTEXITCODE -ne 0) {
    throw "Could not calculate the remote SHA256 hash."
}
$remoteHash = (($remoteHashLine | Select-Object -First 1) -split "\s+")[0].ToLowerInvariant()
if ($remoteHash -ne $localHash) {
    throw "SHA256 mismatch: local=$localHash remote=$remoteHash"
}

Write-Host "[PASS] Archive and deployment script transferred; SHA256 matches."

if ($Deploy) {
    Write-Host "===== Starting deployment on DevOps VM ====="
    Invoke-Native -Command "ssh.exe" -Arguments ($sshOptions + @(
        $remoteTarget,
        "chmod 700 '$remoteDeployScript' && '$remoteDeployScript' '$remoteArchive'"
    ))
} else {
    Write-Host ""
    Write-Host "Run deployment when ready:"
    Write-Host "ssh $remoteTarget `"chmod 700 '$remoteDeployScript' && '$remoteDeployScript' '$remoteArchive'`""
}
