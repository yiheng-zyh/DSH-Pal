param([string]$PipeName="")
# On-demand, read-only sampler. No command lines, paths, user names or termination actions.
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false)
$writer=$null
if($PipeName) {
 if($PipeName -notmatch '^DSHPalPerf-[a-f0-9-]{36}$') {throw 'Invalid sampler pipe'}
 # Keep control reads asynchronous so unchanged refresh rates never block sampling.
 $pipe=New-Object System.IO.Pipes.NamedPipeClientStream('.', $PipeName, [IO.Pipes.PipeDirection]::InOut, [IO.Pipes.PipeOptions]::Asynchronous)
 $pipe.Connect(10000)
 $writer=New-Object System.IO.StreamWriter($pipe,(New-Object System.Text.UTF8Encoding($false)))
 $writer.AutoFlush=$true
 $reader=New-Object System.IO.StreamReader($pipe)
 $control=$reader.ReadLineAsync()
}
. (Join-Path $PSScriptRoot "performance-adapters.ps1")
$adapters=@()
try {
Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class PalDxgi {
 [StructLayout(LayoutKind.Sequential)] public struct Luid {public uint Low;public int High;}
 [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] public struct Desc {
  [MarshalAs(UnmanagedType.ByValTStr,SizeConst=128)] public string Name;
  public uint Vendor,Device,Sub,Revision; public UIntPtr Dedicated,System,Shared;public Luid Id;public uint Flags;
 }
 [DllImport("dxgi.dll")] static extern int CreateDXGIFactory1(ref Guid id,out IntPtr factory);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int Enumerate(IntPtr self,uint index,out IntPtr adapter);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int Describe(IntPtr self,out Desc desc);
 public static Desc[] Read() {
  var id=new Guid("770aae78-f26f-4dba-a829-253c83d1b387"); IntPtr f;
  if(CreateDXGIFactory1(ref id,out f)!=0)return new Desc[0];var all=new List<Desc>();
  try {var v=Marshal.ReadIntPtr(f);var enumerate=(Enumerate)Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(v,12*IntPtr.Size),typeof(Enumerate));
   for(uint i=0;i<32;i++){IntPtr a;if(enumerate(f,i,out a)!=0)break;
    try {var get=(Describe)Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(Marshal.ReadIntPtr(a),10*IntPtr.Size),typeof(Describe));Desc d;if(get(a,out d)==0&&(d.Flags&2)==0)all.Add(d);}finally{Marshal.Release(a);}}
  } finally{Marshal.Release(f);} return all.ToArray();
 }
}
"@
$adapters=@([PalDxgi]::Read() | ForEach-Object {
 @{key=('luid_0x{0:x8}_0x{1:x8}' -f ([uint32]([int64]$_.Id.High -band 0xffffffff)), $_.Id.Low);name=$_.Name;dedicatedTotal=($_.Dedicated.ToUInt64()+$_.System.ToUInt64());sharedTotal=$_.Shared.ToUInt64()}
})
} catch {}
Add-Type -Path (Join-Path $PSScriptRoot 'performance-counters.cs')
$counters=New-Object PalPerformanceCounters
$intervalMs=1000
$lastProcesses=-10000L
$elapsed=[Diagnostics.Stopwatch]::StartNew()
$processes=@();$processTime=0L;$processMs=0L
try {
 while($true) {
  $watch=[Diagnostics.Stopwatch]::StartNew()
  if($writer -and $control.IsCompleted) {
   $command=$control.GetAwaiter().GetResult()
   if($null -eq $command){break}
   if($command -in @('500','1000','2000')){$intervalMs=[int]$command}
   $control=$reader.ReadLineAsync()
  }
  $stamp=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  $fast=$counters.ReadFast()
  $includeProcesses=$elapsed.ElapsedMilliseconds-$lastProcesses -ge 3000
  if($includeProcesses) {
   $processWatch=[Diagnostics.Stopwatch]::StartNew()
   $processes=@($counters.ReadProcesses($fast));$processTime=$stamp;$lastProcesses=$elapsed.ElapsedMilliseconds
   $processMs=$processWatch.ElapsedMilliseconds
  }
  $gpus=@(Get-PalGpuSnapshot -Adapters $adapters -Fast $fast)
  $result=@{diskActive=$fast.diskActive;diskRead=$fast.diskRead;diskWrite=$fast.diskWrite;time=$stamp;gpus=$gpus;processTime=$processTime;sampleMs=$watch.Elapsed.TotalMilliseconds;processMs=$processMs;intervalMs=$intervalMs;
    samplerCpuMs=[Diagnostics.Process]::GetCurrentProcess().TotalProcessorTime.TotalMilliseconds;
    gpuStatus=$(if($fast.gpuAvailable){'ok'}else{'driver-unavailable'})}
  if($includeProcesses){$result.processes=$processes;$result.processCount=$processes.Count}
  $line=$result | ConvertTo-Json -Depth 7 -Compress
  if($writer){$writer.WriteLine($line)}else{[Console]::WriteLine($line)}
  Start-Sleep -Milliseconds ([Math]::Max(20,$intervalMs-$watch.ElapsedMilliseconds))
 }
} finally {$counters.Dispose();if($writer){$writer.Dispose();$pipe.Dispose()}}
