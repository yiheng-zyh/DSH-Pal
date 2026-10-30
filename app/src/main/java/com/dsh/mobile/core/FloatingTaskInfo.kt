package com.dsh.mobile.core

/** Bounded public information only, shared with notification projection. */
object FloatingTaskInfo {
    data class Info(val heading: String, val body: String)
    fun capsule(task: TaskState, detail: Int, showTitle: Boolean): Info {
        val original=project(task,detail,showTitle)
        if(task.phase==TaskState.Phase.Idle) return Info("等你开工",if(showTitle && task.title.isNotBlank())task.title.take(80) else "任务开始后显示实时进度")
        if(detail==0) return original
        if(task.phase==TaskState.Phase.Waiting) return Info(task.approval?.prompt?.take(160) ?: original.heading,"等待你的确认" + if(showTitle && task.title.isNotBlank()) " · ${task.title.take(80)}" else "")
        if(task.phase==TaskState.Phase.Done || task.phase==TaskState.Phase.Failed) return original
        val action=TaskNotice.compact(task)
        val primary=action.substringAfter(" · ",action).take(160)
        val phase=if(action.contains(" · ")) action.substringBefore(" · ") else original.heading
        val progress=when {
            task.todosTotal>0 -> "事项 ${task.todosDone.coerceIn(0,task.todosTotal)}/${task.todosTotal}"
            task.step>0 -> if(task.stepTotal>0) "步骤 ${task.step}/${task.stepTotal}" else "第 ${task.step} 步"
            else -> ""
        }
        val secondary=listOf(phase,progress,if(showTitle)task.title.take(80) else "").filter {it.isNotBlank()}.joinToString(" · ")
        val history=if(detail==2) TaskNotice.visibleLines(task).map {it.text}.filter {it!=action}.takeLast(2) else emptyList()
        return Info(primary,(listOf(secondary)+history).joinToString("\n").take(480))
    }
    fun project(task: TaskState, detail: Int, showTitle: Boolean): Info {
        val phase = when(task.phase) {
            TaskState.Phase.Idle -> "吃口饭，等你开工"
            TaskState.Phase.Thinking -> "正在思考"
            TaskState.Phase.Running -> "正在执行"
            TaskState.Phase.Waiting -> "需要你确认"
            TaskState.Phase.Done -> "任务完成啦"
            TaskState.Phase.Failed -> "任务遇到问题"
        }
        val steps = when {
            task.todosTotal > 0 -> "事项 ${task.todosDone.coerceIn(0, task.todosTotal)}/${task.todosTotal}"
            task.step > 0 -> if(task.stepTotal > 0) "步骤 ${task.step}/${task.stepTotal}" else "第 ${task.step} 步"
            else -> ""
        }
        val lines = mutableListOf<String>()
        if(showTitle && task.title.isNotBlank()) lines += task.title.take(80)
        if(steps.isNotEmpty()) lines += steps
        if(detail > 0 && task.phase != TaskState.Phase.Idle) {
            val public = if(detail == 1) listOf(TaskState.NoticeLine(TaskNotice.compact(task))) else TaskNotice.visibleLines(task)
            lines += (if(detail == 1) public.take(1) else public.take(4)).map { it.text.take(160) }
        }
        if(lines.isEmpty()) lines += if(task.phase == TaskState.Phase.Idle) "任务开始后在这里看进度" else "双击角色查看对话"
        return Info(phase, lines.distinct().joinToString("\n").take(720))
    }
}
