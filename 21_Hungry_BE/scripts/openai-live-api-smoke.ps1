param(
  [Parameter(Mandatory=$true)][string]$BaseUrl,
  [Parameter(Mandatory=$true)][string]$BearerToken,
  [Parameter(Mandatory=$true)][Guid]$GroupId,
  [Nullable[Guid]]$EncounterId,
  [string]$WavPath,
  [string]$ImagePath,
  [string]$PdfPath,
  [string]$ConfirmBodyPath
)
$ErrorActionPreference='Stop'
$headers=@{Authorization="Bearer $BearerToken"}
$reusedExisting=$null -ne $EncounterId
if($reusedExisting){
  # Recovery checks the existing encounter and jobs before creating any new input.
  $encounterId=$EncounterId.Value
  $detail=Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/encounters/$encounterId" -Headers $headers
}else{
  foreach($required in @($WavPath,$ImagePath,$PdfPath)){if([string]::IsNullOrWhiteSpace($required)){throw 'WavPath, ImagePath, and PdfPath are required when EncounterId is omitted'}}
  $idempotency=[Guid]::NewGuid().ToString()
  $created=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/care-groups/$GroupId/encounters" -Headers ($headers+@{'Idempotency-Key'=$idempotency}) -ContentType 'application/json' -Body (@{recordType='VISIT';title='OpenAI synthetic live smoke';occurredOn=(Get-Date).ToString('yyyy-MM-dd')}|ConvertTo-Json)
  $encounterId=$created.data.id
  $version=0;$inputVersion=1
  foreach($upload in @(@{Path=$ImagePath;Endpoint='documents';Field='files'},@{Path=$PdfPath;Endpoint='documents';Field='files'},@{Path=$WavPath;Endpoint='audio';Field='file'})){
    $metadata=@{expectedVersion=$version;expectedInputVersion=$inputVersion}|ConvertTo-Json -Compress
    $form=@{metadata=$metadata};$form[$upload.Field]=Get-Item -LiteralPath $upload.Path
    $response=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/encounters/$encounterId/$($upload.Endpoint)" -Headers ($headers+@{'Idempotency-Key'=[Guid]::NewGuid().ToString()}) -Form $form
    $version=$response.data.version;$inputVersion=$response.data.inputVersion
  }
}
$deadline=(Get-Date).AddMinutes(10)
do{$detail=Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/encounters/$encounterId" -Headers $headers;if($detail.data.processingState -in @('FAILED','READY','NEEDS_REVIEW')){break};Start-Sleep -Seconds 2}while((Get-Date)-lt$deadline)
if($detail.data.processingState -eq 'FAILED'){throw "OpenAI processing failed; inspect the safe job error codes for encounter $encounterId"}
if((Get-Date)-ge$deadline){throw "Timed out waiting for encounter processing: $encounterId"}
$review=Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/encounters/$encounterId/review-items?reviewState=ALL" -Headers $headers
if($ConfirmBodyPath){$confirmation=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/encounters/$encounterId/review-items/confirm" -Headers ($headers+@{'Idempotency-Key'=[Guid]::NewGuid().ToString()}) -ContentType 'application/json' -Body (Get-Content -LiteralPath $ConfirmBodyPath -Raw);$confirmation.data|ConvertTo-Json -Depth 12}
$from=[Uri]::EscapeDataString((Get-Date).ToUniversalTime().ToString('o'));$to=[Uri]::EscapeDataString((Get-Date).AddDays(15).ToUniversalTime().ToString('o'))
$tasks=Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/care-groups/$GroupId/tasks?from=$from&to=$to&encounterId=$encounterId" -Headers $headers
@{encounterId=$encounterId;reusedExistingEncounter=$reusedExisting;processingState=$detail.data.processingState;reviewItems=$review.data.items.Count;linkedTasks=$tasks.data.items.Count;confirmationExecuted=[bool]$ConfirmBodyPath}|ConvertTo-Json
