package app.opengrok

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * 总经理负责制:立项 → 规划(用户确认)→ 员工并行执行 → 总经理逐个审查(打回仅返工一次)→ 结项汇报。
 * All state mutations run on the Main dispatcher (store.scope), so no extra locking is needed.
 * Staff results travel through the manager↔staff private thread (dm:*), fully visible to the user.
 */
object Pipeline {
    private val jobs = HashMap<String, Job>()

    fun start(store: Store, manager: Bot, request: String) {
        val pid = UUID.randomUUID().toString().take(8)
        store.projects.add(Project(pid, manager.id, "规划中…", request, Stage.PLANNING, emptyList()))
        store.chat("bot:${manager.id}").add(Msg(role = "project", text = pid, from = manager.id))
        store.save()
        launch(store, pid) { doPlan(store, pid) }
    }

    fun confirm(store: Store, pid: String) {
        if (store.project(pid)?.stage != Stage.AWAIT_CONFIRM) return
        launch(store, pid) { doExecute(store, pid) }
    }

    fun replan(store: Store, pid: String) {
        val p = store.project(pid) ?: return
        if (p.stage != Stage.AWAIT_CONFIRM) return
        store.upd(pid) { it.copy(stage = Stage.PLANNING, assignments = emptyList(), title = "重新规划中…", note = "") }
        launch(store, pid) { doPlan(store, pid, previous = p) }
    }

    fun cancel(store: Store, pid: String) {
        jobs.remove(pid)?.cancel()
        store.upd(pid) { it.copy(stage = Stage.CANCELLED, note = "已被用户终止。") }
    }

