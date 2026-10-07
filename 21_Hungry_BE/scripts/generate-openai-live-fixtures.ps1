param([string]$OutputDirectory=(Join-Path $PSScriptRoot '..\tmp\openai-live-fixtures'),[switch]$SkipAudio)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Speech
Add-Type -AssemblyName System.Drawing

$root=[IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($root)|Out-Null

function New-Page([string[]]$Lines,[string]$Path) {
  $bitmap=New-Object Drawing.Bitmap 1200,1700
  $graphics=[Drawing.Graphics]::FromImage($bitmap)
  try {
    $graphics.Clear([Drawing.Color]::White)
    $title=[Drawing.Font]::new('Malgun Gothic',[single]52,[Drawing.FontStyle]::Bold,[Drawing.GraphicsUnit]::Pixel)
    $body=[Drawing.Font]::new('Malgun Gothic',[single]36,[Drawing.FontStyle]::Regular,[Drawing.GraphicsUnit]::Pixel)
    try {
      $graphics.DrawString($Lines[0],$title,[Drawing.Brushes]::Black,70,80)
      for($i=1;$i -lt $Lines.Count;$i++){$graphics.DrawString($Lines[$i],$body,[Drawing.Brushes]::Black,70,160+($i*85))}
      $bitmap.Save($Path,[Drawing.Imaging.ImageFormat]::Jpeg)
    } finally {$title.Dispose();$body.Dispose()}
  } finally {$graphics.Dispose();$bitmap.Dispose()}
}

function Write-Ascii($Stream,[string]$Value){$bytes=[Text.Encoding]::ASCII.GetBytes($Value);$Stream.Write($bytes,0,$bytes.Length)}
function U([string]$Value){return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Value))}
function New-Pdf([string[]]$Images,[string]$Path) {
  $stream=New-Object IO.MemoryStream
  $offsets=New-Object 'System.Collections.Generic.List[long]'
  $offsets.Add(0)
  try {
    Write-Ascii $stream "%PDF-1.4`n"
    $objects=@()
    $objects+=,@{Text='<< /Type /Catalog /Pages 2 0 R >>'}
    $objects+=,@{Text='<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>'}
    for($page=0;$page -lt 2;$page++){
      $imageObject=4+($page*3);$contentObject=5+($page*3);$name='/Im'+($page+1)
      $objects+=,@{Text="<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << $name $imageObject 0 R >> >> /Contents $contentObject 0 R >>"}
      [byte[]]$jpeg=[IO.File]::ReadAllBytes($Images[$page])
      $objects+=,@{Prefix="<< /Type /XObject /Subtype /Image /Width 1200 /Height 1700 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length $($jpeg.Length) >>`nstream`n";Bytes=$jpeg;Suffix="`nendstream"}
      [byte[]]$content=[Text.Encoding]::ASCII.GetBytes("q 595 0 0 842 0 0 cm $name Do Q")
      $objects+=,@{Prefix="<< /Length $($content.Length) >>`nstream`n";Bytes=$content;Suffix="`nendstream"}
    }
    for($i=0;$i -lt $objects.Count;$i++){
      $offsets.Add($stream.Position);Write-Ascii $stream (($i+1).ToString()+" 0 obj`n")
      $object=$objects[$i]
      if($object.Text){Write-Ascii $stream ($object.Text+"`n")}
      else{Write-Ascii $stream $object.Prefix;$stream.Write($object.Bytes,0,$object.Bytes.Length);Write-Ascii $stream ($object.Suffix+"`n")}
      Write-Ascii $stream "endobj`n"
    }
    $xref=$stream.Position;Write-Ascii $stream ("xref`n0 "+($objects.Count+1)+"`n0000000000 65535 f `n")
    for($i=1;$i -lt $offsets.Count;$i++){Write-Ascii $stream ($offsets[$i].ToString('0000000000')+" 00000 n `n")}
    Write-Ascii $stream ("trailer`n<< /Size "+($objects.Count+1)+" /Root 1 0 R >>`nstartxref`n$xref`n%%EOF`n")
    [IO.File]::WriteAllBytes($Path,$stream.ToArray())
  } finally {$stream.Dispose()}
}

