package org.freedesktop.gstreamer.nnstreamer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.widget.ToggleButton;

import org.freedesktop.gstreamer.GStreamer;
import org.freedesktop.gstreamer.GStreamerSurfaceView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;

class Conditions{
    String name;
    int count;

    Conditions() {};

    void setName(String _name){
        this.name = _name;
    }
    String getName(){
        return this.name;
    }

    void setCount(int _count){
        this.count = _count;
    }
    int getCount(){
        return this.count;
    }
}

public class NNStreamerActivity extends Activity implements
        SurfaceHolder.Callback,
        View.OnClickListener, PixelCopy.OnPixelCopyFinishedListener {
    private static final String TAG = "NNStreamer";
    private static final int PERMISSION_REQUEST_ALL = 3;
    private static final int PIPELINE_ID = 1;
    private static final String downloadPath = Environment.getExternalStorageDirectory().getPath() + "/nnstreamer/tflite_model";

    private native void nativeInit(int w, int h); /* Initialize native code, build pipeline, etc */
    private native void nativeFinalize(); /* Destroy pipeline and shutdown native code */
    private native void nativeStart(int id, int option); /* Start pipeline with id */
    private native void nativeStop();     /* Stop the pipeline */
    private native void nativePlay();     /* Set pipeline to PLAYING */
    private native void nativePause();    /* Set pipeline to PAUSED */
    private static native boolean nativeClassInit(); /* Initialize native class: cache Method IDs for callbacks */
    private native void nativeSurfaceInit(Object surface);
    private native void nativeSurfaceFinalize();
    private native String nativeGetName(int id, int option);
    private native String nativeGetDescription(int id, int option);
    private native void nativeDeleteLineAndLabel();
    private native void nativeInsertLineAndLabel();
    private native void nativeGetCondition(Object[] conditions);
    private native boolean nativeGetAutoCapture();
    private long native_custom_data;      /* Native code will use this to keep private data */

    private int pipelineId = 0;
    private CountDownTimer pipelineTimer = null;
    private boolean initialized = false;

    private DownloadModel downloadTask = null;
    private ArrayList<String> downloadList = new ArrayList<>();

    private ImageButton buttonSetting;
    private ImageButton buttonGallery;
    private ImageButton buttonCapture;

    private SurfaceView surfaceView;

    private TextView textViewConditionList;
    private TextView textViewCountDown;

    private Boolean captureMode = false;
    private Boolean checkFlag = false;


    private static final int CAMERA_REQUEST = 1888;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* Check permissions */
        if (!checkPermission(Manifest.permission.CAMERA) ||
                !checkPermission(Manifest.permission.INTERNET) ||
                !checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ||
                !checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ||
                !checkPermission(Manifest.permission.WAKE_LOCK)) {
            ActivityCompat.requestPermissions(this,
                    new String[] {
                            Manifest.permission.CAMERA,
                            Manifest.permission.INTERNET,
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.WAKE_LOCK
                    }, PERMISSION_REQUEST_ALL);
            return;
        }

        initActivity();
    }

    @Override
    public void onPause() {
        super.onPause();

        stopPipelineTimer();
        nativePause();
    }

    @Override
    public void onResume() {
        super.onResume();

        /* Start pipeline */
        if (initialized) {
            if (downloadTask != null && downloadTask.isProgress()) {
                Log.d(TAG, "Now downloading model files");
            } else {
                startPipeline(PIPELINE_ID);
            }
        }

        textViewCountDown.setText("");
        if(captureMode) nativeInsertLineAndLabel();
        else nativeDeleteLineAndLabel();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        stopPipelineTimer();
        nativeFinalize();
    }

    /**
     * This shows the toast from the UI thread.
     * Called from native code.
     */
    private void setMessage(final String message) {
        runOnUiThread(new Runnable() {
            public void run() {
                showToast(message);
            }
        });
    }

    /**
     * Native code calls this once it has created its pipeline and
     * the main loop is running, so it is ready to accept commands.
     * Called from native code.
     */
    private void onGStreamerInitialized(final String title, final String desc) {
        /* GStreamer is initialized and ready to play pipeline. */
        runOnUiThread(new Runnable() {
            public void run() {
                /* Update pipeline title and description here */

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Log.e(TAG, "InterruptedException " + e.getMessage());
                }

                nativePlay();

            }
        });
    }

    static {
        System.loadLibrary("gstreamer_android");
        System.loadLibrary("nnstreamer-jni");
        nativeClassInit();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        /* SurfaceHolder.Callback interface implementation */
        Log.d(TAG, "Surface changed to format " + format + " width "
                + width + " height " + height);
        nativeSurfaceInit(holder.getSurface());
    }

    public void surfaceCreated(SurfaceHolder holder) {
        /* SurfaceHolder.Callback interface implementation */
        Log.d(TAG, "Surface created: " + holder.getSurface());
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        /* SurfaceHolder.Callback interface implementation */
        Log.d(TAG, "Surface destroyed");
        nativeSurfaceFinalize();
    }

    @Override
    public void onClick(View v) {
        /* View.OnClickListener interface implementation */
        final int viewId = v.getId();

        if (pipelineTimer != null) {
            /* Do nothing, new pipeline will be started soon. */
            return;
        }

        switch (viewId) {
            case R.id.main_button_setting:
                Intent intent_setting = new Intent(NNStreamerActivity.this, SettingActivity.class);
                startActivityForResult(intent_setting, 200);

                break;
            case R.id.main_button_gallery:
                Intent pickerIntent = new Intent(Intent.ACTION_PICK);
                pickerIntent.setType(android.provider.MediaStore.Images.Media.CONTENT_TYPE);
                pickerIntent.setData(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);

                startActivityForResult(pickerIntent,100);
                break;
            case R.id.main_button_capture:
                nativeDeleteLineAndLabel();
                Log.d(TAG, "captureMode : "+captureMode);
                if(captureMode){
                    Thread checkCapture = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            // TODO Auto-generated method stub
                            Queue<Integer> queue = new LinkedList<>();
                            while(true){
                                if(!captureMode) return;

                                Date date = new Date(System.currentTimeMillis());
                                SimpleDateFormat sdfNow = new SimpleDateFormat("HHmmss");
                                String formatDate = sdfNow.format(date);
                                int now = Integer.parseInt(formatDate);
                                Log.d(TAG, "In Thread : " + now + " , " + queue.size());
                                if(nativeGetAutoCapture()){
                                    queue.add(now);
                                }
                                if(!queue.isEmpty()) {
                                    int head = queue.peek();
                                    if (Math.abs(now - head) > 10) {
                                        queue.poll();
                                    }
                                    if (queue.size() > 30) {
                                        checkFlag = true;
                                        break;
                                    }
                                }
                            }
                            
//                            CountDownTimer countDownTimer = new CountDownTimer(3000, 1000) {
//                                public void onTick(long millisUntilFinished) {
//                                    textViewCountDown.setText(String.format(Locale.getDefault(), "%d", millisUntilFinished / 1000L));
//                                }
//
//                                public void onFinish() {
//                                    textViewCountDown.setText("Done.");
//                                }
//                            }.start();
//                            new Handler().postDelayed(new Runnable()
//                            {
//                                @Override
//                                public void run()
//                                {
//
//                                    nativePause();
//                                    Bitmap bitmap = Bitmap.createBitmap(surfaceView.getWidth(),
//                                            surfaceView.getHeight(), Bitmap.Config.ARGB_8888);;
//                                    PixelCopy.request(surfaceView,bitmap,NNStreamerActivity.this,new Handler());
//                                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
//                                    bitmap.compress(Bitmap.CompressFormat.JPEG,30,stream);
//                                    byte[] byteArray = stream.toByteArray();
//
//                                    Intent previewIntent = new Intent(NNStreamerActivity.this, PreviewActivity.class);
//                                    previewIntent.putExtra("photo", byteArray);
//                                    startActivity(previewIntent);
//                                    nativeInsertLineAndLabel();
//                                }
//                            }, 3000);
                        }
                    });

                    checkCapture.start();

                    Thread take = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            while(!checkFlag){
                                if(!captureMode) return;
                            }

                            nativePause();
                            Bitmap bitmap = Bitmap.createBitmap(surfaceView.getWidth(),
                                    surfaceView.getHeight(), Bitmap.Config.ARGB_8888);
                            PixelCopy.request(surfaceView, bitmap, NNStreamerActivity.this, new Handler(Looper.getMainLooper()));
                            ByteArrayOutputStream stream = new ByteArrayOutputStream();
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream);
                            byte[] byteArray = stream.toByteArray();

                            Intent previewIntent = new Intent(NNStreamerActivity.this, PreviewActivity.class);
                            previewIntent.putExtra("photo", byteArray);
                            startActivity(previewIntent);
                            nativeInsertLineAndLabel();
                            return;
                        }
                    });

                    take.start();
                }else{
                    nativePause();
                    Bitmap bitmap = Bitmap.createBitmap(surfaceView.getWidth(),
                            surfaceView.getHeight(), Bitmap.Config.ARGB_8888);;
                    PixelCopy.request(surfaceView,bitmap,NNStreamerActivity.this,new Handler());
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG,30,stream);
                    byte[] byteArray = stream.toByteArray();

                    Intent previewIntent = new Intent(NNStreamerActivity.this, PreviewActivity.class);
                    previewIntent.putExtra("photo", byteArray);
                    startActivity(previewIntent);
                    nativeInsertLineAndLabel();
                }
                break;
            default:
                break;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == 200 && resultCode == RESULT_OK){
            String conditionList = data.getStringExtra("conditionList");
            String conditionDisplay = ""; // to show conditionList on screen

            String[] conditionString = conditionList.split("\n");
            Conditions[] conditions = new Conditions[conditionString.length];
            for(int i = 0; i < conditionString.length; ++i) {
                String[] condi = conditionString[i].split(" : ");
                conditions[i] = new Conditions();
                conditions[i].setName(condi[0]);
                conditions[i].setCount(Integer.parseInt(condi[1]));

                conditionDisplay += conditionString[i] + "  "; // to show conditionList on screen
            }
            nativeGetCondition(conditions);
            textViewConditionList.setText(conditionDisplay);
        }else if(requestCode == 100){
            if(resultCode == RESULT_OK && data != null)
            {
                try{
                    InputStream in = getContentResolver().openInputStream(data.getData());

                    Bitmap img = BitmapFactory.decodeStream(in);
                    in.close();

                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    img.compress(Bitmap.CompressFormat.JPEG,30,stream);
                    byte[] byteArray = stream.toByteArray();

                    Intent selectedImageIntent = new Intent(NNStreamerActivity.this, SelectedImageActivity.class);
                    selectedImageIntent.putExtra("photo", byteArray);
                    startActivity(selectedImageIntent);
                }catch(Exception e)
                {

                }
            }
            else if(resultCode == RESULT_CANCELED)
            {
                Toast.makeText(this, "사진 선택 취소", Toast.LENGTH_LONG).show();
            }
        }
    }

    public void onToggleClicked(View v){
        boolean on = ((ToggleButton) v).isChecked();

        if(on){
            captureMode = true;
            nativeInsertLineAndLabel();
        }else{
            captureMode = false;
            nativeDeleteLineAndLabel();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_ALL) {
            for (int grant : grantResults) {
                if (grant != PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Permission denied, close app.");
                    finish();
                    return;
                }
            }

            initActivity();
            return;
        }

        finish();
    }

    /**
     * Check the given permission is granted.
     */
    private boolean checkPermission(final String permission) {
        return (ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED);
    }

    /**
     * Create toast with given message.
     */
    private void showToast(final String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /**
     * Initialize GStreamer and the layout.
     */
    private void initActivity() {
        if (initialized) {
            return;
        }

        /* Initialize GStreamer and warn if it fails */
        try {
            GStreamer.init(this);
        } catch(Exception e) {
            showToast(e.getMessage());
            finish();
            return;
        }

        /* Initialize with media resolution. */
        nativeInit(GStreamerSurfaceView.media_width, GStreamerSurfaceView.media_height);

        setContentView(R.layout.main);

        buttonSetting = (ImageButton) this.findViewById(R.id.main_button_setting);
        buttonSetting.setOnClickListener(this);

        buttonGallery = (ImageButton) this.findViewById(R.id.main_button_gallery);
        buttonGallery.setOnClickListener(this);

        buttonCapture = (ImageButton) this.findViewById(R.id.main_button_capture);
        buttonCapture.setOnClickListener(this);

        textViewConditionList = (TextView) this.findViewById(R.id.main_textview_conditions);

        textViewCountDown = (TextView) this.findViewById(R.id.main_textview_countdown);

        /* Video surface for camera */
        surfaceView = (SurfaceView) this.findViewById(R.id.main_surface_video);
        SurfaceHolder sh = surfaceView.getHolder();
        sh.addCallback(this);

        /* Start with disabled buttons, until the pipeline in native code is initialized. */
//        enableButton(false);

        initialized = true;
    }

    /**
     * Start pipeline and update UI.
     */
    private void startPipeline(int newId) {
        pipelineId = newId;
//        enableButton(false);

        /* Pause current pipeline and start new pipeline */
        nativePause();

        if (checkModels()) {
            setPipelineTimer();
        } else {
            showDownloadDialog();
        }
    }

    /**
     * Cancel pipeline timer.
     */
    private void stopPipelineTimer() {
        if (pipelineTimer != null) {
            pipelineTimer.cancel();
            pipelineTimer = null;
        }
    }

    /**
     * Set timer to start new pipeline.
     */
    private void setPipelineTimer() {
        final long time = 200;

        stopPipelineTimer();
        pipelineTimer = new CountDownTimer(time, time) {
            @Override
            public void onTick(long millisUntilFinished) {
            }

            @Override
            public void onFinish() {
                int option = 0;

                pipelineTimer = null;
                if (pipelineId == PIPELINE_ID) {
                    /* Set pipeline option here */
                }

                nativeStart(pipelineId, option);
            }
        }.start();
    }

    /**
     * Check a model file exists in specific directory.
     */
    private boolean checkModelFile(String fileName) {
        File modelFile;

        modelFile = new File(downloadPath, fileName);
        if (!modelFile.exists()) {
            Log.d(TAG, "Cannot find model file " + fileName);
            downloadList.add(fileName);
            return false;
        }

        return true;
    }

    /**
     * Start to download model files.
     */
    private void downloadModels() {
        downloadTask = new DownloadModel(this, downloadPath);
        downloadTask.execute(downloadList);
    }

    /**
     * Check all necessary files exists in specific directory.
     */
    private boolean checkModels() {
        downloadList.clear();

        checkModelFile("box_priors.txt");
        checkModelFile("ssd_mobilenet_v2_coco.tflite");
        checkModelFile("coco_labels_list.txt");

        return !(downloadList.size() > 0);
    }

    /**
     * Show dialog to download model files.
     */
    private void showDownloadDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setCancelable(false);
        builder.setMessage(R.string.download_model_file);

        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                finish();
            }
        });

        builder.setPositiveButton(R.string.download, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                downloadModels();
            }
        });

        builder.show();
    }

    @Override
    public void onPixelCopyFinished(int i) {

    }
}
