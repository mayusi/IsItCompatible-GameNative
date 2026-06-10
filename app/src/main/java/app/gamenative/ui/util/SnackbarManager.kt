package app.gamenative.ui.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A snackbar message that may carry an optional action label and callback.
 * When [actionLabel] is null the snackbar is shown without an action button.
 */
data class SnackbarMessage(
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    /** If true, the snackbar does not auto-dismiss (uses SnackbarDuration.Indefinite). */
    val persistent: Boolean = false,
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
