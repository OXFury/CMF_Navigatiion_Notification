package com.example.cmfnavigation

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

class NavigationService : Service(), LocationListener {
    companion object {
        const val ACTION_START = "com.example.cmfnavigation.START"
        const val EXTRA_DESTINATION = "destination"
        private const val TAG = "CMFNavigation"
        private const val GPS_INTERVAL_MS = 1000L
        private const val GPS_DISTANCE_M = 2f
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var locationManager: LocationManager
    private var steps: List<RouteStep> = emptyList()
    private var stepIndex = 0
    private var destinationName = ""
    private var destinationLat = 0.0
    private var destinationLon = 0.0
    private var lastLocation: Location? = null
    private var offRouteCount = 0
    private var lastRerouteMs = 0L
    private var alertedStage = -1
    private var alertedStep = -1
    private var routeReady = false
    private var pendingDestination = ""

    override fun onCreate() {
        super.onCreate()
        NavigationNotifications.createChannels(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        startForeground(NavigationNotifications.SERVICE_ID, NavigationNotifications.serviceNotification(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val query = intent.getStringExtra(EXTRA_DESTINATION).orEmpty()
            if (query.isNotBlank()) {
                destinationName = query
                pendingDestination = query
                startLocationUpdates()
                val known = getLastKnownLocation()
                if (known != null) {
                    lastLocation = known
                    executor.execute { prepareRoute(query, known) }
                } else {
                    NavigationNotifications.postRoute(this, "Waiting for GPS", "Waiting for a location fix before calculating the route.")
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun prepareRoute(query: String, from: Location?) {
        try {
            val destination = geocode(query)
            destinationLat = destination.first
            destinationLon = destination.second
            val origin = from ?: lastLocation ?: getLastKnownLocation()
                ?: throw IllegalStateException("Waiting for a GPS fix. Move outdoors and try again.")
            val newSteps = fetchRoute(origin.latitude, origin.longitude, destinationLat, destinationLon)
            steps = newSteps
            stepIndex = 0
            alertedStage = -1
            alertedStep = -1
            offRouteCount = 0
            routeReady = true
            pendingDestination = ""
            Log.i(TAG, "Route loaded with ${steps.size} steps")
            NavigationNotifications.postRoute(this, "Route started", "To $destinationName · ${formatDistance(steps.sumOf { it.distance })}")
        } catch (e: Exception) {
            Log.e(TAG, "Route setup failed", e)
            NavigationNotifications.postRoute(this, "Navigation unavailable", e.message ?: "Could not calculate route")
        }
    }

    private fun geocode(query: String): Pair<Double, Double> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = getJson("https://nominatim.openstreetmap.org/search?q=$encoded&format=jsonv2&limit=1")
        if (json.length() == 0) throw IllegalStateException("Place not found. Try a more specific destination.")
        val first = json.getJSONObject(0)
        return first.getString("lat").toDouble() to first.getString("lon").toDouble()
    }

    private fun fetchRoute(lat: Double, lon: Double, destLat: Double, destLon: Double): List<RouteStep> {
        val url = "https://router.project-osrm.org/route/v1/driving/$lon,$lat;$destLon,$destLat?overview=false&steps=true&geometries=geojson"
        val root = getJsonObject(url)
        if (root.optString("code") != "Ok") throw IllegalStateException("Routing service could not find a route.")
        val route = root.getJSONArray("routes").getJSONObject(0)
        val leg = route.getJSONArray("legs").getJSONObject(0)
        val rawSteps = leg.getJSONArray("steps")
        val parsed = ArrayList<RouteStep>()
        for (i in 0 until rawSteps.length()) {
            val s = rawSteps.getJSONObject(i)
            val maneuver = s.getJSONObject("maneuver")
            val coords = s.getJSONObject("geometry").getJSONArray("coordinates")
            val points = ArrayList<Pair<Double, Double>>()
            for (j in 0 until coords.length()) {
                val p = coords.getJSONArray(j)
                points.add(p.getDouble(1) to p.getDouble(0))
            }
            if (points.isNotEmpty()) {
                parsed.add(RouteStep(
                    name = s.optString("name", ""),
                    distance = s.optDouble("distance", 0.0),
                    duration = s.optDouble("duration", 0.0),
                    instruction = instruction(maneuver.optString("type"), maneuver.optString("modifier")),
                    points = points
                ))
            }
        }
        if (parsed.isEmpty()) throw IllegalStateException("The route did not contain navigation steps.")
        return parsed
    }

    private fun instruction(type: String, modifier: String): String {
        val m = modifier.replace('-', ' ').trim()
        return when (type) {
            "depart" -> "Continue"
            "arrive" -> "Arrive"
            "turn" -> "Turn ${m.ifBlank { "ahead" }}"
            "new name" -> "Continue"
            "merge" -> "Merge ${m}".trim()
            "on ramp" -> "Take ramp ${m}".trim()
            "off ramp" -> "Take exit ${m}".trim()
            "fork" -> "Keep ${m}".trim()
            "roundabout", "rotary" -> "Enter roundabout"
            "end of road" -> "Turn ${m}".trim()
            "continue" -> "Continue ${m}".trim()
            else -> if (m.isNotBlank()) "Go ${m}" else "Continue"
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        if (!routeReady || steps.isEmpty()) {
            val query = pendingDestination
            if (query.isNotBlank()) {
                pendingDestination = ""
                executor.execute { prepareRoute(query, location) }
            }
            return
        }
        executor.execute { processFix(location) }
    }

    private fun processFix(location: Location) {
        if (!routeReady || steps.isEmpty()) return
        if (stepIndex >= steps.size) {
            finishNavigation()
            return
        }
        val current = steps[stepIndex]
        val projection = projectOntoPolyline(location.latitude, location.longitude, current.points)
        val remainingOnStep = max(0.0, current.distance - projection.alongMeters)
        val offRoute = distanceToPolyline(location.latitude, location.longitude, current.points) > 40.0
        offRouteCount = if (offRoute) offRouteCount + 1 else 0

        if (offRouteCount >= 4 && System.currentTimeMillis() - lastRerouteMs >= 15_000L) {
            lastRerouteMs = System.currentTimeMillis()
            offRouteCount = 0
            prepareRoute(destinationName, location)
            return
        }

        if (remainingOnStep < 25.0 && stepIndex < steps.lastIndex) {
            stepIndex++
            alertedStage = -1
            alertedStep = -1
            processFix(location)
            return
        }

        val threshold = when {
            remainingOnStep <= 50.0 -> 50
            remainingOnStep <= 200.0 -> 200
            remainingOnStep <= 500.0 -> 500
            else -> 0
        }
        if (threshold > 0) {
            val stage = when (threshold) { 50 -> 3; 200 -> 2; else -> 1 }
            if (alertedStep != stepIndex || stage > alertedStage) {
                alertedStep = stepIndex
                alertedStage = stage
                val title = "${current.instruction} in $threshold m"
                val street = current.name.ifBlank { "Unnamed road" }
                val remaining = remainingRouteMeters(remainingOnStep)
                val etaSeconds = remainingRouteSeconds(remainingOnStep)
                val body = "$street · ${formatDistance(remaining)} left · ${formatDuration(etaSeconds)}"
                NavigationNotifications.postRoute(this, title, body)
            }
        }
        if (current.instruction == "Arrive" && remainingOnStep < 25.0) finishNavigation()
    }

    private fun remainingRouteMeters(currentRemaining: Double): Double =
        currentRemaining + steps.drop(stepIndex + 1).sumOf { it.distance }

    private fun remainingRouteSeconds(currentRemaining: Double): Double {
        val current = steps.getOrNull(stepIndex)
        val currentPart = if (current != null && current.distance > 0) current.duration * currentRemaining / current.distance else 0.0
        return currentPart + steps.drop(stepIndex + 1).sumOf { it.duration }
    }

    private fun finishNavigation() {
        routeReady = false
        NavigationNotifications.postRoute(this, "You have arrived", "You reached $destinationName.")
        stopLocationUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun getLastKnownLocation(): Location? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { null }
    }

    private fun startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            NavigationNotifications.postRoute(this, "Location permission needed", "Allow location access and start navigation again.")
            stopSelf()
            return
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, GPS_DISTANCE_M, this, Looper.getMainLooper())
        } catch (e: Exception) {
            Log.e(TAG, "Could not start GPS updates", e)
            NavigationNotifications.postRoute(this, "GPS unavailable", "Enable device location and try again.")
        }
    }

    private fun stopLocationUpdates() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
    }

    private fun getJson(url: String): JSONArray {
        val conn = openConnection(url)
        return try { JSONArray(readBody(conn)) } finally { conn.disconnect() }
    }

    private fun getJsonObject(url: String): JSONObject {
        val conn = openConnection(url)
        return try { JSONObject(readBody(conn)) } finally { conn.disconnect() }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 12_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "CMFNavigationAlerts/1.0 (Android app)")
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        if (code !in 200..299) throw IllegalStateException("Server error ($code). Please try again.")
        return body
    }

    private data class RouteStep(
        val name: String,
        val distance: Double,
        val duration: Double,
        val instruction: String,
        val points: List<Pair<Double, Double>>
    )

    private data class Projection(val alongMeters: Double, val distanceMeters: Double)

    private fun projectOntoPolyline(lat: Double, lon: Double, points: List<Pair<Double, Double>>): Projection {
        if (points.size < 2) return Projection(0.0, Double.MAX_VALUE)
        var bestDistance = Double.MAX_VALUE
        var bestAlong = 0.0
        var traversed = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            val segmentLength = haversine(a.first, a.second, b.first, b.second)
            val meanLat = Math.toRadians((a.first + b.first + lat) / 3.0)
            val sx = (b.second - a.second) * 111_320.0 * cos(meanLat)
            val sy = (b.first - a.first) * 110_540.0
            val px = (lon - a.second) * 111_320.0 * cos(meanLat)
            val py = (lat - a.first) * 110_540.0
            val denom = sx * sx + sy * sy
            val t = if (denom == 0.0) 0.0 else ((px * sx + py * sy) / denom).coerceIn(0.0, 1.0)
            val dx = px - t * sx
            val dy = py - t * sy
            val d = sqrt(dx * dx + dy * dy)
            if (d < bestDistance) {
                bestDistance = d
                bestAlong = traversed + segmentLength * t
            }
            traversed += segmentLength
        }
        return Projection(bestAlong, bestDistance)
    }

    private fun distanceToPolyline(lat: Double, lon: Double, points: List<Pair<Double, Double>>) =
        projectOntoPolyline(lat, lon, points).distanceMeters

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun formatDistance(meters: Double): String =
        if (meters >= 1000) String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
        else "${meters.roundToInt()} m"

    private fun formatDuration(seconds: Double): String {
        val minutes = (seconds / 60.0).roundToInt()
        return if (minutes < 60) "$minutes min" else "${minutes / 60} hr ${minutes % 60} min"
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLocationUpdates()
        executor.shutdownNow()
        super.onDestroy()
    }
}
