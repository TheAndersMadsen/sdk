package com.penumbraos.bridge_settings

import android.util.Log
import com.penumbraos.sdk.api.ShellClient

private const val TAG = "LauncherController"

data class LauncherInfo(
    val label: String,
    val component: String
)

class LauncherController(private val shellClient: ShellClient) {

    companion object {
        private val KNOWN_LAUNCHER_NAMES = mapOf(
            "humane.experience.systemnavigation/humaneinternal.system.ipc.HumaneExperienceActivity" to "Humane SystemNavigation",
            "com.penumbraos.mabl.pin/com.penumbraos.mabl.MainActivity" to "MABL",
            "com.android.settings/.FallbackHome" to "Settings Fallback",
        )
    }

    /**
     * Queries available launcher apps by resolving activities with CATEGORY_HOME.
     * Uses `pm query-activities` to find all apps registered as home/launcher.
     */
    suspend fun getAvailableLaunchers(): List<LauncherInfo> {
        return try {
            val result = shellClient.executeCommand(
                "pm query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
            )
            val output = result.output.trim()
            Log.d(TAG, "query-activities output:\n$output")

            if (!result.isSuccess || output.isEmpty()) {
                Log.w(
                    TAG,
                    "Failed to query launcher activities, exit=${result.exitCode}, error='${result.error}'"
                )
                return emptyList()
            }

            parseLaunchers(output)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get available launchers", e)
            emptyList()
        }
    }

    /**
     * Parses the output of `pm query-activities --brief` which outputs lines like:
     *   com.android.launcher3/.Launcher
     *   com.example.app/.HomeActivity
     *
     * Or in some Android versions with a "priority=N" header line followed by component names.
     */
    private fun parseLaunchers(output: String): List<LauncherInfo> {
        val launchers = mutableListOf<LauncherInfo>()

        for (line in output.lines()) {
            val trimmed = line.trim()
            // Skip empty lines, header lines like "priority=0", and other non-component lines
            if (trimmed.isEmpty() || !trimmed.contains("/")) continue

            // Component format: package/class (e.g., com.android.launcher3/.Launcher)
            val component = trimmed
            val packageName = component.substringBefore("/")

            val label = KNOWN_LAUNCHER_NAMES[component] ?: packageName

            launchers.add(LauncherInfo(label = label, component = component))
            Log.d(TAG, "Found launcher: $label ($component)")
        }

        Log.i(TAG, "Found ${launchers.size} available launchers")
        return launchers
    }

    /**
     * Gets the current default launcher using `cmd shortcut get-default-launcher`.
     * Returns the component name string, or null if it can't be determined.
     */
    suspend fun getCurrentLauncher(): String? {
        return try {
            val result = shellClient.executeCommand("cmd shortcut get-default-launcher")
            val output = result.output.trim()
            Log.d(TAG, "get-default-launcher output: '$output', exit=${result.exitCode}")

            if (!result.isSuccess) {
                Log.w(
                    TAG,
                    "Failed to get default launcher, exit=${result.exitCode}, error='${result.error}'"
                )
                return null
            }

            // Output format varies. It may be just a component name, or it may include
            // extra text. Try to extract a component name (package/class pattern).
            val componentRegex = Regex("""[\w.]+/[\w.]+""")
            val match = componentRegex.find(output)

            if (match != null) {
                val component = match.value
                Log.i(TAG, "Current default launcher: $component")
                component
            } else {
                Log.w(TAG, "Could not parse default launcher from output: '$output'")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current launcher", e)
            null
        }
    }

    /**
     * Sets the default launcher using `cmd package set-home-activity`
     * Verifies the change took effect by re-querying afterward, then force-stops
     * the old launcher so Android immediately switches to the new one
     *
     * @param componentName The component name in package/class format (e.g., "com.android.launcher3/.Launcher")
     * @return true if the launcher was changed successfully
     */
    suspend fun setDefaultLauncher(componentName: String): Boolean {
        return try {
            Log.i(TAG, "Setting default launcher to: $componentName")

            // Get the current launcher before changing so we can force-stop it
            val previousLauncher = getCurrentLauncher()
            val previousPackage = previousLauncher?.substringBefore("/")
            val newPackage = componentName.substringBefore("/")

            val result = shellClient.executeCommandWithTimeout(
                "cmd package set-home-activity $componentName",
                timeoutMs = 10000
            )

            Log.d(
                TAG,
                "set-home-activity result: exit=${result.exitCode}, output='${result.output}', error='${result.error}'"
            )

            if (!result.isSuccess) {
                Log.w(
                    TAG,
                    "set-home-activity command failed, exit=${result.exitCode}, error='${result.error}', output='${result.output}'"
                )
                return false
            }

            // Verify the change took effect
            val currentLauncher = getCurrentLauncher()
            if (currentLauncher != null && currentLauncher == componentName) {
                Log.i(TAG, "Default launcher successfully changed to: $componentName")
            } else {
                Log.w(
                    TAG,
                    "Launcher change may not have taken effect. Expected=$componentName, got=$currentLauncher"
                )
            }

            // Force-stop the old launcher so Android immediately resolves to the new one.
            // Only do this if the launcher actually changed (different package).
            if (previousPackage != null && previousPackage != newPackage) {
                Log.i(TAG, "Force-stopping previous launcher: $previousPackage")
                val stopResult = shellClient.executeCommandWithTimeout(
                    "am force-stop $previousPackage",
                    timeoutMs = 5000
                )
                Log.d(
                    TAG,
                    "force-stop result: exit=${stopResult.exitCode}, output='${stopResult.output}', error='${stopResult.error}'"
                )
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set default launcher to $componentName", e)
            false
        }
    }
}
