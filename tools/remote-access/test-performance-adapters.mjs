import test from 'node:test';
import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {fileURLToPath} from 'node:url';
const run=promisify(execFile);
test('GPU inventory keeps inactive integrated, AMD and same-name adapters separate',{skip:process.platform!=='win32',timeout:15000},async()=>{
 const script=`. $env:PAL_ADAPTERS_SCRIPT
 $adapters=@(
 @{key='IGPU';name='Intel Graphics';dedicatedTotal=0;sharedTotal=8000},
 @{key='AMD';name='AMD Radeon';dedicatedTotal=16000;sharedTotal=8000},
 @{key='AMD2';name='AMD Radeon';dedicatedTotal=8000;sharedTotal=8000})
 $fast=@{gpuAvailable=$true;loads=@{amd=32};dedicated=@{amd=900};shared=@{amd=0;igpu=700}}
 $mixed=@(Get-PalGpuSnapshot $adapters $fast)
 $unknown=@(Get-PalGpuSnapshot $adapters @{gpuAvailable=$false;loads=@{};dedicated=$null;shared=$null})
 $empty=@(Get-PalGpuSnapshot @() $fast)
 @{mixed=$mixed;unknown=$unknown;empty=$empty}|ConvertTo-Json -Depth 6 -Compress`;
 const {stdout}=await run('powershell.exe',['-NoProfile','-NonInteractive','-EncodedCommand',Buffer.from(script,'utf16le').toString('base64')],{windowsHide:true,timeout:12000,env:{...process.env,PAL_ADAPTERS_SCRIPT:fileURLToPath(new URL('./performance-adapters.ps1',import.meta.url))}});
 const value=JSON.parse(stdout.replace(/^\uFEFF/,''));
 assert.equal(value.mixed.length,3);assert.equal(value.mixed[0].dedicatedTotal,0);assert.equal(value.mixed[0].sharedUsed,700);assert.equal(value.mixed[0].load,null);
 assert.equal(value.mixed[1].load,32);assert.equal(value.mixed[1].sharedUsed,0);assert.equal(value.mixed[2].dedicatedUsed,null);
 assert.notEqual(value.mixed[1].id,value.mixed[2].id);assert.equal(value.mixed[1].name,value.mixed[2].name);
 assert.ok(value.unknown.every(g=>g.load===null&&g.dedicatedUsed===null&&g.sharedUsed===null));assert.deepEqual(value.empty,[]);
});
