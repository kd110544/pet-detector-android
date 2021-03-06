/*
* Copyright 2016 The TensorFlow Authors. All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*       http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package pp.petdetector;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.SystemClock;
import android.support.design.widget.Snackbar;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import pp.petdetector.env.BorderedText;
import pp.petdetector.env.ImageUtils;
import pp.petdetector.env.Logger;
import pp.petdetector.tracking.MultiBoxTracker;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;

/**
* An activity that uses a SSD MobileNet V2 and ObjectTracker to detect and then track
* objects.
*/
public class MainActivity extends CameraActivity implements OnImageAvailableListener {
    private static final String MODEL_FILE = "file:///android_asset/ssd_mobilenet_v2.pb";
    private static final String LABELS_FILE = "pet_label.txt";

    // Minimum detection confidence to track a detection.
    private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.6f;

    private static final Logger LOGGER = new Logger();

    private static final int CROP_SIZE = 300;

    private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);

    private static final boolean SAVE_PREVIEW_BITMAP = false;
    private static final float TEXT_SIZE_DIP = 10;

    private Integer sensorOrientation;

    private Classifier classifier;

    private long lastProcessingTimeMs;
    private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;
    private Bitmap cropCopyBitmap = null;

    private boolean computingDetection = false;

    private long timestamp = 0;

    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;

    private MultiBoxTracker tracker;

    private byte[] luminanceCopy;

    private BorderedText borderedText;

    private Snackbar initSnackbar;

    private boolean initialized = false;
    private View.OnClickListener btnName_click = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            Intent intent = new Intent(MainActivity.this, Act2.class);
            startActivity(intent);
        }
    };

    @Override
    public void onPreviewSizeChosen(final Size size, final int rotation) {


        new Thread(this::init).start();


        FrameLayout container = findViewById(R.id.container);
        initSnackbar = Snackbar.make(container, "Initializing...", Snackbar.LENGTH_INDEFINITE);
        Button btnName = findViewById(R.id.btnName);
        btnName.setOnClickListener(btnName_click);

        final float textSizePx =
        TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
        borderedText = new BorderedText(textSizePx);
        borderedText.setTypeface(Typeface.MONOSPACE);

        tracker = new MultiBoxTracker(this);

        previewWidth = size.getWidth();
        previewHeight = size.getHeight();

        sensorOrientation = rotation - getScreenOrientation();
        LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(CROP_SIZE, CROP_SIZE, Config.ARGB_8888);

        frameToCropTransform =
                ImageUtils.getTransformationMatrix(
                        previewWidth, previewHeight,
                        CROP_SIZE, CROP_SIZE,
                        sensorOrientation, false);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

        trackingOverlay = findViewById(R.id.tracking_overlay);
        trackingOverlay.addCallback(
                canvas -> {
                    tracker.draw(canvas);
                    if (isDebug()) {
                        tracker.drawDebug(canvas);
                    }
                });

        addCallback(
                canvas -> {
                    if (!isDebug()) {
                        return;
                    }
                    final Bitmap copy = cropCopyBitmap;
                    if (copy == null) {
                        return;
                    }

                    final int backgroundColor = Color.argb(100, 0, 0, 0);
                    canvas.drawColor(backgroundColor);

                    final Matrix matrix = new Matrix();
                    final float scaleFactor = 2;
                    matrix.postScale(scaleFactor, scaleFactor);
                    matrix.postTranslate(
                            canvas.getWidth() - copy.getWidth() * scaleFactor,
                            canvas.getHeight() - copy.getHeight() * scaleFactor);
                    canvas.drawBitmap(copy, matrix, new Paint());

                    final Vector<String> lines = new Vector<String>();
                    if (classifier != null) {
                        final String statString = classifier.getStatString();
                        final String[] statLines = statString.split("\n");
                        Collections.addAll(lines, statLines);
                    }
                    lines.add("");
                    lines.add("Frame: " + previewWidth + "x" + previewHeight);
                    lines.add("Crop: " + copy.getWidth() + "x" + copy.getHeight());
                    lines.add("View: " + canvas.getWidth() + "x" + canvas.getHeight());
                    lines.add("Rotation: " + sensorOrientation);
                    lines.add("Inference time: " + lastProcessingTimeMs + "ms");

                    borderedText.drawLines(canvas, 10, canvas.getHeight() - 10, lines);
                });

    }

    OverlayView trackingOverlay;

    void init() {
        runOnUiThread(()->initSnackbar.show());

        try {
            classifier = Classifier.create(
                    getAssets(), MODEL_FILE, LABELS_FILE, CROP_SIZE, CROP_SIZE);
        } catch (Exception e) {
            LOGGER.e("Exception initializing classifier!", e);
            finish();
        }

        runOnUiThread(()->initSnackbar.dismiss());
        initialized = true;
    }

    @Override
    protected void processImage() {
        ++timestamp;
        final long currTimestamp = timestamp;
        byte[] originalLuminance = getLuminance();
        tracker.onFrame(
                previewWidth,
                previewHeight,
                getLuminanceStride(),
                sensorOrientation,
                originalLuminance,
                timestamp);
        trackingOverlay.postInvalidate();

        // No mutex needed as this method is not reentrant.
        if (computingDetection || !initialized) {
            readyForNextImage();
            return;
        }
        computingDetection = true;
        LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");
        Log.i("資訊","一直讀取影像");

        rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);
        Log.i("資訊", "正在處理圖片");

        if (luminanceCopy == null) {
            luminanceCopy = new byte[originalLuminance.length];
        }
        System.arraycopy(originalLuminance, 0, luminanceCopy, 0, originalLuminance.length);
        readyForNextImage();

        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap);
        }

        runInBackground(
                () -> {
                    LOGGER.i("Running detection on image " + currTimestamp);
                    final long startTime = SystemClock.uptimeMillis();

                    cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                    List<Classifier.Recognition> results = classifier.recognizeImage(croppedBitmap);
                    lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

                    final List<Classifier.Recognition> mappedRecognitions = new LinkedList<>();

                    for (final Classifier.Recognition result : results) {
                        final RectF location = result.getLocation();
                        if (location != null && result.getConfidence() >= MINIMUM_CONFIDENCE_TF_OD_API) {
                            cropToFrameTransform.mapRect(location);
                            result.setLocation(location);
                            mappedRecognitions.add(result);
                        }
                    }

                    tracker.trackResults(mappedRecognitions, luminanceCopy, currTimestamp);
                    trackingOverlay.postInvalidate();

                    requestRender();
                    computingDetection = false;
                });
    }

    @Override
    protected int getLayoutId() {
        return R.layout.camera_connection_fragment_tracking;
    }

    @Override
    protected Size getDesiredPreviewFrameSize() {
        return DESIRED_PREVIEW_SIZE;
    }
}
