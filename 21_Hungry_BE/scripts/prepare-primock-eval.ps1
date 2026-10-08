[CmdletBinding()]
param([switch]$EvidenceHoldout, [switch]$V18Holdout, [switch]$V181Final)
$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot '../src/test/resources/ai/eval/primock57'
$commit = 'cd2ac707ad03cb4d2531f4ec6b90c659bf4357c5'
$base = "https://raw.githubusercontent.com/babylonhealth/primock57/$commit"
# Text files only. Never clone LFS or fetch audio. Split is fixed before model evaluation.
$selection = [ordered]@{
    day3_consultation06='development'; day3_consultation08='development'
    day3_consultation02='development'; day2_consultation09='development'
    day3_consultation04='development'; day3_consultation05='development'
    day2_consultation01='holdout'; day2_consultation03='holdout'
    day5_consultation12='holdout'; day1_consultation12='holdout'
}
if ($EvidenceHoldout) {
    $root = Join-Path $PSScriptRoot '../src/test/resources/ai/eval/primock57-evidence-holdout'
    $selection = [ordered]@{ day1_consultation01='new-holdout'; day1_consultation02='new-holdout' }
}
if ($V18Holdout) {
    if ($EvidenceHoldout) { throw 'Choose only one fixed dataset.' }
    $root = Join-Path $PSScriptRoot '../src/test/resources/ai/eval/primock57-v18-holdout'
    $selection = [ordered]@{ day1_consultation03='v18-holdout'; day2_consultation04='v18-holdout'; day4_consultation01='v18-holdout'; day5_consultation01='v18-holdout' }
    if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot '../eval-results/prompt-v1.7-quality-2026-10-09/v1.8-candidate-freeze.json'))) { throw 'Freeze v1.8 before preparing unseen data.' }
}
if ($V181Final) {
    if ($EvidenceHoldout -or $V18Holdout) { throw 'Choose only one fixed dataset.' }
    $root = Join-Path $PSScriptRoot '../src/test/resources/ai/eval/primock57-v181-final'
    $selection = [ordered]@{ day4_consultation02='v181-final'; day5_consultation02='v181-final' }
    if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot '../eval-results/prompt-v1.7-quality-2026-10-09/v1.8.1-plan-r2.json'))) { throw 'Freeze v1.8.1 before preparing new final data.' }
}
New-Item -ItemType Directory -Path "$root/original" -Force | Out-Null
function Download-Original([string]$remote, [string]$target) {
    if (-not (Test-Path -LiteralPath $target)) { Invoke-WebRequest -UseBasicParsing -Uri "$base/$remote" -OutFile $target }
}
Download-Original 'README.md' "$root/original/README.md"
Download-Original 'LICENSE.md' "$root/original/LICENSE.md"
Download-Original 'transcripts/README.md' "$root/original/transcripts-README.md"
Download-Original 'notes/README.md' "$root/original/notes-README.md"
$manifest = @()
foreach($entry in $selection.GetEnumerator()) {
    $id=$entry.Key
    Download-Original "notes/$id.json" "$root/original/$id.note.json"
    $turns=@()
    foreach($speaker in @('doctor','patient')) {
        $remote="transcripts/${id}_${speaker}.TextGrid"
        $target="$root/original/${id}_${speaker}.TextGrid"
        Download-Original $remote $target
        $grid=Get-Content -LiteralPath $target -Raw -Encoding utf8
        $pattern='(?s)intervals \[(\d+)\]:\s*xmin = ([\d.]+)\s*xmax = ([\d.]+)\s*text = "((?:[^"]|"")*)"'
        foreach($match in [regex]::Matches($grid,$pattern)) {
            $value=$match.Groups[4].Value.Replace('""','"')
            if(-not [string]::IsNullOrWhiteSpace($value)) {
                $turns += [ordered]@{speaker=$speaker;start=[double]::Parse($match.Groups[2].Value,[cultureinfo]::InvariantCulture);end=[double]::Parse($match.Groups[3].Value,[cultureinfo]::InvariantCulture);interval=[int]$match.Groups[1].Value;en=$value}
            }
        }
    }
    if($turns.Count -eq 0){ throw "TextGrid parsing failed: $id" }
    $ordered=@($turns | Sort-Object { $_.start },{ $_.speaker })
    $index=0
    foreach($turn in $ordered){$index++;$turn['id']=('{0:D3}' -f $index)}
    $case=[ordered]@{id=$id;set=$entry.Value;dataset='PriMock57';commit=$commit;license='CC-BY-4.0';mockConsultation=$true;turns=$ordered}
    $path="$root/$id.en.json"
    $case | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $path -Encoding utf8
    $manifest += [ordered]@{id=$id;set=$entry.Value;utterances=$ordered.Count;englishFile="$id.en.json";translationFile="$id.ko.json";note="original/$id.note.json"}
}
[ordered]@{version='1.0.0';commit=$commit;selectedBeforeEvaluation=$true;cases=$manifest} | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath "$root/manifest.json" -Encoding utf8
Write-Host "Prepared $($selection.Count) public mock consultations. No audio or clinical service API was used."
