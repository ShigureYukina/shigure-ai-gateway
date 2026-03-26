$ErrorActionPreference = 'Stop'

$mock = $null
$app = $null

try {
    $mock = Start-Process -FilePath py -ArgumentList 'scripts/mock_openai.py' -PassThru

    $appArgs = @(
        '-jar',
        'target/shortlink-ai-gateway.jar',
        '--spring.cloud.nacos.discovery.enabled=false',
        '--spring.cloud.service-registry.auto-registration.enabled=false',
        '--short-link.ai-gateway.upstream.provider-base-url.openai=http://127.0.0.1:18080',
        '--server.port=18010'
    )
    $app = Start-Process -FilePath java -ArgumentList $appArgs -PassThru

    Start-Sleep -Seconds 10

    Write-Output '=== NON_STREAM ==='
    $nonStreamBody = '{"model":"gpt-4o-mini-compatible","messages":[{"role":"user","content":"hello"}],"stream":false}'
    $nonResp = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18010/v1/chat/completions' -ContentType 'application/json' -Headers @{ Authorization = 'Bearer demo-key' } -Body $nonStreamBody
    $nonResp | ConvertTo-Json -Depth 10
    Write-Output ''

    Write-Output '=== STREAM ==='
    $streamBody = '{"model":"gpt-4o-mini-compatible","messages":[{"role":"user","content":"hello"}],"stream":true}'
    $streamResp = Invoke-WebRequest -Method Post -Uri 'http://127.0.0.1:18010/v1/chat/completions' -ContentType 'application/json' -Headers @{ Authorization = 'Bearer demo-key' } -Body $streamBody
    $streamResp.Content
    Write-Output ''
}
finally {
    if ($app -and -not $app.HasExited) {
        Stop-Process -Id $app.Id -Force
    }
    if ($mock -and -not $mock.HasExited) {
        Stop-Process -Id $mock.Id -Force
    }
}
