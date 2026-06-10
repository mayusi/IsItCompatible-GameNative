package app.gamenative.utils

import android.content.Context
import app.gamenative.PrefManager
import com.winlator.container.Container
import com.winlator.core.GPUInformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Applies the best config from BestConfigService as a temporary (in-memory) override
 * on every game launch.
 *
 * Rules:
 * - Skipped when PrefManager.autoApplyBestConfig is false.
 * - Skipped when the launch was already initiated by an app.gamenative.LAUNCH_GAME intent
 *   that carried a container_config (IntentLaunchManager already has an active override —
 *   that config wins; we must not clobber it).
 * - Only applied when matchType is exact_gpu_match or gpu_family_match.
 * - Best-effort: any failure just logs and proceeds without touching the config.
 * - Timeout: 8 seconds; if the API is slow the launch continues unaffected.
 */
object BestConfigApplier {

    private const val TAG = "BestConfigApplier"
    private const val TIMEOUT_MS = 8_000L

    /**
     * Attempt to fetch and apply the best config for the given game.
     *
     * Must be called from a coroutine context that can handle IO. Does NOT throw.
     */
    suspend fun maybeApply(context: Context, appId: String, container: Container) {
        // 1. Guard: feature flag.
        if (!PrefManager.autoApplyBestConfig) {
            Timber.tag(TAG).d("autoApplyBestConfig disabled — skipping for $appId")
            return
        }

        // 2. Guard: if the intent path already set an override, its config wins.
        if (IntentLaunchManager.hasTemporaryOverride(appId)) {
            Timber.tag(TAG).d("Intent-supplied config is active for $appId — skipping auto-apply")
            return
        }

        try {
            val gameName = ContainerUtils.resolveGameName(appId)
            val gpuName = withContext(Dispatchers.IO) {
                GPUInformation.getRenderer(context) ?: ""
            }
            val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId).name

            Timber.tag(TAG).d("Fetching best config for game='$gameName' gpu='$gpuName' store='$gameSource'")

            val response = withTimeoutOrNull(TIMEOUT_MS) {
                BestConfigService.fetchBestConfig(gameName, gpuName, gameSource)
            }

            if (response == null) {
                Timber.tag(TAG).d(
                    "No config returned (timeout or API error) for $appId — proceeding with user config",
                )
                return
            }

            val matchType = response.matchType
            if (matchType != "exact_gpu_match" && matchType != "gpu_family_match") {
                Timber.tag(TAG).d(
                    "matchType='$matchType' for $appId — not applying (only exact/family matches used)",
                )
                return
            }

            // Re-check after the async call; a concurrent intent launch could have set an override.
            if (IntentLaunchManager.hasTemporaryOverride(appId)) {
                Timber.tag(TAG).d("Intent override appeared during fetch for $appId — skipping auto-apply")
                return
            }

            val storeMatch = response.matchedStore.equals(gameSource, ignoreCase = true)
            val filteredConfig = BestConfigService.filterConfigByMatchType(
                response.bestConfig,
                matchType,
                storeMatch,
            )

            val configMap = BestConfigService.parseConfigToContainerData(
                context = context,
                configJson = filteredConfig,
                matchType = matchType,
                applyKnownConfig = true,
                storeMatch = storeMatch,
                forceApply = false,
                matchedGpu = response.matchedGpu,
            )

            if (configMap == null || configMap.isEmpty()) {
                Timber.tag(TAG).d("parseConfigToContainerData returned empty/null for $appId — skipping")
                return
            }

            val baseData = ContainerUtils.toContainerData(container)
            val overrideData = ContainerUtils.applyBestConfigMapToContainerData(baseData, configMap)

            // Use applyAutoConfigOverride so this silent override does NOT trigger the
            // "save container config?" prompt when the session ends — it is intentionally
            // ephemeral (discarded on exit, not persisted to disk).
            IntentLaunchManager.applyAutoConfigOverride(context, appId, overrideData)
            Timber.tag(TAG).i(
                "Auto-applied best config for $appId (matchType=$matchType, gpu=${response.matchedGpu})",
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Best-config auto-apply failed for $appId — proceeding with user config")
        }
    }
}
