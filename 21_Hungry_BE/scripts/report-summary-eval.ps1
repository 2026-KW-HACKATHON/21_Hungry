[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot '../eval-results/primock57-2026-10-09'
$rows = @()
foreach($file in Get-ChildItem -LiteralPath $root -Filter record.json -Recurse) {
    $r = Get-Content -LiteralPath $file.FullName -Raw -Encoding utf8 | ConvertFrom-Json
    $row = [ordered]@{caseId=$r.caseId;set=$r.set;promptVersion=$r.promptVersion;layout=$r.requestLayout;requestSha256=$r.requestSha256;inputSha256=$r.inputSha256;status=$r.status;errorCode=$r.errorCode;inputTokens=$r.usage.input_tokens;outputTokens=$r.usage.output_tokens;cachedTokens=$r.usage.input_tokens_details.cached_tokens;reasoningTokens=$r.usage.output_tokens_details.reasoning_tokens;elapsedMillis=$r.elapsedMillis;estimatedUsd=$r.estimatedUsd;chargedUsd=$r.chargedUsd;invalidRootQuoteCount=0;invalidItemQuoteCount=0;outOfRangeIndexes=0;parsedItemsNeedingReview=0;parsedItemsReady=0;semanticStatus='NOT_AUTOMATICALLY_GRADED'}
    $responsePath=Join-Path $file.DirectoryName 'response.json'
    if(Test-Path -LiteralPath $responsePath) {
        $response=Get-Content -LiteralPath $responsePath -Raw -Encoding utf8 | ConvertFrom-Json
        $texts=@($response.output | ForEach-Object {$_.content} | Where-Object {$_.type -eq 'output_text'} | ForEach-Object {$_.text})
        if($texts.Count -gt 0) {
            try {$output=$texts[0] | ConvertFrom-Json} catch {$output=$null}
            if($null -ne $output) {
                $request=Get-Content -LiteralPath (Join-Path $file.DirectoryName 'request.json') -Raw -Encoding utf8 | ConvertFrom-Json
                $context=$request.input[1].content[0].text.Replace("SERVER_CONTEXT_JSON:`n",'') | ConvertFrom-Json
                $sources=@{}; foreach($s in $context.sources){$sources[$s.sourceId]=$s.text}
                foreach($e in $output.evidence){if(-not $sources.ContainsKey($e.sourceId) -or -not $sources[$e.sourceId].Contains($e.quote)){$row.invalidRootQuoteCount++}}
                foreach($item in $output.items){foreach($e in $item.evidence){if(-not $sources.ContainsKey($e.sourceId) -or -not $sources[$e.sourceId].Contains($e.quote)){$row.invalidItemQuoteCount++}}}
                foreach($section in $output.details.PSObject.Properties){foreach($detail in $section.Value){foreach($idx in $detail.evidenceIndexes){if($idx -lt 0 -or $idx -ge $output.evidence.Count){$row.outOfRangeIndexes++}}}}
            }
        }
    }
    $parsedPath=Join-Path $file.DirectoryName 'parsed.json'
    if(Test-Path -LiteralPath $parsedPath) {
        $parsed=Get-Content -LiteralPath $parsedPath -Raw -Encoding utf8 | ConvertFrom-Json
        $row.parsedItemsNeedingReview=@($parsed.items | Where-Object {$_.reviewState -eq 'NEEDS_REVIEW'}).Count
        $row.parsedItemsReady=@($parsed.items | Where-Object {$_.reviewState -eq 'READY'}).Count
    }
    $rows += [pscustomobject]$row
}
$report=[ordered]@{reviewStatus='Structural audit only; NOT semantic/clinical approval';calls=$rows.Count;chargedUsd=($rows | Measure-Object chargedUsd -Sum).Sum;results=$rows}
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $root 'audit.json') -Encoding utf8
$rows | Group-Object promptVersion,layout | ForEach-Object {
    [pscustomobject]@{variant=$_.Name;calls=$_.Count;parsed=@($_.Group|Where-Object {$_.status -eq 'PARSED_NOT_QUALITY_APPROVED'}).Count;failed=@($_.Group|Where-Object {$_.status -eq 'FAILED'}).Count;chargedUsd=($_.Group|Measure-Object chargedUsd -Sum).Sum}
} | Format-Table -AutoSize
Write-Output ('Total accounted USD: {0:F6}. No semantic pass is inferred from parsing.' -f $report.chargedUsd)
