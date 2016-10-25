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
    private String mSelectedFormat = FORMAT_OPTIONS[0];
    private int mSelectedWidth = RESOLUTION_OPTIONS[0][0];
    private int mSelectedHeight = RESOLUTION_OPTIONS[0][1];
    private int mSelectedDpi = RESOLUTION_OPTIONS[0][2];
    private int mSelectedBitrate = BITRATE_OPTIONS[0];
    private int mResultCode;
    private Intent mResultData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mResultCode = savedInstanceState.getInt(STATE_RESULT_CODE);
            mResultData = savedInstanceState.getParcelable(STATE_RESULT_DATA);
        }

        mContext = this;
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        //startService();
        startCaptureScreen();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
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

    private void startService() {
        Intent intent = new Intent(this, ScreenCapture.class);
        intent.putExtra(Common.EXTRA_RESULT_CODE, mResultCode);
        intent.putExtra(Common.EXTRA_RESULT_DATA, mResultData);
        intent.putExtra(Common.EXTRA_VIDEO_FORMAT, mSelectedFormat);
        intent.putExtra(Common.EXTRA_SCREEN_WIDTH, mSelectedWidth);
        intent.putExtra(Common.EXTRA_SCREEN_HEIGHT, mSelectedHeight);
        intent.putExtra(Common.EXTRA_SCREEN_DPI, mSelectedDpi);
        intent.putExtra(Common.EXTRA_VIDEO_BITRATE, mSelectedBitrate);
        Log.d(TAG, "===== start service =====");
        startService(intent);
        finish();
    }
}
