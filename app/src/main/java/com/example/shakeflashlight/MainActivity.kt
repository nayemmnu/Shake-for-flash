package com.example.shakeflashlight

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.shakeflashlight.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startShakeService()
        } else {
            binding.switchEnable.isChecked = false
            Toast.makeText(
                this,
                "Camera and notification permissions are required for this feature",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val running = isServiceRunningPref()
        binding.switchEnable.isChecked = running
        updateStatusText(running)

        binding.switchEnable.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestPermissionsAndStart()
            } else {
                stopShakeService()
                updateStatusText(false)
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.CAMERA)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissionsNeeded.isEmpty()) {
            startShakeService()
        } else {
            permissionLauncher.launch(permissionsNeeded.toTypedArray())
        }
    }

    private fun startShakeService() {
        val intent = Intent(this, ShakeFlashlightService::class.java).apply {
            action = ShakeFlashlightService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        setServiceRunningPref(true)
        updateStatusText(true)
    }

    private fun stopShakeService() {
        val intent = Intent(this, ShakeFlashlightService::class.java).apply {
            action = ShakeFlashlightService.ACTION_STOP
        }
        startService(intent)
        setServiceRunningPref(false)
    }

    private fun updateStatusText(running: Boolean) {
        binding.statusText.text = getString(
            if (running) R.string.status_on else R.string.status_off
        )
    }

    private fun isServiceRunningPref(): Boolean =
        getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("running", false)

    private fun setServiceRunningPref(value: Boolean) {
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("running", value).apply()
    }
}
