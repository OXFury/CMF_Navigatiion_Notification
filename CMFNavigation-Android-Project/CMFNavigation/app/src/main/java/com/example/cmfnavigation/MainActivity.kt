package com.example.cmfnavigation

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var destination: EditText
    private lateinit var status: TextView
    private val requestCode = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestNotificationPermissionIfNeeded()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            gravity = Gravity.TOP
        }
        val heading = TextView(this).apply {
            text = "CMF Navigation Alerts"
            textSize = 24f
        }
        val explanation = TextView(this).apply {
            text = "Navigation is sent as ordinary Android notifications. Nothing X may mirror them to your CMF Watch Pro 2; this app does not connect to the watch."
            textSize = 14f
            setPadding(0, 12, 0, 20)
        }
        destination = EditText(this).apply {
            hint = "Destination (place or address)"
            singleLine = true
        }
        val start = Button(this).apply {
            text = "Find place & start"
            setOnClickListener { startNavigation() }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                stopService(Intent(this@MainActivity, NavigationService::class.java))
                status.text = "Navigation stopped."
            }
        }
        val test = Button(this).apply {
            text = "Send test alert to watch"
            setOnClickListener {
                if (!hasNotificationPermission()) {
                    requestNotificationPermissionIfNeeded()
                    Toast.makeText(this@MainActivity, "Allow notifications, then tap test again.", Toast.LENGTH_LONG).show()
                } else {
                    NavigationNotifications.postTest(this@MainActivity)
                    status.text = "Test alert posted. Check whether Nothing X mirrors it."
                }
            }
        }
        status = TextView(this).apply {
            text = "Ready."
            textSize = 14f
            setPadding(0, 18, 0, 0)
        }
        val note = TextView(this).apply {
            text = "Before testing: allow notifications for this app in Android settings, then enable this app in Nothing X's app-notification settings. Keep location permission enabled while navigating."
            textSize = 13f
            setPadding(0, 18, 0, 0)
        }

        listOf(heading, explanation, destination, start, stop, test, status, note).forEach {
            root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 10
            })
        }
        setContentView(root)
    }

    private fun startNavigation() {
        val query = destination.text.toString().trim()
        if (query.isEmpty()) {
            destination.error = "Enter a destination"
            return
        }
        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), requestCode)
            return
        }
        if (!hasNotificationPermission()) {
            requestNotificationPermissionIfNeeded()
            Toast.makeText(this, "Allow notifications, then start again.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, NavigationService::class.java).apply {
            action = NavigationService.ACTION_START
            putExtra(NavigationService.EXTRA_DESTINATION, query)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        status.text = "Navigation starting for: $query"
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "Location permission granted. Tap start again.", Toast.LENGTH_SHORT).show()
        }
    }
}
