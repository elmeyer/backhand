package com.elmeyer.backhand;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Class for a back-of-device finger-on-camera interaction detector.
 * Most code dealing with camera set-up has been taken from
 * <a href="https://github.com/googlesamples/android-Camera2Basic/blob/master/Application/src/main/java/com/example/android/camera2basic/Camera2BasicFragment.java">
 *     https://github.com/googlesamples/android-Camera2Basic/blob/master/Application/src/main/java/com/example/android/camera2basic/Camera2BasicFragment.java#L627</a>
 */
public class Backhand {
    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "Backhand";

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private static Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * The {@link CameraManager} from the main Activity used to open {@link #mCamera}.
     */
    private static CameraManager mCameraManager;

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String mCameraId;

    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private static ImageReader mImageReader;

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private static CameraDevice mCamera;

    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private static CaptureRequest.Builder mPreviewRequestBuilder;

    /**
     * {@link CaptureRequest} generated by {@link #mPreviewRequestBuilder}
     */
    private static CaptureRequest mPreviewRequest;

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private static CameraCaptureSession mCameraCaptureSession;

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private static HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private static Handler mBackgroundHandler;

    /**
     * A {@link Mat} holding a grayscale image captured from the camera preview.
     */
    private static Mat mImgGray;

    /**
     * The timestamp of the last detected black frame, for taps.
     */
    private static Long mTimeOfLastMotion = null;

    /**
     * The tap event to send after successful detection.
     */
    private static Tap mTapEvent = null;

    /**
     * The swipe event to send after successful detection.
     */
    private static Swipe mSwipeEvent = null;

    /**
     * The callback interface used to communicate detected events with the Activity.
     */
    private static OnSwipeListener mOnSwipeListener;

    /**
     * An array holding the luminance calculators to be executed later.
     */
    private static LumaAnalysisRunnable[] mLumaAnalysisRunnables =
            new LumaAnalysisRunnable[6];

    /**
     * An integer storing the current fps, for timing.
     */
    private static int mFps = 0;

    /**
     * An integer acting as a frame counter.
     */
    private static int mFrames = 0;

    /**
     * The number of the last frame (within the second) that had an action.
     */
    private static int mLastFrame = 0;

    /**
     * A {@link Looper} to allow background FPS calculation.
     */
    private static Looper mLooper;

    /**
     * A {@link Handler} running {@link #mFpsUpdateRunnable} every second.
     */
    private static Handler mFpsHandler;

    /**
     * A {@link Runnable} updating {@link #mFps} every second.
     */
    private static Runnable mFpsUpdateRunnable = new Runnable()
    {
        @Override
        public void run()
        {
            mFps = mFrames;
            mFrames = 0;
            Log.d(TAG, "FPS: " + mFps);
            mFpsHandler.postDelayed(this, 1000);
        }
    };

    /**
     * {@link ImageReader.OnImageAvailableListener} that takes a preview image and passes it
     * to OpenCV to detect the gesture.
     * from <a href="https://stackoverflow.com/a/33268451">https://stackoverflow.com/a/33268451</a>
     */
    private static final ImageReader.OnImageAvailableListener mOnImageAvailableListener =
            new ImageReader.OnImageAvailableListener()
            {
                @Override
                public void onImageAvailable(ImageReader reader)
                {
                    Image image = mImageReader.acquireNextImage();
                    if (image != null) {
                        /**
                         * "Spec guarantees that planes[0] is luma and has pixel stride of 1."
                         * (from: <a href="http://nezarobot.blogspot.com/2016/03/android-surfacetexture-camera2-opencv.html">
                         *     http://nezarobot.blogspot.com/2016/03/android-surfacetexture-camera2-opencv.html</a>)
                         */
//                        System.out.println("Acquired image");
                        mImgGray = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC1,
                                image.getPlanes()[0].getBuffer());
                        image.close();
                        /*
                        try {
                            detectMotion();
                            mFrames++;
                            // Thread.sleep(50); // why do we need this?
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        */
                        detectMotion();
                        mFrames++;
                    }
                }
            };

