package com.hrst.temi_mqtt_client;

import com.hrst.temi_mqtt_client.R;
import com.hrst.temi_mqtt_client.BuildConfig;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.MalformedURLException;
import java.net.URL;

import com.robotemi.sdk.BatteryData;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;
import com.robotemi.sdk.listeners.OnBatteryStatusChangedListener;
import com.robotemi.sdk.listeners.OnDetectionStateChangedListener;
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener;
import com.robotemi.sdk.listeners.OnRobotReadyListener;
import com.robotemi.sdk.listeners.OnUserInteractionChangedListener;
import com.robotemi.sdk.navigation.listener.OnCurrentPositionChangedListener;
import com.robotemi.sdk.navigation.model.Position;

import info.mqtt.android.service.Ack;
import info.mqtt.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public class MainActivity extends AppCompatActivity implements
        OnRobotReadyListener,
        OnBatteryStatusChangedListener,
        OnGoToLocationStatusChangedListener,
        OnCurrentPositionChangedListener,
        OnDetectionStateChangedListener,
        OnUserInteractionChangedListener {
    private static final String TAG = "MAIN";
    public static final String VIDEO_URL = "com.hrst.media.VIDEO_URL";
    public static final String WEBVIEW_URL = "com.hrst.media.WEBVIEW_URL";

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static Robot sRobot;
    private static Position sCurrentPosition;
    // Temporarily set this for testing
    private static String sSerialNumber = BuildConfig.ROBOT_SERIAL;
    private static TextView logsTextView;
    private static ScrollView logsScrollView;

    private static URL serverURL;

    static {
        try {
            serverURL = new URL(BuildConfig.VIDEOROOM_URL);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
    }
    private static String sRobotName = BuildConfig.ROBOT_NAME;

    private static MqttAndroidClient mMqttClient;
    private static Context sContext;
    private static class BroadcastReceiverHandler extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // onBroadcastReceived(intent);
        }
    }
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiverHandler();

    private static class MqttCallbackHandler implements MqttCallback {
        @SuppressLint("LogNotTimber")
        @Override
        public void connectionLost(Throwable cause) {
            // this method is called when connection to server is lost
            if (logsTextView != null) {
                logsTextView.append("\n[MQTT] Connection Lost.");
            }
            Log.i(TAG, "Connection Lost");
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // called when delivery for a message has been completed, and all acknowledgements have been received
        }

        @SuppressLint("LogNotTimber")
        @Override
        public void messageArrived(String topic, MqttMessage message) throws Exception {
            // this method is called when a message arrives from the server
            String payloadStr = new String(message.getPayload(), StandardCharsets.UTF_8);
            Log.i(TAG, "[MQTT] Topic: " + topic);
            Log.i(TAG, "[MQTT] Message: " + payloadStr);

            sHandler.post(() -> {
                try {
                    JSONObject payloadJson;
                    try {
                        payloadJson = new JSONObject(payloadStr);
                    } catch (JSONException e) {
                        // Not a JSON object, wrap raw payload
                        payloadJson = new JSONObject();
                        payloadJson.put("raw", payloadStr);
                        payloadJson.put("location", payloadStr); // Assume it might be a location
                        payloadJson.put("utterance", payloadStr); // Or a TTS utterance
                        payloadJson.put("x", payloadStr); // Or a coordinate
                        payloadJson.put("y", payloadStr);
                        payloadJson.put("angle", payloadStr);
                    }
                    parseMessage(topic, payloadJson);
                } catch (Exception e) {
                    Log.e(TAG, "Error handling MQTT message", e);
                }
            });
        }
    }

    private static class MqttActionListenerHandler implements IMqttActionListener {
        private final MqttAndroidClient client;
        
        public MqttActionListenerHandler(MqttAndroidClient client) {
            this.client = client;
        }

        @SuppressLint("LogNotTimber")
        @Override
        public void onSuccess(IMqttToken asyncActionToken) {
            if (logsTextView != null) {
                logsTextView.append("\n[MQTT] Connected.");
            }
            Log.i(TAG, "Successfully connected to MQTT broker");
            try {
                // subscribe to all command-type messages directed at this robot
                client.subscribe("temi/" + sSerialNumber + "/command/#", 0);
            } catch (Exception e) {
                e.printStackTrace();
            }

            // start a background task that periodically sends robot status information
            // to the MQTT broker
            sHandler.post(periodicTask);
        }

        @SuppressLint("LogNotTimber")
        @Override
        public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
            if (logsTextView != null) {
                logsTextView.append("\n[MQTT] Failed to Connect.");
            }
            Log.i(TAG, "Failed to connect to MQTT broker");
        }
    }

    private static class PeriodicTask implements Runnable {
        @SuppressLint("LogNotTimber")
        @Override
        public void run() {
            Log.i(TAG, "Publish status");
            sHandler.postDelayed(this, 3000);

            try {
                 robotPublishStatus();
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }
    private static final Runnable periodicTask = new PeriodicTask();

    //----------------------------------------------------------------------------------------------
    // ACTIVITY LIFE CYCLE METHODS
    //----------------------------------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sContext = getApplicationContext();
        setContentView(R.layout.activity_main);
        logsTextView = findViewById(R.id.logsTextView);
        logsTextView.setTextColor(Color.WHITE);
        logsScrollView = findViewById(R.id.logsScrollView);

        // initialize robot
        sRobot = Robot.getInstance();

        // initialize hostname
        EditText etHostname = findViewById(R.id.et_hostname);
        etHostname.setText(BuildConfig.MQTT_HOSTNAME);

        findViewById(R.id.button_connect).performClick();
    }

    @Override
    protected void onStart() {
        super.onStart();

        // add robot event listeners
        sRobot.addOnRobotReadyListener(this);
        sRobot.addOnBatteryStatusChangedListener(this);
        sRobot.addOnGoToLocationStatusChangedListener(this);
        sRobot.addOnDetectionStateChangedListener(this);
        sRobot.addOnUserInteractionChangedListener(this);
        sRobot.addOnCurrentPositionChangedListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();

        // remove robot event listeners
        sRobot.removeOnRobotReadyListener(this);
        sRobot.removeOnBatteryStatusChangedListener(this);
        sRobot.removeOnGoToLocationStatusChangedListener(this);
        sRobot.removeDetectionStateChangedListener(this);
        sRobot.removeOnUserInteractionChangedListener(this);
    }

    @SuppressLint("LogNotTimber")
    @Override
    protected void onDestroy() {
        super.onDestroy();

        // disconnect MQTT client from broker
        if (mMqttClient != null && mMqttClient.isConnected()) {
            Log.i(TAG, "[MQTT] Disconnecting MQTT client from broker");
            mMqttClient.unsubscribe("temi/" + sSerialNumber + "/command/#");
            mMqttClient.disconnect();
            Log.i(TAG, "[MQTT] Done");
        }
    }

    //----------------------------------------------------------------------------------------------
    // ROBOT EVENT LISTENERS
    //----------------------------------------------------------------------------------------------
    /**
     * Configures robot after it is ready
     * @param isReady True if robot initialized correctly; False otherwise
     */
//    @Override
    public void onRobotReady(boolean isReady) {
        if (isReady) {
            String oldSerial = sSerialNumber;
            // Use serial from BuildConfig if provided, otherwise use robot's actual serial
            if (BuildConfig.ROBOT_SERIAL != null && !BuildConfig.ROBOT_SERIAL.trim().isEmpty()) {
                sSerialNumber = BuildConfig.ROBOT_SERIAL.trim();
            } else {
                sSerialNumber = sRobot.getSerialNumber();
            }
            
            // Use name from BuildConfig if provided
            if (BuildConfig.ROBOT_NAME != null && !BuildConfig.ROBOT_NAME.trim().isEmpty()) {
                sRobotName = BuildConfig.ROBOT_NAME.trim();
            }
            
            Log.i(TAG, "[ROBOT][READY] Serial: " + sSerialNumber + " Name: " + sRobotName);
            
            if (mMqttClient != null && mMqttClient.isConnected() && !Objects.equals(oldSerial, sSerialNumber)) {
                Log.i(TAG, "[MQTT] Serial changed, resubscribing...");
                try {
                    if (oldSerial != null && !oldSerial.isEmpty()) {
                        mMqttClient.unsubscribe("temi/" + oldSerial + "/command/#");
                    }
                    mMqttClient.subscribe("temi/" + sSerialNumber + "/command/#", 0);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            
            sRobot.hideTopBar(); // hides temi's top menu bar
            sRobot.toggleNavigationBillboard(true); // hides navigation billboard
            Log.i(TAG, "[ROBOT][READY]");

            // place app in temi's top bar menu
            try {
                final ActivityInfo activityInfo = getPackageManager().getActivityInfo(getComponentName(), PackageManager.GET_META_DATA);
                sRobot.onStart(activityInfo);
            } catch (PackageManager.NameNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Handles battery update events
     * @param batteryData Object containing battery state
     */
//    @Override
    public void onBatteryStatusChanged(@Nullable BatteryData batteryData) {
        JSONObject payload = new JSONObject();

        try {
            payload.put("percentage", batteryData != null ? batteryData.getBatteryPercentage() : 0);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        try {
            payload.put("is_charging", batteryData != null && batteryData.isCharging());
        } catch (JSONException e) {
            e.printStackTrace();
        }

        try {
            if (mMqttClient != null && mMqttClient.isConnected()) {
                MqttMessage message = new MqttMessage(payload.toString().getBytes(StandardCharsets.UTF_8));
                mMqttClient.publish("temi/" + sSerialNumber + "/status/utils/battery", message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Handles go-to event updates
     * @param location Go-to location name
     * @param status Current status
     * @param descriptionId Description-identifier of the event
     * @param description Verbose description of the event
     */
//    @Override
    public void onGoToLocationStatusChanged(@NotNull String location, @NotNull String status, int descriptionId, @NotNull String description) {
        JSONObject payload = new JSONObject();

        try {
            payload.put("location", location);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        try {
            payload.put("status", status);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        try {
            payload.put("description_id", descriptionId);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        try {
            payload.put("description", description);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        try {
            if (mMqttClient != null && mMqttClient.isConnected()) {
                MqttMessage message = new MqttMessage(payload.toString().getBytes(StandardCharsets.UTF_8));
                mMqttClient.publish("temi/" + sSerialNumber + "/event/waypoint/goto", message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * On user detection events
     * @param state User detection state
     */
    @Override
    public void onDetectionStateChanged(int state) {
        JSONObject payload = new JSONObject();

        try {
            payload.put("state", state);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        try {
            if (mMqttClient != null && mMqttClient.isConnected()) {
                MqttMessage message = new MqttMessage(payload.toString().getBytes(StandardCharsets.UTF_8));
                mMqttClient.publish("temi/" + sSerialNumber + "/event/user/detection", message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * On user interaction
     * @param isInteracting True if user is interacting, False otherwise
     */
//    @Override
    public void onUserInteraction(boolean isInteracting) {
        JSONObject payload = new JSONObject();

        try {
            payload.put("is_interacting", isInteracting);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        try {
            if (mMqttClient != null && mMqttClient.isConnected()) {
                MqttMessage message = new MqttMessage(payload.toString().getBytes(StandardCharsets.UTF_8));
                mMqttClient.publish("temi/" + sSerialNumber + "/event/user/interaction", message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //----------------------------------------------------------------------------------------------
    // UI HANDLER
    //----------------------------------------------------------------------------------------------
    /**
     * Connects to MQTT broker
     * @param v View context
     */
    @SuppressLint("LogNotTimber")
    public void connectToMqtt(View v) throws MqttException {
        EditText etHostname = findViewById(R.id.et_hostname);
        String hostname = etHostname.getText().toString().trim();
        if (hostname.isEmpty()) {
            hostname = BuildConfig.MQTT_HOSTNAME;
        }

        String hostUri = "tcp://" + hostname + ":1883";
        Log.i(TAG, hostUri);

        // Hide keyboard
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(etHostname.getWindowToken(), 0);

        // initialize MQTT
        if (mMqttClient != null && mMqttClient.isConnected() && hostUri.equals(mMqttClient.getServerURI())) {
            logsTextView.append("\n[MQTT] Disconnecting..");
            Log.i(TAG, "Already connected to MQTT broker. Disconnecting..");
            mMqttClient.disconnect();
        }
        initMqtt(hostUri, "temi-" + sSerialNumber);
    }

    /**
     * Initializes MQTT client
     * @param hostUri Host name / URI
     * @param clientId Identifier used to uniquely identify this client
     */
    private void initMqtt(String hostUri, String clientId) throws MqttException {
        logsTextView.append("\n[MQTT] Connecting..");
        mMqttClient = new MqttAndroidClient(getApplicationContext(), hostUri, clientId, Ack.AUTO_ACK);

        mMqttClient.setCallback(new MqttCallbackHandler());

        // options that control how the client connects to a server
        // https://www.eclipse.org/paho/files/javadoc/org/eclipse/paho/client/mqttv3/MqttConnectOptions.html
        MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();

        // client and server should forget the state across reconnects.
        mqttConnectOptions.setCleanSession(true);

        // have the client automatically attempt to reconnect to the server if the connection is lost
        mqttConnectOptions.setAutomaticReconnect(true);

        // the maximum time interval the client will wait for the network connection to the MQTT server to be established [seconds]
        mqttConnectOptions.setConnectionTimeout(10);

        // set the "Last Will and Testament" (LWT) for the connection
        JSONObject payload = new JSONObject();
        mqttConnectOptions.setWill("temi/" + sSerialNumber + "/lwt", payload.toString().getBytes(StandardCharsets.UTF_8), 1, false);

        // set username
        if (BuildConfig.MQTT_USERNAME != null && !BuildConfig.MQTT_USERNAME.trim().isEmpty()) {
            mqttConnectOptions.setUserName(BuildConfig.MQTT_USERNAME.trim());
        }

        // set password
        if (BuildConfig.MQTT_PASSWORD != null && !BuildConfig.MQTT_PASSWORD.trim().isEmpty()) {
            mqttConnectOptions.setPassword(BuildConfig.MQTT_PASSWORD.trim().toCharArray());
        }

        mMqttClient.connect(mqttConnectOptions, null, new MqttActionListenerHandler(mMqttClient));
    }

    /**
     * Publish robot status information
     * @throws JSONException Exception is thrown when it fails to publish
     */
    public static void robotPublishStatus() throws JSONException {
        JSONObject payload = new JSONObject();
        JSONArray waypointArray = new JSONArray();

        List<String> waypointList = sRobot.getLocations();

        // collect all waypoints
        for (String waypoint : waypointList) {
            waypointArray.put(waypoint);
        }

        // generate payload
        payload.put("waypoint_list", waypointArray);
        BatteryData batteryData = sRobot.getBatteryData();
        payload.put("battery_percentage", batteryData != null ? batteryData.getBatteryPercentage() : 0);

        if (sCurrentPosition != null) {
            payload.put("x", sCurrentPosition.getX());
            payload.put("y", sCurrentPosition.getY());
            payload.put("yaw", sCurrentPosition.getYaw());
            payload.put("angle", sCurrentPosition.getTiltAngle());

            JSONObject positionObj = new JSONObject();
            positionObj.put("x", sCurrentPosition.getX());
            positionObj.put("y", sCurrentPosition.getY());
            positionObj.put("yaw", sCurrentPosition.getYaw());
            positionObj.put("angle", sCurrentPosition.getTiltAngle());
            payload.put("position", positionObj);
        }

        try {
            MqttMessage message = new MqttMessage(payload.toString().getBytes(StandardCharsets.UTF_8));
            if (mMqttClient != null && mMqttClient.isConnected()) {
                mMqttClient.publish("temi/" + sSerialNumber + "/status/info", message);
                logsScrollView.scrollTo(0, logsScrollView.getBottom());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //----------------------------------------------------------------------------------------------
    // MQTT MESSAGE PARSER
    //----------------------------------------------------------------------------------------------
    /**
     * Parses MQTT messages
     * @param topic Message topic
     * @param payload Message payload
     * @throws JSONException Exception is thrown if parsing fails
     */
    @SuppressLint("LogNotTimber")
    private static void parseMessage(String topic, JSONObject payload) throws JSONException {
        String[] topicTree = topic.split("/");

        if (topicTree.length < 4) {
            Log.w(TAG, "Invalid topic format: " + topic);
            return;
        }

        String robotID = topicTree[1];
        String category = topicTree[3];

        if (robotID.equals(sSerialNumber)) {
            switch (category) {
                case "waypoint":
                    if (topicTree.length > 4) {
                        parseWaypoint(topicTree[4], payload);
                    } else {
                        // Default to goto if command is missing
                        parseWaypoint("goto", payload);
                    }
                    break;

                case "move":
                    if (topicTree.length > 4) {
                        parseMove(topicTree[4], payload);
                    }
                    break;

                case "tts":
                    String utterance = payload.optString("utterance", payload.optString("raw"));
                    if (!utterance.isEmpty()) {
                        Log.i(TAG, "[TTS] Speaking: " + utterance);
                        sRobot.speak(TtsRequest.create(utterance, true));
                    }
                    break;

                case "media":
                    if (topicTree.length > 4) {
                        parseMedia(topicTree[4], payload);
                    }
                    break;

                default:
                    Log.i(TAG, "Unknown category: " + category);
                    break;
            }
        }
    }

    /**
     * Parses waypoint messages
     * @param command Command type
     * @param payload Message Payload
     * @throws JSONException Exception is thrown when it's unable to get the location name from the payload
     */
    @SuppressLint("LogNotTimber")
    private static void parseWaypoint(String command, JSONObject payload) throws JSONException {
        String locationName = payload.optString("location", payload.optString("raw")).trim();
        if (locationName.isEmpty()) {
            Log.w(TAG, "[WAYPOINT] Empty location name");
            return;
        }

        switch (command) {
            case "save":
                Log.i(TAG, "[WAYPOINT] Saving location: " + locationName);
                sRobot.saveLocation(locationName);
                break;

            case "delete":
                Log.i(TAG, "[WAYPOINT] Deleting location: " + locationName);
                sRobot.deleteLocation(locationName);
                break;

            case "goto":
                List<String> locations = sRobot.getLocations();
                for (String location : locations) {
                    if (location.equalsIgnoreCase(locationName)) {
                        Log.i(TAG, "[WAYPOINT] Going to: " + location);
                        sRobot.goTo(location);
                        return;
                    }
                }
                Log.w(TAG, "[WAYPOINT] Location not found: " + locationName + ". Available: " + locations);
                break;

            default:
                Log.i(TAG, "[WAYPOINT] Unknown Command: " + command);
                break;
        }
    }

    /**
     * Parses Move messages
     * @param command Command type
     * @param payload Message Payload
     * @throws JSONException Exception is thrown if it's unable to get the (x, y) location from the payload
     */
    @SuppressLint("LogNotTimber")
    private static void parseMove(String command, JSONObject payload) throws JSONException {
        switch (command) {
            case "joystick":
                float x = Float.parseFloat(payload.getString("x"));
                float y = Float.parseFloat(payload.getString("y"));
                logsTextView.append("\n" + "[MQTT] Joystick (" + x + ", " + y + ")");
                sRobot.skidJoy(x, y);
                break;

            case "position":
                float pos_x = (float) payload.optDouble("x", 0.0);
                float pos_y = (float) payload.optDouble("y", 0.0);
                float yaw = (float) payload.optDouble("yaw", 0.0);
                int angle = payload.optInt("angle", 0);
                logsTextView.append("\n" + "[MQTT] goToPosition (" + pos_x + ", " + pos_y + ", " + yaw + ", " + angle + ")");
                sRobot.goToPosition(new Position(pos_x, pos_y, yaw, angle));
                break;

            case "turn_by":
                float turnAngle = Float.parseFloat(payload.getString("angle"));
                logsTextView.append("\n" + "[MQTT] TurnBy ( " + turnAngle + " )");
                sRobot.turnBy(Integer.parseInt(payload.getString("angle")), 1.0f);
                break;

            case "tilt":
                float tiltAngle = Float.parseFloat(payload.getString("angle"));
                logsTextView.append("\n" + "[MQTT] Tilt ( " + tiltAngle + " )");
                sRobot.tiltAngle(Integer.parseInt(payload.getString("angle")));
                break;

            case "tilt_by":
                float tiltByAngle = Float.parseFloat(payload.getString("angle"));
                logsTextView.append("\n" + "[MQTT] TiltBy ( " + tiltByAngle + " )");
                sRobot.tiltBy(Integer.parseInt(payload.getString("angle")), 1.0f);
                break;

            case "stop":
                logsTextView.append("\n" + "[MQTT] Stop");
                sRobot.stopMovement();
                break;

            default:
                Log.i(TAG, "[MOVE] Unknown Movement Command");
                break;
        }
    }

    /**
     * Parses Media messages
     * @param media Media type
     * @param payload Message Payload
     * @throws JSONException Exception is thrown if it's unable to get payload data
     */
    @SuppressLint("LogNotTimber")
    private static void parseMedia(String media, JSONObject payload) throws JSONException {
        switch (media) {
            case "video":
                logsTextView.append("\n" + "[MQTT] Play Video");
                playVideo(sContext, payload.getString("url"));
                break;

            case "webview":
                logsTextView.append("\n" + "[MQTT] Show WebView");
                showWebview(sContext, payload.getString("url"));
                break;

            case "join":
                logsTextView.append("\n" + "[MQTT] Join Video Room - Jitsi Disabled");
                // LaunchJitsi(sRobotName);
                break;

            case "leave":
                logsTextView.append("\n" + "[MQTT] Leave Video Room - Jitsi Disabled");
                // hangUp();
                break;

            default:
                Log.i(TAG, "[MOVE] Unknown Media");
                break;
        }
    }

    //----------------------------------------------------------------------------------------------
    // MEDIA SERVICES
    //----------------------------------------------------------------------------------------------
    /**
     * Play YouTube from URL
     * @param context Context
     * @param videoId YouTube video ID
     */
    public static void playYoutube(Context context, String videoId){
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + videoId));
        intent.putExtra("VIDEO_ID", videoId);
        intent.putExtra("force_fullscreen", true);

        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.i(TAG, "YouTube not found");
        }
    }

    /**
     * Play video from URL
     * @param url URL to compatible video (https://developer.android.com/guide/topics/media/media-formats)
     */
    @SuppressLint("LogNotTimber")
    public static void playVideo(Context context, String url) {
        Intent intent = new Intent(context, VideoActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(VIDEO_URL, url);

        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.i(TAG, "Video not found");
        }
    }

    /**
     * Show web-view from URL
     * @param url URL to display
     */
    @SuppressLint("LogNotTimber")
    public static void showWebview(Context context, String url) {
        Intent intent = new Intent(context, WebViewActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(WEBVIEW_URL, url);

        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.i(TAG, "URL not found");
        }
    }

    public void LaunchJitsi(String text) {
        // Jitsi Disabled
    }

    private void registerForBroadcastMessages() {
        // Jitsi Disabled
    }

    // Example for handling different JitsiMeetSDK events
    @SuppressLint("LogNotTimber")
    private void onBroadcastReceived(Intent intent) {
        // Jitsi Disabled
    }

    // Example for sending actions to JitsiMeetSDK
    private void hangUp() {
        // Jitsi Disabled
    }

    @SuppressLint("LogNotTimber")
    @Override
    public void onCurrentPositionChanged(@NotNull Position position) {
        sCurrentPosition = position;
        JSONObject payload = new JSONObject();

        try {
            if (position != null) {
                payload.put("x", position.getX());
                payload.put("y", position.getY());
                payload.put("yaw", position.getYaw());
                payload.put("angle", position.getTiltAngle());

                JSONObject positionObj = new JSONObject();
                positionObj.put("x", position.getX());
                positionObj.put("y", position.getY());
                positionObj.put("yaw", position.getYaw());
                positionObj.put("angle", position.getTiltAngle());
                payload.put("position", positionObj);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        Log.i("onCurrentPositionChanged", "[MQTT] Position Update: " + payload.toString());
        logsTextView.append("\n[MQTT] Position Update: " + payload.toString());
        try {
            if (mMqttClient != null && mMqttClient.isConnected()) {
                MqttMessage message = new MqttMessage(payload.toString().getBytes(StandardCharsets.UTF_8));
                mMqttClient.publish("temi/" + sSerialNumber + "/status/position", message);
                logsScrollView.scrollTo(0, logsScrollView.getBottom());
            }
        } catch (Exception e) {
            Log.i("Error onCurrentPositionChanged","Error to publish mqtt message in onCurrentPositionChanged");
            e.printStackTrace();
        }
    }
}
