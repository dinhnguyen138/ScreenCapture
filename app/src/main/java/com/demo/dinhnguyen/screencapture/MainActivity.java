package com.demo.dinhnguyen.screencapture;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.MediaFormat;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private static final String[] FORMAT_OPTIONS = {
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_VIDEO_VP8
    };

    private static final int[][] RESOLUTION_OPTIONS = {
            {1280, 720, 320},
            {800, 480, 160}
    };

    private static final int[] BITRATE_OPTIONS = {
            6144000, // 6 Mbps
            4096000, // 4 Mbps
            2048000, // 2 Mbps
            1024000 // 1 Mbps
    };

    private static final int REQUEST_MEDIA_PROJECTION = 100;
    private static final String STATE_RESULT_CODE = "result_code";
    private static final String STATE_RESULT_DATA = "result_data";

    private Context mContext;
    private MediaProjectionManager mMediaProjectionManager;
    private Handler mHandler = new Handler(new HandlerCallback());
    private Messenger mMessenger = new Messenger(mHandler);
    private Messenger mServiceMessenger = null;
    private String mSelectedFormat = FORMAT_OPTIONS[0];
    private int mSelectedWidth = RESOLUTION_OPTIONS[0][0];
    private int mSelectedHeight = RESOLUTION_OPTIONS[0][1];
    private int mSelectedDpi = RESOLUTION_OPTIONS[0][2];
    private int mSelectedBitrate = BITRATE_OPTIONS[2];
    private String mReceiverIp = "";
    private int mResultCode;
    private Intent mResultData;

    private class HandlerCallback implements Handler.Callback {
        public boolean handleMessage(Message msg) {
            Log.d(TAG, "Handler got event, what: " + msg.what);
            return false;
        }
    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Service connected, name: " + name);
            mServiceMessenger = new Messenger(service);
            try {
                Message msg = Message.obtain(null, Common.MSG_REGISTER_CLIENT);
                msg.replyTo = mMessenger;
                mServiceMessenger.send(msg);
                Log.d(TAG, "Connected to service, send register client back");
            } catch (RemoteException e) {
                Log.d(TAG, "Failed to send message back to service, e: " + e.toString());
                e.printStackTrace();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "Service disconnected, name: " + name);
            mServiceMessenger = null;
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState != null) {
            mResultCode = savedInstanceState.getInt(STATE_RESULT_CODE);
            mResultData = savedInstanceState.getParcelable(STATE_RESULT_DATA);
        }

        mContext = this;
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startService();
    }

    @Override
    public void onResume() {
        super.onResume();

        // start discovery task
        //mDiscoveryTask = new DiscoveryTask();
        //mDiscoveryTask.execute();
    }

    @Override
    public void onPause() {
        super.onPause();
        //mDiscoveryTask.cancel(true);
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        doUnbindService();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main_menu, menu);
        //if (mInputSurface != null) {
        //    menu.findItem(R.id.action_start).setVisible(false);
        //    menu.findItem(R.id.action_stop).setVisible(true);
        //} else {
        //    menu.findItem(R.id.action_start).setVisible(true);
        //    menu.findItem(R.id.action_stop).setVisible(false);
        //}
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_start) {
            Log.d(TAG, "==== start ====");
            if (mReceiverIp != null) {
                startCaptureScreen();
                //invalidateOptionsMenu();
            } else {
                Toast.makeText(mContext, "Server mode", Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (id == R.id.action_stop) {
            Log.d(TAG, "==== stop ====");
            stopScreenCapture();
            //invalidateOptionsMenu();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != Activity.RESULT_OK) {
                Log.d(TAG, "User cancelled");
                Toast.makeText(mContext, "user cancelled", Toast.LENGTH_SHORT).show();
                return;
            }
            Log.d(TAG, "Starting screen capture");
            mResultCode = resultCode;
            mResultData = data;
            startCaptureScreen();
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mResultData != null) {
            outState.putInt(STATE_RESULT_CODE, mResultCode);
            outState.putParcelable(STATE_RESULT_DATA, mResultData);
        }
    }

    private void startCaptureScreen() {
        if (mResultCode != 0 && mResultData != null) {
            startService();
        } else {
            Log.d(TAG, "Requesting confirmation");
            // This initiates a prompt dialog for the user to confirm screen projection.
            startActivityForResult(
                    mMediaProjectionManager.createScreenCaptureIntent(),
                    REQUEST_MEDIA_PROJECTION);
        }
    }

    private void stopScreenCapture() {
        if (mServiceMessenger == null) {
            return;
        }
        final Intent stopCastIntent = new Intent(Common.ACTION_STOP_CAST);
        sendBroadcast(stopCastIntent);
        /*
        try {
            Message msg = Message.obtain(null, Common.MSG_STOP_CAST);
            mServiceMessenger.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send stop message to service");
            e.printStackTrace();
        }*/
    }

    private void startService() {
        if (mResultCode != 0 && mResultData != null && mReceiverIp != null) {
            Intent intent = new Intent(this, ScreenCapture.class);
            intent.putExtra(Common.EXTRA_RESULT_CODE, mResultCode);
            intent.putExtra(Common.EXTRA_RESULT_DATA, mResultData);
            intent.putExtra(Common.EXTRA_RECEIVER_IP, mReceiverIp);
            intent.putExtra(Common.EXTRA_VIDEO_FORMAT, mSelectedFormat);
            intent.putExtra(Common.EXTRA_SCREEN_WIDTH, mSelectedWidth);
            intent.putExtra(Common.EXTRA_SCREEN_HEIGHT, mSelectedHeight);
            intent.putExtra(Common.EXTRA_SCREEN_DPI, mSelectedDpi);
            intent.putExtra(Common.EXTRA_VIDEO_BITRATE, mSelectedBitrate);
            Log.d(TAG, "===== start service =====");
            startService(intent);
            bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        } else {
            Intent intent = new Intent(this, ScreenCapture.class);
            startService(intent);
            bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private void doUnbindService() {
        if (mServiceMessenger != null) {
            try {
                Message msg = Message.obtain(null, Common.MSG_UNREGISTER_CLIENT);
                msg.replyTo = mMessenger;
                mServiceMessenger.send(msg);
            } catch (RemoteException e) {
                Log.d(TAG, "Failed to send unregister message to service, e: " + e.toString());
                e.printStackTrace();
            }
            unbindService(mServiceConnection);
        }
    }
}
