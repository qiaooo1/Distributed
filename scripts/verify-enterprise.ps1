[CmdletBinding()]
param([string]$BaseUrl = 'http://127.0.0.1:18080')

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'session.ps1')
$base = $BaseUrl.TrimEnd('/')
$admin = New-JobLabSession -BaseUrl $base
$operator = New-JobLabSession -BaseUrl $base -Username 'operator'
$viewer = New-JobLabSession -BaseUrl $base -Username 'viewer'
$other = New-JobLabSession -BaseUrl $base -Tenant 'globex'
$runId = [guid]::NewGuid().ToString('N')

function Assert-Result($condition, [string]$message) {
    if (!$condition) { throw $message }
}

function Call-JobLab([string]$method, [string]$path, [hashtable]$headers, [hashtable]$body) {
    $request = @{ Method = $method; Uri = $base + $path; TimeoutSec = 8; Headers = $headers }
    if ($null -ne $body) {
        $request['ContentType'] = 'application/json'
        $request['Body'] = $body | ConvertTo-Json -Compress
    }
    Invoke-RestMethod @request
}

function Assert-HttpStatus([int]$expected, [scriptblock]$operation) {
    try {
        & $operation | Out-Null
    } catch {
        $actual = [int]$_.Exception.Response.StatusCode
        if ($actual -eq $expected) { return }
        throw "Expected HTTP $expected, received $actual. $($_.Exception.Message)"
    }
    throw "Expected HTTP $expected but the operation succeeded"
}

$jobBody = @{
    reportType = 'DAILY_TOTALS'
    fromDate = '2026-01-01'
    toDate = '2026-01-03'
    runAt = (Get-Date).ToUniversalTime().AddMinutes(4).ToString('o')
}
$key = 'ent-' + $runId
Assert-HttpStatus 401 { Call-JobLab GET '/api/jobs' @{} }
Assert-HttpStatus 403 { Call-JobLab POST '/api/jobs' ($viewer + @{ 'Idempotency-Key' = $key }) $jobBody }

$own = Call-JobLab POST '/api/jobs' ($operator + @{ 'Idempotency-Key' = $key; 'X-Request-Id' = $key }) $jobBody
$another = Call-JobLab POST '/api/jobs' ($other + @{ 'Idempotency-Key' = $key }) $jobBody
Assert-Result ($own.id -ne $another.id) 'Idempotency keys must be isolated by tenant'
Assert-HttpStatus 404 { Call-JobLab GET "/api/jobs/$($own.id)" $other }
Assert-HttpStatus 404 { Call-JobLab GET "/api/jobs/$($own.id)/result" $other }
Assert-HttpStatus 404 { Call-JobLab POST "/api/jobs/$($own.id)/cancel" $other }
Assert-HttpStatus 404 { Call-JobLab GET "/api/jobs?cursor=$($own.id)" $other }
Assert-Result ((Call-JobLab GET "/api/jobs/$($own.id)" $viewer).id -eq $own.id) 'Viewer must be able to read the tenant job'
$cancelOwn = Call-JobLab POST "/api/jobs/$($own.id)/cancel" $operator
$cancelOther = Call-JobLab POST "/api/jobs/$($another.id)/cancel" $other
Assert-Result ($cancelOwn.state -eq 'CANCELLED' -and $cancelOther.state -eq 'CANCELLED') 'Cannot cancel test jobs'
Write-Host 'PASS JWT, role checks and tenant isolation'

$memberName = 'test-' + $runId
$password = 'JobLab-Test-' + $runId.Substring(0, 16)
$member = Call-JobLab POST '/api/admin/members' $admin @{ username = $memberName; password = $password; role = 'VIEWER' }
$memberSession = New-JobLabSession -BaseUrl $base -Username $memberName -Password $password
Call-JobLab GET '/api/jobs' $memberSession | Out-Null
Assert-HttpStatus 404 { Call-JobLab PUT "/api/admin/members/$($member.id)" $other @{ role = 'ADMIN'; enabled = $true } }
Call-JobLab PUT "/api/admin/members/$($member.id)" $admin @{ role = 'VIEWER'; enabled = $false } | Out-Null
Assert-HttpStatus 401 { Call-JobLab GET '/api/jobs' $memberSession }
Assert-HttpStatus 403 { Call-JobLab GET '/api/operations/audit' $viewer }
Write-Host 'PASS tenant administration and immediate JWT revocation'

$schedule = Call-JobLab POST '/api/schedules' $operator @{
    name = 'verify-' + $runId
    cronExpression = '*/15 * * * * *'
    timeZone = 'Asia/Shanghai'
    reportType = 'DAILY_TOTALS'
    lookbackDays = 2
    maxAttempts = 3
    misfirePolicy = 'FIRE_ONCE'
    overlapPolicy = 'SKIP_IF_ACTIVE'
}
try {
    Assert-HttpStatus 404 { Call-JobLab GET "/api/schedules/$($schedule.id)" $other }
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $current = Call-JobLab GET "/api/schedules/$($schedule.id)" $viewer
        if ($current.lastFireAt) { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    Assert-Result ($null -ne $current.lastFireAt) 'Schedule never fired'
    $page = Call-JobLab GET '/api/jobs?limit=100' $viewer
    $occurrence = $page.items | Where-Object { $_.scheduleId -eq $schedule.id } | Select-Object -First 1
    Assert-Result ($null -ne $occurrence) 'Schedule failed to materialize a tenant job'
    $audit = Call-JobLab GET '/api/operations/audit?limit=100' $admin
    Assert-Result (($audit.items | Where-Object {
        $_.action -eq 'JOB_SUBMITTED' -and $_.resourceId -eq $own.id -and $_.requestId -eq $key
    }).Count -ge 1) 'Submission audit record could not be correlated'
    Assert-Result (($audit.items | Where-Object {
        $_.action -eq 'SCHEDULE_FIRED' -and $_.resourceId -eq $occurrence.id
    }).Count -ge 1) 'Schedule fire audit record is missing'
    Write-Host 'PASS persisted IANA cron scheduling and correlated audit events'
} finally {
    Call-JobLab PUT "/api/schedules/$($schedule.id)/state" $operator @{ enabled = $false } | Out-Null
}