$wav=Join-Path $root 'synthetic-ko.wav'
$image=Join-Path $root 'synthetic-rx.jpg'
$page1=Join-Path $root 'synthetic-page-1.jpg'
$page2=Join-Path $root 'synthetic-page-2.jpg'
$pdf=Join-Path $root 'synthetic-pages.pdf'
$task=Join-Path $root 'synthetic-task.jpg'

if(-not $SkipAudio){
  $speech=New-Object System.Speech.Synthesis.SpeechSynthesizer
  try {
    $speech.SelectVoiceByHints([System.Speech.Synthesis.VoiceGender]::NotSet,[System.Speech.Synthesis.VoiceAge]::NotSet,0,[Globalization.CultureInfo]::GetCultureInfo('ko-KR'))
    $speech.SetOutputToWaveFile($wav)
    $speech.Speak((U '7J20IOyekOujjOuKlCDtlbTsu6TthqTsnYQg7JyE7ZWcIOyZhOyghO2VnCDqsIDsg4Eg6riw66Gd7J6F64uI64ukLiDsi5zsm5Qg7Iut7J28IOyYpOyghCDsl7Qg7Iuc7JeQIOqwgOyhsSDtmozsnZgg7KSA67mEIOyekOujjOulvCDssZnqsqgg7KO87IS47JqULg=='))
  } finally {$speech.Dispose()}
}

New-Page @((U '6rCA7IOBIOyymOuwqeyghA=='),(U '7ZmY7J6QOiDtmY3quLjrj5ko6rCA7IOBIOyduOusvCk='),(U '6rCA7IOB7JW97JeQ7J20IDUg67CA66as6re4656o'),(U '7ZWY66OoIDLtmowsIDHtmowgMeyglQ=='),(U '67O17JqpIOq4sOqwhDogMjAyNi0xMC0xMCB+IDIwMjYtMTAtMTI='),(U '67O17JqpIOyLnOqwgTog7Jik7KCEIDjsi5wsIOyYpO2bhCA47Iuc'),(U '7Iuk7KCcIOydmOujjCDrqqnsoIHsnLzroZwg7IKs7Jqp7ZWY7KeAIOuniOyEuOyalC4=')) $image
New-Page @((U '6rCA7IOBIOynhOujjCDsnpDro4wgLSAx7Kq9'),(U '6rCA7IOB7JW97JeQ7J20IDUg67CA66as6re4656o'),(U '7ZWY66OoIDLtmowsIOyYpOyghCA47Iuc7JmAIOyYpO2bhCA47Iuc'),(U '6riw6rCEOiAyMDI2LTEwLTEwIH4gMjAyNi0xMC0xMg=='),(U '7Y6Y7J207KeAIOq3vOqxsCDtmZXsnbgg66y46rWsOiDtjIzrnoAg7Jqw7IKw')) $page1
New-Page @((U '6rCA7IOBIOynhOujjCDsnpDro4wgLSAy7Kq9'),(U '7J2867CYIOyXheustDogMjAyNi0xMC0xMSDsmKTsoIQgMTDsi5w='),(U '6rCA7KGxIO2ajOydmCDspIDruYQg7J6Q66OMIOyxmeq4sOq4sA=='),(U '7KGw6rG0IOyXhuuKlCDrqoXtmZXtlZwg64uo67CcIOyXheustA=='),(U '7Y6Y7J207KeAIOq3vOqxsCDtmZXsnbgg66y46rWsOiDrhbjrnoAg6rOg656Y')) $page2
New-Pdf @($page1,$page2) $pdf
New-Page @((U '6rCA7IOBIOydvOuwmCDsl4XrrLQ='),(U '64Kg7KecOiAyMDI2LTEwLTEx'),(U '7Iuc6rCEOiDsmKTsoIQgMTDsi5w='),(U '7ZWgIOydvDog6rCA7KGxIO2ajOydmCDspIDruYQg7J6Q66OMIOyxmeq4sOq4sA=='),(U '7IaM7JqUIOyLnOqwhDogMzDrtoQ='),(U '67CY67O1OiDsl4bsnYw='),(U '7KGw6rG0IOyXhuuKlCDrqoXtmZXtlZwg64uo67CcIOyXheustA==')) $task

Write-Output "Created synthetic fixtures in $root"
