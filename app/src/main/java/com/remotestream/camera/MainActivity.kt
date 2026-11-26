package com.remotestream.camera

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.remotestream.camera.service.CameraStreamingService
import com.remotestream.camera.ui.screens.MainScreen
import com.remotestream.camera.ui.theme.RemoteCameraStreamerTheme
import com.remotestream.camera.ui.viewmodel.CameraViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: CameraViewModel by viewModels()

    private val stopBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            viewModel.stopStreaming()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true

        if (cameraGranted && micGranted) {
            viewModel.reconnectSignaling()
        } else {
            Toast.makeText(
                this,
                "Camera and Microphone permissions are required for remote streaming.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register broadcast receiver for notification stop action
        val filter = IntentFilter(CameraStreamingService.BROADCAST_STOP_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopBroadcastReceiver, filter)
        }

        checkAndRequestPermissions()

        setContent {
            RemoteCameraStreamerTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            viewModel.reconnectSignaling()
        }
    }

    override fun onResume() {
        super.onResume()
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (camOk && micOk) {
            viewModel.reconnectSignaling()
        }
    }

    override fun onStop() {
        super.onStop()
        Log.i("MainActivity", "[${System.currentTimeMillis()}] MainActivity.onStop() — socket remains in CameraStreamingService")
    }

    override fun onDestroy() {
        Log.i("MainActivity", "[${System.currentTimeMillis()}] MainActivity.onDestroy() — socket NOT disconnected")
        super.onDestroy()
        try {
            unregisterReceiver(stopBroadcastReceiver)
        } catch (_: Exception) {}
    }
}
