package com.intel.xiangxiao.glcamerademo;

import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;


public class GLCameraDemo extends Activity implements TextureView.SurfaceTextureListener, Button.OnClickListener {
    private static final String TAG = "GLCameraDemo";
    private TextureView mTextureView;
    private Button mButton;
    private boolean mStatus;
    private int mWidth, mHeight;
    private Context mContext;

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private CameraManager mCameraManager;
    private GLRenderThread mGLRenderThread;
    private VideoEncoderThread mVideoEncoderThread;
    private VideoEncoder mVideoEncoder;

    final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    Log.d(TAG, "The Camera frame rate is: " + mGLRenderThread.mCameraPreviewFrameRate);
                    Log.d(TAG, "The Video frame rate is: " + mGLRenderThread.mVideoPlaybackFrameRate);
                    Log.d(TAG, "The GLRender frame rate is: " + mGLRenderThread.mGLRenderFrameRate);
                    mGLRenderThread.mCameraPreviewFrameRate = 0;
                    mGLRenderThread.mVideoPlaybackFrameRate = 0;
                    mGLRenderThread.mGLRenderFrameRate = 0;
                    break;
                case 2:
                    Toast.makeText(mContext, "Please use system Camera app record a video firstly", Toast.LENGTH_LONG).show();
                    break;
                default:
                    break;
            }
            super.handleMessage(msg);
        }
    };

    Timer timer = new Timer();
    TimerTask timerTask = new TimerTask() {
        @Override
        public void run() {
            Message message = new Message();
            message.what = 1;
            mHandler.sendMessage(message);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = getApplicationContext();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.main_activity_view);
        mTextureView = (TextureView)findViewById(R.id.camera_view);
        //mTextureView.setRotation(180.0f);
        mButton = (Button)findViewById(R.id.switch_button);
        mButton.setText("start");
        mButton.setOnClickListener(this);
        mStatus = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        if (mGLRenderThread != null) {
            mGLRenderThread.onPause();
        }
        if (mVideoEncoderThread != null) {
            try {
                mVideoEncoderThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            mVideoEncoderThread = null;
        }
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        mCameraManager = (CameraManager)getSystemService(CAMERA_SERVICE);
        mTextureView.setSurfaceTextureListener(this);
        mBackgroundThread = new HandlerThread("backgroundThread");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        if (mGLRenderThread != null) {
            mGLRenderThread.onResume();
        }
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

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "onSurfaceTextureAvailable");
        //initCamera2(surface, width, height);
        mWidth = width;
        mHeight = height;
        mGLRenderThread = new GLRenderThread(mContext, mHandler, mBackgroundThread, mBackgroundHandler,
                surface, width, height, mCameraManager);
        mGLRenderThread.start();
        timer.schedule(timerTask, 2000, 1000);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        //Log.d(TAG, "onSurfaceTextureUpdated");
    }

    @Override
    public void onClick(View v) {
        if (mStatus){
            mGLRenderThread.removeVideoEncoderSurface();
            /*mVideoEncoderThread.stopVideoRecord();
            try {
                mVideoEncoderThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            mVideoEncoderThread = null;*/
            mVideoEncoder.stopVideoRecorder();
            mVideoEncoder = null;
            Log.d(TAG, "onClick: true");
            mButton.setText("start");
        } else {/*
            try {
                mVideoEncoderThread = new VideoEncoderThread(720, 1280);
            } catch (IOException e) {
                e.printStackTrace();
            }
            mVideoEncoderThread.start();*/
            mVideoEncoder = new VideoEncoder(720, 1280);
            mGLRenderThread.setVideoEncoderSurface(mVideoEncoder.getVideoEncoderSurface());
            mVideoEncoder.startVideoRecorder();
            Log.d(TAG, "onClick: false");
            mButton.setText("stop");
        }
        mStatus = !mStatus;
    }
}

class GLRenderThread extends Thread {
    private static final String TAG = "CLRenderThread";

    private static final int FLOAT_SIZE = 4;
    private static final int TRIANGLE_VERTICES_STRIDE = 5 * FLOAT_SIZE;
    private static final int TRIANGLE_VERTICES_POS_OFFSET = 0;
    private static final int TRIANGLE_VERTICES_UV_OFFSET = 3;