    private fun launch(store: Store, pid: String, block: suspend () -> Unit) {
        jobs[pid]?.cancel()
        jobs[pid] = store.scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(store, pid, "出错:${e.message}")
            }
        }
    }

    private fun fail(store: Store, pid: String, why: String) =
        store.upd(pid) { it.copy(stage = Stage.CANCELLED, note = why) }

    internal fun extractJson(s: String): JSONObject? {
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(s.substring(a, b + 1)) } catch (_: Exception) { null }
    }

    private suspend fun doPlan(store: Store, pid: String, previous: Project? = null) {
        val p = store.project(pid) ?: return
        val manager = store.bot(p.managerId) ?: return fail(store, pid, "找不到总经理 Bot。")
        val staff = store.bots.filter { it.id != manager.id && !it.isManager }
        if (staff.isEmpty()) return fail(store, pid, "还没有员工 Bot。请先新建员工 Bot,再立项。")
        val roster = staff.joinToString("\n") { "- ${it.name}:${it.prompt.take(80).replace("\n", " ")}" }
        val sys = manager.prompt + "\n\n你现在要为用户的任务制定分工方案。只输出一个 JSON,不要任何其他文字:" +
            "{\"title\":\"项目名(10字内)\",\"assignments\":[{\"bot\":\"员工名\",\"task\":\"该员工要完成的具体任务,含交付要求\"}]}。" +
            "只能从下列员工中挑选,每人最多一项,不需要的人不要派:\n$roster"
        val user = "用户任务:${p.request}" +
            (if (previous != null) "\n\n(用户不满意上一版方案,请换一种分工思路。上一版:" +
                previous.assignments.joinToString(";") { "${store.botName(it.botId)}→${it.task.take(40)}" } + ")" else "")
        val raw = Agent.once(store, manager, sys, user)
        val j = extractJson(raw) ?: return fail(store, pid, "总经理没有给出可解析的方案,请重新立项。原始输出:${raw.take(200)}")
        val arr = j.optJSONArray("assignments")
        val list = mutableListOf<Assignment>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val b = staff.firstOrNull { it.name == o.optString("bot") } ?: continue
                if (list.any { it.botId == b.id }) continue
                list.add(Assignment(b.id, o.optString("task")))
            }
        }
        if (list.isEmpty()) return fail(store, pid, "方案里没有有效的员工分工,请重新立项。")
        store.upd(pid) {
            it.copy(title = j.optString("title").ifBlank { "未命名项目" }, assignments = list, stage = Stage.AWAIT_CONFIRM)
        }
    }

    private suspend fun runAssignment(store: Store, pid: String, botId: String, feedback: String?) {
        val p = store.project(pid) ?: return
        val a = p.assignments.firstOrNull { it.botId == botId } ?: return
        val staff = store.bot(botId) ?: return
        val dm = store.dmId(p.managerId, botId)
        val text = if (feedback == null)
            "【项目:${p.title}】\n你的任务:${a.task}\n完成后请用文字汇报成果:做了什么、产出文件的路径、关键结论。"
        else
            "【返工】总经理审查意见:$feedback\n请据此修改,并重新汇报成果。"
        store.addDm(dm, Msg(role = "assistant", text = text, from = p.managerId))
        store.setA(pid, botId) { it.copy(status = "running", attempts = it.attempts + 1) }
        val reply = store.runBotIn(dm, staff)
        store.setA(pid, botId) { it.copy(status = "submitted", deliverable = reply) }
    }

    private suspend fun review(store: Store, pid: String, botId: String): Pair<Boolean, String> {
        val p = store.project(pid)!!
        val a = p.assignments.first { it.botId == botId }
        val manager = store.bot(p.managerId)!!
        val sys = manager.prompt + "\n\n你现在以总经理身份审查员工提交的成果。只输出一个 JSON,不要其他文字:" +
            "{\"verdict\":\"pass\"或\"rework\",\"comments\":\"具体、可执行的意见\"}。成果没有达成任务、无法验证或有明显错误时才打回。"
        val raw = Agent.once(store, manager, sys,
            "项目:${p.title}\n员工:${store.botName(botId)}\n任务:${a.task}\n\n员工提交的成果:\n${a.deliverable.take(6000)}")
        val j = extractJson(raw)
            ?: return false to "(审查结果无效，不能自动通过。请检查总经理输出并重新审查。原文:${raw.take(120)})"
        val verdict = j.optString("verdict").trim().lowercase()
        val comments = j.optString("comments").ifBlank { "审查模型未提供具体意见。" }
        return when (verdict) {
            "pass" -> true to comments
            "rework" -> false to comments
            else -> false to "(无法识别的审查结论「${verdict.take(40)}」，按未通过处理。)$comments"
        }
    }

    private fun postReview(store: Store, p: Project, botId: String, pass: Boolean, comments: String) {
        store.addDm(store.dmId(p.managerId, botId),
            Msg(role = "assistant", text = "审查结论:${if (pass) "通过" else "打回返工"}\n$comments", from = p.managerId))
    }

    private suspend fun doExecute(store: Store, pid: String) {
        store.upd(pid) { it.copy(stage = Stage.EXECUTING, note = "") }
        val first = store.project(pid)!!
        coroutineScope {
            first.assignments.map { a -> async { runAssignment(store, pid, a.botId, null) } }.awaitAll()
        }

        store.upd(pid) { it.copy(stage = Stage.REVIEWING) }
        val p = store.project(pid)!!
        for (a in p.assignments) {
            val (pass, comments) = review(store, pid, a.botId)
            postReview(store, p, a.botId, pass, comments)
            if (pass) {
                store.setA(pid, a.botId) { it.copy(status = "approved", review = comments) }
            } else {
                store.setA(pid, a.botId) { it.copy(status = "rework", review = comments) }
                runAssignment(store, pid, a.botId, comments)          // rework happens exactly once
                val (pass2, comments2) = review(store, pid, a.botId)
                postReview(store, p, a.botId, pass2, comments2)
                store.setA(pid, a.botId) { it.copy(status = if (pass2) "approved" else "rejected", review = comments2) }
            }
        }

        store.upd(pid) { it.copy(stage = Stage.REPORTING) }
        val done = store.project(pid)!!
        val manager = store.bot(done.managerId)!!
        val body = done.assignments.joinToString("\n\n") {
            "【${store.botName(it.botId)}】任务:${it.task}\n审查:${statusLabel(it.status)} ${it.review}\n成果摘要:${it.deliverable.take(1500)}"
        }
        val report = Agent.once(store, manager,
            manager.prompt + "\n\n你要向用户做结项汇报:先一句话结论,再逐人说明完成度与审查结果,列出产出位置,最后给出风险或建议。用简洁中文。",
            "项目:${done.title}\n用户原始任务:${done.request}\n\n$body")
        store.upd(pid) { it.copy(stage = Stage.DONE, report = report) }
        store.chat("bot:${done.managerId}").add(
            Msg(role = "assistant", text = "【结项汇报】${done.title}\n$report", from = done.managerId))
        store.save()
    }
}

fun stageLabel(s: Stage) = when (s) {
    Stage.PLANNING -> "规划中"
    Stage.AWAIT_CONFIRM -> "待确认"
    Stage.EXECUTING -> "执行中"
    Stage.REVIEWING -> "审查中"
    Stage.REPORTING -> "汇总中"
    Stage.DONE -> "已结项"
    Stage.CANCELLED -> "已终止"
    Stage.INTERRUPTED -> "已中断"
}

fun statusLabel(s: String) = when (s) {
    "pending" -> "待派工"
    "running" -> "执行中"
    "submitted" -> "已提交"
    "approved" -> "通过"
    "rework" -> "返工中"
    "rejected" -> "未通过"
    else -> s
}
