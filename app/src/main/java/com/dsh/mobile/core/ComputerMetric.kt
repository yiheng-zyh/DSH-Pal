package com.dsh.mobile.core

enum class ComputerMetric(val title:String) { CPU("CPU"),GPU("GPU"),RAM("内存"),VRAM("显存"),SHARED("共享内存") }
fun computerValue(sample:ComputerSample,metric:ComputerMetric,gpuId:String?,process:String?=null):Double? {
    if(process!=null) {
        val p=sample.processes.find{it.identity==process} ?: return null
        return when(metric){ComputerMetric.CPU->p.cpu;ComputerMetric.GPU->p.gpu;ComputerMetric.RAM->p.memory;ComputerMetric.VRAM->p.dedicated;ComputerMetric.SHARED->p.shared}
    }
    val gpu=computerGpu(sample,gpuId)
    return when(metric){ComputerMetric.CPU->sample.cpu;ComputerMetric.GPU->gpu?.load;ComputerMetric.RAM->sample.memory;ComputerMetric.VRAM->gpu?.used;ComputerMetric.SHARED->gpu?.shared}
}
fun computerGpu(sample:ComputerSample?,gpuId:String?):ComputerGpu? = if(gpuId==null)sample?.gpus?.firstOrNull() else sample?.gpus?.find{it.id==gpuId}
object ComputerMonitorNavigation {
    private val pending=kotlinx.coroutines.flow.MutableStateFlow(false)
    val requested=pending as kotlinx.coroutines.flow.StateFlow<Boolean>
    fun open(){pending.value=true}
    fun consume(){pending.value=false}
}