    private final float[] mBigTriangleVerticesData = {
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0, 0.f, 0.f,
             1.0f, -1.0f, 0, 1.f, 0.f,
            -1.0f,  1.0f, 0, 0.f, 1.f,
             1.0f,  1.0f, 0, 1.f, 1.f,
    };
    private final float[] mSmallTriangleVerticesData = {
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0, 0.f, 0.f,
             0.0f, -1.0f, 0, 1.f, 0.f,
            -1.0f,  0.0f, 0, 0.f, 1.f,
             0.0f,  0.0f, 0, 1.f, 1.f,
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
            "uniform int  uFlag;                                \n" +
            "uniform samplerExternalOES sTexture0;              \n" +
            "uniform samplerExternalOES sTexture1;              \n" +
            "void main() {                                      \n" +
            "   vec4 baseColor;                                 \n" +
            "   vec4 topColor;                                  \n" +
            "   baseColor = texture2D(sTexture0, vTextureCoord);\n" +
            "   topColor = texture2D(sTexture1, vTextureCoord); \n" +
            "   if(uFlag == 1){                                 \n" +
            "       gl_FragColor = baseColor;                   \n" +
            "   } else {                                        \n" +
            "       gl_FragColor = topColor;                    \n" +
            "   }                                               \n" +
            "}                                                  \n";

    private float[] mMPMatrix = new float[16];
    private float[] mProjMatrix = new float[16];
    private float[] mMMatrix = new float[16];
    private float[] mVMatrix = new float[16];
    private float[] mSTMatrix = new float[16];
    private float mRatio = 1.0f;

    private int maPositionHandle;
    private int maTextureCoordHandle;
    private int muMPMatrixHandle;
    private int muSTMatrixHandle;
    private int muRatioHandle;
    private int muFlagHandle;
    private int msTexture0Handle;
    private int msTexture1Handle;

    private EGL10 mEGL;
    private EGLDisplay mEGLDisplay;
    private EGLSurface mEGLSurface, mVideoEncoderEGLSurface;
    private EGLContext mEGLContext;
    private EGLConfig mEGLConfig;

    private SurfaceTexture currentWindowSurface, mCameraSurface, mVideoSurface;
    private int mTextureViewWidth, mTextureViewHeight;
    private int[] textures = new int[2];
    private int mTextureID0, mTextureID1;
    private int mProgram;
    private boolean updateCameraSurface = false, updateVideoSurface = false;
    private boolean isPreviewing = true;

    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final int EGL_CONTEXT_CLIENT_VERSTION = 0x3098;
    private static final int EGL_OPENGL_ES2_BIT = 4;

    private CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCameraCaptureSession;
    private String mCameraId;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    private final String lock0 = "true";
    private final String lock1 = "true";
    private MediaPlayer mMediaPlayer;
    private static final File OUTPUT_DIR = Environment.getExternalStorageDirectory();

    public int mCameraPreviewFrameRate, mVideoPlaybackFrameRate, mGLRenderFrameRate;

    private Context mContext;
    private Handler mMainThreadHandler;
    public GLRenderThread(Context context, Handler mainThreadHandler, HandlerThread handlerThread, Handler backgroundHandler, SurfaceTexture surface, int width, int height, CameraManager cameraManager) {
        mContext = context;
        mMainThreadHandler = mainThreadHandler;
        mBackgroundThread = handlerThread;
        mBackgroundHandler = backgroundHandler;
        currentWindowSurface = surface;
        mTextureViewWidth = width;
        mTextureViewHeight = height;
        mCameraManager = cameraManager;

        mBigTriangleVertices = ByteBuffer.allocateDirect(mBigTriangleVerticesData.length
                * FLOAT_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mBigTriangleVertices.put(mBigTriangleVerticesData).position(0);

        mSmallTriangleVertices = ByteBuffer.allocateDirect(mSmallTriangleVerticesData.length
                * FLOAT_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mSmallTriangleVertices.put(mSmallTriangleVerticesData).position(0);

        Matrix.setIdentityM(mSTMatrix, 0);
        Matrix.setIdentityM(mMMatrix, 0);

        mVideoEncoderEGLSurface = EGL10.EGL_NO_SURFACE;

        mCameraPreviewFrameRate = 0;
        mVideoPlaybackFrameRate = 0;
        mGLRenderFrameRate = 0;
    }

    @Override
    public void run() {
        initEGL();
        initOpenGLES2();
        while (isPreviewing) {
            synchronized (lock0) {
                if (updateCameraSurface) {
//                    Log.d(TAG, "camera preview update.");
                    mCameraSurface.updateTexImage();
                    mCameraSurface.getTransformMatrix(mSTMatrix);
                    updateCameraSurface = false;
                    mCameraPreviewFrameRate++;
                }
            }
            synchronized (lock1) {
                if (updateVideoSurface) {
//                    Log.d(TAG, "video playback update.");
                    mVideoSurface.updateTexImage();
                    updateVideoSurface = false;
                    mVideoPlaybackFrameRate++;
                }
            }
            GLES20.glViewport(0, 0, mTextureViewWidth, mTextureViewHeight);
            mGLRenderFrameRate++;
            compositeFrame(mEGLSurface);
            synchronized(mVideoEncoderEGLSurface) {
                if (mVideoEncoderEGLSurface != EGL10.EGL_NO_SURFACE) {
                    GLES20.glViewport(0, 0, 720, 1280);
                    compositeFrame(mVideoEncoderEGLSurface);
                }
            }
        }
        termGL();
    }

    public void onPause() {
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

        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.release();
            mMediaPlayer = null;
        }

        isPreviewing = false;
    }

    public void onResume() {
        isPreviewing = true;
    }

    public void setVideoEncoderSurface(Surface surface) {
        mVideoEncoderEGLSurface = mEGL.eglCreateWindowSurface(mEGLDisplay,
                mEGLConfig, surface, null);
        checkEGLError("eglCreateWindowSurface : mVideoEncoderEGLSurface");
    }

    public void removeVideoEncoderSurface() {
        synchronized (mVideoEncoderEGLSurface) {
            if (mVideoEncoderEGLSurface != EGL10.EGL_NO_SURFACE) {
                mEGL.eglDestroySurface(mEGLDisplay, mVideoEncoderEGLSurface);
                mVideoEncoderEGLSurface = EGL10.EGL_NO_SURFACE;
            }
        }
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
        mProgram = program;

        maPositionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        checkGLError("glGetAttribLocation aPosition");
        if (maPositionHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aPosition");
        }
        maTextureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
        checkGLError("glGetAttribLocation aTextureCoord");
        if (maTextureCoordHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aTextureCoord");
        }
        muMPMatrixHandle = GLES20.glGetUniformLocation(program, "uMPMatrix");
        checkGLError("glGetUniformLocation uMPMatrix");
        if (muMPMatrixHandle == -1) {
            throw new RuntimeException("Could not get uniform location for uMPMatrix");
        }
        muSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix");
        checkGLError("glGetUniformLocation uSTMatrix");
        if (muSTMatrixHandle == -1) {
            throw new RuntimeException("Could not get uniform location for uSTMatrix");
        }
        muRatioHandle = GLES20.glGetUniformLocation(program, "uRatio");
        checkGLError("glGetUniformLocation uRatio");
        if (muRatioHandle == -1) {
            throw new RuntimeException("Could not get uniform location for uRatio");
        }
        muFlagHandle = GLES20.glGetUniformLocation(mProgram, "uFlag");
        checkGLError("glGetUniformLocation uFlag");
        if (muFlagHandle == -1) {
            throw new RuntimeException("Could not get uniform location for uFlag");
        }
        msTexture0Handle = GLES20.glGetUniformLocation(mProgram, "sTexture0");
        checkGLError("glGetUniformLocation sTexture0");
        if (msTexture0Handle == -1) {
            throw new RuntimeException("Could not get uniform location for sTexture0");
        }
        msTexture1Handle = GLES20.glGetUniformLocation(mProgram, "sTexture1");
        checkGLError("glGetUniformLocation sTexture1");
        if (msTexture1Handle == -1) {
            throw new RuntimeException("Could not get uniform location for sTexture1");
        }

        GLES20.glGenTextures(2, textures, 0);
        mTextureID0 = textures[0];
        mTextureID1 = textures[1];
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID0);
        checkGLError("glBindTexture mTextureID0");
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        mCameraSurface = new SurfaceTexture(mTextureID0);
        mCameraSurface.setOnFrameAvailableListener(
                new SurfaceTexture.OnFrameAvailableListener() {
                    @Override
                    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                        synchronized (lock0) {
                            updateCameraSurface = true;
                            //Log.d(TAG, "CameraFrameAvailable!");
                        }
                    }
                }
        );
        initCamera2();

        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID1);
        checkGLError("glBindTexture mTextureID1");
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        mVideoSurface = new SurfaceTexture(mTextureID1);
        mVideoSurface.setOnFrameAvailableListener(
                new SurfaceTexture.OnFrameAvailableListener() {
                    @Override
                    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                        synchronized (lock1) {
                            updateVideoSurface = true;
                            //Log.d(TAG, "VideoFrameAvailable!");
                        }
                    }
                }
        );
        initMediaPlayer();

        //Viewport Transform
        GLES20.glViewport(0, 0, mTextureViewWidth, mTextureViewHeight);
        //Projection Transform
        Matrix.frustumM(mProjMatrix, 0, -mRatio, mRatio, -1.0f, 1.0f, 1.0f, 300.0f);
        /* set the LookAt's up direction to negative, since
         * the textureView orientation is upside-down
         */
        //Model-View Transform
        Matrix.setLookAtM(mVMatrix, 0, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);

        Matrix.multiplyMM(mMPMatrix, 0, mVMatrix, 0, mMMatrix, 0);
        Matrix.multiplyMM(mMPMatrix, 0, mProjMatrix, 0, mMPMatrix, 0);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.643f, 0.776f, 0.223f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(mProgram);
        checkGLError("glUseProgram");

        GLES20.glUniformMatrix4fv(muMPMatrixHandle, 1, false, mMPMatrix, 0);
    }

    private void initCamera2() {
        Log.d(TAG, "init camera");
        try {
            for (String cameraid : mCameraManager.getCameraIdList()){
                CameraCharacteristics cameraCharacteristics = mCameraManager.getCameraCharacteristics(cameraid);
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK){
                    Log.d(TAG, "Found a back-facing camera.");

                    StreamConfigurationMap info = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                    Log.d(TAG, "mTextureView size: " + mTextureViewWidth + "x" + mTextureViewHeight);
                    Size optimalSize = chooseBigEnoughSize(info.getOutputSizes(mCameraSurface.getClass()),mTextureViewWidth, mTextureViewHeight);
                    Log.d(TAG, "preview size: " + optimalSize);
                    mCameraSurface.setDefaultBufferSize(optimalSize.getWidth(), optimalSize.getHeight());
                    mRatio = (float)optimalSize.getHeight() / optimalSize.getWidth();

                    mCameraId = cameraid;
                    mCameraManager.openCamera(mCameraId, mCameraStateCallback, mBackgroundHandler);
                    return;
                }
            }
        } catch (CameraAccessException ex){
            Log.e(TAG, "open camera2 failed." + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void initMediaPlayer() {
        Log.d(TAG, "initMediaPlayer");

        mMediaPlayer = new MediaPlayer();

        String outputPath = null;
        String Extention = ".mp4";
        File[] files = new File(OUTPUT_DIR.toString() + "/DCIM/Camera/").listFiles();
        for (File file : files) {
            if (file.isFile()) {
                if (file.getPath().substring(file.getPath().length()
                        -Extention.length()).equals(Extention)) {
                    outputPath = file.getAbsolutePath();
                    break;
                }
            }
        }
        if (outputPath == null) {
            Log.d(TAG, "There's no video file exist");
            Message message = new Message();
            message.what = 2;
            mMainThreadHandler.sendMessage(message);
            return;
        }
        Log.d(TAG, "outputPath is: " + outputPath);
//        String outputPath = new File(OUTPUT_DIR, "test.mp4").toString();


        try {
            mMediaPlayer.setDataSource(outputPath);
            mMediaPlayer.setSurface(new Surface(mVideoSurface));
            mMediaPlayer.prepareAsync();
            mMediaPlayer.setOnPreparedListener(
                    new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer mp) {
                            if (mp != null) {
                                mp.start();
                            }
                        }
                    }
            );
            mMediaPlayer.setLooping(true);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private void compositeFrame(EGLSurface eglSurface) {
        mEGL.eglMakeCurrent(mEGLDisplay, eglSurface, eglSurface, mEGLContext);
        //checkEGLError("eglMakeCurrent");

        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);
        GLES20.glUniform1f(muRatioHandle, mRatio);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID0);
        GLES20.glUniform1i(msTexture0Handle, 0);

        mBigTriangleVertices.position(TRIANGLE_VERTICES_POS_OFFSET);
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_STRIDE, mBigTriangleVertices);
        checkGLError("glVertexAttribPointer maPositionHandle");
        GLES20.glEnableVertexAttribArray(maPositionHandle);
        checkGLError("glEnableVertexAttribArray maPositionHandle");

        mBigTriangleVertices.position(TRIANGLE_VERTICES_UV_OFFSET);
        GLES20.glVertexAttribPointer(maTextureCoordHandle, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_STRIDE, mBigTriangleVertices);
        checkGLError("glVertexAttribPointer maTextureHandle");
        GLES20.glEnableVertexAttribArray(maTextureCoordHandle);
        checkGLError("glEnableVertexAttribArray maTextureHandle");

        GLES20.glUniform1i(muFlagHandle, 1);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGLError("glDrawArrays0");

        /*GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID0);*/
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID1);
        GLES20.glUniform1i(msTexture1Handle, 1);

        mSmallTriangleVertices.position(TRIANGLE_VERTICES_POS_OFFSET);
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_STRIDE, mSmallTriangleVertices);
        checkGLError("glVertexAttribPointer maPositionHandle");
        GLES20.glEnableVertexAttribArray(maPositionHandle);
        checkGLError("glEnableVertexAttribArray maPositionHandle");

        mSmallTriangleVertices.position(TRIANGLE_VERTICES_UV_OFFSET);
        GLES20.glVertexAttribPointer(maTextureCoordHandle, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_STRIDE, mSmallTriangleVertices);
        checkGLError("glVertexAttribPointer maTextureHandle");
        GLES20.glEnableVertexAttribArray(maTextureCoordHandle);
        checkGLError("glEnableVertexAttribArray maTextureHandle");

        GLES20.glUniform1i(muFlagHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGLError("glDrawArrays1");

        if (! mEGL.eglSwapBuffers(mEGLDisplay, eglSurface)) {
            Log.e(TAG, "Cannot swap buffers!");
        }
        checkGLError("eglSwapBuffers");
    }

    private void termGL() {
        GLES20.glDeleteTextures(2, textures, 0);
        GLES20.glDeleteProgram(mProgram);
        mEGL.eglDestroyContext(mEGLDisplay, mEGLContext);
        mEGL.eglDestroySurface(mEGLDisplay, mEGLSurface);
        mEGLContext = EGL10.EGL_NO_CONTEXT;
        mEGLSurface = EGL10.EGL_NO_SURFACE;
    }

    static class CompareSizesByArea implements Comparator<Size> {
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
                List<Surface> outputSurfaces = new ArrayList<>();
                outputSurfaces.add(new Surface(mCameraSurface));
                mCameraDevice.createCaptureSession(outputSurfaces, mCaptureStateCallback, mBackgroundHandler);
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
                CaptureRequest.Builder requestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                requestBuilder.addTarget(new Surface(mCameraSurface));

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
    private void checkGLError(String op) {
        int error;
        //Log.d(TAG, op);
        if ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(TAG, op + ": glError " + error);
            throw new RuntimeException(op + ": glError " + error);
        }
    }
}
class VideoEncoderThread extends Thread {
    private static final String TAG = "VideoEncoderThread";
    private static final File OUTPUT_DIR = Environment.getExternalStorageDirectory();
    private MediaFormat mMediaFormat;
    private MediaCodec mVideoEncoder;
    private MediaMuxer mMediaMuxer;
    private Surface mSurface;
    private int mTrackIndex;
    private int mFrameCount;
    private boolean mVideoEncoderDone;

