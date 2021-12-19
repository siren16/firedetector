package com.ai.firedetector;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.Toast;
import org.tensorflow.lite.Interpreter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;


public class MainActivity extends AppCompatActivity {
    private int REQUEST_CODE_PERMISSIONS = 1001;
    private final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA"};
    private TextureView textureView;
    private TextView box0,box1,box2,box3,score,classIndex,distancePerson, directionPerson;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimensions;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    String[] indexToClass= {"연기", "연기", "연기" ,"불"}; //흰회검
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textureView = findViewById(R.id.textureView);
        box0 = findViewById(R.id.box0);
        box1 = findViewById(R.id.box1);
        box2 = findViewById(R.id.box2);
        box3 = findViewById(R.id.box3);
        score = findViewById(R.id.score);
        classIndex = findViewById(R.id.classIndex);
        if (allPermissionsGranted()) {
            startCamera(); //start camera if permission has been granted by user
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (textureView.isAvailable()) {
            try {
                openCamera();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            stopBackgroundThread();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                this.finish();
            }
        }
    }

    // 유틸 함수
    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void startCamera() {
        textureView.setSurfaceTextureListener(textureListener);
    }

    private void openCamera() throws CameraAccessException {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        cameraId = manager.getCameraIdList()[0];
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        imageDimensions = map.getOutputSizes(SurfaceTexture.class)[0];
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            manager.openCamera(cameraId, stateCallback, null);
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

    }

