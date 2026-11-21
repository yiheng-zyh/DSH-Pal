package com.dsh.mobile.core
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class ComputerPerformanceTest {
 private fun sample(cpu:String="12",load:String="null",identity:String="42:1")=ComputerSample.parse(JSONObject("""{"schema":1,"time":1000,"instance":"one","cpu":$cpu,"memoryUsed":100,"memoryTotal":1000,"gpus":[{"id":"a","name":"GPU","load":$load,"dedicatedUsed":400,"dedicatedTotal":800}],"processes":[{"identity":"$identity","pid":42,"name":"model","cpu":null,"gpu":$load,"memory":80,"dedicated":300}]}"""))
 @Test fun unavailableMetricsStayUnknown(){val s=sample();assertNull(s.gpus[0].load);assertNull(s.processes[0].cpu);assertEquals(400.0,computerValue(s,ComputerMetric.VRAM,"a")!!,0.01)}
 @Test fun missingAdapterDoesNotBorrowAnotherGpu(){assertNull(computerValue(sample(),ComputerMetric.VRAM,"missing"))}
 @Test fun processMetricsUseIdentityRatherThanRecycledPid(){val s=sample();assertNull(computerValue(s,ComputerMetric.VRAM,"a","42:2"));assertEquals(300.0,computerValue(s,ComputerMetric.VRAM,"a","42:1")!!,0.01)}
 @Test fun outOfRangePercentagesAreNotDisplayedAsValidReadings(){assertNull(sample(cpu="101").cpu);assertNull(sample(load="-1").gpus[0].load)}
 @Test fun memoryAndGpuMetricsHaveSeparateScales(){val s=sample(load="35");assertEquals(100.0,computerValue(s,ComputerMetric.RAM,"a")!!,0.01);assertEquals(35.0,computerValue(s,ComputerMetric.GPU,"a")!!,0.01)}
 @Test(expected=IllegalArgumentException::class) fun oldGatewayDataIsRejected(){ComputerSample.parse(JSONObject("""{"items":[],"time":1000}"""))}
 @Test fun sharedMemoryOnIntegratedGpuHasItsOwnScale(){
  val s=sample().copy(gpus=listOf(ComputerGpu("intel","Intel",null,0.0,0.0,700.0,8000.0)))
  assertEquals(700.0,computerValue(s,ComputerMetric.SHARED,"intel")!!,.01)
  assertEquals(0.0,computerValue(s,ComputerMetric.VRAM,"intel")!!,.01)
  assertNull(computerGpu(s,"removed"));assertNull(computerValue(s,ComputerMetric.SHARED,"removed"))
 }
 @Test fun hybridAdaptersDoNotMixValuesOrFollowListOrder(){
  val a=ComputerGpu("intel","Intel",5.0,0.0,0.0,700.0,8000.0)
  val b=ComputerGpu("amd","AMD",70.0,2000.0,16000.0,20.0,8000.0)
  val s=sample().copy(gpus=listOf(a,b))
  assertEquals(70.0,computerValue(s,ComputerMetric.GPU,"amd")!!,.01)
  assertEquals(2000.0,computerValue(s.copy(gpus=listOf(b,a)),ComputerMetric.VRAM,"amd")!!,.01)
  assertEquals(700.0,computerValue(s,ComputerMetric.SHARED,"intel")!!,.01)
 }
 @Test fun noGpuStillSupportsCpuAndRamWithoutInventingGpuData(){
  val s=sample().copy(gpus=emptyList())
  assertNotNull(computerValue(s,ComputerMetric.CPU,null));assertNotNull(computerValue(s,ComputerMetric.RAM,null))
  assertNull(computerValue(s,ComputerMetric.GPU,null));assertNull(computerValue(s,ComputerMetric.SHARED,null))
 }
 @Test fun processSharedMemoryIsExplicitlyAnAggregate(){
  val s=sample().copy(processes=listOf(sample().processes.first().copy(shared=99.0)))
  assertEquals(99.0,computerValue(s,ComputerMetric.SHARED,"a","42:1")!!,.01)
 }
}