    public VideoEncoderThread(int width, int length) throws IOException {

        mVideoEncoderDone = false;
        mFrameCount = 0;
        mMediaFormat = MediaFormat.createVideoFormat("video/avc", width, length);
        mMediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 2000000);
        mMediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        mMediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        mVideoEncoder = MediaCodec.createEncoderByType("video/avc");
        mVideoEncoder.setCallback(new MediaCodec.Callback() {

            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                Log.d(TAG, "onInputBufferAvailable");
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                Log.d(TAG, "onOutputBufferAvailable");
                ByteBuffer outputBuffer = codec.getOutputBuffer(index);

                if (outputBuffer != null) {
                    outputBuffer.position(info.offset);
                    outputBuffer.limit(info.offset + info.size);
                    info.presentationTimeUs = computePresentationTimeNsec(mFrameCount++);
                    mMediaMuxer.writeSampleData(mTrackIndex, outputBuffer, info);
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    mVideoEncoderDone = true;
                }
                mVideoEncoder.releaseOutputBuffer(index, false);
                if (mVideoEncoderDone) {
                    stopVideoRecord();
                }
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                Log.e(TAG, "onError");
                if (e.isRecoverable()) {
                    Log.e(TAG, "isRecoverable");
                }
                if (e.isTransient()) {
                    Log.e(TAG, "isTransient");
                }
                e.printStackTrace();
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                Log.d(TAG, "onOutputFormatChanged" + format);
                mTrackIndex = mMediaMuxer.addTrack(format);
                mMediaMuxer.start();
            }
        });
        Log.d(TAG, "configure format: " + mMediaFormat);
        mVideoEncoder.configure(mMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mSurface = mVideoEncoder.createInputSurface();

        String outputPath = new File(OUTPUT_DIR, "test.mp4").toString();
        Log.d(TAG, "outputPath = : " + outputPath);
        mMediaMuxer = new MediaMuxer(outputPath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        mVideoEncoder.start();
    }

    public void stopVideoRecord() {
        mVideoEncoder.signalEndOfInputStream();
        mVideoEncoder.stop();
        mVideoEncoder.release();
        mVideoEncoder = null;

        mMediaMuxer.stop();
        mMediaMuxer.release();
        mMediaMuxer = null;
    }

    public Surface getVideoEncoderSurface() {
        return mSurface;
    }

    private static long computePresentationTimeNsec(int frameIndex) {
        final long ONE_BILLION = 1000000;
        return frameIndex * ONE_BILLION / 30;
    }
}
class VideoEncoder {
    private static final String TAG = "VideoEncoder";
    private static final File OUTPUT_DIR = Environment.getExternalStorageDirectory();
    public MediaRecorder mMediaRecorder;
    private Surface mSurface;