    /**
     * A {@link android.hardware.camera2.CameraDevice.StateCallback} configuring the
     * {@link #mPreviewRequest} and starting the {@link #mCameraCaptureSession}.
     */
    private static final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback()
    {
        @Override
        public void onOpened(@NonNull CameraDevice camera)
        {
            mCameraOpenCloseLock.release();

            try {
                mCamera = camera;

                Surface surface = mImageReader.getSurface();

                mPreviewRequestBuilder =
                        mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

                // render into ImageReader
                mPreviewRequestBuilder.addTarget(surface);

                mCamera.createCaptureSession(Arrays.asList(surface),
                        new CameraCaptureSession.StateCallback()
                        {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession session) {
                                // camera closed
                                if (mCamera == null) {
                                    return;
                                }

                                mCameraCaptureSession = session;

                                // some "sane" settings: macro autofocus, no flash (for now)
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_MACRO);
                                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                                        CaptureRequest.FLASH_MODE_OFF);

                                mPreviewRequest = mPreviewRequestBuilder.build();

                                try {
                                    mCameraCaptureSession.setRepeatingRequest(mPreviewRequest,
                                            null, mBackgroundHandler);
                                    mFpsHandler.post(mFpsUpdateRunnable);
                                } catch (CameraAccessException e) {
                                    e.printStackTrace();
                                }
                            }

                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                Log.e(TAG, "Failed to configure camera");
                            }
                        }, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera)
        {
            mCameraOpenCloseLock.release();
            camera.close();
            mCamera = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error)
        {
            mCameraOpenCloseLock.release();
            camera.close();
            mCamera = null;
            Log.e(TAG, "Camera received error " + error);
        }
    };

    /**
     * Constructor for the back-of-device finger-on-camera interaction detector.
     * It is highly advised to call this asynchronously, since opening the camera and setting the
     * appropriate settings may take some time.
     * @param manager {@link CameraManager} allowing access to camera(s)
     * @throws SecurityException if the necessary permissions for camera use haven't been granted
     * @throws CameraAccessException if there is an error accessing the camera
     */
    public Backhand(OnSwipeListener onSwipeListener, CameraManager manager, Looper looper)
    throws SecurityException, CameraAccessException
    {
        startBackgroundThread();

        mOnSwipeListener = onSwipeListener;
        mCameraManager = manager;
        mLooper = looper;
        mFpsHandler = new Handler(mLooper);

        Size smallest = null;

        for (String cameraId : manager.getCameraIdList()) {
            CameraCharacteristics characteristics =
                    manager.getCameraCharacteristics(cameraId);

            // skip front facing camera
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT)
                continue;

            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null)
                continue;

            mCameraId = cameraId;

            // get smallest possible preview size to make computation more efficient
            smallest = Collections.min(
                    Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)),
                    new CompareSizesByArea());

            mImageReader = ImageReader.newInstance(smallest.getWidth(), smallest.getHeight(),
                    ImageFormat.YUV_420_888, 1);

            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener,
                    mBackgroundHandler);

            openCamera();
        }

        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "Unable to load OpenCV");
        }

        mImgGray = new Mat(smallest.getHeight(), smallest.getWidth(), CvType.CV_8UC1);

        // Fill mLumaAnalysisRunnables with thirds of the image
        int rowThird = smallest.getHeight() / 3;
        int colThird = smallest.getWidth() / 3;

        /**
         * {@link Third}
         */
        for (int i = 0; i < 3; i++) {
            mLumaAnalysisRunnables[i] =
                    new LumaAnalysisRunnable(rowThird*i, rowThird*(i+1),
                            0, smallest.getWidth(), mImgGray);
            mLumaAnalysisRunnables[i+3] =
                    new LumaAnalysisRunnable(0, smallest.getHeight(),
                            colThird*i, colThird*(i+1), mImgGray);
        }

        Log.i(TAG, "Instantiated new " + this.getClass());
    }

    /**
     * Helper method for computing the average luminosity of a contiguous top->bottom/left->right image area.
     * @param offset Offset: 0 for full top->bottom, 3 for full left-> right, between/more for less
     * @param computeAvg Whether or not to compute the average luminance over the specified area
     * @return average luminosity over specified area or 0
     */
    private static double computeLumaForward(int offset, boolean computeAvg)
    {
        Thread lumaAnalysisThreads[];
        if (offset <= 3) {
            lumaAnalysisThreads = new Thread[3];
        } else {
            lumaAnalysisThreads = new Thread[6];
        }

        // Compute the average luminosity of all thirds of the image concurrently
        for (int i = offset; i < lumaAnalysisThreads.length; i++) {
            mLumaAnalysisRunnables[i].updateImg(mImgGray);
            Thread t = new Thread(mLumaAnalysisRunnables[i]);
            t.start();
            lumaAnalysisThreads[i] = t;
        }

        // Wait for computations to finish
        for (int i = offset; i < lumaAnalysisThreads.length; i++) {
            try {
                lumaAnalysisThreads[i].join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if (computeAvg) {
            double lumaAvg = 0;
            for (int i = offset; i < lumaAnalysisThreads.length; i++) {
                lumaAvg += mLumaAnalysisRunnables[i].mLuma;
            }
            lumaAvg = lumaAvg / (double) (lumaAnalysisThreads.length - offset);

            return lumaAvg;
        } else {
            return 0;
        }
    }

    /**
     * Helper method for computing the average luminosity of a contiguous right->left/bottom->top image area.
     * @param offset Offset: 3 for full right->left, 6 for full bottom->top, less/between for less
     * @param computeAvg Whether or not to compute the average luminance over the specified area
     * @return average luminosity over specified area or 0
     */
    private static double computeLumaBackward(int offset, boolean computeAvg)
    {
        Thread lumaAnalysisThreads[] = new Thread[6];

        int countFrom;
        if (offset <= 3) {
            countFrom = 5;
        } else {
            countFrom = 2;
        }

        // Compute the average luminosity of all thirds of the image concurrently
        for (int i = countFrom; i > (lumaAnalysisThreads.length - offset - 1); i--) {
            mLumaAnalysisRunnables[i].updateImg(mImgGray);
            Thread t = new Thread(mLumaAnalysisRunnables[i]);
            t.start();
            lumaAnalysisThreads[i] = t;
        }

        // Wait for computations to finish
        for (int i = countFrom; i > (lumaAnalysisThreads.length - offset - 1); i--) {
            try {
                lumaAnalysisThreads[i].join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if (computeAvg) {
            double lumaAvg = 0;
            for (int i = countFrom; i > (lumaAnalysisThreads.length - offset - 1); i--) {
                lumaAvg += mLumaAnalysisRunnables[i].mLuma;
            }
            if (offset <= 3) {
                lumaAvg = lumaAvg / (double) (offset);
            } else {
                lumaAvg = lumaAvg / (double) (offset - 3);
            }

            return lumaAvg;
        } else {
            return 0;
        }
    }

    /**
     * Helper method to compute luminance in parallel for some regions.
     * @param which Which regions to compute
     *
     * NOTE: You must retrieve the computed luminance yourself afterwards.
     */
    private static void computeLuma(Third... which)
    {
        Thread[] lumaAnalysisThreads = new Thread[which.length];

        int j = 0;
        for (Third i : which) {
            mLumaAnalysisRunnables[i.which].updateImg(mImgGray);
            Thread t = new Thread(mLumaAnalysisRunnables[i.which]);
            t.start();
            lumaAnalysisThreads[j++] = t;
        }

        for (Thread t : lumaAnalysisThreads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /*
    private static void detectMotion() throws InterruptedException {
//        Log.i(TAG, "Mat size: " + mImgGray.size());
//        Log.i(TAG, "Center luma value: " + mImgGray.get(mImgGray.rows()/2, mImgGray.cols()/2)[0]);
        Long time = System.currentTimeMillis();
        if (mTimeOfLastMotion == null ||
                (((time - mTimeOfLastMotion) < 500) && ((mFrames - mLastFrame) == 3) )) { // TODO: Fix time interval
            // TODO: Fix this

            // Average the results to get the average luminance for the entire image
            double globalLumaAvg = computeLumaForward(0);

            // Log.i(TAG, "average luminosity: " + globalLumaAvg);
            if ((globalLumaAvg < 50.0) && (mTapEvent == null)) { // TODO: Fix threshold
                mTimeOfLastMotion = time;
                mLastFrame = mFrames;
                mTapEvent = Tap.SINGLE;
            } else if (mTapEvent != null) {
                if (mTapEvent == Tap.SINGLE) {
                    mTapEvent = Tap.DOUBLE;
                } else if (mTapEvent == Tap.DOUBLE) {
                    mTapEvent = Tap.TRIPLE;
                } else if (mTapEvent == Tap.TRIPLE) {
                    mTapEvent = Tap.MAYBE_HELD;
                } else if (mTapEvent == Tap.MAYBE_HELD) {
                    mTapEvent = Tap.HELD;
                }
            } else {
                if (mTapEvent == Tap.MAYBE_HELD) {
                    mTapEvent = Tap.TRIPLE;
                }
            }
        } else if ((mTimeOfLastMotion != null) && (time - mTimeOfLastMotion > 500)) { // TODO: Fix time interval
            mTimeOfLastMotion = null;
            mLastFrame = 0;
            if (mTapEvent != null) {
                if ((mTapEvent == Tap.SINGLE) || (mTapEvent == Tap.DOUBLE)
                    || (mTapEvent == Tap.TRIPLE)) {
                    mOnSwipeListener.onTap(mTapEvent);
                    Log.i(TAG, mTapEvent + " tap");
                    mTapEvent = null;
                } else if (mTapEvent == Tap.HELD) {
                    mTapEvent = null;
                }
            }
        }
    }
    */

    private static void detectMotion()
    {
        computeLumaForward(0, false);
        Log.d(TAG, "Top luminance: " + mLumaAnalysisRunnables[Third.TOP.which].mLuma);
        Log.d(TAG, "Center luminance: " + mLumaAnalysisRunnables[Third.CENTER_HORIZ.which].mLuma);
        Log.d(TAG, "Bottom luminance: " + mLumaAnalysisRunnables[Third.BOTTOM.which].mLuma);
    }

    /**
     * @deprecated Use {@link LumaAnalysisRunnable} instead.
     * @return Luminance of the center point
     */
    @Deprecated
    private static double getPointLuma() {
        return mImgGray.get(mImgGray.rows()/2, mImgGray.cols()/2)[0];
    }

    /**
     * Returns the average luminosity of a 50x50 square around the center of the image
     * @deprecated Use {@link LumaAnalysisRunnable} instead.
     */
    @Deprecated
    private static double getCenterLuma() {
        return getRangeAverage((mImgGray.rows()/2) - 25, (mImgGray.rows()/2) + 25,
                               (mImgGray.cols()/2) - 25, (mImgGray.cols()/2) + 25);
    }

    /**
     * @deprecated Use {@link LumaAnalysisRunnable} instead.
     * @return Average luminance across the entire image
     */
    @Deprecated
    private static double getGlobalLumaAvg()
    {
        return getRangeAverage(0, mImgGray.rows(), 0, mImgGray.cols());
    }

    /**Gets the average luminance of a specified region.
     * @param startRow Number of the first row to include in the calculation (Range: 0...mImgGray.rows()-1)
     * @param endRow Number of the first row to exclude from the calculation (Range: 1...mImgGray.rows())
     * @param startCol Number of the first column to include in the calculation (Range: 0...mImgGray.cols()-1)
     * @param endCol Number of the first column to exclude from the calculation (Range: 1...mImgGray.cols())
     * @return Average luminance value in the specified area, or -1 on illegal region specification
     */
    @Deprecated
    private static double getRangeAverage(int startRow, int endRow, int startCol, int endCol)
    {
        if ((startRow < endRow) && (startCol < endCol)) {
            double accumulatedLuminance = 0;
            for (int i = startRow; i < endRow; i++) {
                for (int j = startCol; j < endCol; j++) {
                    accumulatedLuminance += mImgGray.get(i, j)[0];
                }
            }

            return accumulatedLuminance / (double) ((endRow - startRow) * (endCol - startCol));
        } else {
            return -1;
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread()
    {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread()
    {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Opens the camera specified by {@link #mCameraId}.
     * @throws SecurityException if the necessary permissions for camera use haven't been granted
     * @throws CameraAccessException if there is an error accessing the camera
     */
    private void openCamera()
    throws SecurityException, CameraAccessException
    {
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            mCameraManager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera()
    {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCameraCaptureSession) {
                mCameraCaptureSession.close();
                mCameraCaptureSession = null;
            }
            if (null != mCamera) {
                mCamera.close();
                mCamera = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Helper method for pausing.
     * You *MUST* call this in your main activity's onPause before anything else!
     */
    public void onPause()
    {
        closeCamera();
        stopBackgroundThread();
    }

    /**
     * Helper method for resuming.
     * You *MUST* call this in your main activity's onResume!
     * @throws SecurityException if the necessary permissions for camera use haven't been granted
     * @throws CameraAccessException if there is an error accessing the camera
     */
    public void onResume()
    throws SecurityException, CameraAccessException
    {
        startBackgroundThread();
        openCamera();
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size>
    {

        @Override
        public int compare(Size lhs, Size rhs)
        {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    /**
     * Interface to communicate the detected gesture events to the main application.
     */
    public interface OnSwipeListener
    {
        void onSwipe(Swipe swipe);
        void onTap(Tap tap);
    }
}
