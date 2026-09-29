param([ValidateSet('Init','StartDependencies','Start','Test','Stop','StopDependencies','Status')][string]$Action = 'Status')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$infra = Join-Path $root '../siga-infrastructure'
$local = Join-Path $root '.local'
$configPath = Join-Path $local 'catalog.json'
$compose = @('compose','--project-name','siga-local','--env-file',"$infra/compose/.env.local",'-f',"$infra/compose/compose.local.yml")
function Check-Exit { if ($LASTEXITCODE -ne 0) { throw 'Command failed; preserve data and diagnose.' } }
function Sql([string]$query,[string]$database='siga') {
    $query | docker exec -i siga-local-postgres-1 psql -X -v ON_ERROR_STOP=1 -U postgres -d $database
    Check-Exit
}
function Existing-Stack {
    docker @compose config --quiet
    Check-Exit
    foreach ($name in @('siga-local-postgres-1','siga_redis','siga_rabbitmq')) {
        $data = docker inspect $name | ConvertFrom-Json
        Check-Exit
        if ($data[0].Config.Labels.'com.docker.compose.project' -ne 'siga-local') { throw 'Unexpected Compose ownership.' }
    }
}
function Import-Catalog([bool]$tests) {
    $env:DEBUG = 'false'
    if (!(Test-Path $configPath)) { throw 'Run -Action Init first.' }
    $config = Get-Content $configPath -Raw | ConvertFrom-Json
    $env:CATALOG_DB_URL = "jdbc:postgresql://127.0.0.1:$($config.dbPort)/siga"
    $env:CATALOG_DB_USER = 'siga_catalog'
    $env:CATALOG_DB_PASSWORD = $config.password
    if ($tests) {
        $env:CATALOG_DB_URL = "jdbc:postgresql://127.0.0.1:$($config.dbPort)/siga_catalog_local_test"
        $env:CATALOG_DB_USER = 'siga_catalog_test'
        $env:CATALOG_DB_PASSWORD = $config.testPassword
    }
}
Push-Location $root
try {
    switch ($Action) {
        'StartDependencies' {
            Existing-Stack
            docker @compose start postgres redis rabbitmq
            Check-Exit
            $deadline = [DateTime]::UtcNow.AddSeconds(45)
            do {
                $ErrorActionPreference = 'Continue'
                docker exec siga-local-postgres-1 pg_isready -U postgres -d siga 2>$null | Out-Null
                $pgReady = $LASTEXITCODE -eq 0
                docker exec siga_redis redis-cli ping 2>$null | Out-Null
                $redisReady = $LASTEXITCODE -eq 0
                docker exec siga_rabbitmq rabbitmq-diagnostics -q ping 2>$null | Out-Null
                $rabbitReady = $LASTEXITCODE -eq 0
                $ErrorActionPreference = 'Stop'
                if ($pgReady -and $redisReady -and $rabbitReady) { Write-Host 'PostgreSQL, Redis and RabbitMQ ready'; return }
                Start-Sleep -Milliseconds 500
            } while ([DateTime]::UtcNow -lt $deadline)
            throw 'Dependency readiness timeout; existing containers preserved.'
        }
        'Init' {
            Existing-Stack
            New-Item -ItemType Directory -Force $local | Out-Null
            if (!(Test-Path $configPath)) {
                $count = 'SELECT (SELECT count(*) FROM pg_roles WHERE rolname IN (''siga_catalog'',''siga_catalog_test'')) + (SELECT count(*) FROM pg_namespace WHERE nspname=''catalog'') + (SELECT count(*) FROM pg_database WHERE datname=''siga_catalog_local_test'');' | docker exec -i siga-local-postgres-1 psql -X -At -U postgres -d siga
                Check-Exit
                if ([int]$count -ne 0) { throw 'Catalog exists without its secrets. Recover config; do not regenerate.' }
                $port = (docker inspect siga-local-postgres-1 | ConvertFrom-Json)[0].HostConfig.PortBindings.'5432/tcp'[0].HostPort
                $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
                $bytes = New-Object byte[] 32
                $rng.GetBytes($bytes); $password = [BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()
                $rng.GetBytes($bytes); $testPassword = [BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()
                $rng.Dispose()
                @{dbPort=$port;password=$password;testPassword=$testPassword} | ConvertTo-Json | Set-Content $configPath
            }
            $config = Get-Content $configPath -Raw | ConvertFrom-Json
            if ($config.password -notmatch '^[a-f0-9]{64}$' -or $config.testPassword -notmatch '^[a-f0-9]{64}$') { throw 'Invalid stored configuration.' }
            $sql = @'
SELECT format('CREATE ROLE siga_catalog LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD %L', '__PASSWORD__') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='siga_catalog')
\gexec
SELECT format('CREATE ROLE siga_catalog_test LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD %L', '__TEST_PASSWORD__') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='siga_catalog_test')
\gexec
REVOKE CONNECT ON DATABASE siga FROM PUBLIC;
GRANT CONNECT ON DATABASE siga TO siga_iam, siga_catalog;
REVOKE ALL ON DATABASE siga FROM siga_catalog_test;
SELECT 'CREATE SCHEMA catalog AUTHORIZATION siga_catalog' WHERE NOT EXISTS (SELECT FROM pg_namespace WHERE nspname='catalog')
\gexec
REVOKE ALL ON SCHEMA catalog FROM PUBLIC;
SELECT 'CREATE DATABASE siga_catalog_local_test OWNER postgres' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='siga_catalog_local_test')
\gexec
REVOKE ALL ON DATABASE siga_catalog_local_test FROM PUBLIC;
GRANT CONNECT ON DATABASE siga_catalog_local_test TO siga_catalog_test;
'@
            Sql ($sql.Replace('__PASSWORD__',$config.password).Replace('__TEST_PASSWORD__',$config.testPassword))
            Sql "SELECT 'CREATE SCHEMA catalog AUTHORIZATION siga_catalog_test' WHERE NOT EXISTS (SELECT FROM pg_namespace WHERE nspname='catalog')`n\gexec`nREVOKE ALL ON SCHEMA catalog FROM PUBLIC;" 'siga_catalog_local_test'
            Write-Host 'Catalog provisioned; existing passwords are never changed.'
        }
        'Test' {
            Import-Catalog $true
            $ErrorActionPreference = 'Continue'
            ./mvnw.cmd -B '-Dsiga.build.directory=.local/test-build' verify
            $ErrorActionPreference = 'Stop'
            Check-Exit
        }
        'Start' {
            Import-Catalog $false
            $pidFile = Join-Path $local 'catalog.pid'
            if (Test-Path $pidFile) {
                $running = Get-Process -Id ([int](Get-Content $pidFile)) -ErrorAction SilentlyContinue
                if ($running) { throw 'Recorded Catalog PID is running.' }
            }
            $port = if ($env:CATALOG_PORT) { [int]$env:CATALOG_PORT } else { 8082 }
            $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,$port)
            try { $listener.Start() } finally { $listener.Stop() }
            $ErrorActionPreference = 'Continue'
            ./mvnw.cmd -B '-DskipTests' package
            $ErrorActionPreference = 'Stop'
            Check-Exit
            $built = Join-Path $local 'build/siga-catalog-service-0.0.1-SNAPSHOT.jar'
            $hash = (Get-FileHash $built -Algorithm SHA256).Hash
            New-Item -ItemType Directory -Force "$local/runtime" | Out-Null
            $jar = Join-Path $local "runtime/catalog-$hash.jar"
            if (!(Test-Path $jar)) { Copy-Item $built $jar }
            $process = Start-Process (Get-Command java).Source -ArgumentList @('-jar',('"{0}"' -f $jar)) -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput "$local/catalog.stdout.log" -RedirectStandardError "$local/catalog.stderr.log"
            $process.Id | Set-Content $pidFile
            $jar | Set-Content "$local/catalog.jar"
            $deadline = [DateTime]::UtcNow.AddSeconds(45)
            do {
                $process.Refresh()
                if ($process.HasExited) { throw 'Catalog exited; inspect .local logs.' }
                try { if ((Invoke-RestMethod "http://127.0.0.1:$port/actuator/health/readiness" -TimeoutSec 2).status -eq 'UP') { Write-Host "Catalog UP on 127.0.0.1:$port"; return } } catch { }
                Start-Sleep -Milliseconds 500
            } while ([DateTime]::UtcNow -lt $deadline)
            throw 'Readiness timeout; process and logs preserved.'
        }
        'Stop' {
            if (!(Test-Path "$local/catalog.pid")) { return }
            $catalogPid = [int](Get-Content "$local/catalog.pid")
            $process = Get-CimInstance Win32_Process -Filter "ProcessId=$catalogPid"
            if (!$process) { return }
            $jar = Get-Content "$local/catalog.jar"
            if (!$process.CommandLine.Contains($jar)) { throw 'PID does not match Catalog runtime.' }
            Stop-Process -Id $catalogPid
        }
        'StopDependencies' { Existing-Stack; docker @compose stop postgres redis rabbitmq; Check-Exit }
        'Status' { Existing-Stack; docker @compose ps; Check-Exit }
    }
} finally { Pop-Location }
