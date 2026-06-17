package app.gamenative.ui.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A snackbar message that may carry an optional action label and callback.
 * When [actionLabel] is null the snackbar is shown without an action button.
 *
 * Crash-diagnosis extension:
 * [diagnosisTitle] and [diagnosisBody] carry the human-readable crash explanation produced by
 * [app.gamenative.utils.CrashClassifier].  When these are set and [onAction] is null, the
 * snackbar action button is labelled "Why?" and tapping it opens a [MessageDialog] with the
 * full diagnosis instead of executing a fix action.  When [onAction] is also set (i.e. a
 * one-tap fix is available), the snackbar action still runs the fix; the diagnosis text is
 * prepended to the snackbar [message] so the user can still read it inline.
 */
data class SnackbarMessage(
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    /** If true, the snackbar does not auto-dismiss (uses SnackbarDuration.Indefinite). */
    val persistent: Boolean = false,
    /** Optional dialog title shown when the user taps "Why?" on a diagnosis-only snackbar. */
    val diagnosisTitle: String? = null,
    /**
     * Full plain-English diagnosis of the crash: what kind of problem occurred and what the
     * auto-fixer does about it.  When non-null and [onAction] is null, the snackbar shows a
     * "Why?" action that opens a [MessageDialog] with this text.
     */
    val diagnosisBody: String? = null,
)

object SnackbarManager {
    private val _messages = Channel<String>(capacity = Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _richMessages = Channel<SnackbarMessage>(capacity = Channel.BUFFERED)
    val richMessages = _richMessages.receiveAsFlow()

    fun show(message: String) {
        _messages.trySend(message)
    }

    /**
     * Show a snackbar with an optional action button and optional persistent display.
     * Consumed by the top-level [PluviaMain] scaffolded snackbar host.
     */
    fun showRich(message: SnackbarMessage) {
        _richMessages.trySend(message)
    }
}
