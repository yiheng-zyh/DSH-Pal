using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;

// Persistent PDH queries: a batch read for high-rate totals, separate slow process snapshots.
public sealed class PalPerformanceCounters : IDisposable {
 [StructLayout(LayoutKind.Sequential)] struct Value {public uint Status;public double Number;}
 [StructLayout(LayoutKind.Sequential)] struct Item {public IntPtr Name;public Value Value;}
 [DllImport("pdh.dll",CharSet=CharSet.Unicode)] static extern uint PdhOpenQuery(string source,UIntPtr data,out IntPtr query);
 [DllImport("pdh.dll",CharSet=CharSet.Unicode)] static extern uint PdhAddEnglishCounter(IntPtr query,string path,UIntPtr data,out IntPtr counter);
 [DllImport("pdh.dll")] static extern uint PdhCollectQueryData(IntPtr query);
 [DllImport("pdh.dll",CharSet=CharSet.Unicode)] static extern uint PdhGetFormattedCounterArray(IntPtr counter,uint format,ref uint bytes,out uint count,IntPtr items);
 [DllImport("pdh.dll")] static extern uint PdhCloseQuery(IntPtr query);
 static readonly Regex Luid=new Regex(@"luid_0x[0-9a-f]+_0x[0-9a-f]+",RegexOptions.Compiled|RegexOptions.IgnoreCase);
 static readonly Regex Pid=new Regex(@"^pid_(\d+)_",RegexOptions.Compiled|RegexOptions.IgnoreCase);
 static readonly Regex Engine=new Regex(@"^(pid_\d+)_(luid_0x[0-9a-f]+_0x[0-9a-f]+_phys_\d+_eng_\d+)_",RegexOptions.Compiled|RegexOptions.IgnoreCase);
 IntPtr fast,slow,engines,adapterDedicated,adapterShared,processDedicated,processShared;
 Dictionary<string,double> previous=new Dictionary<string,double>();
 Stopwatch processClock=Stopwatch.StartNew();
 public sealed class Fast {
  public Dictionary<string,double> loads=new Dictionary<string,double>();
  public Dictionary<int,double> processLoads=new Dictionary<int,double>();
  public Dictionary<string,double> dedicated,shared;
  public bool gpuAvailable;
 }
 public sealed class ProcessInfo {
  public int pid;public string identity,name;public double? cpu,gpu,dedicated,shared;public long memory;
 }
 public PalPerformanceCounters() {
  if(PdhOpenQuery(null,UIntPtr.Zero,out fast)!=0)throw new InvalidOperationException("PDH unavailable");
  PdhOpenQuery(null,UIntPtr.Zero,out slow);
  engines=Add(fast,@"\GPU Engine(*)\Utilization Percentage");
  adapterDedicated=Add(fast,@"\GPU Adapter Memory(*)\Dedicated Usage");
  adapterShared=Add(fast,@"\GPU Adapter Memory(*)\Shared Usage");
  processDedicated=Add(slow,@"\GPU Process Memory(*)\Dedicated Usage");
  processShared=Add(slow,@"\GPU Process Memory(*)\Shared Usage");
  PdhCollectQueryData(fast);if(slow!=IntPtr.Zero)PdhCollectQueryData(slow);
 }
 static IntPtr Add(IntPtr q,string path){IntPtr c;return q!=IntPtr.Zero&&PdhAddEnglishCounter(q,path,UIntPtr.Zero,out c)==0?c:IntPtr.Zero;}
 static Dictionary<string,double> Read(IntPtr counter) {
  if(counter==IntPtr.Zero)return null;
  uint bytes=0,count;PdhGetFormattedCounterArray(counter,0x8200,ref bytes,out count,IntPtr.Zero);
  if(bytes==0||bytes>16777216)return null;
  IntPtr buffer=Marshal.AllocHGlobal((int)bytes);
  try {
   if(PdhGetFormattedCounterArray(counter,0x8200,ref bytes,out count,buffer)!=0)return null;
   var values=new Dictionary<string,double>();int stride=Marshal.SizeOf(typeof(Item));
   if(count>20000||count*stride>bytes)return null;
   for(int i=0;i<count;i++){
    Item item=(Item)Marshal.PtrToStructure(IntPtr.Add(buffer,i*stride),typeof(Item));
    if(item.Name==IntPtr.Zero||item.Value.Status>1||Double.IsNaN(item.Value.Number)||Double.IsInfinity(item.Value.Number)||item.Value.Number<0)continue;
    string name=Marshal.PtrToStringUni(item.Name);if(name!=null)values[name]=item.Value.Number;
   }return values.Count==0?null:values;
  } finally {Marshal.FreeHGlobal(buffer);}
 }
 static Dictionary<string,double> Adapters(Dictionary<string,double> input) {
  if(input==null)return null;var values=new Dictionary<string,double>();
  foreach(var row in input){var m=Luid.Match(row.Key);if(!m.Success)continue;double old;values.TryGetValue(m.Value.ToLowerInvariant(),out old);values[m.Value.ToLowerInvariant()]=old+row.Value;}return values.Count==0?null:values;
 }
 static Dictionary<int,double> Processes(Dictionary<string,double> input) {
  if(input==null)return null;var values=new Dictionary<int,double>();
  foreach(var row in input){var m=Pid.Match(row.Key);int id;if(!m.Success||!Int32.TryParse(m.Groups[1].Value,out id))continue;double old;values.TryGetValue(id,out old);values[id]=old+row.Value;}return values.Count==0?null:values;
 }
 public Fast ReadFast() {
  var result=new Fast();if(PdhCollectQueryData(fast)!=0)return result;
  result.dedicated=Adapters(Read(adapterDedicated));result.shared=Adapters(Read(adapterShared));
  var raw=Read(engines);result.gpuAvailable=raw!=null&&raw.Count>0;
  if(raw==null)return result;
  var sums=new Dictionary<string,double>();
  foreach(var row in raw){var match=Engine.Match(row.Key);var pid=Pid.Match(row.Key);if(!match.Success||!pid.Success)continue;
   int id;if(!Int32.TryParse(pid.Groups[1].Value,out id))continue;
   double old;result.processLoads.TryGetValue(id,out old);result.processLoads[id]=Math.Max(old,Math.Min(100,row.Value));
   string key=match.Groups[2].Value.ToLowerInvariant();sums.TryGetValue(key,out old);sums[key]=old+row.Value;
  }
  foreach(var row in sums){string key=Luid.Match(row.Key).Value.ToLowerInvariant();double old;result.loads.TryGetValue(key,out old);result.loads[key]=Math.Max(old,Math.Min(100,row.Value));}
  return result;
 }
 public ProcessInfo[] ReadProcesses(Fast current) {
  Dictionary<int,double> dedicated=null,shared=null;
  if(slow!=IntPtr.Zero&&PdhCollectQueryData(slow)==0){dedicated=Processes(Read(processDedicated));shared=Processes(Read(processShared));}
  var next=new Dictionary<string,double>();var list=new List<ProcessInfo>();
  double delta=processClock.Elapsed.TotalSeconds;processClock.Restart();
  foreach(var p in Process.GetProcesses()){
   using(p){try {
    if(p.Id<=0)continue;
    string identity=p.Id+":"+p.StartTime.ToUniversalTime().Ticks;
    double seconds=p.TotalProcessorTime.TotalSeconds,old;double? cpu=null;
    if(delta>0&&previous.TryGetValue(identity,out old)&&seconds>=old)cpu=Math.Max(0,Math.Min(100,(seconds-old)/delta/Environment.ProcessorCount*100));
    next[identity]=seconds;
    double value;double? gpu=current.gpuAvailable?(current.processLoads.TryGetValue(p.Id,out value)?value:0):(double?)null;
    double? dm=dedicated!=null?(dedicated.TryGetValue(p.Id,out value)?value:0):(double?)null;
    double? sm=shared!=null?(shared.TryGetValue(p.Id,out value)?value:0):(double?)null;
    list.Add(new ProcessInfo{pid=p.Id,identity=identity,name=p.ProcessName,cpu=cpu,gpu=gpu,memory=p.WorkingSet64,dedicated=dm,shared=sm});
   }catch{}}
  }
  previous=next;list.Sort((a,b)=>b.memory.CompareTo(a.memory));if(list.Count>1024)list.RemoveRange(1024,list.Count-1024);
  return list.ToArray();
 }
 public void Dispose(){if(fast!=IntPtr.Zero){PdhCloseQuery(fast);fast=IntPtr.Zero;}if(slow!=IntPtr.Zero){PdhCloseQuery(slow);slow=IntPtr.Zero;}}
}
