# Vendor-neutral DXGI inventory; absent PDH counters must not hide an adapter.
function Get-PalGpuSnapshot {
 param($Adapters,$Fast)
 $seen=@{}
 foreach($a in $Adapters) {
  $key=([string]$a.key).ToLowerInvariant()
  if(!$key -or $seen.ContainsKey($key)){continue}
  $seen[$key]=$true
  @{id=$key;name=$a.name;dedicatedTotal=$a.dedicatedTotal;sharedTotal=$a.sharedTotal;
    load=$(if($Fast.gpuAvailable -and $Fast.loads.ContainsKey($key)){$Fast.loads[$key]}else{$null});
    dedicatedUsed=$(if($null -ne $Fast.dedicated -and $Fast.dedicated.ContainsKey($key)){$Fast.dedicated[$key]}else{$null});
    sharedUsed=$(if($null -ne $Fast.shared -and $Fast.shared.ContainsKey($key)){$Fast.shared[$key]}else{$null})}
 }
}
