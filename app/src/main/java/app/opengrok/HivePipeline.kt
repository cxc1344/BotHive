package app.opengrok

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class StageStatus(val label: String) {
    PENDING("待接力"),
    RUNNING("执行中"),
    DONE("已完成"),
    FAILED("异常")
}

data class PipelineStage(
    val index: Int,
    val title: String,
    val botId: String,
    val instruction: String,
    val status: StageStatus = StageStatus.PENDING,
    val outputSummary: String = ""
)

data class HiveDagPipeline(
    val id: String,
    val title: String,
    val goal: String,
    val stages: List<PipelineStage>,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class PipelineTemplate(
    val name: String,
    val subtitle: String,
    val stageTitles: List<String>,
    val stageInstructions: List<String>
)

/**
 * v0.7.2 Super-Agent DAG Auto-Relay Pipeline Engine ("接力流水线").
 *
 * Chains multiple Worker Bots sequentially: when Stage i finishes, its output is automatically
 * written to [HiveBlackboard] and handed off as upstream context to wake Stage i+1's Worker Bot.
 */
object HivePipeline {
    private val main = Handler(Looper.getMainLooper())
    private var file: File? = null
    val pipelines = mutableStateListOf<HiveDagPipeline>()

    val TEMPLATES = listOf(
        PipelineTemplate(
            name = "深度调研三段式",
            subtitle = "资料检索 → 交叉比对 → 结论交付",
            stageTitles = listOf("资料检索", "交叉比对", "结论交付"),
            stageInstructions = listOf(
                "搜集核心事实、关键数据与最新进展，整理成精炼要点。",
                "对上游检索结果做交叉校验、优劣对比与风险拆解。",
                "综合前两棒产出，输出最终可执行结论与下一步建议。"
            )
        ),
        PipelineTemplate(
            name = "代码重构流水线",
            subtitle = "架构审计 → 增量改造 → 自测核验",
            stageTitles = listOf("架构审计", "增量改造", "自测核验"),
            stageInstructions = listOf(
                "审查目标模块结构，列出主要隐患与最小改造方案。",
                "按上游审计清单实施清晰、低风险的增量改造。",
                "核对边界条件、补齐验证清单并输出变更摘要。"
            )
        ),
        PipelineTemplate(
            name = "出行 / 研学全案",
            subtitle = "攻略采集 → 路线编排 → 行前清单",
            stageTitles = listOf("攻略采集", "路线编排", "行前清单"),
            stageInstructions = listOf(
                "收集目的地交通、核心点位与避坑提示。",
                "按时间动线编排松紧合适的每日行程。",
                "整理必备物品、预算估算与应急备选方案。"
            )
        ),
        PipelineTemplate(
            name = "多视角决策链",
            subtitle = "正方立论 → 反方拆台 → 裁判拍板",
            stageTitles = listOf("正方立论", "反方拆台", "裁判拍板"),
            stageInstructions = listOf(
                "全力论证该方案的最大收益、可行性与最佳切入点。",
                "从成本、执行风险与盲区角度尖锐拆台并提出反例。",
                "权衡正反两方论点，给出最终拍板建议与执行护栏。"
            )
        )
    )

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "hive_pipelines.json")
        load()
    }

    private fun load() {
        val f = file ?: return
        if (!f.isFile) return
        runCatching {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<HiveDagPipeline>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sArr = o.optJSONArray("stages") ?: JSONArray()
                val stages = mutableListOf<PipelineStage>()
                for (j in 0 until sArr.length()) {
                    val so = sArr.optJSONObject(j) ?: continue
                    stages.add(
                        PipelineStage(
                            index = so.optInt("index", j),
                            title = so.optString("title", "阶段 ${j + 1}"),
                            botId = so.optString("botId", AGENT_ID),
                            instruction = so.optString("instruction", ""),
                            status = StageStatus.entries.firstOrNull { it.name == so.optString("status") } ?: StageStatus.PENDING,
                            outputSummary = so.optString("outputSummary", "")
                        )
                    )
                }
                out.add(
                    HiveDagPipeline(
                        id = o.optString("id", "pl_" + UUID.randomUUID().toString().take(6)),
                        title = o.optString("title", "流水线"),
                        goal = o.optString("goal", ""),
                        stages = stages,
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
            onMain { pipelines.clear(); pipelines.addAll(out) }
        }
    }

    private fun persist(list: List<HiveDagPipeline>) {
        val f = file ?: return
        runCatching {
            val arr = JSONArray()
            for (p in list) {
                val sArr = JSONArray()
                for (s in p.stages) {
                    sArr.put(
                        JSONObject()
                            .put("index", s.index)
                            .put("title", s.title)
                            .put("botId", s.botId)
                            .put("instruction", s.instruction)
                            .put("status", s.status.name)
                            .put("outputSummary", s.outputSummary)
                    )
                }
                arr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("title", p.title)
                        .put("goal", p.goal)
                        .put("stages", sArr)
                        .put("createdAt", p.createdAt)
                        .put("updatedAt", p.updatedAt)
                )
            }
            f.writeText(arr.toString(2))
        }
    }

    /** Creates a multi-stage DAG pipeline from [template] and launches Stage 0 immediately. */
    fun launchFromTemplate(store: Store, template: PipelineTemplate, goal: String): HiveDagPipeline {
        val workers = (store.bots.map { it.id } + AGENT_ID).distinct()
        val id = "pl_" + UUID.randomUUID().toString().take(6)
        val stages = template.stageTitles.mapIndexed { idx, stTitle ->
            PipelineStage(
                index = idx,
                title = stTitle,
                botId = workers[idx % workers.size],
                instruction = template.stageInstructions.getOrElse(idx) { "推进本阶段任务。" },
                status = if (idx == 0) StageStatus.RUNNING else StageStatus.PENDING
            )
        }
        val pipe = HiveDagPipeline(id = id, title = "${template.name}：${goal.take(18)}", goal = goal.trim(), stages = stages)
        val snap = listOf(pipe) + pipelines.toList()
        onMain { pipelines.add(0, pipe) }
        persist(snap)
        dispatchStage(store, pipe, stages.first(), upstreamSummary = "")
        return pipe
    }

    /** Marks the active stage DONE, writes output to Blackboard, and automatically wakes the next Worker! */
    fun advanceStage(store: Store, pipelineId: String, summary: String): String {
        val cur = pipelines.firstOrNull { it.id == pipelineId } ?: return "未找到流水线 $pipelineId。"
        val runningIdx = cur.stages.indexOfFirst { it.status == StageStatus.RUNNING }
            .takeIf { it >= 0 } ?: cur.stages.indexOfFirst { it.status == StageStatus.PENDING }
        if (runningIdx < 0) return "流水线 ${cur.id} 全部阶段均已完成。"

        val cleanSummary = summary.trim().ifBlank { "已完成第 ${runningIdx + 1} 棒任务" }
        val finishedStage = cur.stages[runningIdx]
        HiveBlackboard.write(
            key = "${cur.id}_stage_${runningIdx + 1}_${finishedStage.title}",
            value = cleanSummary,
            tag = "流水线接力",
            authorBotId = finishedStage.botId
        )

        val nextIdx = runningIdx + 1
        val newStages = cur.stages.mapIndexed { i, s ->
            when {
                i == runningIdx -> s.copy(status = StageStatus.DONE, outputSummary = cleanSummary)
                i == nextIdx -> s.copy(status = StageStatus.RUNNING)
                else -> s
            }
        }
        val updated = cur.copy(stages = newStages, updatedAt = System.currentTimeMillis())
        val snap = pipelines.map { if (it.id == pipelineId) updated else it }
        onMain {
            val pos = pipelines.indexOfFirst { it.id == pipelineId }
            if (pos >= 0) pipelines[pos] = updated
        }
        persist(snap)

        return if (nextIdx < newStages.size) {
            val nextStage = newStages[nextIdx]
            dispatchStage(store, updated, nextStage, upstreamSummary = cleanSummary)
            "流水线 ${updated.id} 已完成「${finishedStage.title}」，并自动接力唤醒下一棒「${nextStage.title}」(${store.botName(nextStage.botId)})！"
        } else {
            "流水线 ${updated.id} 全部 ${newStages.size} 棒均已完成并写入共享黑板！"
        }
    }

    fun delete(id: String) {
        val snap = pipelines.filterNot { it.id == id }
        onMain { pipelines.removeAll { it.id == id } }
        persist(snap)
    }

    private fun dispatchStage(store: Store, pipe: HiveDagPipeline, stage: PipelineStage, upstreamSummary: String) {
        val chatId = if (stage.botId == AGENT_ID) AGENT_ID else "bot:${stage.botId}"
        val pipeTicket = SuperAgent.createTicket(
            title = "[接力 ${stage.index + 1}/${pipe.stages.size}] ${stage.title}",
            goal = pipe.goal,
            ownerBotId = stage.botId,
            priority = 1,
            source = "pipeline",
            threadChatId = chatId
        )
        SuperAgent.updateTicket(pipeTicket.id, checkpoint = "流水线 ${pipe.id} 自动派发")
        val prompt = buildString {
            append("【蜂巢 DAG 自动接力 · 流水线 ${pipe.id}】\n")
            append("总目标：${pipe.goal}\n")
            append("当前棒次：第 ${stage.index + 1}/${pipe.stages.size} 棒「${stage.title}」\n")
            append("本棒职责：${stage.instruction}\n")
            if (upstreamSummary.isNotBlank()) {
                append("上一棒交接摘要（已同步至共享黑板）：\n$upstreamSummary\n")
            }
            append("完成后请调用 pipeline_advance(id=\"${pipe.id}\", summary=\"你的精炼产出\") 自动唤醒下一棒 Worker。")
        }
        if (stage.botId == AGENT_ID) store.sendAgent(prompt) else store.send(chatId, prompt)
    }
}
