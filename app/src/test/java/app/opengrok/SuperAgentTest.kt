package app.opengrok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SuperAgentTest {

    @Test
    fun contextSpillInlineShortAndSpillsLongWithRecall() {
        val dir = Files.createTempDirectory("spill_test").toFile()
        try {
            ContextSpill.initDir(dir)

            val shortRes = ContextSpill.inlineOrSpill("shell", "ok short output")
            assertNull(shortRes.second)
            assertEquals("ok short output", shortRes.first)

            val bigLines = (1..120).joinToString("\n") { i ->
                "line-$i payload-data-abcdefghijklmnopqrstuvwxyz-" + if (i == 67) "TARGET_NEEDLE_67" else "normal"
            }
            val (preview, spillId) = ContextSpill.inlineOrSpill("shell", bigLines)
            assertNotNull(spillId)
            assertTrue(preview.contains(spillId!!))
            assertTrue(preview.contains("recall_spill"))

            val grepHit = ContextSpill.recall(spillId, query = "TARGET_NEEDLE_67")
            assertTrue(grepHit.contains("L67:"))
            assertTrue(grepHit.contains("TARGET_NEEDLE_67"))

            val slice = ContextSpill.recall(spillId, startLine = 10, maxLines = 15)
            assertTrue(slice.contains("L10:"))
            assertTrue(slice.contains("L24:"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ticketPersistenceAndWatchdogCrashRecovery() {
        val dir = Files.createTempDirectory("ticket_test").toFile()
        val f = File(dir, "tickets.json")
        try {
            SuperAgent.initFile(f)
            val t = SuperAgent.createTicket(
                title = "修复 CI 构建",
                goal = "定位编译报错并验证单测通过",
                ownerBotId = "bot_dev",
                source = "webhook",
                sourceKey = "github:ci"
            )
            SuperAgent.updateTicket(t.id, status = TicketStatus.RUNNING, checkpoint = "已定位报错行号", spillId = "sp_12345678")
            assertEquals(TicketStatus.RUNNING, SuperAgent.tickets.first().status)

            // Simulate process restart: reload from disk -> RUNNING ticket auto-recovers to PAUSED!
            SuperAgent.initFile(f)
            assertEquals(1, SuperAgent.tickets.size)
            val reloaded = SuperAgent.tickets.first()
            assertEquals(TicketStatus.PAUSED, reloaded.status)
            assertEquals(listOf("已定位报错行号"), reloaded.checkpoints)
            assertEquals(listOf("sp_12345678"), reloaded.spillIds)

            val resumePrompt = SuperAgent.buildResumePrompt(reloaded, isResume = true)
            assertTrue(resumePrompt.contains("断点续跑"))
            assertTrue(resumePrompt.contains("已定位报错行号"))
            assertTrue(resumePrompt.contains("sp_12345678"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun superRouterFastPathNoiseAndDirectWorkerRouting() {
        val dir = Files.createTempDirectory("router_test").toFile()
        val f = File(dir, "tickets.json")
        try {
            SuperAgent.initFile(f)
            val noise = SuperRouter.routeEvent("sys", "后台保活运行中", "chief", "agent")
            assertEquals(RouteKind.IGNORE_NOISE, noise.kind)

            val t = SuperAgent.createTicket(
                title = "盯紧微信测试群",
                goal = "汇总测试反馈",
                ownerBotId = "worker_1",
                source = "notification",
                sourceKey = "com.tencent.mm:测试"
            )
            SuperAgent.updateTicket(t.id, status = TicketStatus.RUNNING)

            val direct = SuperRouter.routeEvent("com.tencent.mm:测试", "发现一个新闪退", "chief", "agent")
            assertEquals(RouteKind.DIRECT_WORKER, direct.kind)
            assertEquals(t.id, direct.ticketId)
            assertEquals("worker_1", direct.targetBotId)

            val slow = SuperRouter.routeEvent("com.other.app", "全新紧急生产告警", "chief", "agent")
            assertEquals(RouteKind.ESCALATE_ROUTER, slow.kind)
            assertEquals("chief", slow.targetBotId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun watchdogDetectsStalledRunningTicket() {
        val dir = Files.createTempDirectory("wd_test").toFile()
        val f = File(dir, "tickets.json")
        try {
            SuperAgent.initFile(f)
            val t = SuperAgent.createTicket("长任务", "跑全量评测", "worker_a", threadChatId = "bot:worker_a")
            SuperAgent.updateTicket(t.id, status = TicketStatus.RUNNING)
            val future = System.currentTimeMillis() + HiveWatchdog.DEFAULT_STALL_MS + 10_000L
            val flagged = HiveWatchdog.scanOnce(setOf("bot:worker_a"), future)
            assertEquals(1, flagged)
            assertEquals(TicketStatus.PAUSED, SuperAgent.tickets.first().status)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun superRouterCooldownAndKeywordSubscription() {
        val dir = Files.createTempDirectory("router_cd_test").toFile()
        val f = File(dir, "tickets.json")
        try {
            SuperAgent.initFile(f)
            val t = SuperAgent.createTicket(
                title = "监控生产告警",
                goal = "发现 P0 告警立即排查",
                ownerBotId = "ops_bot",
                priority = 1,
                sourceKey = "P0告警"
            )
            SuperAgent.updateTicket(t.id, status = TicketStatus.RUNNING)

            val t0 = 1_000_000L
            val first = SuperRouter.routeEvent("com.slack", "触发 P0告警 数据库延迟飙升", "chief", "agent", nowMs = t0)
            assertEquals(RouteKind.DIRECT_WORKER, first.kind)
            assertEquals("ops_bot", first.targetBotId)

            // Within 30s cooldown: filtered at 0 Token to prevent notification storm
            val storm = SuperRouter.routeEvent("com.slack", "触发 P0告警 重复推送", "chief", "agent", nowMs = t0 + 5_000L)
            assertEquals(RouteKind.IGNORE_NOISE, storm.kind)

            // After 30s cooldown: routes directly to Worker again
            val after = SuperRouter.routeEvent("com.slack", "触发 P0告警 仍未恢复", "chief", "agent", nowMs = t0 + 35_000L)
            assertEquals(RouteKind.DIRECT_WORKER, after.kind)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun hiveBlackboardPersistenceAndFilter() {
        val dir = Files.createTempDirectory("bb_test").toFile()
        val f = File(dir, "blackboard.json")
        try {
            HiveBlackboard.initFile(f)
            HiveBlackboard.write("竞品亮点", "DeepSeek Fast/Slow Path 路由", "调研", "bot_1")
            HiveBlackboard.write("接口地址", "https://api.example.com", "规范", "bot_2")
            assertEquals(2, HiveBlackboard.read().size)
            assertEquals(1, HiveBlackboard.read(tag = "调研").size)

            HiveBlackboard.initFile(f)
            assertEquals(2, HiveBlackboard.read().size)
            HiveBlackboard.remove("接口地址")
            assertEquals(1, HiveBlackboard.read().size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
