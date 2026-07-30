package io.github.khopland

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.lang.ref.WeakReference
import java.util.UUID

private val UNDO_LOG = logger<LayoutProfileUndoService>()

internal data class LayoutUndoSnapshot(
    val chrome: LayoutProfile,
    val projects: List<LayoutUndoProject>,
)

internal data class LayoutUndoProject(
    val project: WeakReference<Project>,
    val projectName: String,
    val layoutName: String,
)

@Service(Service.Level.APP)
internal class LayoutProfileUndoService : Disposable {
    private var snapshot: LayoutUndoSnapshot? = null

    fun capture(projects: List<Project>): LayoutUndoSnapshot? {
        val captureId = UUID.randomUUID().toString()
        val capturedProjects = mutableListOf<LayoutUndoProject>()
        return try {
            projects.filterNot(Project::isDisposed).forEachIndexed { index, project ->
                val layoutName = "[IDE Layout Profiles] Undo $captureId-$index"
                PlatformLayoutAdapter.saveTemporary(project, layoutName)
                capturedProjects += LayoutUndoProject(
                    project = WeakReference(project),
                    projectName = project.name,
                    layoutName = layoutName,
                )
            }
            LayoutUndoSnapshot(
                chrome = LayoutProfile.capture(0, "Before profile apply"),
                projects = capturedProjects,
            )
        } catch (error: Exception) {
            capturedProjects.forEach { PlatformLayoutAdapter.delete(it.layoutName) }
            UNDO_LOG.warn("Could not capture the layout before applying a profile", error)
            null
        }
    }

    @Synchronized
    fun remember(captured: LayoutUndoSnapshot) {
        clearSnapshot(snapshot)
        snapshot = captured
    }

    fun discard(captured: LayoutUndoSnapshot?) {
        clearSnapshot(captured)
    }

    @Synchronized
    fun hasSnapshot(): Boolean = snapshot != null

    @Synchronized
    fun clear() {
        clearSnapshot(snapshot)
        snapshot = null
    }

    @Synchronized
    fun restore(): ApplyOutcome? {
        val captured = snapshot ?: return null
        snapshot = null
        val failures = mutableListOf<ApplyFailure>()
        var appliedProjects = 0
        try {
            captured.chrome.applyChrome()
            captured.projects.forEach { savedProject ->
                val project = savedProject.project.get()
                if (project == null || project.isDisposed) return@forEach
                try {
                    PlatformLayoutAdapter.applyTemporary(project, savedProject.layoutName)
                    appliedProjects += 1
                } catch (error: Exception) {
                    failures += ApplyFailure(savedProject.projectName, error)
                }
            }
        } catch (error: Exception) {
            failures += ApplyFailure(null, error)
        } finally {
            clearSnapshot(captured)
        }
        return ApplyOutcome(
            result = when {
                failures.isEmpty() -> ApplyResult.APPLIED
                appliedProjects > 0 -> ApplyResult.PARTIALLY_APPLIED
                else -> ApplyResult.FAILED
            },
            appliedProjects = appliedProjects,
            failures = failures,
        )
    }

    @Synchronized
    override fun dispose() {
        clear()
    }

    private fun clearSnapshot(captured: LayoutUndoSnapshot?) {
        captured?.projects?.forEach { savedProject ->
            runCatching { PlatformLayoutAdapter.delete(savedProject.layoutName) }
                .onFailure { UNDO_LOG.debug("Could not delete a temporary undo layout", it) }
        }
    }
}
