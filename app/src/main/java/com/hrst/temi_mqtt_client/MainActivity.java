package com.hrst.temi_mqtt_client;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import org.jitsi.meet.sdk.*;
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
import android.util.Log;
import android.view.View;
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

import org.eclipse.paho.android.service.MqttAndroidClient;
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

    private static final Handler sHandler = new Handler();
    private static Robot sRobot;
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

    private MqttAndroidClient mMqttClient;
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onBroadcastReceived(intent);
        }
    };

    private final Runnable periodicTask = new Runnable() {
        // periodically publishes robot status to the MQTT broker.
        @SuppressLint("LogNotTimber")
        @Override
        public void run() {
            Log.i(TAG, "Publish status");
            sHandler.postDelayed(this, 3000);

            try {
                 MainActivity.this.robotPublishStatus();
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    };

    //----------------------------------------------------------------------------------------------
    // ACTIVITY LIFE CYCLE METHODS
    //----------------------------------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        logsTextView = findViewById(R.id.logsTextView);
        logsTextView.setTextColor(Color.WHITE);
        logsScrollView = findViewById(R.id.logsScrollView);

        // initialize robot
        sRobot = Robot.getInstance();

        // initialize hostname
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
            try {
                Log.i(TAG, "[MQTT] Disconnecting MQTT client from broker");
                mMqttClient.unsubscribe("temi/" + sSerialNumber + "/command/#");
                mMqttClient.disconnect();
                Log.i(TAG, "[MQTT] Done");
            } catch (MqttException e) {
                e.printStackTrace();
            }
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
            sSerialNumber = sRobot.getSerialNumber();
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
        } catch (MqttException e) {
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
        } catch (MqttException e) {
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
        } catch (MqttException e) {
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
        } catch (MqttException e) {
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
        String hostUri;
        hostUri = "tcp://" + BuildConfig.MQTT_HOSTNAME.trim() + ":1883";
        Log.i(TAG, hostUri);

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
        mMqttClient = new MqttAndroidClient(getApplicationContext(), hostUri, clientId);

        mMqttClient.setCallback(new MqttCallback() {
            @SuppressLint("LogNotTimber")
            @Override
            public void connectionLost(Throwable cause) {
                // this method is called when connection to server is lost
                logsTextView.append("\n[MQTT] Connection Lost.");
                Log.i(TAG, "Connection Lost");
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // called when delivery for a message has been completed, and all acknowledgements have been received
            }

            @SuppressLint("LogNotTimber")
            @Override
            public void messageArrived(String topic, MqttMessage message) throws JSONException {
                // this method is called when a message arrives from the server
                Log.i(TAG, topic);
                Log.i(TAG, message.toString());
                JSONObject payload = new JSONObject(message.toString());
                parseMessage(topic, payload);
                logsScrollView.scrollTo(0, logsScrollView.getBottom());
            }
        });

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
        if (BuildConfig.MQTT_USERNAME != null) {
            BuildConfig.MQTT_USERNAME.trim();
        }

        // set password
        if (BuildConfig.MQTT_PASSWORD != null) {
            BuildConfig.MQTT_PASSWORD.trim();
        }

        try {
            mMqttClient.connect(mqttConnectOptions, null, new IMqttActionListener() {
                @SuppressLint("LogNotTimber")
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    logsTextView.append("\n[MQTT] Connected.");
                    Log.i(TAG, "Successfully connected to MQTT broker");
                    try {
                        // subscribe to all command-type messages directed at this robot
                        mMqttClient.subscribe("temi/" + sSerialNumber + "/command/#", 0);
                    } catch (MqttException e) {
                        e.printStackTrace();
                    }

                    // start a background task that periodically sends robot status information
                    // to the MQTT broker
                    sHandler.post(periodicTask);
                }

                @SuppressLint("LogNotTimber")
                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    logsTextView.append("\n[MQTT] Failed to Connect.");
                    Log.i(TAG, "Failed to connect to MQTT broker");
                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    /**
     * Publish robot status information
     * @throws JSONException Exception is thrown when it fails to publish
     */
    public void robotPublishStatus() throws JSONException {
        JSONObject payload = new JSONObject();
        JSONArray waypointArray = new JSONArray();

        List<String> waypointList = sRobot.getLocations();

        // collect all waypoints
        for (String waypoint : waypointList) {
            waypointArray.put(waypoint);
        }

        // generate payload
        payload.put("waypoint_list", waypointArray);
        payload.put("battery_percentage", Objects.requireNonNull(sRobot.getBatteryData()).getBatteryPercentage());

        try {
            MqttMessage message = new MqttMessage(payload.toString().getBytes(StandardCharsets.UTF_8));
            if (mMqttClient.isConnected()) {
                mMqttClient.publish("temi/" + sSerialNumber + "/status/info", message);
                logsScrollView.scrollTo(0, logsScrollView.getBottom());
            }
        } catch (MqttException e) {
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
    private void parseMessage(String topic, JSONObject payload) throws JSONException {
        String[] topicTree = topic.split("/");

        String robotID = topicTree[1];
        String type = topicTree[2];
        String category = topicTree[3];

        Log.d(TAG, "Robot-ID: " + robotID);
        Log.d(TAG, "Type: " + type);
        Log.d(TAG, "Category: " + category);

        if (robotID.equals(sSerialNumber)) {
            switch (category) {
                case "waypoint":
                    parseWaypoint(topicTree[4], payload);
                    break;

                case "move":
                    parseMove(topicTree[4], payload);
                    break;

                case "tts":
                    sRobot.speak(TtsRequest.create(payload.getString("utterance"), true));
                    break;

                case "media":
                    parseMedia(topicTree[4], payload);
                    break;

                default:
                    Log.i(TAG, "Invalid topic: " + topic);
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
    private void parseWaypoint(String command, JSONObject payload) throws JSONException {
        String locationName = payload.getString("location");

        switch (command) {
            case "save":
                sRobot.saveLocation(locationName);
                break;

            case "delete":
                sRobot.deleteLocation(locationName);
                break;

            case "goto":
                // check that the location exists, then go to that location
                for (String location : sRobot.getLocations()){
                    if (location.equals(locationName.toLowerCase().trim())) {
                        sRobot.goTo(locationName.toLowerCase().trim());
                    }
                }
                break;

            default:
                Log.i(TAG, "[WAYPOINT] Unknown Locations Command");
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
    private void parseMove(String command, JSONObject payload) throws JSONException {
        switch (command) {
            case "joystick":
                float x = Float.parseFloat(payload.getString("x"));
                float y = Float.parseFloat(payload.getString("y"));
                logsTextView.append("\n" + "[MQTT] Joystick (" + x + ", " + y + ")");
                sRobot.skidJoy(x, y);
                break;

            case "position":
                float pos_x = Float.parseFloat(payload.getString("x"));
                float pos_y = Float.parseFloat(payload.getString("y"));
                int angle = Integer.parseInt(payload.getString("angle"));
                float yaw = Float.parseFloat(payload.getString("yaw"));
                logsTextView.append("\n" + "[MQTT] goToPosition (" + pos_x + ", " + pos_y + ", " + angle + "," + yaw + ")");
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
    private void parseMedia(String media, JSONObject payload) throws JSONException {
        switch (media) {
            case "video":
                logsTextView.append("\n" + "[MQTT] Play Video");
                playVideo(this, payload.getString("url"));
                break;

            case "webview":
                logsTextView.append("\n" + "[MQTT] Show WebView");
                showWebview(this, payload.getString("url"));
                break;

            case "join":
                logsTextView.append("\n" + "[MQTT] Join Video Room");
                LaunchJitsi(sRobotName);
                break;

            case "leave":
                logsTextView.append("\n" + "[MQTT] Leave Video Room");
                hangUp();
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
    public void playVideo(Context context, String url) {
        Intent intent = new Intent(this, VideoActivity.class);
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
    public void showWebview(Context context, String url) {
        Intent intent = new Intent(this, WebViewActivity.class);
        intent.putExtra(WEBVIEW_URL, url);

        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.i(TAG, "URL not found");
        }
    }

    public void LaunchJitsi(String text) {
        if (text.length() > 0) {
            JitsiMeetConferenceOptions options = new JitsiMeetConferenceOptions.Builder()
                    .setServerURL(serverURL)
                    .setRoom(text)
                    .setAudioMuted(false)
                    .setWelcomePageEnabled(false)
                    .build();
            JitsiMeetActivity.launch(this, options);
        }
    }

    private void registerForBroadcastMessages() {
        IntentFilter intentFilter = new IntentFilter();

        /* This registers for every possible event sent from JitsiMeetSDK
           If only some of the events are needed, the for loop can be replaced
           with individual statements:
           ex:  intentFilter.addAction(BroadcastEvent.Type.AUDIO_MUTED_CHANGED.getAction());
                intentFilter.addAction(BroadcastEvent.Type.CONFERENCE_TERMINATED.getAction());
                ... other events
         */
        for (BroadcastEvent.Type type : BroadcastEvent.Type.values()) {
            intentFilter.addAction(type.getAction());
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, intentFilter);
    }

    // Example for handling different JitsiMeetSDK events
    @SuppressLint("LogNotTimber")
    private void onBroadcastReceived(Intent intent) {
        if (intent != null) {
            BroadcastEvent event = new BroadcastEvent(intent);

            switch (event.getType()) {
                case CONFERENCE_JOINED:
                    Log.i(TAG, "Conference Joined with url%s" + event.getData().get("url"));
                    break;
                case PARTICIPANT_JOINED:
                    Log.i(TAG, "Participant joined%s" + event.getData().get("name"));
                    break;
            }
        }
    }

    // Example for sending actions to JitsiMeetSDK
    private void hangUp() {
        Intent hangupBroadcastIntent = BroadcastIntentHelper.buildHangUpIntent();
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(hangupBroadcastIntent);
    }

    @SuppressLint("LogNotTimber")
    @Override
    public void onCurrentPositionChanged(@NotNull Position position) {

        JSONObject payload = new JSONObject();

        try {
            if (position != null) {
                payload.put("position_x", position.getX());
                payload.put("position_y", position.getY());
                payload.put("tilt", position.getTiltAngle());
                payload.put("yaw", position.getYaw());
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
            }
        } catch (MqttException e) {
            Log.i("Error onCurrentPositionChanged","Error to publish mqtt message in onCurrentPositionChanged");
            e.printStackTrace();
        }
    }
}
