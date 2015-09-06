package com.intel.xiangxiao.glcamerademo;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;


public class GLCameraDemo extends Activity implements TextureView.SurfaceTextureListener, Button.OnClickListener {
    private static final String TAG = "GLCameraDemo";
    private TextureView mTextureView;
    private SurfaceTexture mSurface;
    private Button mButton;
    private boolean mStatus;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    private CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCameraCaptureSession;
    private String mCameraId;
    private ImageReader mCameraBuffer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity_view);
        mTextureView = (TextureView)findViewById(R.id.camera_view);
        mTextureView.setRotation(180.0f);
        mButton = (Button)findViewById(R.id.switch_button);
        mButton.setText("start");
        mButton.setOnClickListener(this);
        mStatus = false;
        //setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        mSurface.setDefaultBufferSize(0, 0);

        try {
            if (mCameraCaptureSession != null){
                mCameraCaptureSession.close();
                mCameraCaptureSession = null;
            }
        } finally {
            if (mCameraDevice != null){
                mCameraDevice.close();
                mCameraDevice = null;
                mCameraManager = null;
                mCameraId = null;
            }
        }

        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
        } catch (InterruptedException ex) {
            Log.d(TAG, "Background work thread was interrupted while joined", ex);
            ex.printStackTrace();
        }

        if (mCameraBuffer != null){
            mCameraBuffer.close();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        mTextureView.setSurfaceTextureListener(this);
        mBackgroundThread = new HandlerThread("background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
    }

    private void initCamera2(SurfaceTexture surfaceTexture, int width, int height){
        Log.d(TAG, "init camera");
        mCameraManager = (CameraManager)getSystemService(CAMERA_SERVICE);
        try {
            for (String cameraid : mCameraManager.getCameraIdList()){
                CameraCharacteristics cameraCharacteristics = mCameraManager.getCameraCharacteristics(cameraid);
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK){
                    Log.d(TAG, "Found a back-facing camera.");

                    StreamConfigurationMap info = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                    Size largestSize = Collections.max(Arrays.asList(info.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
                    Log.d(TAG, "Capture size: " + largestSize);

                    mCameraBuffer = ImageReader.newInstance(largestSize.getWidth(),
                            largestSize.getHeight(), ImageFormat.JPEG, 2);
                    mCameraBuffer.setOnImageAvailableListener(mImageCaptureListener, mBackgroundHandler);

                    Log.d(TAG, "mTextureView size: " + mTextureView.getWidth() + "x" + mTextureView.getHeight());
                    Size optimalSize = chooseBigEnoughSize(info.getOutputSizes(surfaceTexture.getClass()),width, height);
                    Log.d(TAG, "preview size: " + optimalSize);
                    surfaceTexture.setDefaultBufferSize(optimalSize.getWidth(), optimalSize.getHeight());

                    mCameraId = cameraid;
                    mSurface = surfaceTexture;

                    mCameraManager.openCamera(mCameraId, mCameraStateCallback, mBackgroundHandler);
                    return;
                }
            }
        } catch (CameraAccessException ex){
            Log.e(TAG, "open camera2 failed." + ex.getMessage());
            ex.printStackTrace();
        }
    }

    /**
     * Invoked when a {@link TextureView}'s SurfaceTexture is ready for use.
     *
     * @param surface The surface returned by
     *                {@link TextureView#getSurfaceTexture()}
     * @param width   The width of the surface
     * @param height  The height of the surface
     */
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "onSurfaceTextureAvailable");
        initCamera2(surface, width, height);
    }

    /**
     * Invoked when the {@link SurfaceTexture}'s buffers size changed.
     *
     * @param surface The surface returned by
     *                {@link TextureView#getSurfaceTexture()}
     * @param width   The new width of the surface
     * @param height  The new height of the surface
     */
    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

    }

    /**
     * Invoked when the specified {@link SurfaceTexture} is about to be destroyed.
     * If returns true, no rendering should happen inside the surface texture after this method
     * is invoked. If returns false, the client needs to call {@link SurfaceTexture#release()}.
     * Most applications should return true.
     *
     * @param surface The surface about to be destroyed
     */
    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return false;
    }

    /**
     * Invoked when the specified {@link SurfaceTexture} is updated through
     * {@link SurfaceTexture#updateTexImage()}.
     *
     * @param surface The surface just updated
     */
    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {

    }

    /**
     * Called when a view has been clicked.
     *
     * @param v The view that was clicked.
     */
    @Override
    public void onClick(View v) {
        mStatus = !mStatus;
        if (mStatus){
            Log.d(TAG, "onClick: true");
            mButton.setText("start");
        } else {
            Log.d(TAG, "onClick: false");
            mButton.setText("stop");
        }
    }

    static class CompareSizesByArea implements Comparator<Size> {

        /**
         * Compares the two specified objects to determine their relative ordering. The ordering
         * implied by the return value of this method for all possible pairs of
         * {@code (lhs, rhs)} should form an <i>equivalence relation</i>.
         * This means that
         * <ul>
         * <li>{@code compare(a,a)} returns zero for all {@code a}</li>
         * <li>the sign of {@code compare(a,b)} must be the opposite of the sign of {@code
         * compare(b,a)} for all pairs of (a,b)</li>
         * <li>From {@code compare(a,b) > 0} and {@code compare(b,c) > 0} it must
         * follow {@code compare(a,c) > 0} for all possible combinations of {@code
         * (a,b,c)}</li>
         * </ul>
         *
         * @param lhs an {@code Object}.
         * @param rhs a second {@code Object} to compare with {@code lhs}.
         * @return an integer < 0 if {@code lhs} is less than {@code rhs}, 0 if they are
         * equal, and > 0 if {@code lhs} is greater than {@code rhs}.
         * @throws ClassCastException if objects are not of the correct type.
         */
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long)lhs.getWidth()*lhs.getHeight()-(long)rhs.getWidth()*rhs.getHeight());
        }
    }

    final CameraDevice.StateCallback mCameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            Log.d(TAG, "open camera2 successfully.");
            mCameraDevice = camera;
            try {
                mCameraDevice.createCaptureSession(Arrays.asList(new Surface(mSurface)), mCaptureStateCallback, mBackgroundHandler);
            } catch (CameraAccessException ex) {
                Log.e(TAG, "Fail to create a camera capture session.");
                ex.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {

        }

        @Override
        public void onError(CameraDevice camera, int error) {

        }
    };

    final CameraCaptureSession.StateCallback mCaptureStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(CameraCaptureSession session) {
            Log.d(TAG, "finish configure camera.");
            mCameraCaptureSession = session;

            try {
                CaptureRequest.Builder requestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                requestBuilder.addTarget(new Surface(mSurface));

                try {
                    mCameraCaptureSession.setRepeatingRequest(requestBuilder.build(), null, mBackgroundHandler);
                } catch (CameraAccessException ex) {
                    Log.d(TAG, "setRepeatingRequest failed.");
                    ex.printStackTrace();
                }

            } catch (CameraAccessException ex) {
                Log.d(TAG, "createCaptureRequest failed.");
                ex.printStackTrace();
            }

        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            Log.d(TAG, "configuration error!");
        }

        @Override
        public void onClosed(CameraCaptureSession session) {
            super.onClosed(session);
            mCameraCaptureSession = null;
        }
    };

    final ImageReader.OnImageAvailableListener mImageCaptureListener = new ImageReader.OnImageAvailableListener(){
        /**
         * Callback that is called when a new image is available from ImageReader.
         *
         * @param reader the ImageReader the callback is associated with.
         * @see ImageReader
         * @see Image
         */
        @Override
        public void onImageAvailable(ImageReader reader) {

        }
    };

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values.
     * @param choices The list of sizes that the camera supports for the intended output class
     * @param width The minimum desired width
     * @param height The minimum desired height
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    static Size chooseBigEnoughSize(Size[] choices, int width, int height) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        for (Size option : choices) {
            Log.d(TAG, "option size: " + option.getWidth() + "x" + option.getHeight());
            /*Attention: As internal format's sizes are different
              with android view size,so wo need to invert this*/
            if (option.getWidth() >= height && option.getHeight() >= width) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }
}