    private void createCameraPreview() throws CameraAccessException {
        SurfaceTexture texture = textureView.getSurfaceTexture();
        texture.setDefaultBufferSize(imageDimensions.getWidth(), imageDimensions.getHeight());
        Surface surface = new Surface(texture);
        captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        captureRequestBuilder.addTarget(surface);
        cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                if (cameraDevice == null) {
                    return;
                }
                cameraCaptureSession = session;
                try {
                    updatePreview();
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                Toast.makeText(getApplicationContext(), "Configuration Changed", Toast.LENGTH_LONG).show();
            }
        }, null);
    }

    private void updatePreview() throws CameraAccessException {
        if (cameraDevice == null) {
            return;
        }
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);

    }

    protected void stopBackgroundThread() throws InterruptedException {
        mBackgroundThread.quitSafely();
        mBackgroundThread.join();
        mBackgroundThread = null;
        mBackgroundHandler = null;
    }

    private TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        boolean processing;
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            try {
                openCamera();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
        }
        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            if(processing){
                return ;
            }
            processing = true;
            Bitmap photo = textureView.getBitmap();
            photo = getResizedBitmap(photo,320);
            Bitmap bmp = photo;
            new ImageTask(photo, new ImageResponse(){
                @Override
                public void processFinished(){
                    float[][][][] input = new float[1][320][320][3];
                    float[][][] output = new float[1][6300][9];
                    for(int y=0; y<320; y++){
                        for(int x=0; x<320; x++){
                            int pixel = bmp.getPixel(x,y);
                            input[0][y][x][0] = Color.red(pixel)/255.0f;
                            input[0][y][x][1] = Color.green(pixel)/255.0f;
                            input[0][y][x][2] = Color.blue(pixel)/255.0f;
                        }
                    }
                    Interpreter lite = getTfliteInterpreter("fire.tflite");
                    lite.run(input,output);
                    for(int i=0; i<6300; i++){ //[0,1,2,3] = [x,y,w,h]
                        output[0][i][0] *= 320;
                        output[0][i][1] *= 320;
                        output[0][i][2] *= 320;
                        output[0][i][3] *= 320;
                    }
                    //confidence 한번 거르는 작업을 했음.
                    ArrayList<Float> tmp = new ArrayList<>();
                    ArrayList<Object[]> filter = new ArrayList<>();
                    for(int i=0; i<6300; i++){
                        if(output[0][i][4]>0.25){
                            for(int j=0; j<5; j++){
                                tmp.add(output[0][i][j]);
                            }
                            for(int j=5; j<9; j++){
                                tmp.add(output[0][i][j]*output[0][i][4]);
                            }
                            filter.add(tmp.toArray());
                            tmp.clear();
                        }
                    }
                    ArrayList<Object[]> box = new ArrayList<>();
                    for(int i=0; i<filter.size(); i++){
                        tmp.add((float)filter.get(i)[0]-((float)filter.get(i)[2])/2);
                        tmp.add((float)filter.get(i)[1]-((float)filter.get(i)[3])/2);
                        tmp.add((float)filter.get(i)[0]+((float)filter.get(i)[2])/2);
                        tmp.add((float)filter.get(i)[1]+((float)filter.get(i)[3])/2);
                        box.add(tmp.toArray());
                        tmp.clear();
                    }
                    float resultValue=0;
                    float resultClassIndex=0;
                    int resultFilterIndex=0;
                    ArrayList<Object> result = new ArrayList();

                    //결과값 1개 뽑기
                    for(int i=0; i<filter.size(); i++){
                        boolean isRightRange = true;
                        for(int j=0; j<4; j++){
                            float value = (float) box.get(i)[j];
                            if(value<0.0||value>320.0){
                                isRightRange = false;
                                break;
                            }
                            tmp.add((float)box.get(i)[j]);
                        }

                        //값이 정상이었으면 진행
                        if(isRightRange == false)continue;

                        //클래스 중에서 어떤 애가 가장 확률이 높은가
                        for(int j=0; j<4; j++){
                            float currValue = (float)filter.get(i)[5+j];
                            if(currValue > resultValue){
                                resultValue = currValue;
                                resultClassIndex = j;
                                resultFilterIndex= i;
                            }
                        }
                    }

                    if(resultValue<0.45){
                        processing = false;
                        return;
                    }

                    //result에는 확률값, 클래스 인덱스저장됨
                    float xl=(float)box.get(resultFilterIndex)[0];
                    float yb=(float)box.get(resultFilterIndex)[1];
                    float xr=(float)box.get(resultFilterIndex)[2];
                    float yt=(float)box.get(resultFilterIndex)[3];
                    result.add(resultValue);
                    result.add((int)resultClassIndex);
                    processing = false;

                    runOnUiThread(new Runnable() { //UI바꾸는건 mainThread에서 해야하기 때문에 이 쓰레드에서 작업을 해주어야 오류가 안남.
                        @Override
                        public void run() {
                            box0.setText(String.format("%.3f", xl));
                            box1.setText(String.format("%.3f", yb));
                            box2.setText(String.format("%.3f", xr));
                            box3.setText(String.format("%.3f", yt));
                            score.setText(String.format("%.3f", result.get(0)));
                            classIndex.setText(indexToClass[Integer.parseInt(result.get(1).toString())]);
                        }
                    });
                    processing = false;
                }
            }).execute();
        }
    };

    private interface ImageResponse{
        void processFinished();
    }

    private class ImageTask extends AsyncTask<Void, Void, Exception> {
        private Bitmap photo;
        private ImageResponse imageResponse;
        ImageTask(Bitmap photo, ImageResponse imageResponse) {
            this.photo = photo;
            this.imageResponse = imageResponse;
        }
        @Override
        protected Exception doInBackground(Void... params) {
            imageResponse.processFinished();
            return null;
        }
        @Override
        protected void onPostExecute(Exception result) {

        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            try {
                createCameraPreview();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    private Interpreter getTfliteInterpreter(String modelPath){
        try{
            return new Interpreter(loadModelFile(MainActivity.this, modelPath));
        }
        catch(Exception e){
            e.printStackTrace();
        }
        return null;
    }

    public MappedByteBuffer loadModelFile(Activity activity, String modelPath) throws IOException {
        AssetFileDescriptor fileDescriptor = activity.getAssets().openFd(modelPath);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    public Bitmap getResizedBitmap(Bitmap image, int maxSize) {
        int width = image.getWidth();
        int height = image.getHeight();
        float bitmapRatio = (float)width / (float) height;
        if (bitmapRatio > 1) {
            width = maxSize;
            height = (int) (width / bitmapRatio);
        } else {
            height = maxSize;
            width = (int) (height * bitmapRatio);
        }
        return Bitmap.createScaledBitmap(image, width, height, true);
    }
}