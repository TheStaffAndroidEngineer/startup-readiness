package staff.android.startup.planning

import java.util.Collections

enum class InitOutcome { Success, Failure }
data class StartupTask(
    val id: String,
    val estimatedMillis: Long,
    val prerequisites: Set<String>,
    val outcome: InitOutcome = InitOutcome.Success,
)
data class ReadinessContract(val id: String, val required: Set<String>)
enum class TaskStatus { Succeeded, Failed, Blocked }
data class TaskResult(
    val status: TaskStatus,
    val startMillis: Long?,
    val finishMillis: Long?,
    val failedCauses: Set<String>,
)
data class EndpointResult(
    val ready: Boolean,
    val readyMillis: Long?,
    val criticalPath: List<String>,
    val failedCauses: Set<String>,
)
data class StartupPlan(
    val tasks: Map<String, TaskResult>,
    val endpoints: Map<String, EndpointResult>,
)
interface StartupAnalysis {
    fun plan(tasks: List<StartupTask>, contracts: List<ReadinessContract>): StartupPlan
}

/** Supplied DAG and outcomes; unlimited parallelism, not a runtime scheduler. */
object ReferenceStartup : StartupAnalysis {
    override fun plan(tasks: List<StartupTask>, contracts: List<ReadinessContract>): StartupPlan {
        require(tasks.size <= 100) { "At most 100 tasks" }
        val graph = tasks.associate { it.id to it.copy(prerequisites = it.prerequisites.toSet()) }
        val endpoints = contracts.map { it.copy(required = it.required.toSet()) }
        require(graph.size == tasks.size) { "Duplicate task ID" }
        require(graph.values.all { it.id.isNotBlank() && it.estimatedMillis >= 0 })
        require(graph.values.all { task -> task.prerequisites.all { it in graph } })
        require(endpoints.map { it.id }.toSet().size == endpoints.size) { "Duplicate endpoint ID" }
        require(endpoints.all { it.id.isNotBlank() && it.required.all { id -> id in graph } })
        validateAcyclic(graph)

        val results = mutableMapOf<String, TaskResult>()
        val paths = mutableMapOf<String, List<String>>()
        fun evaluate(id: String): TaskResult {
            results[id]?.let { return it }
            val task = graph.getValue(id)
            val required = task.prerequisites.sorted()
            val dependencies = required.map(::evaluate)
            val causes = dependencies.flatMap { it.failedCauses }.toSet()
            val result = if (causes.isNotEmpty()) {
                TaskResult(TaskStatus.Blocked, null, null, immutableIds(causes))
            } else {
                val last = latest(required, results)
                val start = last?.let { results.getValue(it).finishMillis!! } ?: 0L
                val finish = Math.addExact(start, task.estimatedMillis)
                if (task.outcome == InitOutcome.Failure) {
                    TaskResult(TaskStatus.Failed, start, finish, immutableIds(setOf(id)))
                } else {
                    paths[id] = (last?.let { paths.getValue(it) } ?: emptyList()) + id
                    TaskResult(TaskStatus.Succeeded, start, finish, emptySet())
                }
            }
            results[id] = result
            return result
        }

        val readiness = endpoints.associate { endpoint ->
            val required = endpoint.required.sorted()
            val causes = required.map(::evaluate).flatMap { it.failedCauses }.toSet()
            val result = if (causes.isNotEmpty()) {
                EndpointResult(false, null, emptyList(), immutableIds(causes))
            } else {
                val last = latest(required, results)
                EndpointResult(
                    true, last?.let { results.getValue(it).finishMillis!! } ?: 0L,
                    immutablePath(last?.let { paths.getValue(it) } ?: emptyList()), emptySet(),
                )
            }
            endpoint.id to result
        }
        return StartupPlan(
            Collections.unmodifiableMap(results.toSortedMap()),
            Collections.unmodifiableMap(readiness.toSortedMap()),
        )
    }

    private fun latest(ids: List<String>, results: Map<String, TaskResult>): String? =
        ids.minWithOrNull(compareByDescending<String> { results.getValue(it).finishMillis!! }.thenBy { it })

    private fun validateAcyclic(graph: Map<String, StartupTask>) {
        val active = mutableSetOf<String>()
        val complete = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in complete) return
            require(active.add(id)) { "Cycle in declared graph" }
            graph.getValue(id).prerequisites.forEach(::visit)
            active.remove(id)
            complete.add(id)
        }
        graph.keys.forEach(::visit)
    }

    private fun immutableIds(ids: Set<String>): Set<String> =
        Collections.unmodifiableSet(ids.toSortedSet())

    private fun immutablePath(ids: List<String>): List<String> =
        Collections.unmodifiableList(ids.toList())
}

/** Deliberately wrong: serializes every declaration and calls every endpoint ready. */
object LearnerStartup : StartupAnalysis {
    override fun plan(tasks: List<StartupTask>, contracts: List<ReadinessContract>): StartupPlan {
        var cursor = 0L
        val results = tasks.associate { task ->
            val start = cursor
            cursor += task.estimatedMillis
            task.id to TaskResult(TaskStatus.Succeeded, start, cursor, emptySet())
        }
        val path = tasks.map { it.id }
        return StartupPlan(results, contracts.associate {
            it.id to EndpointResult(true, cursor, path, emptySet())
        })
    }
}
