param([ValidateSet('smoke', 'soak', 'soak-check')][string]$Profile = 'smoke')
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Add-Type -Namespace LabFlow -Name LoadTestPowerState -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
public static extern uint SetThreadExecutionState(uint flags);
'@
# Keep this process's thread alive for the whole child run; release on every exit.
if ([LabFlow.LoadTestPowerState]::SetThreadExecutionState([uint32]2147483649) -eq 0) {
    throw 'Unable to prevent system sleep during the load test.'
}
try {
    $env:LABFLOW_LOAD_PROFILE = $Profile
    & node (Join-Path $repoRoot 'scripts/monitoring/run-cycle.mjs')
    $result = $LASTEXITCODE
} finally {
    [void][LabFlow.LoadTestPowerState]::SetThreadExecutionState([uint32]2147483648)
}
exit $result
