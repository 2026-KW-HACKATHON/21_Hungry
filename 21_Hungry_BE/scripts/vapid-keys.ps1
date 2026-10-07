param(
  [Parameter(Mandatory=$true)][ValidatePattern('^(mailto:.+@.+|https://.+)$')][string]$Subject,
  [string]$OutputPath=(Join-Path $PSScriptRoot '..\secrets\vapid.env')
)

$ErrorActionPreference='Stop'

Add-Type -ReferencedAssemblies System.Numerics.dll -TypeDefinition @'
using System;
using System.Linq;
using System.Numerics;
public static class VapidPairValidator {
  private sealed class Point { public BigInteger X,Y; public Point(BigInteger x,BigInteger y){X=x;Y=y;} }
  private static readonly BigInteger P=Parse("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF");
  private static readonly BigInteger A=P-3;
  private static readonly Point G=new Point(Parse("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296"),Parse("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5"));
  private static BigInteger Parse(string hex){var b=Enumerable.Range(0,hex.Length/2).Select(i=>Convert.ToByte(hex.Substring(i*2,2),16)).Reverse().Concat(new byte[]{0}).ToArray();return new BigInteger(b);}
  private static BigInteger FromBigEndian(byte[] value){return new BigInteger(value.Reverse().Concat(new byte[]{0}).ToArray());}
  private static BigInteger M(BigInteger value){value%=P;return value.Sign<0?value+P:value;}
  private static Point Add(Point x,Point y){if(x==null)return y;if(y==null)return x;if(x.X==y.X&&M(x.Y+y.Y)==0)return null;BigInteger slope=x.X==y.X?M((3*x.X*x.X+A)*BigInteger.ModPow(2*x.Y,P-2,P)):M((y.Y-x.Y)*BigInteger.ModPow(M(y.X-x.X),P-2,P));BigInteger rx=M(slope*slope-x.X-y.X);return new Point(rx,M(slope*(x.X-rx)-x.Y));}
  private static Point Multiply(BigInteger scalar){Point result=null,current=G;while(scalar>0){if(!scalar.IsEven)result=Add(result,current);current=Add(current,current);scalar>>=1;}return result;}
  public static bool Matches(byte[] publicKey,byte[] privateKey){var q=Multiply(FromBigEndian(privateKey));return q!=null&&q.X==FromBigEndian(publicKey.Skip(1).Take(32).ToArray())&&q.Y==FromBigEndian(publicKey.Skip(33).Take(32).ToArray());}
}
'@

function ConvertTo-Base64Url([byte[]]$Bytes) {
  return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}

function ConvertFrom-Base64Url([string]$Value) {
  $base64=$Value.Replace('-','+').Replace('_','/')
  switch($base64.Length % 4){2{$base64+='=='}3{$base64+='='}1{throw 'Invalid base64url length'}}
  return [Convert]::FromBase64String($base64)
}

function Read-KeyFile([string]$Path) {
  $values=@{}
  foreach($line in Get-Content -LiteralPath $Path -Encoding utf8){
    if($line -match '^([A-Z0-9_]+)=(.*)$'){$values[$Matches[1]]=$Matches[2]}
  }
  return $values
}

function Assert-KeyPair([string]$PublicKey,[string]$PrivateKey) {
  [byte[]]$public=ConvertFrom-Base64Url $PublicKey
  [byte[]]$private=ConvertFrom-Base64Url $PrivateKey
  if($public.Length -ne 65 -or $public[0] -ne 4){throw 'VAPID public key must be a 65-byte uncompressed P-256 point'}
  if($private.Length -ne 32){throw 'VAPID private key must be a 32-byte P-256 scalar'}
  if(-not [VapidPairValidator]::Matches($public,$private)){throw 'VAPID public/private keys do not match'}
}

$resolved=[IO.Path]::GetFullPath($OutputPath)
if(Test-Path -LiteralPath $resolved){
  $existing=Read-KeyFile $resolved
  Assert-KeyPair $existing.VAPID_PUBLIC_KEY $existing.VAPID_PRIVATE_KEY
  if($existing.VAPID_SUBJECT -ne $Subject){throw 'Existing VAPID subject differs; refusing to overwrite the secret file'}
  Write-Output "Validated existing VAPID key pair at $resolved (not overwritten)."
  exit 0
}

$ecdsa=[Security.Cryptography.ECDsa]::Create([Security.Cryptography.ECCurve+NamedCurves]::nistP256)
try{
  $parameters=$ecdsa.ExportParameters($true)
  [byte[]]$point=@(4)+$parameters.Q.X+$parameters.Q.Y
  $publicKey=ConvertTo-Base64Url $point
  $privateKey=ConvertTo-Base64Url $parameters.D
  Assert-KeyPair $publicKey $privateKey
  $directory=Split-Path -Parent $resolved
  [IO.Directory]::CreateDirectory($directory)|Out-Null
  $content=@("VAPID_PUBLIC_KEY=$publicKey","VAPID_PRIVATE_KEY=$privateKey","VAPID_SUBJECT=$Subject") -join [Environment]::NewLine
  [IO.File]::WriteAllText($resolved,$content+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
  if($IsLinux -or $IsMacOS){& chmod 600 $resolved}
  Write-Output "Created and validated a VAPID P-256 key pair at $resolved. The private key was not printed."
}finally{$ecdsa.Dispose()}
