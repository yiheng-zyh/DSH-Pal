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
}
