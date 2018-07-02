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
    private static Long mTimeSinceLastTap = null;

    /**
     * The tap event to send after successful detection.
     */
    private static Tap mTapEvent = null;

    /**
     * The callback interface used to communicate detected events with the Activity.
     */
    private static OnSwipeListener mOnSwipeListener;

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
                        detectMotion();
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
    public Backhand(OnSwipeListener onSwipeListener, CameraManager manager)
    throws SecurityException, CameraAccessException
    {
        startBackgroundThread();

        mOnSwipeListener = onSwipeListener;
        mCameraManager = manager;

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
            Size smallest = Collections.min(
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

        Log.i(TAG, "Instantiated new " + this.getClass());
    }

    private static void detectMotion() {
//        Log.i(TAG, "Mat size: " + mImgGray.size());
//        Log.i(TAG, "Center luma value: " + mImgGray.get(mImgGray.rows()/2, mImgGray.cols()/2)[0]);
        double centerLuma = mImgGray.get(mImgGray.rows() / 2, mImgGray.cols() / 2)[0];
        Long time = System.currentTimeMillis();
        if (centerLuma == 0.0
                && (mTimeSinceLastTap == null
                    || ((time - mTimeSinceLastTap) < 500) && (time - mTimeSinceLastTap > 120))) {
            mTimeSinceLastTap = time;
            Log.i(TAG, "Tap");
            if (mTapEvent == null) {
                mTapEvent = Tap.SINGLE;
            } else if (mTapEvent == Tap.SINGLE) {
                mTapEvent = Tap.DOUBLE;
            } else if (mTapEvent == Tap.DOUBLE) {
                mTapEvent = Tap.TRIPLE;
            } else if (mTapEvent == Tap.TRIPLE) {
                mTapEvent = Tap.HELD;
            }
        } else if ((mTimeSinceLastTap != null) && (time - mTimeSinceLastTap > 500)) {
            mTimeSinceLastTap = null;
            if (mTapEvent != null) {
                if (mTapEvent != Tap.HELD) {
                    mOnSwipeListener.onTap(mTapEvent);
                    mTapEvent = null;
                } else {
                    mTapEvent = null;
                }
            }
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
