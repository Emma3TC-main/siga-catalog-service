param([ValidateSet('Start','Stop')][string]$Action='Start')
$ErrorActionPreference='Stop'
$catalog=Split-Path $PSScriptRoot -Parent
$identityRoot=[IO.Path]::GetFullPath((Join-Path $catalog '../siga-identity-service'))
$state=Join-Path $catalog '.local'
if ($Action -eq 'Stop') {
    if (!(Test-Path "$state/identity.pid")) { return }
    $identityPid=[int](Get-Content "$state/identity.pid")
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$identityPid"
    if (!$process) { return }
    if (!$process.CommandLine.Contains((Get-Content "$state/identity.jar"))) { throw 'Identity PID mismatch.' }
    Stop-Process -Id $identityPid
    return
}
try {
    if ((Invoke-RestMethod 'http://127.0.0.1:9081/actuator/health/readiness' -TimeoutSec 2).status -eq 'UP') {
        Write-Host 'Identity already available; process preserved.'; return
    }
} catch { }
foreach($port in @(8081,9081)) {
    $listener=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,$port)
    try { $listener.Start() } finally { $listener.Stop() }
}
# Reuse historical configuration and keys; never call SetupKeys or initialize another instance.
. "$identityRoot/scripts/Import-LocalEnvironment.ps1"
$env:DEBUG='false'
$env:PORT='8081'
$env:MANAGEMENT_PORT='9081'
$jar=Join-Path $identityRoot '.local/siga-local-runtime/target/siga-identity-service-0.0.1-SNAPSHOT.jar'
if (!(Test-Path $jar)) { throw 'Historical Identity runtime missing. Follow Identity docs/LOCAL.md; do not regenerate keys.' }
New-Item -ItemType Directory -Force $state | Out-Null
$process=Start-Process (Get-Command java).Source -ArgumentList @('-jar',('"{0}"' -f $jar)) -WorkingDirectory $identityRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$state/identity.stdout.log" -RedirectStandardError "$state/identity.stderr.log"
$process.Id | Set-Content "$state/identity.pid"
$jar | Set-Content "$state/identity.jar"
$deadline=[DateTime]::UtcNow.AddSeconds(45)
do {
    $process.Refresh()
    if ($process.HasExited) { throw 'Identity exited; inspect Catalog .local/identity logs.' }
    try { if ((Invoke-RestMethod 'http://127.0.0.1:9081/actuator/health/readiness' -TimeoutSec 2).status -eq 'UP') { Write-Host 'Historical Identity UP'; return } } catch { }
    Start-Sleep -Milliseconds 500
} while ([DateTime]::UtcNow -lt $deadline)
throw 'Identity readiness timeout; logs/process preserved.'

