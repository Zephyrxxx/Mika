package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.action.*
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.decision.*
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OperationDecisionTest {
    @Test fun temporaryUncertaintyRetainsThePlanAndResumesAfterPlannerRecovery() = runTest {
        var calls = 0
        val scrollTools = setOf("phone_scroll")
        val port = object : DecisionPort {
            override suspend fun choose(input: ChoiceInput): ChoiceAttempt = error("unused")
            override suspend fun chooseBatch(input: DecisionBatchInput): DecisionBatchAttempt {
                calls++
                return if (calls == 1) DecisionBatchAttempt.Abstain("uncertain", DecisionFailure("direction", 0.52, 0.48, 0.65, 0.1))
                else DecisionBatchAttempt.Pick(mapOf("operation" to "scroll", "direction" to "down"), "jev")
            }
        }
        val actor = OperationCoordinator(port, FastMode.Enabled)
        actor.install(raw(), scrollTools, "task")
        assertEquals("uncertain", actor.propose("task", screen(), scrollTools, emptyMap(), 1).reason)
        assertNotNull(actor.plan); assertEquals("recovering", actor.planState)
        assertFalse(actor.propose("task", screen(), scrollTools, emptyMap(), 2).attempted)
        assertEquals(1, calls)
        val notice = actor.takePlannerNotice()!!
        assertTrue(notice.contains("direction")); assertTrue(notice.contains("0.52")); assertTrue(notice.contains("did not execute"))
        actor.after("phone_observe", true, null, false, byDecision = false)
        assertEquals("phone_scroll", actor.propose("task", screen("fresh observation"), scrollTools, emptyMap(), 3).proposal!!.call.name)
        assertEquals(2, calls); assertNotNull(actor.plan)
    }

    @Test fun repeatedFailuresOnTheSamePageRequestANewPlanWithoutDisablingLaterPhases() = runTest {
        var calls = 0
        val port = object : DecisionPort {
            override suspend fun choose(input: ChoiceInput): ChoiceAttempt = error("unused")
            override suspend fun chooseBatch(input: DecisionBatchInput): DecisionBatchAttempt { calls++; return DecisionBatchAttempt.Abstain("uncertain") }
        }
        val actor = OperationCoordinator(port, FastMode.Enabled)
        actor.install(raw(), setOf("phone_scroll"), "task")
        repeat(3) {
            actor.propose("task", screen(), setOf("phone_scroll"), emptyMap(), it + 1)
            assertNotNull(actor.takePlannerNotice())
        }
        assertEquals(3, calls); assertNull(actor.plan)
        assertFalse(actor.propose("task", screen(), setOf("phone_scroll"), emptyMap(), 4).attempted)
        actor.install(raw(), setOf("phone_scroll"), "task")
        assertTrue(actor.propose("task", screen("different phase"), setOf("phone_scroll"), emptyMap(), 5).attempted)
    }

    @Test fun complexPagesAskOnlyForTheSelectedBranchAfterRouting() = runTest {
        val captured = mutableListOf<DecisionBatchInput>()
        val port = object : DecisionPort {
            override suspend fun choose(input: ChoiceInput): ChoiceAttempt = error("unused")
            override suspend fun chooseBatch(input: DecisionBatchInput): DecisionBatchAttempt {
                captured += input
                return DecisionBatchAttempt.Pick(if (input.stage == "route") mapOf("operation" to "scroll") else mapOf("direction" to "down"), "jev", 200)
            }
        }
        val original = snapshot()
        val large = OperationView.json(original.copy(elements = (1..120).map { i -> original.elements.first().copy(ref = "@item$i", name = "候选条目 $i") }), "phone")!!
        val apps = (1..200).associate { "com.example.app$it" to "应用 $it" }
        val actor = OperationCoordinator(port, FastMode.Enabled)
        actor.install(raw(), offered, "task")
        val attempt = actor.propose("task", large, offered, apps, 1)
        assertEquals("phone_scroll", attempt.proposal!!.call.name)
        assertEquals(listOf("route", "parameters"), captured.map { it.stage })
        assertEquals(setOf("operation"), captured[0].questions.keys)
        assertEquals(setOf("direction"), captured[1].questions.keys)
        assertFalse(captured[1].state.contains("com.example.app"))
        assertFalse(captured[1].state.contains("候选条目"))
        assertTrue(captured[1].state.length < captured[0].state.length / 3)
        assertFalse(captured.any { it.state.contains("stateToken") || it.state.contains("snapshotId") || it.state.contains("private existing") })
        assertEquals(400L, attempt.proposal!!.picked.elapsedMs)
    }

    @Test fun exportLargePageFixturesFromTheActualTwoStageCoordinator() = runTest {
        val captured = mutableListOf<DecisionBatchInput>()
        val port = object : DecisionPort {
            override suspend fun choose(input: ChoiceInput): ChoiceAttempt = error("unused")
            override suspend fun chooseBatch(input: DecisionBatchInput): DecisionBatchAttempt {
                captured += input
                return DecisionBatchAttempt.Pick(if (input.stage == "route") mapOf("operation" to "scroll") else mapOf("direction" to "down"), "jev")
            }
        }
        val original = snapshot()
        val large = OperationView.json(original.copy(elements = (1..120).map { n -> original.elements[1].copy(
            ref = "@e$n", name = "列表项目 $n", centerX = (n % 10) * 100 + 50, centerY = (n / 10) * 75 + 30,
            bounds = listOf((n % 10) * 100, (n / 10) * 75, (n % 10) * 100 + 90, (n / 10) * 75 + 60)) }), "phone")!!
        val goal = "向下滚动继续浏览列表，不要点击项目"
        val actor = OperationCoordinator(port, FastMode.Enabled)
        actor.install("""{"objective":"$goal","doneWhen":"列表底部出现没有更多内容","surface":"phone","actions":["tap","scroll"]}""", offered, goal)
        assertEquals("phone_scroll", actor.propose(goal, large, offered, emptyMap(), 1).proposal!!.call.name)
        assertEquals(2, captured.size)
        val records = captured.map { input -> buildJsonObject {
            put("case", "complex_scroll_${input.stage}"); put("stage", input.stage); put("routeQuestion", input.routeQuestion)
            if (input.stage == "parameters") put("requiresPreviousMatch", true)
            put("expected", JsonObject((if (input.stage == "route") mapOf("operation" to "scroll") else mapOf("direction" to "down")).mapValues { JsonPrimitive(it.value) }))
            put("requiredByChoice", JsonObject(input.requiredByChoice.mapValues { JsonArray(it.value.map(::JsonPrimitive)) }))
            putJsonObject("request") {
                put("model", "jev-latest"); put("state", Json.parseToJsonElement(input.state))
                put("questions", JsonObject(input.questions.mapValues { (_, q) -> buildJsonObject {
                    put("type", "choice"); put("instructions", q.instructions); put("criteria", JsonObject(q.choices.mapValues { JsonPrimitive(it.value) }))
                } }))
            }
        } }
        java.io.File("build/jev-staged-fixtures.json").writeText(JsonArray(records).toString(), Charsets.UTF_8)
    }

    @Test fun scopedPhaseOmitsUnusedChoicesAndUsesPlannedGestureDistance() = runTest {
        val scoped = raw().dropLast(1) + ",\"actions\":[\"scroll\"],\"gestureAmount\":\"small\"}"
        val captured = mutableListOf<DecisionBatchInput>()
        val actor = OperationCoordinator(port(mapOf("operation" to "scroll", "direction" to "down"), captured::add), FastMode.Enabled)
        actor.install(scoped, offered, "task")
        val call = actor.propose("task", screen(), offered, mapOf("com.example.unrelated" to "Unrelated app"), 1).proposal!!.call
        assertEquals(1, Json.parseToJsonElement(call.argsJson).jsonObject.getValue("amount").jsonPrimitive.int)
        assertEquals(setOf("operation", "direction"), captured.single().questions.keys)
        assertEquals(setOf("return_to_planner", "scroll"), captured.single().questions.getValue("operation").choices.keys)
        assertFalse(captured.single().state.contains("Unrelated app"))
        assertTrue(runCatching { OperationPlanning.parse(scoped.replace("\"scroll\"", "\"invented\""), offered) }.isFailure)
    }

    @Test fun releaseGuardAndChangedUserGoalDoNotResumeAnOldPhase() = runTest {
        val actor = OperationCoordinator(port(emptyMap()) { error("must not call") }, FastMode.Enabled)
        actor.install(raw(), offered, "task")
        actor.install("""{"control":"release"}""", offered, "task")
        assertFalse(actor.propose("task", screen(), offered, emptyMap(), 1).attempted)
        actor.install(raw(), offered, "task")
        actor.after("phone_tap", true, true, true)
        assertNull(actor.plan)
        actor.goalChanged("another user request")
        assertNull(actor.takePlannerNotice())
        actor.install(raw(), offered, "task")
        actor.recover("uncertain")
        actor.goalChanged("new user request")
        assertNull(actor.plan); assertNull(actor.takePlannerNotice())
    }

    @Test fun cancellationBetweenRoutingAndParametersNeverProducesAnAction() = runTest {
        var calls = 0
        val port = object : DecisionPort {
            override suspend fun choose(input: ChoiceInput): ChoiceAttempt = error("unused")
            override suspend fun chooseBatch(input: DecisionBatchInput): DecisionBatchAttempt {
                calls++
                if (input.stage == "parameters") throw CancellationException("user cancelled")
                return DecisionBatchAttempt.Pick(mapOf("operation" to "tap"), "jev")
            }
        }
        val original = snapshot()
        val large = OperationView.json(original.copy(elements = (1..120).map { original.elements.first().copy(ref = "@e$it") }), "phone")!!
        val actor = OperationCoordinator(port, FastMode.Enabled)
        actor.install(raw(), offered, "task")
        try { actor.propose("task", large, offered, emptyMap(), 1); fail("cancellation must propagate") }
        catch (_: CancellationException) { }
        assertEquals(2, calls)
    }

    @Test fun selectionRequestsDoNotIncludePreparedCommandBodies() = runTest {
        val rawPlan = raw(prepared = """[{"tool":"phone_system_command","args":{"command":"echo private-payload"},"whenToUse":"diagnostic needed"}]""")
        val actor = OperationCoordinator(port(mapOf("operation" to "prepared", "prepared" to "call_0")) { request ->
            assertFalse(request.state.contains("private-payload"))
            assertFalse(request.questions.toString().contains("private-payload"))
        }, FastMode.Enabled)
        actor.install(rawPlan, offered, "task")
        assertTrue(actor.propose("task", screen(), offered, emptyMap(), 1).proposal!!.call.argsJson.contains("private-payload"))
    }

    @Test fun exportRepresentativeLiveFixturesUsingProductionQuestions() {
        data class Case(val id: String, val goal: String, val op: String, val required: Map<String, String> = emptyMap(), val web: Boolean = false,
                        val actions: Set<String> = emptySet())
        val cases = listOf(
            Case("tap", "点击报告.txt打开文件", "tap", mapOf("target" to "@e1")),
            Case("long_press", "长按报告.txt打开它的菜单", "long_press", mapOf("target" to "@e1")),
            Case("scroll", "向下滚动一屏继续浏览，不要打开任何项目", "scroll", mapOf("direction" to "down")),
            Case("swipe", "手指从屏幕中下方向上划动一次", "swipe", mapOf("direction" to "up")),
            Case("drag", "把报告.txt拖动到目标区域", "drag", mapOf("target" to "@e1", "to_target" to "@e2"), web = true),
            Case("input", "在搜索框填入咖啡，暂不搜索", "type_text", mapOf("field" to "@e3", "text" to "search_query")),
            Case("open_app", "打开日历App", "open_app", mapOf("app" to "com.example.calendar")),
            Case("complete", "保存设置", "return_to_planner"),
            Case("swipe_scoped", "手指从屏幕中下方向上划动一次", "swipe", mapOf("direction" to "up"), actions = setOf("swipe"))
        )
        val records = cases.map { c ->
            val surface = if (c.web) "web" else "phone"
            val phase = OperationPlan(c.goal, if (c.id == "complete") "页面已经显示保存成功" else "这个操作已实际完成", surface,
                mapOf("search_query" to "咖啡"), emptyList(), 32, actions = c.actions)
            val original = snapshot()
            val labels = listOf("报告.txt", "目标区域", "搜索")
            val snap = original.copy(elements = original.elements.mapIndexed { i, e -> e.copy(name = labels[i],
                role = if (i == 0) "TextView" else e.role, states = emptySet()) } +
                if (c.id == "complete") listOf(UiElement("@e4", "TextView", "保存成功", 500, 100, listOf(300, 50, 700, 150), null, false, false, false)) else emptyList())
            val observed = OperationView.json(snap, surface, phase.texts)!!
            val view = if (c.web) JsonObject(observed.filterKeys { it != "currentApp" } +
                mapOf("currentSite" to JsonPrimitive("example.org"), "title" to JsonPrimitive("文件管理"))) else
                JsonObject(observed + ("currentAppName" to JsonPrimitive("文件管理")))
            val q = OperationCoordinator.questions(phase, view, offered, mapOf("com.example.calendar" to "日历", "com.example.mail" to "邮箱"))
            assertFalse("Fixture must match the production combined path", OperationCoordinator.splitNeeded(OperationCoordinator.requestState(c.goal, phase, view, emptyList(), q), q))
            assertTrue(c.id, c.op in q.questions.getValue("operation").choices)
            c.required.forEach { (k, v) -> assertTrue(c.id, v in q.questions.getValue(k).choices) }
            buildJsonObject {
                put("case", c.id); put("expected", JsonObject((c.required + ("operation" to c.op)).mapValues { JsonPrimitive(it.value) }))
                put("requiredByChoice", JsonObject(q.branches.mapValues { JsonArray(it.value.map(::JsonPrimitive)) }))
                putJsonObject("request") {
                    put("model", "jev-latest")
                    put("state", OperationCoordinator.requestState(c.goal, phase, view, emptyList(), q))
                    put("questions", JsonObject(q.questions.mapValues { (_, question) -> buildJsonObject {
                        put("type", "choice"); put("instructions", question.instructions)
                        put("criteria", JsonObject(question.choices.mapValues { JsonPrimitive(it.value) }))
                    } }))
                }
            }
        }
        val target = java.io.File("build/jev-operation-fixtures.json")
        target.parentFile.mkdirs(); target.writeText(JsonArray(records).toString(), Charsets.UTF_8)
    }
    private val offered = setOf("phone_tap", "phone_scroll", "phone_swipe", "phone_back", "phone_type_text", "phone_clear_text",
        "phone_press_key", "phone_gesture", "phone_launch_app", "web_tap", "web_scroll", "web_swipe", "web_back", "web_type_text",
        "web_clear_text", "web_press_key", "web_interact", "phone_system_command")
    private fun raw(surface: String = "phone", prepared: String = "[]", max: Int = 32) =
        """{"objective":"Complete the current app task","doneWhen":"The result is visible","surface":"$surface","texts":{"query":"exact planned value"},"prepared":$prepared,"maxActions":$max}"""
    private fun snapshot(name: String = "自定义菜单", secret: Boolean = false) = ScreenSnapshot("s1", "com.example", listOf(
        UiElement("@e1", "Switch", name, 200, 300, listOf(100, 200, 300, 400), null, true, false, false, states = setOf(UiStates.CHECKED)),
        UiElement("@e2", "TextView", name, 700, 800, listOf(600, 700, 800, 900), null, true, false, false),
        UiElement("@e3", "EditText", "private existing field text", 500, 500, listOf(400, 400, 600, 600), null, true, true, false, secret = secret, placeholder = "搜索")
    ), 1000, 1000)
    private fun screen(name: String = "自定义菜单", surface: String = "phone") = OperationView.json(snapshot(name), surface)!!
    private fun port(values: Map<String, String>, capture: (DecisionBatchInput) -> Unit = {}) = object : DecisionPort {
        override suspend fun choose(input: ChoiceInput): ChoiceAttempt = error("must batch")
        override suspend fun chooseBatch(input: DecisionBatchInput): DecisionBatchAttempt {
            capture(input); return DecisionBatchAttempt.Pick(values, "jev", 250)
        }
    }

    @Test fun observesOrdinaryFormsSwitchesAndDuplicateLabelsWithoutSendingExistingInputs() = runTest {
        val view = screen()
        assertFalse(view.toString().contains("private existing field text"))
        val q = OperationCoordinator.questions(OperationPlanning.parse(raw(), offered), view, offered, emptyMap())
        assertTrue(q.questions.getValue("target").choices.keys.containsAll(listOf("@e1", "@e2")))
        val payload = OperationCoordinator.requestState("task", OperationPlanning.parse(raw(), offered), view, emptyList(), q)
        assertTrue(payload.getValue("screen").toString().contains("checked"))
        assertFalse(q.questions.getValue("target").choices.getValue("@e1").contains("checked"))
        assertTrue(q.questions.getValue("field").choices.containsKey("@e3"))
        val secrets = OperationView.json(snapshot(secret = true), "phone")!!
        assertFalse(secrets.toString().contains("private existing field text"))
        assertFalse(OperationCoordinator.questions(OperationPlanning.parse(raw(), offered), secrets, offered, emptyMap()).questions.containsKey("field"))
        assertNull(OperationView.json(snapshot().copy(protected = true), "phone"))
    }

    @Test fun mapsClicksGesturesInputsAppsAndKeysToExistingTools() = runTest {
        val cases = mapOf(
            "tap" to "phone_tap", "double_tap" to "phone_tap", "long_press" to "phone_swipe", "drag" to "phone_swipe",
            "scroll" to "phone_scroll", "swipe" to "phone_swipe", "type_text" to "phone_type_text", "clear_text" to "phone_clear_text",
            "back" to "phone_back", "press_key" to "phone_press_key", "open_app" to "phone_launch_app", "zoom_in" to "phone_gesture", "zoom_out" to "phone_gesture")
        for ((op, tool) in cases) {
            val values = mapOf("operation" to op, "target" to "@e1", "to_target" to "@e2", "field" to "@e3",
                "text" to "query", "direction" to "up", "amount" to "normal", "key" to "KEYCODE_ENTER", "app" to "com.example.other")
            val actor = OperationCoordinator(port(values), FastMode.Enabled)
            actor.install(raw(), offered, "task")
            val picked = actor.propose("task", screen(), offered, mapOf("com.example.other" to "Other"), 1).proposal!!
            assertEquals(op, tool, picked.call.name)
            assertTrue(picked.planned)
            val args = Json.parseToJsonElement(picked.call.argsJson).jsonObject
            when (op) {
                "tap", "double_tap" -> { assertEquals("@e1", args.getValue("ref").jsonPrimitive.content); assertEquals(if (op == "tap") 1 else 2, args.getValue("clicks").jsonPrimitive.int) }
                "long_press" -> { assertEquals(args["startX"], args["endX"]); assertEquals(args["startY"], args["endY"]); assertEquals(900, args.getValue("durationMs").jsonPrimitive.int) }
                "drag" -> { assertEquals(200, args.getValue("startX").jsonPrimitive.int); assertEquals(700, args.getValue("endX").jsonPrimitive.int) }
                "swipe" -> { assertEquals(700, args.getValue("startY").jsonPrimitive.int); assertEquals(300, args.getValue("endY").jsonPrimitive.int) }
                "type_text" -> { assertEquals("exact planned value", args.getValue("text").jsonPrimitive.content); assertFalse(args.containsKey("pressEnter")) }
                "zoom_in", "zoom_out" -> assertEquals(2, args.getValue("paths").jsonArray.size)
            }
            assertEquals(op != "open_app", picked.stateToken != null)
        }
    }

    @Test fun webDraggingAndHoverUseRefsRatherThanInventedCoordinates() = runTest {
        for (op in listOf("drag", "hover")) {
            val actor = OperationCoordinator(port(mapOf("operation" to op, "target" to "@e1", "to_target" to "@e2")), FastMode.Enabled)
            actor.install(raw("web"), offered, "task")
            val call = actor.propose("task", screen(surface = "web"), offered, emptyMap(), 1).proposal!!.call
            assertEquals("web_interact", call.name)
            val args = Json.parseToJsonElement(call.argsJson).jsonObject
            assertEquals(op, args.getValue("action").jsonPrimitive.content)
            if (op == "drag") assertEquals("@e2", args.getValue("toRef").jsonPrimitive.content)
        }
    }

    @Test fun allowsMoreThanEightChangedScreensButStopsRepeatedUnchangedActions() = runTest {
        val actor = OperationCoordinator(port(mapOf("operation" to "scroll", "direction" to "down", "amount" to "normal")), FastMode.Enabled)
        actor.install(raw(), offered, "task")
        for (i in 1..12) {
            assertNotNull(actor.propose("task", screen("Page $i"), offered, emptyMap(), i).proposal)
            actor.after("phone_scroll", true, true, false)
        }
        assertNotNull(actor.propose("task", screen("same"), offered, emptyMap(), 13).proposal)
        assertNotNull(actor.propose("task", screen("same"), offered, emptyMap(), 14).proposal)
        assertEquals("no_progress", actor.propose("task", screen("same"), offered, emptyMap(), 15).reason)
        assertNull(actor.plan)
    }

    @Test fun preparedBuiltInIsOneShotAndMustBeAvailable() = runTest {
        val prepared = """[{"tool":"phone_system_command","args":{"command":"echo planned"},"whenToUse":"The planned diagnostic is needed"}]"""
        val actor = OperationCoordinator(port(mapOf("operation" to "prepared", "prepared" to "call_0")), FastMode.Enabled)
        actor.install(raw(prepared = prepared), offered, "task")
        assertEquals("phone_system_command", actor.propose("task", screen(), offered, emptyMap(), 1).proposal!!.call.name)
        actor.after("phone_system_command", true, null, false)
        assertNull(actor.propose("task", screen(), offered, emptyMap(), 2).proposal)
        try { OperationPlanning.parse(raw(prepared = prepared), offered - "phone_system_command"); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun invalidTargetsGoalChangesErrorsAndPhaseReviewReturnToPlanner() = runTest {
        val actor = OperationCoordinator(port(mapOf("operation" to "tap", "target" to "@invented")), FastMode.Enabled)
        actor.install(raw(), offered, "task")
        assertNull(actor.propose("task", screen(), offered, emptyMap(), 1).proposal)
        actor.install(raw(), offered, "task")
        assertFalse(actor.propose("new task", screen(), offered, emptyMap(), 2).attempted)
        actor.install(raw(), offered, "task"); actor.after("phone_tap", false, false, false)
        assertNotNull(actor.plan); assertEquals("recovering", actor.planState)
        assertFalse(actor.propose("task", screen(), offered, emptyMap(), 2).attempted)
        assertTrue(actor.takePlannerNotice()!!.contains("action_failed"))
        actor.install(raw(max = 1), offered, "task"); actor.after("phone_tap", true, true, false)
        assertEquals("phase_review", actor.propose("task", screen(), offered, emptyMap(), 3).reason)
    }

    @Test fun cancellationPropagatesAndUnusedFieldsAreNotRequired() = runTest {
        val q = OperationCoordinator.questions(OperationPlanning.parse(raw(), offered), screen(), offered, emptyMap())
        assertEquals(setOf("direction"), q.branches["scroll"])
        assertFalse(q.questions.containsKey("amount"))
        val actor = OperationCoordinator(object : DecisionPort {
            override suspend fun choose(input: ChoiceInput): ChoiceAttempt = error("unused")
            override suspend fun chooseBatch(input: DecisionBatchInput): DecisionBatchAttempt = throw CancellationException()
        }, FastMode.Enabled)
        actor.install(raw(), offered, "task")
        try { actor.propose("task", screen(), offered, emptyMap(), 1); fail() } catch (_: CancellationException) { }
    }

    @Test fun coordinateGestureIsRejectedIfPageChangesDuringExecutionGate() = runTest {
        var changed = false; var swipes = 0
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = this@OperationDecisionTest.snapshot(if (changed) "new screen" else "old screen")
            override suspend fun peek() = snapshot()
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult { swipes++; return ActionResult.Ok(true) }
        }
        val tools = toolDispatcher(screen)
        val view = tools.operationView("phone")!!
        val executor = ActionExecutor(tools, OperationGuards(), null, gate = { changed = true }, recorder = ActionRecorder(RunSink.NONE))
        val result = executor.run(Part.ToolCall("gesture", "phone_swipe", """{"startX":200,"startY":300,"endX":200,"endY":300,"durationMs":900}"""),
            Origin.Decision(1, planned = true, stateToken = view.getValue("stateToken").jsonPrimitive.content))
        assertTrue(result is ActionOutcome.Refused)
        assertEquals(0, swipes)
    }

    @Test fun plannedActorUsesExistingPermissionPolicy() {
        assertEquals(Origin.Model(1).policy.copy(source = "decision"), Origin.Decision(1, planned = true).policy)
        assertFalse(Origin.Decision(1).policy.usesGrant)
    }

    @Test fun preparedCoordinatesStayBoundToThePlannerObservation() = runTest {
        val prepared = """[{"tool":"phone_swipe","args":{"startX":200,"startY":300,"endX":500,"endY":600},"whenToUse":"perform the specified gesture","snapshotId":"s1"}]"""
        assertTrue(runCatching { OperationPlanning.parse(raw(prepared = prepared), offered) }.isFailure)
        val old = screen("old")
        val actor = OperationCoordinator(port(mapOf("operation" to "prepared", "prepared" to "call_0")), FastMode.Enabled)
        actor.install(raw(prepared = prepared), offered, "task") { surface, id ->
            assertEquals("phone", surface); assertEquals("s1", id); old.getValue("stateToken").jsonPrimitive.content
        }
        assertNull(actor.propose("task", screen("new"), offered, emptyMap(), 1).proposal)
    }

    @Test fun filledFieldCanBeVerifiedWithoutExposingItsContents() {
        val view = OperationView.json(snapshot(), "phone", mapOf("query" to "private existing field text"))!!
        val field = view.getValue("elements").jsonArray.map { it.jsonObject }.single { it["ref"] == JsonPrimitive("@e3") }
        assertEquals(JsonArray(listOf(JsonPrimitive("query"))), field["matchesTextValues"])
        assertFalse(view.toString().contains("private existing field text"))
    }

    @Test fun offModeDoesNotAskForOperations() = runTest {
        val actor = OperationCoordinator(port(emptyMap()) { error("must not call") }, FastMode.Off)
        actor.install(raw(), offered, "task")
        assertFalse(actor.propose("task", screen(), offered, emptyMap(), 1).attempted)
    }

    @Test fun invalidReplacementCannotLeaveAnOldPlanRunning() {
        val actor = OperationCoordinator(port(emptyMap()), FastMode.Enabled)
        actor.install(raw(), offered, "old task")
        assertTrue(runCatching { actor.install("{}", offered, "new task") }.isFailure)
        assertNull(actor.plan)
    }

    @Test fun routingSharesCandidateContextAcrossIndependentQuestions() {
        val prepared = """[{"tool":"phone_system_command","args":{"command":"echo planned"},"whenToUse":"inspect the planned diagnostic"}]"""
        val phase = OperationPlanning.parse(raw(prepared = prepared), offered)
        val table = OperationCoordinator.questions(phase, screen(), offered, mapOf("com.example.calendar" to "日历"))
        val state = OperationCoordinator.requestState("task", phase, screen(), emptyList(), table)
        assertEquals("日历", state.getValue("installedApps").jsonObject.getValue("com.example.calendar").jsonPrimitive.content)
        assertEquals("phone_system_command", state.getValue("prepared").jsonObject.getValue("call_0").jsonObject.getValue("tool").jsonPrimitive.content)
        assertFalse(state.getValue("prepared").toString().contains("echo planned"))
    }
}
