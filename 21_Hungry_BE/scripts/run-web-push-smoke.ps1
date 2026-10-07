param(
  [int]$ApiPort=18082,
  [string]$SmokeOrigin='http://localhost:4173',
  [string]$FrontendOrigin='https://knowone-eight.vercel.app',
  [string]$DatabaseUrl='jdbc:postgresql://localhost:54329/hungry_push_smoke',
  [string]$VapidSubject='mailto:js48765348@gmail.com',
  [string]$SecretPath=(Join-Path $PSScriptRoot '..\secrets\vapid.env')
)
$ErrorActionPreference='Stop'

& (Join-Path $PSScriptRoot 'vapid-keys.ps1') -Subject $VapidSubject -OutputPath $SecretPath
$values=@{}
foreach($line in Get-Content -LiteralPath $SecretPath -Encoding utf8){
  if($line -match '^([A-Z0-9_]+)=(.*)$'){$values[$Matches[1]]=$Matches[2]}
}
foreach($name in @('VAPID_PUBLIC_KEY','VAPID_PRIVATE_KEY','VAPID_SUBJECT')){
  if([string]::IsNullOrWhiteSpace($values[$name])){throw "$name is missing from the VAPID secret file"}
}
if($values.VAPID_SUBJECT -ne $VapidSubject){throw 'VAPID subject mismatch'}

$env:VAPID_PUBLIC_KEY=$values.VAPID_PUBLIC_KEY
$env:VAPID_PRIVATE_KEY=$values.VAPID_PRIVATE_KEY
$env:VAPID_SUBJECT=$values.VAPID_SUBJECT
$env:CORS_ALLOWED_ORIGINS="$SmokeOrigin,$FrontendOrigin"
$env:DB_URL=$DatabaseUrl
$env:DB_USERNAME='hungry'
$env:DB_PASSWORD='hungry_local'
$env:SCHEDULING_ENABLED='true'
$env:AI_WORKER_ENABLED='false'
$env:NOTIFICATION_EVENT_WORKER_ENABLED='true'
$env:PUSH_WORKER_ENABLED='true'
$env:NOTIFICATION_WORKER_CONCURRENCY='1'
$env:FILE_STORAGE_ROOT='tmp/web-push-live-storage'

try {
  & (Join-Path $PSScriptRoot '..\gradlew.bat') bootRun --args="--spring.profiles.active=local --server.port=$ApiPort"
} finally {
  foreach($name in @('VAPID_PUBLIC_KEY','VAPID_PRIVATE_KEY','VAPID_SUBJECT')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}
}
