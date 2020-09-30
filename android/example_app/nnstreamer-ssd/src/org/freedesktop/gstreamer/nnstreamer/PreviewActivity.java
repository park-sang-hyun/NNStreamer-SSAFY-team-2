package org.freedesktop.gstreamer.nnstreamer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.ImageView;

public class PreviewActivity extends Activity {

    private ImageView imageView_preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);

        Intent intent = getIntent();

        byte[] arr = intent.getByteArrayExtra("photo");

        Bitmap photo = BitmapFactory.decodeByteArray(arr,0, arr.length);

        imageView_preview = (ImageView) findViewById(R.id.preview_imageview);
        imageView_preview.setImageBitmap(photo);
    }
}