package com.demo.dinhnguyen.screencapture;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Created by dinhnguyen on 10/7/2016.
 */

public class ScreenCapture extends Service {
    private final String TAG = "CastService";
    private final int NT_ID_CASTING = 0;
    private Handler mHandler = new Handler(new ServiceHandlerCallback());
    private Messenger mMessenger = new Messenger(mHandler);
    private ArrayList<Messenger> mClients = new ArrayList<Messenger>();
    private IntentFilter mBroadcastIntentFilter;
    private MediaProjectionManager mMediaProjectionManager;
    private int mResultCode;
    private Intent mResultData;
    private String mSelectedFormat;
    private int mSelectedWidth;
    private int mSelectedHeight;
    private int mSelectedDpi;
    private int mSelectedBitrate;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private Surface mInputSurface;
    private MediaCodec mVideoEncoder;
    private MediaCodec.BufferInfo mVideoBufferInfo;
    private ServerSocket mServerSocket;
    private Socket mSocket;
    private ServerSocket mDataServerSocket;
    private Socket mDataSocket;
    private OutputStream mSocketOutputStream;
    private boolean mRotate = false;



    private Handler mDrainHandler = new Handler();
    private Runnable mStartRecordingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!startScreenCapture()) {
                Log.e(TAG, "Failed to start capturing screen");
            }
        }
    };
    private Runnable mDrainEncoderRunnable = new Runnable() {
        @Override
        public void run() {
            drainEncoder();
        }
    };

    private class ServiceHandlerCallback implements Handler.Callback {
        @Override
        public boolean handleMessage(Message msg) {
            Log.d(TAG, "Handler got event, what: " + msg.what);
            switch (msg.what) {
                case Common.MSG_REGISTER_CLIENT: {
                    mClients.add(msg.replyTo);
                    break;
                }
                case Common.MSG_UNREGISTER_CLIENT: {
                    mClients.remove(msg.replyTo);
                    break;
                }
                case Common.MSG_STOP_CAST: {
                    stopScreenCapture();
                    closeSocket(true);
                    stopSelf();
                }
            }
            return false;
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Service receive broadcast action: " + action);
            if (action == null) {
                return;
            }
            if (Common.ACTION_STOP_CAST.equals(action)) {
                stopScreenCapture();
                closeSocket(true);
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mBroadcastIntentFilter = new IntentFilter();
        mBroadcastIntentFilter.addAction(Common.ACTION_STOP_CAST);
        registerReceiver(mBroadcastReceiver, mBroadcastIntentFilter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Destroy service");
        stopScreenCapture();
        closeSocket(true);
        unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        mResultCode = intent.getIntExtra(Common.EXTRA_RESULT_CODE, -1);
        mResultData = intent.getParcelableExtra(Common.EXTRA_RESULT_DATA);
        //if (mResultCode != Activity.RESULT_OK || mResultData == null) {
        //    Log.e(TAG, "Failed to start service, mResultCode: " + mResultCode + ", mResultData: " + mResultData);
        //    return START_NOT_STICKY;
        //}
        mSelectedWidth = intent.getIntExtra(Common.EXTRA_SCREEN_WIDTH, Common.DEFAULT_SCREEN_WIDTH);
        mSelectedHeight = intent.getIntExtra(Common.EXTRA_SCREEN_HEIGHT, Common.DEFAULT_SCREEN_HEIGHT);
        mSelectedDpi = intent.getIntExtra(Common.EXTRA_SCREEN_DPI, Common.DEFAULT_SCREEN_DPI);
        mSelectedBitrate = intent.getIntExtra(Common.EXTRA_VIDEO_BITRATE, Common.DEFAULT_VIDEO_BITRATE);
        mSelectedFormat = intent.getStringExtra(Common.EXTRA_VIDEO_FORMAT);
        if (mSelectedFormat == null) {
            mSelectedFormat = Common.DEFAULT_VIDEO_MIME_TYPE;
        }
        Log.d(TAG, "Start with listen mode");
        if (!createServerSocket()) {
            Log.e(TAG, "Failed to create socket to receiver");
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    private void showNotification() {
        final Intent notificationIntent = new Intent(Common.ACTION_STOP_CAST);
        PendingIntent notificationPendingIntent = PendingIntent.getBroadcast(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this);
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setDefaults(Notification.DEFAULT_ALL)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("screen capture")
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop), notificationPendingIntent);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NT_ID_CASTING, builder.build());
    }

    private void dismissNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NT_ID_CASTING);
    }

    private boolean startScreenCapture() {
        Log.d(TAG, "mResultCode: " + mResultCode + ", mResultData: " + mResultData);
        if (mResultCode != 0 && mResultData != null) {
            setUpMediaProjection();
            startRecording();
            showNotification();
            return true;
        }
        return false;
    }

    private void setUpMediaProjection() {
        mMediaProjection = mMediaProjectionManager.getMediaProjection(mResultCode, mResultData);
    }

    private void startRecording() {
        Log.d(TAG, "startRecording");
        prepareVideoEncoder();
        mVirtualDisplay = mMediaProjection.createVirtualDisplay("Recording Display", 1280,
                720, mSelectedDpi, 0 /* flags */, mInputSurface,
                null /* callback */, null /* handler */);
        mVideoEncoder.start();
        // Start the video input.

    }

    private void prepareVideoEncoder() {
        mVideoBufferInfo = new MediaCodec.BufferInfo();
        MediaFormat format = MediaFormat.createVideoFormat(mSelectedFormat, mSelectedWidth , mSelectedHeight );
        int frameRate = 30 ;//Common.DEFAULT_VIDEO_FPS;

        // Set some required properties. The media codec may fail if these aren't defined.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1024000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate);
        //format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / frameRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2); // 1 seconds between I-frames

        // Create a MediaCodec encoder and configure it. Get a Surface we can use for recording into.
        try {
            mVideoEncoder = MediaCodec.createEncoderByType(mSelectedFormat);
            mVideoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mVideoEncoder.createInputSurface();
        } catch (IOException e) {
            Log.e(TAG, "Failed to initial encoder, e: " + e);
            releaseEncoders();
        }
    }

    private boolean drainEncoder() {
        long start = System.currentTimeMillis();
        long data = 0;
        int frame = 0;
        mDrainHandler.removeCallbacks(mDrainEncoderRunnable);
        while (true) {
            int bufferIndex = mVideoEncoder.dequeueOutputBuffer(mVideoBufferInfo, 0);

            if (bufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // nothing available yet
                break;
            } else if (bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once

            } else if (bufferIndex < 0) {
                // not sure what's going on, ignore it
            } else {
                ByteBuffer encodedData = mVideoEncoder.getOutputBuffer(bufferIndex);
                if (encodedData == null) {
                    throw new RuntimeException("couldn't fetch buffer at index " + bufferIndex);
                }

                if (mVideoBufferInfo.size != 0) {
                    encodedData.position(mVideoBufferInfo.offset);
                    encodedData.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size);
                    if (mSocketOutputStream != null) {
                        try {
                            byte[] b = new byte[encodedData.remaining()];
                            encodedData.get(b);
                            long end = System.currentTimeMillis();
                            data += b.length;
                            frame += 1;
                            if((end - start) > 5000) {
                                System.out.println("Current bitrate:" + (data / 5));
                                System.out.println("FPS:" + (frame / 5));
                                start = end;
                                data = 0;
                                frame = 0;
                            }
                            mSocketOutputStream.write(b);
                        } catch (IOException e) {
                            Log.d(TAG, "Failed to write data to socket, stop casting");
                            e.printStackTrace();
                            stopScreenCapture();
                            return false;
                        }
                    }

                }

                mVideoEncoder.releaseOutputBuffer(bufferIndex, false);

                if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }

        mDrainHandler.postDelayed(mDrainEncoderRunnable, 10);
        return true;
    }

    private void stopScreenCapture() {
        dismissNotification();
        releaseEncoders();
        //closeSocket();
        if (mVirtualDisplay == null) {
            return;
        }
        mVirtualDisplay.release();
        mVirtualDisplay = null;
    }

    private void releaseEncoders() {
        mDrainHandler.removeCallbacks(mDrainEncoderRunnable);

        if (mVideoEncoder != null) {
            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;
        }
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        //mResultCode = 0;
        //mResultData = null;
        mVideoBufferInfo = null;
        //mTrackIndex = -1;
    }

    private boolean createServerSocket() {
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mServerSocket = new ServerSocket(Common.DATA_PORT);
                    while (!Thread.currentThread().isInterrupted() && !mServerSocket.isClosed()) {
                        mSocket = mServerSocket.accept();
                        CommunicationThread commThread = new CommunicationThread(mSocket);
                        new Thread(commThread).start();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create server socket or server socket error");
                    e.printStackTrace();
                }
            }
        });
        th.start();

        Thread th1 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mDataServerSocket = new ServerSocket(Common.CONTROL_PORT);
                    while (!Thread.currentThread().isInterrupted() && !mDataServerSocket.isClosed()) {
                        mDataSocket = mDataServerSocket.accept();
                        TestThread commThread = new TestThread(mDataSocket);
                        new Thread(commThread).start();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create server socket or server socket error");
                    e.printStackTrace();
                }
            }
        });
        th1.start();
        return true;
    }

    class TestThread implements Runnable {
        private Socket mClientSocket;

        public TestThread(Socket clientSocket) {
            mClientSocket = clientSocket;
        }

        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    OutputStream mOutputStream = mClientSocket.getOutputStream();
                    BufferedReader input = new BufferedReader(new InputStreamReader(mClientSocket.getInputStream()));
                    while(true) {
                        String data = input.readLine();
                        Log.d(TAG, "Got data from socket: " + data);
                        if (data == null) {
                            mClientSocket.close();
                            return;
                        }

                        if (data.equalsIgnoreCase("start")) {
                            mHandler.post(mStartRecordingRunnable);
                            mDrainHandler.postDelayed(mDrainEncoderRunnable, 10);
                            mOutputStream.write("test1".getBytes());
                        }else if (data.equalsIgnoreCase("stop")) {
                            stopScreenCapture();
                            mOutputStream.write("test1".getBytes());
                        }else if (data.equalsIgnoreCase("end")) {
                            stopScreenCapture();
                            closeSocket();
                            mOutputStream.write("test1".getBytes());
                        }else if (data.equalsIgnoreCase("rotate")) {
                            stopScreenCapture();
                            int temp = mSelectedHeight;
                            mSelectedHeight = mSelectedWidth;
                            mSelectedWidth = temp;
                            mRotate = true;
                            mHandler.post(mStartRecordingRunnable);
                            mDrainHandler.postDelayed(mDrainEncoderRunnable, 10);
                            mOutputStream.write("test1".getBytes());
                        } else if (data.equalsIgnoreCase("360p")) {
                            stopScreenCapture();
                            if(!mRotate) {
                                mSelectedHeight = 360;
                                mSelectedWidth = 480;
                            }
                            else {
                                mSelectedHeight = 480;
                                mSelectedWidth = 360;
                            }
                            mHandler.post(mStartRecordingRunnable);
                            mDrainHandler.postDelayed(mDrainEncoderRunnable, 10);
                            mOutputStream.write("test4".getBytes());
                        } else if (data.equalsIgnoreCase("480p")) {
                            stopScreenCapture();
                            if(!mRotate) {
                                mSelectedHeight = 480;
                                mSelectedWidth = 640;
                            }
                            else {
                                mSelectedHeight = 640;
                                mSelectedWidth = 480;
                            }
                            mHandler.post(mStartRecordingRunnable);
                            mDrainHandler.postDelayed(mDrainEncoderRunnable, 10);
                            mOutputStream.write("test5".getBytes());
                        } else if (data.equalsIgnoreCase("720p")) {
                            stopScreenCapture();
                            if(!mRotate) {
                                mSelectedHeight = 720;
                                mSelectedWidth = 1280;
                            }
                            else {
                                mSelectedHeight = 1280;
                                mSelectedWidth = 720;
                            }
                            mHandler.post(mStartRecordingRunnable);
                            mDrainHandler.postDelayed(mDrainEncoderRunnable, 10);
                            mOutputStream.write("test5".getBytes());
                        }
                        mOutputStream.flush();
                    }
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mClientSocket = null;
                mSocketOutputStream = null;
            }
        }
    }

    class CommunicationThread implements Runnable {
        private Socket mClientSocket;

        public CommunicationThread(Socket clientSocket) {
            mClientSocket = clientSocket;
        }

        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    /*
                    BufferedReader input = new BufferedReader(new InputStreamReader(mClientSocket.getInputStream()));
                    String data = input.readLine();
                    Log.d(TAG, "Got data from socket: " + data);
                    if (data == null || !data.equalsIgnoreCase("mirror")) {
                        mClientSocket.close();
                        return;
                    }
                    */
                    mSocketOutputStream = mClientSocket.getOutputStream();
                    //osw.write(String.format(HTTP_MESSAGE_TEMPLATE, mSelectedWidth, mSelectedHeight));
                    //osw.flush();
                    mSocketOutputStream.flush();
                    if (mSocketOutputStream != null) {
                        //drainEncoder();
                    }

                    return;
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mClientSocket = null;
                mSocketOutputStream = null;
            }
        }
    }

    private void closeSocket() {
        closeSocket(false);
    }

    private void closeSocket(boolean closeServerSocket) {
        if (mSocket != null) {
            try {
                mSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (closeServerSocket) {
            if (mServerSocket != null) {
                try {
                    mServerSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            mServerSocket = null;
        }
        mSocket = null;
        mSocketOutputStream = null;
    }
}
