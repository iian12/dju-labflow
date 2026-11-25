# Keep the private monitoring tunnel alive while this Windows user is logged in.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$stateDirectory = Join-Path $repoRoot '.external-access\monitoring'
New-Item -ItemType Directory -Force -Path $stateDirectory | Out-Null
$lock = New-Object System.Threading.Mutex($false, 'Local\LabFlowMonitoringTunnel')
if (-not $lock.WaitOne(0)) { $lock.Dispose(); exit 0 }
try {
    Set-Content -LiteralPath (Join-Path $stateDirectory 'tunnel-supervisor.pid') -Value $PID
    $sshPath = Join-Path $env:SystemRoot 'System32\OpenSSH\ssh.exe'
    $logPath = Join-Path $stateDirectory 'ssh-tunnel.log'
    while ($true) {
        if ((Test-Path -LiteralPath $logPath) -and (Get-Item -LiteralPath $logPath).Length -gt 2097152) {
            Move-Item -LiteralPath $logPath -Destination ($logPath + '.previous') -Force
        }
        Add-Content -LiteralPath $logPath -Encoding UTF8 -Value ("[{0}] Connecting private monitoring tunnel" -f [DateTime]::UtcNow.ToString('o'))
        $sessionLog = Join-Path $stateDirectory 'ssh-session.log'
        $arguments = @('-N','-T','-p','2222','-o','BatchMode=yes','-o','ConnectTimeout=10','-o','ServerAliveInterval=15','-o','ServerAliveCountMax=3','-o','ExitOnForwardFailure=yes','-L','127.0.0.1:19100:127.0.0.1:9100','-L','127.0.0.1:18081:127.0.0.1:8081','ian@iian12.ddns.net')
        $session = Start-Process -FilePath $sshPath -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardError $sessionLog
        Set-Content -LiteralPath (Join-Path $stateDirectory 'ssh-session.pid') -Value $session.Id
        $session.WaitForExit()
        Add-Content -LiteralPath $logPath -Encoding UTF8 -Value ("[{0}] SSH exited: {1}" -f [DateTime]::UtcNow.ToString('o'), $session.ExitCode)
        if (Test-Path -LiteralPath $sessionLog) {
            Get-Content -LiteralPath $sessionLog | Add-Content -LiteralPath $logPath -Encoding UTF8
        }
        Start-Sleep -Seconds 10
    }
} finally {
    $lock.ReleaseMutex()
    $lock.Dispose()
}