    public VideoEncoder(int width, int height) {
        mMediaRecorder = new MediaRecorder();

        Log.d(TAG, "external directory : " + OUTPUT_DIR.toString());
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String outputPath = new File(OUTPUT_DIR, "VID_" + timeStamp + ".mp4").toString();
        Log.d(TAG, "outputPath: " + outputPath);
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mMediaRecorder.setOutputFile(outputPath);
        mMediaRecorder.setVideoEncodingBitRate(10000000);
        mMediaRecorder.setVideoFrameRate(30);
        //mMediaRecorder.setOrientationHint(180);
        mMediaRecorder.setVideoSize(width, height);
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        try {
            mMediaRecorder.prepare();
        } catch (IOException e) {
            Log.e(TAG, "MediaRecorder prepare failed!!!");
            e.printStackTrace();
        }

        mSurface = mMediaRecorder.getSurface();
    }

    public Surface getVideoEncoderSurface() {
        return mSurface;
    }

    public void startVideoRecorder() {
        Log.d(TAG, "startVideoRecorder");
        try {
            mMediaRecorder.start();
        } catch (IllegalStateException ex) {
            Log.e(TAG, "start recording failed!");
            ex.printStackTrace();
        }
    }

    public void stopVideoRecorder() {
        Log.d(TAG, "stopVideoRecorder");
        mMediaRecorder.stop();
        mMediaRecorder.reset();
        mMediaRecorder.release();
        mMediaRecorder = null;
    }
}