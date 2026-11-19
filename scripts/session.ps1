function New-JobLabSession {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [string]$Tenant = 'acme',
        [string]$Username = 'admin',
        [string]$Password = 'JobLab-Demo-2026!'
    )
    $uri = $BaseUrl.TrimEnd('/') + '/api/auth/token'
    $body = @{ tenant = $Tenant; username = $Username; password = $Password } | ConvertTo-Json -Compress
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        try {
            $token = Invoke-RestMethod -Uri $uri -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 8
            return @{ Authorization = 'Bearer ' + $token.accessToken }
        } catch {
            $status = [int]$_.Exception.Response.StatusCode
            if ($status -notin @(0, 502, 503, 504) -or $attempt -eq 19) { throw }
            Start-Sleep -Seconds 2
        }
    }
}
