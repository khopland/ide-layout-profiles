package io.github.khopland

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val TOPOLOGY_POLL_SECONDS = 2L
private const val TOPOLOGY_DEBOUNCE_SECONDS = 3L
private val LOG = logger<DisplayTopologyAutoSwitchService>()

@Service(Service.Level.APP)
internal class DisplayTopologyAutoSwitchService : Disposable {
    private val debouncer = DisplayTopologyChangeDebouncer(
        TimeUnit.SECONDS.toNanos(TOPOLOGY_DEBOUNCE_SECONDS),
    )
    private var pollingTask: ScheduledFuture<*>? = null

    @Synchronized
    fun refresh() {
        if (!profileService().autoSwitchBestMatch()) {
            pollingTask?.cancel(false)
            pollingTask = null
            return
        }
        if (pollingTask?.isDone == false) return
        pollingTask = null

        debouncer.reset(DisplayTopology.current())
        pollingTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            ::poll,
            TOPOLOGY_POLL_SECONDS,
            TOPOLOGY_POLL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun poll() {
        runSafely("Display topology polling failed", ::pollOnce)
    }

    private fun pollOnce() {
        if (!profileService().autoSwitchBestMatch()) {
            refresh()
            return
        }
        val topology = DisplayTopology.current()
        if (!debouncer.consumeIfStable(topology, System.nanoTime())) return

        ApplicationManager.getApplication().invokeLater(
            {
                runSafely("Could not apply the best matching layout profile") {
                    applyBestMatch(topology)
                }
            },
            ModalityState.any(),
        )
    }

    private fun applyBestMatch(topology: DisplayTopology) {
        if (!profileService().autoSwitchBestMatch()) return
        val currentTopology = DisplayTopology.current()
        if (currentTopology != topology) {
            debouncer.reset(currentTopology)
            return
        }
        val profile = profileService().bestMatch(topology) ?: return
        if (profileService().activeSlot()?.id == profile.id) return
        val projects = ProjectManager.getInstance().openProjects
            .filterNot(Project::isDisposed)
        val reportingProject = projects.firstOrNull() ?: return
        reportApplyOutcome(
            project = reportingProject,
            profile = profile,
            outcome = applyLayoutProfileWithUndo(projects, profile.number),
            allProjects = true,
        )
    }

    @Synchronized
    override fun dispose() {
        pollingTask?.cancel(false)
        pollingTask = null
    }

    private fun profileService(): LayoutProfileService =
        ApplicationManager.getApplication().getService(LayoutProfileService::class.java)

    private fun runSafely(message: String, operation: () -> Unit) {
        try {
            operation()
        } catch (error: Exception) {
            if (error is ProcessCanceledException || error is ControlFlowException) throw error
            LOG.warn(message, error)
        }
    }
}

internal class DisplayTopologyChangeDebouncer(
    private val debounceNanos: Long,
) {
    private var handledTopology = DisplayTopology.EMPTY
    private var candidateTopology: DisplayTopology? = null
    private var candidateSinceNanos = 0L

    init {
        require(debounceNanos >= 0)
    }

    @Synchronized
    fun reset(topology: DisplayTopology) {
        handledTopology = topology
        candidateTopology = null
        candidateSinceNanos = 0L
    }

    @Synchronized
    fun consumeIfStable(topology: DisplayTopology, nowNanos: Long): Boolean {
        if (topology.isEmpty || topology == handledTopology) {
            candidateTopology = null
            return false
        }
        if (candidateTopology != topology) {
            candidateTopology = topology
            candidateSinceNanos = nowNanos
            return false
        }
        if (nowNanos - candidateSinceNanos < debounceNanos) return false

        handledTopology = topology
        candidateTopology = null
        return true
    }
}
