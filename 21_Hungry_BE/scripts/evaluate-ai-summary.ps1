[CmdletBinding()]
param(
    [string]$Model = 'gpt-5.4-mini-2026-03-17',
    [string]$CasesPath = 'src/test/resources/ai/eval/summary-cases-v0.1.0.json',
    [string]$BaselinePromptPath = 'src/main/resources/ai/analysis-prompt-v1.0.txt',
    [string]$CandidatePromptPath = 'src/main/resources/ai/analysis-prompt-v1.1.txt',
    [string]$SchemaPath = 'src/main/resources/ai/analysis-output.schema.json',
    [string]$ApiKeyPath = 'secrets/openai.env',
    [string]$OutputPath = 'build/reports/ai-summary/evaluation.json',
    [int]$MaxOutputTokens = 12000,
    [string]$CaseIds = '',
    [ValidateRange(0, 100)] [int]$MaxCases = 0
)

$ErrorActionPreference = 'Stop'

function Remove-ProviderUnsupportedKeywords {
    param([object]$Node)
    if ($null -eq $Node) { return }
    if ($Node -is [System.Array]) {
        foreach ($item in $Node) { Remove-ProviderUnsupportedKeywords $item }
        return
    }
    if ($Node -is [pscustomobject]) {
        foreach ($name in @('uniqueItems','format','minLength','maxLength','pattern','minimum','maximum','multipleOf','minItems','maxItems')) {
            $Node.PSObject.Properties.Remove($name)
        }
        foreach ($property in @($Node.PSObject.Properties)) { Remove-ProviderUnsupportedKeywords $property.Value }
    }
}

