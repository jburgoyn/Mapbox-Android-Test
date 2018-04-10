package com.werureo.mapboxAndroidTest

import android.annotation.SuppressLint
import android.location.Location
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import com.mapbox.api.directions.v5.models.DirectionsResponse
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.annotations.Marker
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.plugins.locationlayer.LocationLayerMode
import com.mapbox.mapboxsdk.plugins.locationlayer.LocationLayerPlugin
import com.mapbox.services.android.navigation.ui.v5.NavigationLauncher
import com.mapbox.services.android.navigation.ui.v5.NavigationLauncherOptions
import com.mapbox.services.android.navigation.ui.v5.route.NavigationMapRoute
import com.mapbox.services.android.navigation.v5.navigation.NavigationRoute
import com.mapbox.services.android.telemetry.location.LocationEngineListener
import com.mapbox.services.android.telemetry.location.LocationEnginePriority
import com.mapbox.services.android.telemetry.location.LostLocationEngine
import com.mapbox.services.android.telemetry.permissions.PermissionsListener
import com.mapbox.services.android.telemetry.permissions.PermissionsManager
import kotlinx.android.synthetic.main.activity_main.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : AppCompatActivity(), LocationEngineListener, PermissionsListener {

    companion object {
        private const val TAG: String = "DirectionsActivity"
    }

    private lateinit var map: MapboxMap
    private lateinit var permissionsManager: PermissionsManager
    private lateinit var originLocation: Location
    private lateinit var originCoord: LatLng
    private lateinit var destinationCoord: LatLng
    private lateinit var originPosition: Point
    private lateinit var destinationPosition: Point
    private lateinit var currentRoute: DirectionsRoute

    private var locationEngine: LostLocationEngine? = null
    private var locationLayerPlugin: LocationLayerPlugin? = null
    private var destinationMarker: Marker? = null
    private var navigationMapRoute: NavigationMapRoute? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Mapbox Access Token
        Mapbox.getInstance(applicationContext, BuildConfig.MAPBOX_API_KEY)
        setContentView(R.layout.activity_main)

        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { mapboxMap ->
            map = mapboxMap

            enableLocationPlugin()

            originCoord = LatLng(originLocation.latitude, originLocation.longitude)

            mapboxMap.addOnMapClickListener { point ->
                if (destinationMarker != null) {
                    // even though there's a null check, this is unavoidable
                    mapboxMap.removeMarker(destinationMarker!!)
                }

                destinationCoord = point
                destinationMarker = mapboxMap.addMarker(MarkerOptions()
                        .position(destinationCoord))

                destinationPosition = Point.fromLngLat(destinationCoord.longitude, destinationCoord.latitude)
                originPosition = Point.fromLngLat(originCoord.longitude, originCoord.latitude)
                getRoute(originPosition, destinationPosition)

                startButton.isEnabled = true
                startButton.setBackgroundResource(R.color.mapboxBlue)
            }
        }

        startButton.setOnClickListener { view: View? ->
            val origin = originPosition
            val destination = destinationPosition

            val awsPoolId: String? = null
            val simulateRoute = true
            val options = NavigationLauncherOptions.builder()
                    .origin(origin)
                    .destination(destination)
                    .awsPoolId(awsPoolId)
                    .shouldSimulateRoute(simulateRoute)
                    .build()

            NavigationLauncher.startNavigation(this, options)
        }
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("Missing Permission")
    override fun onStart() {
        super.onStart()
        locationEngine?.requestLocationUpdates()
        locationLayerPlugin?.onStart()
        mapView.onStart()
    }

    override fun onStop() {
        super.onStop()
        locationEngine?.removeLocationUpdates()
        locationLayerPlugin?.onStop()
        mapView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
        locationEngine?.deactivate()
    }

    @SuppressWarnings("MissingPermission")
    private fun enableLocationPlugin() {
        if (PermissionsManager.areLocationPermissionsGranted(this)) {
            initializeLocationEngine()

            locationLayerPlugin = LocationLayerPlugin(mapView, map, locationEngine)
            locationLayerPlugin?.setLocationLayerEnabled(LocationLayerMode.TRACKING)
        } else {
            permissionsManager = PermissionsManager(this)
            permissionsManager.requestLocationPermissions(this)
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun initializeLocationEngine() {
        locationEngine = LostLocationEngine(this)
        locationEngine?.priority = LocationEnginePriority.HIGH_ACCURACY
        locationEngine?.activate()

        val lastLocation = locationEngine?.lastLocation
        if (lastLocation != null) {
            originLocation = lastLocation
            setCameraPosition(lastLocation)
        } else {
            locationEngine?.addLocationEngineListener(this)
        }
    }

    private fun setCameraPosition(location: Location) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                LatLng(location.latitude, location.longitude),
                13.0)
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        permissionsManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onExplanationNeeded(permissionsToExplain: MutableList<String>?) {

    }

    override fun onPermissionResult(granted: Boolean) {
        if (granted) {
            enableLocationPlugin()
        }
    }

    @SuppressWarnings("MissingPermission")
    override fun onConnected() {
        locationEngine?.requestLocationUpdates()
    }

    override fun onLocationChanged(location: Location?) {
        if (location != null) {
            originLocation = location
            setCameraPosition(location)
            locationEngine?.removeLocationEngineListener(this)
        }
    }

    @SuppressLint("LogNotTimber")
    private fun getRoute(origin: Point, destination: Point) {
        NavigationRoute.builder()
                .accessToken(Mapbox.getAccessToken())
                .origin(origin)
                .destination(destination)
                .build()
                .getRoute(object : Callback<DirectionsResponse> {
                    override fun onResponse(call: Call<DirectionsResponse>, response: Response<DirectionsResponse>) {
                        Log.d(TAG, "Response code: ${response.code()}")
                        if (response.body() == null) {
                            Log.e(TAG, "No routes found, make sure you set the right user and access token.")
                            return
                        } else if (response.body()!!.routes().count() < 1) {
                            Log.e(TAG, "No routes found")
                            return
                        }

                        currentRoute = response.body()!!.routes()[0]

                        if (navigationMapRoute != null) {
                            navigationMapRoute?.removeRoute()
                        } else {
                            navigationMapRoute = NavigationMapRoute(null, mapView, map, R.style.NavigationMapRoute)
                        }

                        navigationMapRoute?.addRoute(currentRoute)
                    }

                    override fun onFailure(call: Call<DirectionsResponse>?, t: Throwable?) {
                        Log.e(TAG, "Error: ${t?.message}")
                    }
                })
    }
}
