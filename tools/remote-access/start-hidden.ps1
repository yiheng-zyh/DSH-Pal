param([Parameter(Mandatory=$true)][string]$NodePath)
$ErrorActionPreference='Stop'
if(-not [IO.Path]::IsPathRooted($NodePath)){throw 'NodePath must be absolute'}
$launcher=Join-Path $PSScriptRoot 'run.mjs'
# Task Scheduler terminates its PowerShell action but can leave Start-Process
# descendants alive. A non-inherited Job handle makes parent termination close
# the job and terminate only this launcher's complete child process tree.
Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class DshPalChildJob {
  [StructLayout(LayoutKind.Sequential)] struct BasicLimits {
    public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
    public uint LimitFlags;
    public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
    public uint ActiveProcessLimit;
    public UIntPtr Affinity;
    public uint PriorityClass, SchedulingClass;
  }
  [StructLayout(LayoutKind.Sequential)] struct IoCounters {
    public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount;
    public ulong ReadTransferCount, WriteTransferCount, OtherTransferCount;
  }
  [StructLayout(LayoutKind.Sequential)] struct ExtendedLimits {
    public BasicLimits BasicLimitInformation;
    public IoCounters IoInfo;
    public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
  }
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] struct StartupInfo {
    public uint cb; public string lpReserved, lpDesktop, lpTitle;
    public uint dwX,dwY,dwXSize,dwYSize,dwXCountChars,dwYCountChars,dwFillAttribute,dwFlags;
    public ushort wShowWindow,cbReserved2; public IntPtr lpReserved2,hStdInput,hStdOutput,hStdError;
  }
  [StructLayout(LayoutKind.Sequential)] struct ProcessInfo {
    public IntPtr Process,Thread; public uint ProcessId,ThreadId;
  }
  [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr attributes,string name);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job,int kind,ref ExtendedLimits limits,uint length);
  [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool CreateProcess(string application,StringBuilder command,IntPtr pa,IntPtr ta,bool inherit,uint flags,IntPtr environment,string directory,ref StartupInfo startup,out ProcessInfo info);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
  [DllImport("kernel32.dll")] static extern uint ResumeThread(IntPtr thread);
  [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr handle,uint timeout);
  [DllImport("kernel32.dll")] static extern bool GetExitCodeProcess(IntPtr process,out uint code);
  [DllImport("kernel32.dll")] static extern bool TerminateProcess(IntPtr process,uint code);
  [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
  static Exception Failure(string action) { return new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(),action); }
  public static int Run(string executable,string script,string directory) {
    IntPtr job=CreateJobObject(IntPtr.Zero,null);
    if(job==IntPtr.Zero)throw Failure("Create owned job");
    ProcessInfo child=new ProcessInfo();
    try {
      ExtendedLimits limits=new ExtendedLimits();limits.BasicLimitInformation.LimitFlags=0x2000;
      if(!SetInformationJobObject(job,9,ref limits,(uint)Marshal.SizeOf(typeof(ExtendedLimits))))throw Failure("Set owned job lifetime");
      StartupInfo startup=new StartupInfo();startup.cb=(uint)Marshal.SizeOf(typeof(StartupInfo));
      var command=new StringBuilder("\""+executable+"\" \""+script+"\"");
      // Suspended creation prevents any child from escaping before assignment.
      if(!CreateProcess(executable,command,IntPtr.Zero,IntPtr.Zero,false,0x08000004,IntPtr.Zero,directory,ref startup,out child))throw Failure("Create hidden runner");
      if(!AssignProcessToJobObject(job,child.Process)){TerminateProcess(child.Process,1);throw Failure("Assign owned runner");}
      if(ResumeThread(child.Thread)==0xffffffff)throw Failure("Resume owned runner");
      if(WaitForSingleObject(child.Process,0xffffffff)!=0)throw Failure("Wait for owned runner");
      uint code;if(!GetExitCodeProcess(child.Process,out code))throw Failure("Read runner result");
      return unchecked((int)code);
    } finally {
      CloseHandle(job);
      if(child.Thread!=IntPtr.Zero)CloseHandle(child.Thread);
      if(child.Process!=IntPtr.Zero)CloseHandle(child.Process);
    }
  }
}
'@
exit [DshPalChildJob]::Run($NodePath,$launcher,$PSScriptRoot)
