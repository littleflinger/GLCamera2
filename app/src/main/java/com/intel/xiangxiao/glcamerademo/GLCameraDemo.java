package com.intel.xiangxiao.glcamerademo;

import android.app.Activity;
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
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;


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

class GLRenderThread extends Thread implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "CLRenderThread";

    private static final int FLOAT_SIZE = 4;
    private static final int TRIANGLE_VERTICES_STRIDE = 5 * FLOAT_SIZE;
    private static final int TRIANGLE_VERTICES_POS_OFFSET = 0;
    private static final int TRIANGLE_VERTICES_UV_OFFSET = 3;
    private final float[] mBigTriangleVerticesData = {
            -4.5f, -8.0f, 0, 0.f, 0.f,
             4.5f, -8.0f, 0, 1.f, 0.f,
            -4.5f,  8.0f, 0, 0.f, 1.f,
             4.5f,  8.0f, 0, 1.f, 1.f
    };
    private final float[] mSmallTriangleVerticesData = {
            2.25f,  4.0f, 0, 0.f, 0.f,
             4.5f,  4.0f, 0, 1.f, 0.f,
            2.25f,  8.0f, 0, 0.f, 1.f,
             4.5f,  8.0f, 0, 1.f, 1.f
    };

    private FloatBuffer mBigTriangleVertices;
    private FloatBuffer mSmallTriangleVertices;

    private final String mVertexShader =
            "uniform mat4 uMPMatrix;                            \n" +
            "uniform mat4 uSTMatrix;                            \n" +
            "uniform float uRatio;                              \n" +
            "attribute vec4 aPosition;                          \n" +
            "attribute vec4 aTextureCoord;                      \n" +
            "varying vec2 vTextureCoord;                        \n" +
            "void main() {                                      \n" +
            "   vec4 scaledPos = aPosition;                     \n" +
            "   scaledPos.x = scaledPos.x * uRatio;             \n" +
            "   gl_Position = uMPMatrix * scaledPos;            \n" +
            "   vTextureCoord = (uSTMatrix * aTextureCoord).xy; \n" +
            "}                                                  \n";

    private final String mFragmentShader =
            "#extension GL_OES_EGL_image_external : require     \n" +
            "precision mediump float;                           \n" +
            "varying vec2 vTextureCoord;                        \n" +
            "uniform samplerExternalOES sTexture;               \n" +
            "void main() {                                      \n" +
            "   gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}                                                  \n";

    private float[] mMPMatrix = new float[16];
    private float[] mProjMatrix = new float[16];
    private float[] mMMatrix = new float[16];
    private float[] mVMatrix = new float[16];
    private float[] mSTMatrix = new float[16];
    private float mRatio = 1.0f;
    private float mCameraRatio = 1.0f;

    private int maPositionHandle;
    private int maTextureCoordHandle;
    private int muMPMatrixHandle;
    private int muSTMatrixHandle;
    private int muRatioHandle;

    private EGL10 mEGL;
    private EGLDisplay mEGLDisplay;
    private EGLSurface mEGLSurface;
    private EGLContext mEGLContext;
    private EGLConfig mEGLConfig;

    private SurfaceTexture currentWindowSurface, mSurface;
    private int mTextureViewWidth, mTextureViewHeight;
    private int mTextureID;

    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final int EGL_CONTEXT_CLIENT_VERSTION = 0x3098;
    private static final int EGL_OPENGL_ES2_BIT = 4;

    public GLRenderThread(SurfaceTexture surface, int width, int height) {
        currentWindowSurface = surface;
        mTextureViewWidth = width;
        mTextureViewHeight = height;

        mBigTriangleVertices = ByteBuffer.allocateDirect(mBigTriangleVerticesData.length
                * FLOAT_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mBigTriangleVertices.put(mBigTriangleVerticesData).position(0);

        mSmallTriangleVertices = ByteBuffer.allocateDirect(mSmallTriangleVerticesData.length
                * FLOAT_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mSmallTriangleVertices.put(mSmallTriangleVerticesData).position(0);

        Matrix.setIdentityM(mSTMatrix, 0);
        Matrix.setIdentityM(mMMatrix, 0);

        mEGLSurface = EGL10.EGL_NO_SURFACE;
        mEGLDisplay = EGL10.EGL_NO_DISPLAY;
        mEGLContext = EGL10.EGL_NO_CONTEXT;
    }

    @Override
    public void run() {
        initEGL();
        initOpenGLES2();
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {

    }

    private void initEGL(){
        Log.d(TAG, "start initialize EGL");
        mEGL = (EGL10)EGLContext.getEGL();

        mEGLDisplay = mEGL.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        if (mEGLDisplay == EGL10.EGL_NO_DISPLAY) {
            throw new RuntimeException("eglGetDisplay failed "
                    + GLUtils.getEGLErrorString(mEGL.eglGetError()));
        }

        int[] major_minor = new int[2];
        if (!mEGL.eglInitialize(mEGLDisplay,major_minor)) {
            throw new RuntimeException("eglInitialize failed "
                    + GLUtils.getEGLErrorString(mEGL.eglGetError()));
        }

        int[] numConfigs = new int[1];
        EGLConfig[] configs = new EGLConfig[1];
        int[] attribList = {
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 8,
                EGL10.EGL_STENCIL_SIZE, 0,
                EGL10.EGL_NONE
        };

        EGLConfig eglConfig;
        if (!mEGL.eglChooseConfig(mEGLDisplay, attribList, configs, 1, numConfigs)) {
            throw new RuntimeException("eglChooseConfig failed "
                    + GLUtils.getEGLErrorString(mEGL.eglGetError()));
        } else if (numConfigs[0] > 0) {
            eglConfig = configs[0];
        } else {
            throw new RuntimeException("optimized eglConfig have not found.");
        }

        mEGLSurface = mEGL.eglCreateWindowSurface(mEGLDisplay, eglConfig, currentWindowSurface, null);
        checkEGLError("eglCreateWindowSurface");

        int[] attrib_list = {
                EGL_CONTEXT_CLIENT_VERSTION, 2,
                EGL10.EGL_NONE
        };
        mEGLContext = mEGL.eglCreateContext(mEGLDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, attrib_list);
        checkEGLError("eglCreateContext");

        mEGLConfig = eglConfig;

        mEGL.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext);
        checkEGLError("eglMakeCurrent");

        Log.d(TAG, "finish initialize EGL");
    }
    private void initOpenGLES2() {
        Log.d(TAG, "start initialize OPenGL ES2.0");

        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, mVertexShader);
        if (vertexShader == 0) {
            return;
        }
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, mFragmentShader);
        if (fragmentShader == 0) {
            return;
        }

        int program = GLES20.glCreateProgram();
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program : ");
                Log.e(TAG, GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                return;
            }
        }

        maPositionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        if (maPositionHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aPosition");
        }
        maTextureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
        if (maTextureCoordHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aTextureCoord");
        }
        muMPMatrixHandle = GLES20.glGetUniformLocation(program, "uMPMatrix");
        if (muMPMatrixHandle == -1) {
            throw new RuntimeException("Could not get uniform location for uMPMatrix");
        }
        muSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix");
        if (muSTMatrixHandle == -1) {
            throw new RuntimeException("Could not get uniform location for uSTMatrix");
        }
        muRatioHandle = GLES20.glGetUniformLocation(program, "uRatio");
        if (muRatioHandle == -1) {
            throw new RuntimeException("Could not get uniform location for uRatio");
        }

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mTextureID = textures[0];
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID);

        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        mSurface = new SurfaceTexture(mTextureID);
        mSurface.setOnFrameAvailableListener(this);

        if (mSurface != currentWindowSurface) {
            Log.d(TAG, "mSurface is different form currentWindowSurface");
        }

        Matrix.setLookAtM(mVMatrix, 0, 0.0f, 0.0f, 3.0f, 0.0f, 0.0f, 0.0f, 0.0f, 8.0f, 0.0f);
        GLES20.glViewport(0, 0, mTextureViewWidth, mTextureViewHeight);
        mRatio = (float)mTextureViewWidth / mTextureViewHeight;
        Matrix.frustumM(mProjMatrix, 0, -(mRatio * 8), mRatio * 8, -8.0f, 8.0f, 3.0f, 15.0f);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.643f, 0.776f, 0.223f, 1.0f);
    }

    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        if (shader != 0) {
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] isCompiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, isCompiled, 0);
            if (isCompiled[0] == 0) {
                Log.e(TAG, "Could not compile shader " + shaderType + ":");
                Log.e(TAG, GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
        }
        return shader;
    }
    private void checkEGLError(String op) {
        Log.d(TAG, op);
        if (mEGL.eglGetError() != EGL10.EGL_SUCCESS) {
            throw new RuntimeException(op + ": eglError: " + GLUtils.getEGLErrorString(mEGL.eglGetError()));
        }
    }
}






