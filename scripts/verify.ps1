[CmdletBinding()]
param([string]$BaseUrl = 'http://127.0.0.1:18080', [string]$AccessToken)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'session.ps1')
$session = if ($AccessToken) { @{ Authorization = 'Bearer ' + $AccessToken } } else { New-JobLabSession -BaseUrl $BaseUrl }
$jobsUrl = $BaseUrl.TrimEnd('/') + '/api/jobs'
$payload = @{
    reportType = 'DAILY_TOTALS'
    fromDate = '2026-01-01'
    toDate = '2026-01-03'
}
$key = 'verify-' + [guid]::NewGuid().ToString('N')
$headers = $session + @{ 'Idempotency-Key' = $key; 'X-Request-Id' = $key }

function Assert-True($condition, [string]$message) {
    if (!$condition) { throw $message }
}

function Submit-Job($data, $requestHeaders) {
    Invoke-RestMethod -Method Post -Uri $jobsUrl -Headers $requestHeaders -ContentType 'application/json' -Body ($data | ConvertTo-Json -Compress) -TimeoutSec 8
}

function Read-Job([string]$id) {
    Invoke-RestMethod -Uri "$jobsUrl/$id" -Headers $session -TimeoutSec 8
}

$first = $null
for ($i = 0; $i -lt 20 -and !$first; $i++) {
    try {
        $first = Submit-Job $payload $headers
    } catch {
        $status = [int]$_.Exception.Response.StatusCode
        if ($status -notin @(0, 502, 503, 504) -or $i -eq 19) { throw }
        Start-Sleep -Seconds 2
    }
}
Assert-True ($null -ne $first.id) 'Submission must return a job ID'
$duplicate = Submit-Job $payload $headers
Assert-True ($first.id -eq $duplicate.id) 'Identical submission returned a different ID'

try {
    Submit-Job ($payload + @{ maxAttempts = 2 }) $headers | Out-Null
    throw 'The same key with a different body should return 409'
} catch {
    if ([int]$_.Exception.Response.StatusCode -ne 409) { throw }
}
Write-Host 'PASS submission and idempotency conflict'

$deadline = (Get-Date).AddSeconds(65)
do {
    $completed = Read-Job $first.id
    if ($completed.state -in @('SUCCEEDED', 'FAILED', 'CANCELLED')) { break }
    Start-Sleep -Milliseconds 400
} while ((Get-Date) -lt $deadline)

Assert-True ($completed.state -eq 'SUCCEEDED') "Expected SUCCEEDED. Current state: $($completed.state)"
Assert-True ($completed.attemptCount -eq 1) 'The healthy execution should have one attempt'
$report = Invoke-WebRequest -UseBasicParsing -Uri "$jobsUrl/$($first.id)/result" -Headers $session -TimeoutSec 8
$content = if ($report.Content -is [byte[]]) {
    [Text.Encoding]::UTF8.GetString($report.Content)
} else {
    [string]$report.Content
}
Assert-True ($content.Contains('2026-01-01,2,5,439.50')) 'CSV total for 2026-01-01 was incorrect'
Write-Host "PASS RabbitMQ dispatch, worker execution and CSV result ($($first.id))"

$delayHeaders = $session + @{ 'Idempotency-Key' = $key + '-delay' }
$delayed = Submit-Job ($payload + @{ runAt = (Get-Date).ToUniversalTime().AddMinutes(2).ToString('o') }) $delayHeaders
Assert-True ($delayed.state -eq 'SCHEDULED' -and $delayed.attemptCount -eq 0) 'Future job ran early'
$cancelled = Invoke-RestMethod -Method Post -Uri "$jobsUrl/$($delayed.id)/cancel" -Headers $session -TimeoutSec 8
Assert-True ($cancelled.state -eq 'CANCELLED') 'Delayed job could not be cancelled'
Assert-True ((Read-Job $delayed.id).attemptCount -eq 0) 'Cancelled job must have no attempts'
Write-Host "PASS delayed scheduling and cancellation ($($delayed.id))"
