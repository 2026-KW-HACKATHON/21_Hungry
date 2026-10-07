param(
  [Parameter(Mandatory=$true)][string]$BaseUrl,
  [Parameter(Mandatory=$true)][string]$BearerToken,
  [Parameter(Mandatory=$true)][Guid]$GroupId,
  [Nullable[Guid]]$EncounterId,
  [string]$WavPath,
  [string]$ImagePath,
  [string]$PdfPath,
  [string]$ConfirmBodyPath,
  [switch]$AutoConfirmMedications
)
$ErrorActionPreference='Stop'
$headers=@{Authorization="Bearer $BearerToken"}
function Invoke-MultipartUpload([string]$Uri,[string]$Metadata,[string]$Path,[string]$Field,[string]$IdempotencyKey){
  Add-Type -AssemblyName System.Net.Http
  $client=New-Object Net.Http.HttpClient
  $content=New-Object Net.Http.MultipartFormDataContent
  try{
    $client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$BearerToken)
    $metadataContent=[Net.Http.StringContent]::new($Metadata,[Text.Encoding]::UTF8,'application/json')
    $fileContent=[Net.Http.ByteArrayContent]::new([IO.File]::ReadAllBytes([IO.Path]::GetFullPath($Path)))
    $mediaType=switch([IO.Path]::GetExtension($Path).ToLowerInvariant()){'.wav'{'audio/wav'}'.pdf'{'application/pdf'}default{'image/jpeg'}}
    $fileContent.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new($mediaType)
    $content.Add($metadataContent,'metadata')
    $content.Add($fileContent,$Field,[IO.Path]::GetFileName($Path))
    $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post,$Uri)
    $request.Headers.TryAddWithoutValidation('Idempotency-Key',$IdempotencyKey)|Out-Null
    $request.Content=$content
    $response=$client.SendAsync($request).GetAwaiter().GetResult()
    $body=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if(-not $response.IsSuccessStatusCode){throw "Multipart upload failed with HTTP $([int]$response.StatusCode): $body"}
    return $body|ConvertFrom-Json
  }finally{$content.Dispose();$client.Dispose()}
}
$reusedExisting=$null -ne $EncounterId
if($reusedExisting){
  # Recovery checks the existing encounter and jobs before creating any new input.
  # Windows PowerShell may bind Nullable[Guid] as Guid, where .Value resolves to null.
  $encounterId=[Guid]$EncounterId
  $detail=Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/encounters/$encounterId" -Headers $headers
}else{
  foreach($required in @($WavPath,$ImagePath,$PdfPath)){if([string]::IsNullOrWhiteSpace($required)){throw 'WavPath, ImagePath, and PdfPath are required when EncounterId is omitted'}}
  $idempotency=[Guid]::NewGuid().ToString()
  $created=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/care-groups/$GroupId/encounters" -Headers ($headers+@{'Idempotency-Key'=$idempotency}) -ContentType 'application/json' -Body (@{recordType='VISIT';title='OpenAI synthetic live smoke';occurredOn=(Get-Date).ToString('yyyy-MM-dd')}|ConvertTo-Json)
  $encounterId=$created.data.id
  $version=0;$inputVersion=1
  foreach($upload in @(@{Path=$ImagePath;Endpoint='documents';Field='files'},@{Path=$PdfPath;Endpoint='documents';Field='files'},@{Path=$WavPath;Endpoint='audio';Field='file'})){
    $metadata=@{expectedVersion=$version;expectedInputVersion=$inputVersion}|ConvertTo-Json -Compress
    $response=Invoke-MultipartUpload "$BaseUrl/api/v1/encounters/$encounterId/$($upload.Endpoint)" $metadata $upload.Path $upload.Field ([Guid]::NewGuid().ToString())
    $version=$response.data.version;$inputVersion=$response.data.inputVersion
  }
}
$deadline=(Get-Date).AddMinutes(10)
do{$detail=Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/encounters/$encounterId" -Headers $headers;if($detail.data.processingState -in @('FAILED','READY','NEEDS_REVIEW')){break};Start-Sleep -Seconds 2}while((Get-Date)-lt$deadline)
if($detail.data.processingState -eq 'FAILED'){throw "OpenAI processing failed; inspect the safe job error codes for encounter $encounterId"}
if((Get-Date)-ge$deadline){throw "Timed out waiting for encounter processing: $encounterId"}
$review=Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/encounters/$encounterId/review-items?reviewState=ALL" -Headers $headers
if($ConfirmBodyPath){$confirmation=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/encounters/$encounterId/review-items/confirm" -Headers ($headers+@{'Idempotency-Key'=[Guid]::NewGuid().ToString()}) -ContentType 'application/json' -Body (Get-Content -LiteralPath $ConfirmBodyPath -Raw)}
elseif($AutoConfirmMedications){
  $medications=@($review.data.items|Where-Object {$_.itemType -eq 'MEDICATION' -and $_.reviewState -in @('NEEDS_REVIEW','READY')})
  if($medications.Count -eq 0){throw 'No medication review item is available for R02 confirmation'}
  # The synthetic fixture intentionally repeats the same medication in an image and PDF.
  # Confirm the page-backed candidate once, supply the two explicit source times, and resolve
  # the duplicate manually so the smoke does not create duplicate prescriptions or schedules.
  $medication=@($medications|Where-Object {@($_.evidence|Where-Object {$null-ne$_.page}).Count -gt 0}|Select-Object -First 1)
  if($medication.Count -eq 0){$medication=@($medications|Select-Object -First 1)}
  $candidate=$medication[0]
  $payload=[ordered]@{
    schemaVersion=1;name=$candidate.payload.name;doseText=$candidate.payload.doseText;frequencyText=$candidate.payload.frequencyText
    startsOn=$candidate.payload.startsOn;endsOn=$candidate.payload.endsOn;instructions=$candidate.payload.instructions
    supersedesMedicationId=$candidate.payload.supersedesMedicationId
    schedulePlans=@(
      [ordered]@{recurrence='DAILY';firstDate=$candidate.payload.startsOn;lastDate=$candidate.payload.endsOn;weekdays=@();localTime='08:00';durationMinutes=30},
      [ordered]@{recurrence='DAILY';firstDate=$candidate.payload.startsOn;lastDate=$candidate.payload.endsOn;weekdays=@();localTime='20:00';durationMinutes=30}
    )
  }
  $confirmItems=@([ordered]@{itemId=$candidate.id;expectedVersion=$candidate.version;payload=$payload;conflictResolution=@{mode='MANUAL';note='Synthetic fixture duplicate reconciled against the page-backed source.'}})
  $confirmBody=@{expectedInputVersion=$review.data.inputVersion;revisionId=$review.data.revisionId;items=$confirmItems}|ConvertTo-Json -Depth 12 -Compress
  $confirmation=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/encounters/$encounterId/review-items/confirm" -Headers ($headers+@{'Idempotency-Key'=[Guid]::NewGuid().ToString()}) -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($confirmBody))
}
$from=[Uri]::EscapeDataString((Get-Date).ToUniversalTime().ToString('o'));$to=[Uri]::EscapeDataString((Get-Date).AddDays(15).ToUniversalTime().ToString('o'))
$tasks=Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/care-groups/$GroupId/tasks?from=$from&to=$to&encounterId=$encounterId" -Headers $headers
@{encounterId=$encounterId;reusedExistingEncounter=$reusedExisting;processingState=$detail.data.processingState;reviewItems=$review.data.items.Count;reviewItemTypes=@($review.data.items|ForEach-Object {$_.itemType});linkedTasks=$tasks.data.items.Count;confirmationExecuted=($null-ne $confirmation);confirmedMedications=if($confirmation){$confirmation.data.medications.Count}else{0};createdSeries=if($confirmation){$confirmation.data.seriesIds.Count}else{0};createdOccurrences=if($confirmation){$confirmation.data.occurrences.Count}else{0}}|ConvertTo-Json
