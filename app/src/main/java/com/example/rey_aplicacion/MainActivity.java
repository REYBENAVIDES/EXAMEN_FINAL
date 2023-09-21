package com.example.rey_aplicacion;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.widget.TextView;

import com.example.rey_aplicacion.Camaras.CameraConnectionFragment;
import com.example.rey_aplicacion.Camaras.ImageUtils;
import com.example.rey_aplicacion.ml.Modelo;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements ImageReader.OnImageAvailableListener, TextToSpeech.OnInitListener {


    private boolean isProcessingFrame = false;
    private byte[][] yuvBytes = new byte[3][];
    private int[] rgbBytes = null;
    private int yRowStride;
    private Runnable postInferenceCallback;
    private Runnable imageConverter;
    private Bitmap rgbFrameBitmap;
    List<String> etiquetas;
    TextView tv;
    String text;
    TextToSpeech textToSpeech;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textToSpeech=new TextToSpeech(MainActivity.this,MainActivity.this);

        text="";
        {
            tv=findViewById(R.id.resultados);
        }
        etiquetas=new ArrayList<>();
        etiquetas.add("Auditorio");
        etiquetas.add("Biblioteca");
        etiquetas.add("Centro medico");
        etiquetas.add("Comedor 1");
        etiquetas.add("Comedor 2");
        etiquetas.add("Departamento academico");
        etiquetas.add("Departamento de investigacion");
        etiquetas.add("Departamento de archivos");
        etiquetas.add("Facultad Pedagogía");
        etiquetas.add("Facultad de ciencias empresariales");
        etiquetas.add("Facultad de ciencias sociales y económicas");
        etiquetas.add("Instituto de informática");
        etiquetas.add("Parqueadero administrativo");
        etiquetas.add("Parqueadero de autoridades");
        etiquetas.add("Parqueadero de estudiantes");
        etiquetas.add("Polideportivo");
        etiquetas.add("Rectorado");
        etiquetas.add("Rotonda");
        etiquetas.add("Facultad de salud");
        etiquetas.add("Administrativo");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED ) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.CAMERA}, 121);
            }else{
                setFragment();
            }
        } else {
            setFragment();
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull
    int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setFragment();
        } else {
            finish();
        }
    }


    int previewHeight = 0,previewWidth = 0;
    int sensorOrientation;
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    protected void setFragment() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String cameraId = null;
        try {
            cameraId = manager.getCameraIdList()[0];
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        CameraConnectionFragment fragment;
        CameraConnectionFragment camera2Fragment =
                CameraConnectionFragment.newInstance(
                        new CameraConnectionFragment.ConnectionCallback() {
                            @Override
                            public void onPreviewSizeChosen(final Size size, final int rotation) {
                                previewHeight = size.getHeight(); previewWidth = size.getWidth();
                                sensorOrientation = rotation - getScreenOrientation();
                            }
                        },
                        this, R.layout.camera_fragment, new Size(640, 480));
        camera2Fragment.setCamera(cameraId);
        fragment = camera2Fragment;
        getFragmentManager().beginTransaction().replace(R.id.container, fragment).commit();
    }


    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            default:
                return 0;
        }
    }


    @Override
    public void onImageAvailable(ImageReader reader) {
        if (previewWidth == 0 || previewHeight == 0) return;
        if (rgbBytes == null) rgbBytes = new int[previewWidth * previewHeight];
        try {
            final Image image = reader.acquireLatestImage();
            if (image == null) return;
            if (isProcessingFrame) { image.close(); return; }
            isProcessingFrame = true;
            final Image.Plane[] planes = image.getPlanes();
            fillBytes(planes, yuvBytes);
            yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();
            imageConverter = new Runnable() {
                @Override
                public void run() {
                    ImageUtils.convertYUV420ToARGB8888( yuvBytes[0], yuvBytes[1], yuvBytes[2], previewWidth, previewHeight,
                            yRowStride,uvRowStride, uvPixelStride,rgbBytes);
                }
            };
            postInferenceCallback = new Runnable() {
                @Override
                public void run() { image.close(); isProcessingFrame = false; }
            };
            processImage();
        } catch (final Exception e) { }
    }


    protected void fillBytes(final Image.Plane[] planes, final byte[][] yuvBytes) {
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
    }


    private void processImage() {
        imageConverter.run();
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
        rgbFrameBitmap.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight);

        try
        {
            Modelo model = Modelo.newInstance(this);

            ImageProcessor imageProcessor =
                    new ImageProcessor.Builder()
                            .add(new ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
                            .build();

            // Creates inputs for reference.
            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, 224, 224, 3}, DataType.FLOAT32);

            TensorImage tensorImage = new TensorImage(DataType.FLOAT32);
            tensorImage.load(rgbFrameBitmap);
            tensorImage = imageProcessor.process(tensorImage);

            inputFeature0.loadBuffer(tensorImage.getBuffer());

            // Runs model inference and gets result.
            Modelo.Outputs outputs = model.process(inputFeature0);
            TensorBuffer outputFeature0 = outputs.getOutputFeature0AsTensorBuffer();



            int numeromayor_pos=retornar_mayor_posicion(outputFeature0.getFloatArray());


            String rtt=etiquetas.get(numeromayor_pos)+"\n"+outputFeature0.getFloatArray()[numeromayor_pos]*100+" %";
            text=etiquetas.get(numeromayor_pos);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tv.setText(rtt);
                    new tareaHablar().execute();
                }
            });


            model.close();
        } catch (IOException e) {

        }

        postInferenceCallback.run();
    }


    public int retornar_mayor_posicion(float [] array)
    {
        float numeromayor=0;
        int numeromayor_pos=0;
        for(int i=0; i<array.length; i++){
            if(array[i]>numeromayor){ //
                numeromayor = array[i];
                numeromayor_pos=i;
            }
        }
        return numeromayor_pos;
    }


    private class tareaHablar extends AsyncTask<Void,Void,Void> {

        @Override
        protected Void doInBackground(Void... voids) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void unused) {
            hablar();
        }
    }



    @Override
    public void onInit(int status)
    {
        if (status == TextToSpeech.SUCCESS) {
            int result = textToSpeech.setLanguage(new Locale("es" , "ES"));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("error", "No soportado");
            }
        } else {
            Log.e("error", "No soportado");
        }
    }

    @Override
    public void onDestroy()
    {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }


    private void hablar() {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

}