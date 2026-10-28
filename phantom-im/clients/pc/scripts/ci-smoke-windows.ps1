[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("nsis", "msi")]
    [string]$Format,

    [Parameter(Mandatory = $true)]
    [string]$ArtifactRoot
)

$ErrorActionPreference = "Stop"
$process = $null
$installed = $false
$logRoot = Join-Path $env:RUNNER_TEMP "phantom-im-pc-smoke-$Format"
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$stdoutLog = Join-Path $logRoot "stdout.log"
$stderrLog = Join-Path $logRoot "stderr.log"

function Find-Installer([string]$Extension) {
    $installer = Get-ChildItem -Path $ArtifactRoot -Recurse -File -Filter "*.$Extension" |
        Select-Object -First 1
    if (-not $installer) {
        throw "No .$Extension installer found under $ArtifactRoot"
    }
    return $installer.FullName
}

function Find-InstalledExecutable() {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Phantom IM/phantom-im-pc.exe"),
        (Join-Path $env:LOCALAPPDATA "Programs/Phantom IM/phantom-im-pc.exe"),
        (Join-Path $env:ProgramFiles "Phantom IM/phantom-im-pc.exe")
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }
    throw "Installed phantom-im-pc.exe not found. Checked: $($candidates -join ', ')"
}

function Wait-ForGatewayPort([int]$ProcessId, [int]$TimeoutSeconds = 30) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $connection = Get-NetTCPConnection -OwningProcess $ProcessId -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $_.LocalAddress -in @("127.0.0.1", "::1") } |
            Select-Object -First 1
        if ($connection) {
            return [int]$connection.LocalPort
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "No loopback gateway listener appeared for PID $ProcessId"
}

function Assert-GatewayPage([int]$Port) {
    $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/" -UseBasicParsing -NoProxy -TimeoutSec 10
    if ($response.StatusCode -ne 200) {
        throw "Gateway returned HTTP $($response.StatusCode)"
    }
    if ($response.Content -notmatch "<title>Phantom IM</title>") {
        throw "Gateway page did not contain the Phantom IM title"
    }
}

function Uninstall-Installer([string]$Installer) {
    if ($Format -eq "nsis") {
        $uninstaller = Get-ChildItem -Path @(
            (Join-Path $env:LOCALAPPDATA "Phantom IM"),
            (Join-Path $env:LOCALAPPDATA "Programs/Phantom IM"),
            (Join-Path $env:ProgramFiles "Phantom IM")
        ) -Filter "uninstall.exe" -File -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($uninstaller) {
            $uninstall = Start-Process -FilePath $uninstaller.FullName -ArgumentList "/S" -Wait -PassThru
            if ($uninstall.ExitCode -ne 0) {
                throw "NSIS uninstall failed with exit code $($uninstall.ExitCode)"
            }
        }
    } else {
        $uninstall = Start-Process -FilePath "msiexec.exe" `
            -ArgumentList "/x `"$Installer`" /qn /norestart" -Wait -PassThru
        if ($uninstall.ExitCode -notin @(0, 1605, 3010)) {
            throw "MSI uninstall failed with exit code $($uninstall.ExitCode)"
        }
    }
}

$installerExtension = if ($Format -eq "nsis") { "exe" } else { "msi" }
$installer = Find-Installer $installerExtension
try {
    if ($Format -eq "nsis") {
        $install = Start-Process -FilePath $installer -ArgumentList "/S" -Wait -PassThru
    } else {
        $install = Start-Process -FilePath "msiexec.exe" `
            -ArgumentList "/i `"$installer`" /qn /norestart" -Wait -PassThru
    }
    if ($install.ExitCode -notin @(0, 3010)) {
        throw "$Format install failed with exit code $($install.ExitCode)"
    }
    $installed = $true

    $exe = Find-InstalledExecutable
    $process = Start-Process -FilePath $exe `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -PassThru
    Start-Sleep -Seconds 2
    $process.Refresh()
    if ($process.HasExited) {
        Get-Content $stdoutLog, $stderrLog -ErrorAction SilentlyContinue
        throw "Phantom IM exited during startup with code $($process.ExitCode)"
    }

    $port = Wait-ForGatewayPort $process.Id
    Assert-GatewayPage $port
    Write-Host "Phantom IM $Format smoke passed: pid=$($process.Id), gatewayPort=$port, installer=$installer"
} finally {
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        $process.WaitForExit(5000) | Out-Null
    }
    if ($installed) {
        Uninstall-Installer $installer
    }
}
