[CmdletBinding()]
param([string]$BaseUrl = 'http://127.0.0.1:18080', [string]$AccessToken)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'session.ps1')
$session = if ($AccessToken) { @{ Authorization = 'Bearer ' + $AccessToken } } else { New-JobLabSession -BaseUrl $BaseUrl }
$project = Split-Path -Parent $PSScriptRoot
$jobsUrl = $BaseUrl.TrimEnd('/') + '/api/jobs'
$workers = @(docker compose --project-directory $project ps --status running -q worker)
if ($LASTEXITCODE -ne 0 -or $workers.Count -lt 2) {
    throw 'Start at least two workers with REPORT_MIN_RUNTIME_MS=15000 before running this demo.'
}

$workerByHost = @{}
foreach ($worker in $workers) {
    $description = docker inspect --format '{{.Config.Hostname}}|{{range .Config.Env}}{{println .}}{{end}}' $worker
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect worker $worker" }
    $workerHost = ($description[0] -split '\|')[0]
    $runtimeSetting = $description | Where-Object { $_ -match '(^|\|)REPORT_MIN_RUNTIME_MS=\d+$' } | Select-Object -First 1
    if (!$runtimeSetting -or [int]($runtimeSetting -split '=')[-1] -lt 15000) {
        throw 'Set REPORT_MIN_RUNTIME_MS to at least 15000 and recreate the workers first.'
    }
    $workerByHost[$workerHost] = $worker
}

$request = @{ reportType = 'SALES_DETAIL'; fromDate = '2026-01-01'; toDate = '2026-01-03' }
$headers = $session + @{ 'Idempotency-Key' = 'crash-' + [guid]::NewGuid().ToString('N') }
$job = Invoke-RestMethod -Method Post -Uri $jobsUrl -Headers $headers -ContentType 'application/json' -Body ($request | ConvertTo-Json)
$url = "$jobsUrl/$($job.id)"
$victim = $null

try {
    $deadline = (Get-Date).AddSeconds(35)
    do {
        $job = Invoke-RestMethod -Uri $url -Headers $session -TimeoutSec 8
        if ($job.state -eq 'RUNNING') { break }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    if ($job.state -ne 'RUNNING') { throw "Expected RUNNING, received $($job.state)" }

    $originalWorker = $job.attempts[0].workerId
    $victim = $workerByHost[$originalWorker]
    if (!$victim) { throw "Claiming worker $originalWorker does not belong to this Compose project" }

    docker kill $victim | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not stop demo worker $originalWorker" }
    Write-Host "Stopped $originalWorker during attempt 1 for $($job.id)"

    $deadline = (Get-Date).AddSeconds(100)
    do {
        $job = Invoke-RestMethod -Uri $url -Headers $session -TimeoutSec 8
        if ($job.state -in @('SUCCEEDED', 'FAILED', 'CANCELLED')) { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    if ($job.state -ne 'SUCCEEDED' -or $job.attemptCount -ne 2 -or
        $job.attempts[0].outcome -ne 'LEASE_EXPIRED' -or $job.attempts[1].workerId -eq $originalWorker) {
        throw ('Expected recovery by a second worker. Received ' + ($job | ConvertTo-Json -Depth 5 -Compress))
    }
    Write-Host "PASS worker failover to $($job.attempts[1].workerId)"
} finally {
    if ($victim) {
        docker start $victim | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not restart demo container $victim" }
    }
}
