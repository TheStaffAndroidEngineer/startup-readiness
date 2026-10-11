package staff.android.startup.planning

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StartupGraphTest {
    private val analysis: StartupAnalysis =
        if (System.getProperty("startup.solution") == "learner") LearnerStartup else ReferenceStartup
    private fun task(id: String, millis: Long, vararg dependencies: String, outcome: InitOutcome = InitOutcome.Success) =
        StartupTask(id, millis, dependencies.toSet(), outcome)
    private fun endpoint(id: String, vararg required: String) = ReadinessContract(id, required.toSet())
    private val fixture = listOf(
        task("boot", 10), task("records", 30, "boot"), task("policy", 20, "boot"),
        task("list", 5, "records", "policy"), task("map", 50, "records", outcome = InitOutcome.Failure),
        task("search", 80, "records"), task("telemetry", 3000),
    )
    private fun plan() = analysis.plan(fixture, listOf(endpoint("List", "list"), endpoint("Map", "list", "map"), endpoint("Search", "search")))

    @Test fun oneFailedFeatureDoesNotMakeEveryEndpointUnavailable() {
        val result = plan()
        assertEquals(EndpointResult(true, 45, listOf("boot", "records", "list"), emptySet()), result.endpoints["List"])
        assertEquals(EndpointResult(false, null, emptyList(), setOf("map")), result.endpoints["Map"])
        assertEquals(EndpointResult(true, 120, listOf("boot", "records", "search"), emptySet()), result.endpoints["Search"])
    }
    @Test fun optionalTelemetryIsAbsentFromSelectedWork() {
        assertEquals(setOf("boot", "records", "policy", "list", "map", "search"), plan().tasks.keys)
    }
    @Test fun parallelPrerequisitesUseMaximumFinishNotSum() {
        val result = analysis.plan(listOf(task("a", 30), task("b", 80), task("join", 10, "a", "b")), listOf(endpoint("Ready", "join")))
        assertEquals(TaskResult(TaskStatus.Succeeded, 80, 90, emptySet()), result.tasks["join"])
        assertEquals(90L, result.endpoints.getValue("Ready").readyMillis)
    }
    @Test fun aFailedTaskHasACompletionTimeButIsNotReadiness() {
        val result = analysis.plan(listOf(task("index", 40, outcome = InitOutcome.Failure)), listOf(endpoint("Search", "index")))
        assertEquals(TaskResult(TaskStatus.Failed, 0, 40, setOf("index")), result.tasks["index"])
        assertEquals(EndpointResult(false, null, emptyList(), setOf("index")), result.endpoints["Search"])
    }
    @Test fun aBlockedTaskNeverRunsOrClaimsItsOwnDeclaredFailure() {
        val nodes = listOf(task("source", 10, outcome = InitOutcome.Failure), task("child", Long.MAX_VALUE, "source", outcome = InitOutcome.Failure))
        val result = analysis.plan(nodes, listOf(endpoint("Ready", "child")))
        assertEquals(TaskResult(TaskStatus.Blocked, null, null, setOf("source")), result.tasks["child"])
        assertEquals(setOf("source"), result.endpoints.getValue("Ready").failedCauses)
    }
    @Test fun multipleFailedPrerequisitesRemainVisibleWithoutDuplicateCauses() {
        val nodes = listOf(task("a", 5, outcome = InitOutcome.Failure), task("b", 7, outcome = InitOutcome.Failure), task("left", 2, "a"), task("right", 3, "a", "b"))
        val result = analysis.plan(nodes, listOf(endpoint("Ready", "left", "right")))
        assertEquals(setOf("a", "b"), result.endpoints.getValue("Ready").failedCauses)
    }
    @Test fun anOptionalFailureDoesNotPoisonAnotherContract() {
        val result = analysis.plan(listOf(task("rows", 10), task("optional", 1, outcome = InitOutcome.Failure)), listOf(endpoint("List", "rows")))
        assertEquals(EndpointResult(true, 10, listOf("rows"), emptySet()), result.endpoints["List"])
        assertFalse(result.tasks.containsKey("optional"))
    }
    @Test fun aSeparatelyNamedReducedContractCanBeReadyWhileFullOneIsBlocked() {
        val result = analysis.plan(listOf(task("records", 10), task("image", 30, "records", outcome = InitOutcome.Failure)), listOf(endpoint("TextOnly", "records"), endpoint("Illustrated", "records", "image")))
        assertTrue(result.endpoints.getValue("TextOnly").ready)
        assertFalse(result.endpoints.getValue("Illustrated").ready)
        assertEquals(setOf("image"), result.endpoints.getValue("Illustrated").failedCauses)
    }
    @Test fun aChangedSlowBranchMovesOnlyDependentEndpointTimes() {
        val contracts = listOf(endpoint("List", "list"), endpoint("Search", "search"))
        val before = analysis.plan(fixture, contracts)
        val after = analysis.plan(fixture.map { if (it.id == "search") it.copy(estimatedMillis = 20) else it }, contracts)
        assertEquals(before.endpoints["List"], after.endpoints["List"])
        assertEquals(60L, after.endpoints.getValue("Search").readyMillis)
    }
    @Test fun equalFinishTieChoosesImmediatePredecessorId() {
        val nodes = listOf(task("z", 5), task("a", 5), task("join", 0, "z", "a"))
        val result = analysis.plan(nodes, listOf(endpoint("Ready", "join")))
        assertEquals(listOf("a", "join"), result.endpoints.getValue("Ready").criticalPath)
    }
    @Test fun equalEndpointTieChoosesRequiredTaskId() {
        val result = analysis.plan(listOf(task("z", 0), task("a", 0)), listOf(endpoint("Ready", "z", "a")))
        assertEquals(listOf("a"), result.endpoints.getValue("Ready").criticalPath)
        assertEquals(0L, result.endpoints.getValue("Ready").readyMillis)
    }
    @Test fun emptyRequirementIsExplicitlyReadyAtZero() {
        val result = analysis.plan(listOf(task("unused", 99)), listOf(endpoint("Empty")))
        assertEquals(emptyMap<String, TaskResult>(), result.tasks)
        assertEquals(EndpointResult(true, 0, emptyList(), emptySet()), result.endpoints["Empty"])
    }
    @Test fun noContractsSelectNoTasks() {
        assertEquals(StartupPlan(emptyMap(), emptyMap()), analysis.plan(fixture, emptyList()))
    }
    @Test fun declaredCycleIsRejectedEvenWhenNoEndpointSelectsIt() {
        assertThrows(IllegalArgumentException::class.java) {
            analysis.plan(listOf(task("x", 1, "y"), task("y", 1, "x")), emptyList())
        }
    }
    @Test fun selfCycleIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { analysis.plan(listOf(task("a", 1, "a")), listOf(endpoint("Ready", "a"))) }
    }
    @Test fun unknownDependencyIsRejectedGlobally() {
        assertThrows(IllegalArgumentException::class.java) { analysis.plan(listOf(task("a", 1, "missing")), emptyList()) }
    }
    @Test fun unknownRequiredTaskIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { analysis.plan(listOf(task("a", 1)), listOf(endpoint("Ready", "missing"))) }
    }
    @Test fun duplicateTaskAndEndpointIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { analysis.plan(listOf(task("a", 1), task("a", 2)), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { analysis.plan(listOf(task("a", 1)), listOf(endpoint("Ready", "a"), endpoint("Ready", "a"))) }
    }
    @Test fun blankIdsAndNegativeCostsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { analysis.plan(listOf(task(" ", 1)), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { analysis.plan(listOf(task("a", -1)), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { analysis.plan(listOf(task("a", 1)), listOf(endpoint(" ", "a"))) }
    }
    @Test fun exactIdsPreserveWhitespaceAndCase() {
        val result = analysis.plan(listOf(task("A", 1), task(" a ", 2)), listOf(endpoint("Ready", " a ")))
        assertEquals(setOf(" a "), result.tasks.keys)
    }
    @Test fun maximumSingleCostIsValidAndIndependentBranchIsNotAdded() {
        val result = analysis.plan(listOf(task("a", Long.MAX_VALUE), task("b", 1)), listOf(endpoint("Ready", "a", "b")))
        assertEquals(Long.MAX_VALUE, result.endpoints.getValue("Ready").readyMillis)
    }
    @Test fun selectedPathOverflowIsRejected() {
        assertThrows(ArithmeticException::class.java) { analysis.plan(listOf(task("a", Long.MAX_VALUE), task("b", 1, "a")), listOf(endpoint("Ready", "b"))) }
    }
    @Test fun unselectedPathPotentialOverflowIsNotEvaluated() {
        val result = analysis.plan(listOf(task("a", Long.MAX_VALUE), task("b", 1, "a"), task("chosen", 2)), listOf(endpoint("Ready", "chosen")))
        assertEquals(2L, result.endpoints.getValue("Ready").readyMillis)
    }
    @Test fun inputOrderDoesNotChangeThePlan() {
        val contracts = listOf(endpoint("List", "list"), endpoint("Map", "map", "list"), endpoint("Search", "search"))
        assertEquals(analysis.plan(fixture, contracts), analysis.plan(fixture.reversed(), contracts.reversed()))
    }
    @Test fun resultIsDetachedAndUnmodifiable() {
        val dependencies = mutableSetOf("source")
        val required = mutableSetOf("child")
        val result = analysis.plan(listOf(task("source", 1, outcome = InitOutcome.Failure), StartupTask("child", 2, dependencies)), listOf(ReadinessContract("Ready", required)))
        dependencies.clear(); required.clear()
        assertEquals(setOf("source"), result.endpoints.getValue("Ready").failedCauses)
        assertThrows(UnsupportedOperationException::class.java) { (result.tasks as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.endpoints.getValue("Ready").failedCauses as MutableSet).clear() }
    }
    @Test fun acceptedGraphLimitBoundsThisRecursiveExercise() {
        val nodes = (1..100).map { task("n$it", 0) }
        assertEquals(0L, analysis.plan(nodes, listOf(endpoint("Ready", "n100"))).endpoints.getValue("Ready").readyMillis)
        assertThrows(IllegalArgumentException::class.java) { analysis.plan(nodes + task("extra", 0), emptyList()) }
    }
}
