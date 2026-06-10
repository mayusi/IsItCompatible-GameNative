package app.gamenative.ui.screen

/**
 * Destinations for top level screens, excluding home screen destinations.
 */
sealed class PluviaScreen(val route: String) {
    data object LoginUser : PluviaScreen("login")
    data object Home : PluviaScreen("home")
    data object XServer : PluviaScreen("xserver")
    data object Settings : PluviaScreen("settings")
    data object Chat : PluviaScreen("chat/{id}") {
        fun route(id: Long) = "chat/$id"
        const val ARG_ID = "id"
    }
    data object AutoTunerProgress : PluviaScreen("autotuner_progress/{appId}") {
        fun route(appId: String) = "autotuner_progress/$appId"
        const val ARG_APP_ID = "appId"
    }
    data object AutoTunerResults : PluviaScreen("autotuner_results")
}
