package com.chinar.assistant.actions

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.chinar.assistant.data.ActionResult

class FlashlightAction(
    private val context: Context
) : ActionHandler {

    companion object {
        private const val TAG = "FlashlightAction"
    }

    private val cameraManager: CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private var cachedCameraId: String? = null
    private var isTorchOn: Boolean = false

    init {
        registerTorchCallback()
    }

    private fun registerTorchCallback() {
        if (cameraManager == null) return
        try {
            cameraManager.registerTorchCallback(
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId == cachedCameraId || cachedCameraId == null) {
                            isTorchOn = enabled
                            Log.d(TAG, "Torch mode changed on camera $cameraId: enabled=$enabled")
                        }
                    }

                    override fun onTorchModeUnavailable(cameraId: String) {
                        if (cameraId == cachedCameraId) {
                            Log.w(TAG, "Torch mode unavailable on camera $cameraId")
                            isTorchOn = false
                        }
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register TorchCallback", e)
        }
    }

    override suspend fun execute(parameters: Map<String, String>): ActionResult {
        // 1. Availability check via PackageManager
        val hasFlashFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        if (!hasFlashFeature) {
            return ActionResult(
                success = false,
                message = "Flashlight hardware is not available on this device."
            )
        }

        if (cameraManager == null) {
            return ActionResult(
                success = false,
                message = "Camera service is unavailable on this device."
            )
        }

        // 2. Discover camera with flash
        val targetCameraId = getOrFindCameraIdWithFlash()
        if (targetCameraId == null) {
            return ActionResult(
                success = false,
                message = "No camera with flash capability was found."
            )
        }

        // 3. Determine target state
        val requestedState = parameters["state"]?.lowercase()
        val targetEnable = when (requestedState) {
            "on" -> true
            "off" -> false
            else -> !isTorchOn // Toggle if unspecified
        }

        // 4. Apply torch mode
        return try {
            cameraManager.setTorchMode(targetCameraId, targetEnable)
            isTorchOn = targetEnable
            val feedback = if (targetEnable) "Flashlight turned on." else "Flashlight turned off."
            Log.d(TAG, "Successfully set torch mode to $targetEnable on camera $targetCameraId")
            ActionResult(
                success = true,
                message = feedback,
                details = mapOf("cameraId" to targetCameraId, "state" to if (targetEnable) "on" else "off")
            )
        } catch (e: CameraAccessException) {
            val errorMsg = mapCameraAccessException(e)
            Log.e(TAG, "CameraAccessException while setting torch mode", e)
            ActionResult(success = false, message = errorMsg)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while setting torch mode", e)
            ActionResult(
                success = false,
                message = "Camera access was denied by system policy or permission."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error setting torch mode", e)
            ActionResult(
                success = false,
                message = "Could not operate flashlight: ${e.localizedMessage ?: "Unknown error"}"
            )
        }
    }

    private fun getOrFindCameraIdWithFlash(): String? {
        if (cachedCameraId != null) return cachedCameraId

        val manager = cameraManager ?: return null
        try {
            var selectedId: String? = null
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

                if (flashAvailable) {
                    if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                        // Prefer back camera flash
                        cachedCameraId = id
                        return id
                    }
                    if (selectedId == null) {
                        selectedId = id
                    }
                }
            }
            cachedCameraId = selectedId
            return selectedId
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating camera IDs", e)
            return null
        }
    }

    private fun mapCameraAccessException(e: CameraAccessException): String {
        return when (e.reason) {
            CameraAccessException.CAMERA_IN_USE -> "Cannot toggle flashlight: Camera is currently in use by another application."
            CameraAccessException.MAX_CAMERAS_IN_USE -> "Cannot toggle flashlight: Device camera resources are fully in use."
            CameraAccessException.CAMERA_DISABLED -> "Cannot toggle flashlight: Camera is disabled by device administrator policy."
            CameraAccessException.CAMERA_DISCONNECTED -> "Cannot toggle flashlight: Camera device is disconnected."
            else -> "Camera access failure: ${e.message}"
        }
    }
}