function Get-ApiKey {
    if (-not [string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) { return $env:OPENAI_API_KEY.Trim() }
    if (-not (Test-Path -LiteralPath $ApiKeyPath)) { throw "OpenAI key is unavailable. Set OPENAI_API_KEY or provide -ApiKeyPath." }
    $line = Get-Content -LiteralPath $ApiKeyPath -Encoding utf8 | Where-Object { $_ -match '^OPENAI_API_KEY=' } | Select-Object -First 1
    if (-not $line) { throw "OPENAI_API_KEY is missing from the configured key file." }
    return $line.Substring($line.IndexOf('=') + 1).Trim()
}

function Get-OutputText {
    param([object]$Response)
    if ($Response.status -eq 'incomplete') { throw 'OpenAI returned an incomplete response.' }
    foreach ($entry in @($Response.output)) {
        foreach ($part in @($entry.content)) {
            if ($part.type -eq 'refusal' -or $part.refusal) { throw 'OpenAI refused the synthetic evaluation input.' }
            if ($part.type -eq 'output_text' -and $part.text) { return [string]$part.text }
        }
    }
    throw 'OpenAI response did not contain output_text.'
}

function Invoke-SummaryCase {
    param([string]$PromptVersion, [string]$PromptHash, [string]$Prompt, [object]$Case, [int]$CaseIndex, [object]$ProviderSchema, [string]$ApiKey)
    $sources = @()
    $sourceIndex = 0
    foreach ($source in @($Case.sources)) {
        $sourceIndex++
        $sources += [ordered]@{
            sourceId = ('00000000-0000-4000-8000-{0:D12}' -f (($CaseIndex * 10) + $sourceIndex))
            textVersion = 1
            sourceType = $source.sourceType
            mediaType = $source.mediaType
            text = $source.text
        }
    }
    $context = [ordered]@{
        occurredOn = $script:caseSet.occurredOn
        analyzedAt = '2026-10-08T00:00:00Z'
        timezone = $script:caseSet.timezone
        sources = $sources
        knownMedications = @()
    }
    $body = [ordered]@{
        model = $Model
        input = @(
            [ordered]@{ role='developer'; content=@([ordered]@{type='input_text'; text=$Prompt}) },
            [ordered]@{ role='user'; content=@([ordered]@{type='input_text'; text="SERVER_CONTEXT_JSON:`n$($context | ConvertTo-Json -Depth 30 -Compress)"}) }
        )
        text = [ordered]@{ format=[ordered]@{type='json_schema'; name='encounter_analysis'; strict=$true; schema=$ProviderSchema} }
        max_output_tokens = $MaxOutputTokens
        store = $false
    }
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-RestMethod -Method Post -Uri 'https://api.openai.com/v1/responses' -Headers @{Authorization="Bearer $ApiKey"; Accept='application/json'} -ContentType 'application/json; charset=utf-8' -Body ($body | ConvertTo-Json -Depth 100 -Compress)
    $stopwatch.Stop()
    $raw = Get-OutputText $response
    $parsed = $raw | ConvertFrom-Json
    $displayText = (@($parsed.summary) + @($parsed.details.PSObject.Properties.Value | ForEach-Object { @($_) | ForEach-Object { $_.text } })) -join "`n"
    $contractErrors = [System.Collections.Generic.List[string]]::new()
    $allEvidence = @($parsed.evidence) + @($parsed.items | ForEach-Object { @($_.evidence) })
    foreach ($evidence in $allEvidence) {
        $matchingSource = @($sources | Where-Object { $_.sourceId -eq $evidence.sourceId }) | Select-Object -First 1
        if ($null -eq $matchingSource) { $contractErrors.Add('unknown sourceId in evidence'); continue }
        if ($evidence.textVersion -ne $matchingSource.textVersion) { $contractErrors.Add('textVersion mismatch in evidence') }
        if ([string]::IsNullOrEmpty([string]$evidence.quote) -or -not $matchingSource.text.Contains([string]$evidence.quote)) {
            $contractErrors.Add('evidence quote is not an exact source substring')
        }
    }
    $rootEvidenceCount = @($parsed.evidence).Count
    foreach ($detailGroup in @($parsed.details.PSObject.Properties.Value)) {
        foreach ($detail in @($detailGroup)) {
            foreach ($index in @($detail.evidenceIndexes)) {
                if ([int]$index -lt 0 -or [int]$index -ge $rootEvidenceCount) { $contractErrors.Add('detail evidence index is out of range') }
            }
        }
    }
    $reportedSpeechStems = @(
        (-join @([char]0xB9D0,[char]0xD588)),
        (-join @([char]0xC124,[char]0xBA85,[char]0xD588)),
        (-join @([char]0xD638,[char]0xC18C,[char]0xD588)),
        (-join @([char]0xC774,[char]0xC57C,[char]0xAE30,[char]0xD588)),
        (-join @([char]0xC548,[char]0xB0B4,[char]0xD588))
    )
    return [ordered]@{
        promptVersion = $PromptVersion
        promptSha256 = $PromptHash
        model = $Model
        maxOutputTokens = $MaxOutputTokens
        elapsedMs = $stopwatch.ElapsedMilliseconds
        inputTokens = $response.usage.input_tokens
        outputTokens = $response.usage.output_tokens
        summary = $parsed.summary
        details = $parsed.details
        evidence = $parsed.evidence
        items = $parsed.items
        contractCheck = [ordered]@{passed=($contractErrors.Count -eq 0); errors=@($contractErrors | Select-Object -Unique)}
        styleSignals = [ordered]@{
            reportedSpeechStemTypeCount = @($reportedSpeechStems | Where-Object { $displayText.Contains($_) }).Count
            hasConditionalLanguage = [bool]($displayText -match '(\uBA74|\uACBD\uC6B0|\uB530\uB77C|\uB54C\uB9CC)')
        }
    }
}

$apiKey = Get-ApiKey
$script:caseSet = Get-Content -LiteralPath $CasesPath -Raw -Encoding utf8 | ConvertFrom-Json
$schema = Get-Content -LiteralPath $SchemaPath -Raw -Encoding utf8 | ConvertFrom-Json
$schema.PSObject.Properties.Remove('$schema')
$schema.PSObject.Properties.Remove('$id')
$schema.PSObject.Properties.Remove('title')
Remove-ProviderUnsupportedKeywords $schema
$prompts = @(
    [ordered]@{version='1.0'; sha256=(Get-FileHash -LiteralPath $BaselinePromptPath -Algorithm SHA256).Hash.ToLowerInvariant(); text=(Get-Content -LiteralPath $BaselinePromptPath -Raw -Encoding utf8)},
    [ordered]@{version='1.1'; sha256=(Get-FileHash -LiteralPath $CandidatePromptPath -Algorithm SHA256).Hash.ToLowerInvariant(); text=(Get-Content -LiteralPath $CandidatePromptPath -Raw -Encoding utf8)}
)
$selectedCases = @($script:caseSet.cases)
if (-not [string]::IsNullOrWhiteSpace($CaseIds)) {
    $wantedIds = @($CaseIds.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $selectedCases = @($selectedCases | Where-Object { $wantedIds -contains $_.id })
    if ($selectedCases.Count -ne $wantedIds.Count) { throw 'One or more -CaseIds values do not exist in the evaluation set.' }
}
if ($MaxCases -gt 0) { $selectedCases = @($selectedCases | Select-Object -First $MaxCases) }
$results = @()
$caseIndex = 0
foreach ($case in $selectedCases) {
    $caseIndex++
    Write-Host ("Evaluating {0} ({1})" -f $case.id,$case.set)
    $runs = @()
    foreach ($prompt in $prompts) {
        $runs += Invoke-SummaryCase $prompt.version $prompt.sha256 $prompt.text $case $caseIndex $schema $apiKey
    }
    $results += [ordered]@{
        id = $case.id
        set = $case.set
        sources = $case.sources
        requiredFacts = $case.requiredFacts
        forbiddenInferences = $case.forbiddenInferences
        runs = $runs
    }
}
$report = [ordered]@{
    runAt = [DateTimeOffset]::UtcNow.ToString('o')
    evaluationSetVersion = $script:caseSet.evaluationSetVersion
    evaluationSetSha256 = (Get-FileHash -LiteralPath $CasesPath -Algorithm SHA256).Hash.ToLowerInvariant()
    guidelineVersion = $script:caseSet.guidelineVersion
    prompts = @('1.0','1.1')
    model = $Model
    settings = [ordered]@{maxOutputTokens=$MaxOutputTokens; store=$false; structuredOutput='encounter_analysis schema 1.0'}
    safety = 'Synthetic text only; direct Responses API evaluation; no application API, database, prescription confirmation, or task creation.'
    results = $results
}
$outputDirectory = Split-Path -Parent $OutputPath
if ($outputDirectory) { New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null }
$report | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $OutputPath -Encoding utf8
Write-Host ("Wrote evaluation report to {0}. API key was not written." -f $OutputPath)
