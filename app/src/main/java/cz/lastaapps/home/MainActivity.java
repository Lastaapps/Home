package cz.lastaapps.home;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Point;
import android.media.AudioManager;
import android.media.ExifInterface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ViewFlipper;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.iid.FirebaseInstanceId;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    /**list of permissions needed to run app*/
    private static String[] permissions = new String[]{
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
    };
    /**represents if app has WRITE_EXTERNAL_STORAGE perrmision*/
    private static boolean WRITE_PERMISSION_GRANTED = false;

    /**folder, where are the photos stored*/
    private static File directory = null;
    /**total time between photos*/
    private static final int imageTime = 3000;
    /**Time to allocate main thread to setup second imageView with new image*/
    private static final int animationTime = 500;

    /**button used to start image slideshow*/
    private Button home;
    /**flipper showing photos*/
    private ViewFlipper flipper;

    /**The list of the files of the images*/
    private static ArrayList<File> images;
    /**play sad music*/
    private static MediaPlayer player = null;
    /**animating and changing images on the flipper*/
    private static ImageShowTask imgTask = null;

    /**static variable contains current activity*/
    private static MainActivity main;
    /**represents if the activity is in foreground*/
    private static boolean visible = false;

    /**size of flipper to resize images*/
    private static Point flipperSize = null;

    /**used to collect data about app usage*/
    private FirebaseAnalytics mFirebaseAnalytics;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Obtain the FirebaseAnalytics instance.
        mFirebaseAnalytics = FirebaseAnalytics.getInstance(getApplicationContext());

        //init if app has write permission
        WRITE_PERMISSION_GRANTED = checkPermissions();

        //default audio type is now media
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        //show token to cloud messaging
        System.out.println("Token: " + FirebaseInstanceId.getInstance().getInstanceId());

        //fullscreen mode
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        //setting views hierarchy
        setContentView(R.layout.activity_main);

        //activity is reachable now
        main = this;

        //init
        home = (Button) findViewById(R.id.homeButton);
        flipper = (ViewFlipper) findViewById(R.id.flipper);

        //flipper animations
        flipper.setInAnimation(MainActivity.this, android.R.anim.fade_in);
        flipper.setOutAnimation(MainActivity.this, android.R.anim.fade_out);

        //if image show has not started yet
        if (imgTask == null) {

            //generating path to photos
            directory = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), getString(R.string.folder));

            //default properties of shown views
            home.setVisibility(View.VISIBLE);
            flipper.setVisibility(View.INVISIBLE);

            //starts image slide show
            home.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {

                    //if app has not had permission to write, cancels process and shows dialog
                    if (WRITE_PERMISSION_GRANTED == false) {

                        new AlertDialog.Builder(MainActivity.this)
                                .setMessage(R.string.permission)
                                //opens system permission dialog
                                .setPositiveButton(R.string.dialog, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        WRITE_PERMISSION_GRANTED = checkPermissions();
                                    }
                                })
                                //exit app
                                .setNegativeButton(R.string.later, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        System.exit(0);
                                    }
                                })
                                //shows app permission settings
                                .setNeutralButton(R.string.settings, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        Intent intent = new Intent();
                                        intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                        Uri uri = Uri.fromParts("package", getPackageName(), null);
                                        intent.setData(uri);
                                        startActivity(intent);
                                    }
                                })
                                .setCancelable(false)
                                .show();

                        return;
                    }

                    //hides starting button, then shows flipper with images
                    home.setVisibility(View.GONE);
                    flipper.setVisibility(View.VISIBLE);

                    //plays one of three sad songs in cycle
                    int[] songs = new int[]{R.raw.sad1, R.raw.sad2, R.raw.sad3};
                    player = MediaPlayer.create(MainActivity.this, songs[new Random().nextInt(3)]);
                    player.setLooping(true);
                    player.start();

                    //prepares flipper to work
                    flipperSize = new Point(flipper.getWidth(), flipper.getHeight());
                    imgTask = new ImageShowTask(getApplicationContext(), flipper);
                    imgTask.execute();

                }
            });

            if (WRITE_PERMISSION_GRANTED == true)
                //creates folder to store images and default image with manual
                checkForEmptyFolder();

        } else {

            //imageslide is working, so sets up view to be seen properly
            home.setVisibility(View.INVISIBLE);
            flipper.setVisibility(View.VISIBLE);

        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (imgTask != null) {

            //flips flipper dimensions
            int temp = flipperSize.x;
            flipperSize.x = flipperSize.y;
            flipperSize.y = temp;

            //adds imagesviews into flipper
            imgTask.setFlipper(flipper);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        //pauses music in background
        if (player != null)
            player.pause();

        //tels imageTask, that it also has to pause
        visible = false;

        //turn off always on display
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    }

    @Override
    protected void onResume() {
        super.onResume();

        //restarts player
        if (player != null)
            player.start();

        //resumes imageTask if it is running
        visible = true;
        //start always on display
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        //clears RAM
        for (int i = 0; i < flipper.getChildCount(); i++) {
            ImageView imageView = ((ImageView)flipper.getChildAt(0));
            imageView.setImageBitmap(null);
        }

        flipper.removeAllViews();
        flipper = null;
        home = null;
        main = null;
    }

    /**
     * @source https://stackoverflow.com/questions/33162152/storage-permission-error-in-marshmallow/41221852#41221852
     */
    private boolean checkPermissions() {

        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }

        int result;
        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            result = ContextCompat.checkSelfPermission(this, p);
            if (result != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }
        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[listPermissionsNeeded.size()]), 100);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if (requestCode == 100) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                WRITE_PERMISSION_GRANTED = true;
                checkForEmptyFolder();
            }
            return;
        }
    }

    /**returns if activity is not paused, used to pause imageTask*/
    private static boolean isVisible() {
        return visible;
    }

    /**creates new folder in /Pictures and adds there photo with instructions*/
    private void checkForEmptyFolder() {
        if (!directory.exists()) {
            directory.mkdirs();
            directory.mkdir();
        }

        if (directory.listFiles() == null) {
            createDefaultFiles();
        } else if (directory.listFiles().length == 0) {
            createDefaultFiles();
        }

        images = new ArrayList<File>(Arrays.asList(directory.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                if (name.contains(".jpg") || name.contains(".png") || name.contains(".gif"))
                    return true;
                return false;
            }
        })));
        Collections.shuffle(images);
    }

    /**creates photo with instructions*/
    private void createDefaultFiles() {
        try {
            File file = new File(directory, getString(R.string.def_img_name) + ".jpg");
            file.createNewFile();
            writeImage(R.raw.home, file);

            Uri uri = Uri.fromFile(file);
            Intent scanFileIntent = new Intent(
                    Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri);

            explanation();

            sendBroadcast(scanFileIntent);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**Explains how to use app*/
    private void explanation() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.explanation)
                .setPositiveButton(android.R.string.ok, new AlertDialog.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        System.exit(0);
                    }
                }).setCancelable(true)
                .show();
    }

    /**Creates photo in folder*/
    private void writeImage(int id, File file) throws IOException {
        InputStream in = getResources().openRawResource(id);
        FileOutputStream out = new FileOutputStream(file);
        byte[] buff = new byte[1024];
        int read = 0;
        try {
            while ((read = in.read(buff)) > 0) {
                out.write(buff, 0, read);
            }
        } finally {
            in.close();
            out.close();
        }
    }

    /**loads image with index to bitmap
     * @param index index of image from images ArrayList*/
    private static Bitmap loadImage(int index) {
        Bitmap bitmap = null;
        try {
            //gets image location
            File file = images.get(index);

            //loads defaul bitmap
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);

            //checks if the image is not rotated
            ExifInterface exif = new ExifInterface(file.getPath());
            int rotation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rotationInDegrees = exifToDegrees(rotation);

            Matrix matrix = new Matrix();

            //reprezents if the photo is rotated to 90 of 210 degrees
            boolean dimChanged = !(rotationInDegrees == 0 || rotationInDegrees == 180);
            //if the image is rotated, img sizes are changed, because the bitmap is no rotated yet because of RAM usage
            float imgW = dimChanged ? bitmap.getWidth() : bitmap.getHeight();
            float imgH = dimChanged ? bitmap.getHeight() : bitmap.getWidth();
            float scrW = flipperSize.x;
            float scrH = flipperSize.y;

            //scales the image to perfectly fit to flipper
            float quotientW = scrW /imgW;
            float quotientH = scrH /imgH;

            float scaleBoost = Math.round(quotientW * imgH) < scrH ? quotientW : quotientH;

            matrix.postScale(scaleBoost, scaleBoost);

            /*System.err.println("imgW:" + imgW + " imgH:" + imgH +
                    " scrW:" + scrW + " scrH:" + scrH +
                    " qW:" + quotientW + " gH:" + quotientH +
                    " sB:" + scaleBoost);
            */

            //creates scaled bitmap
            bitmap = Bitmap.createBitmap(bitmap,0,0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

            //if the image is also rotated, rotates it to correct position
            if (rotation != 0f) {
                matrix = new Matrix();
                matrix.preRotate(rotationInDegrees);
                bitmap = Bitmap.createBitmap(bitmap,0,0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }

        }catch(IOException ex){
            Log.e("loadImage", ex.getMessage(), ex.getCause());
        }

        return bitmap;
    }

    /**converts efix value to real degrees*/
    private static int exifToDegrees(int exifOrientation) {
        if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_90) { return 90; }
        else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_180) {  return 180; }
        else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_270) {  return 270; }
        return 0;
    }

    /**makes imageShow*/
    private static class ImageShowTask extends AsyncTask<Object, Runnable, Object> {

        private ViewFlipper flipper;
        private Context context;

        private int imageIndex;

        /**is loaded in background while oder bitmap is shown*/
        private Bitmap nextBitmap = null;

        /**creates new imageShow task
         * @param context any context, for example MainActivity
         * @param flipper flipper to setup before start*/
        public ImageShowTask(Context context, ViewFlipper flipper) {
            this.context = context;
            /**index of next image to load*/
            imageIndex = 0;
            setFlipper(flipper);
        }

        public void setFlipper(ViewFlipper flip) {

            //loads first image
            Bitmap bitmap = loadImage(imageIndex);

            //ans 2 imageViews into flipper and gives them default image
            for (int i = 0; i < 2; i++) {
                ImageView view = new ImageView(context);
                view.setImageBitmap(Bitmap.createBitmap(bitmap));
                flip.addView(view);
            }

            //saves flipper to later
            flipper = flip;
        }

        @Override
        protected void onPreExecute() {
            //inits flipper if its not
            flipper.setDisplayedChild(0);
        }

        @Override
        protected Object doInBackground(Object[] objects) {

            try {
                //i can be 0 or 1
                for (int i = 0; true; i++) {
                    imageIndex++;

                    if (i == 2) i = 0;
                    if (imageIndex >= images.size()) imageIndex = 0;
                    //index of next imageView to set bitmap to
                    int j = 1 - i;

                    //final versions to send to main thread
                    final int copyI = i;
                    final int copyJ = j;

                    backgroundWaiting();
                    //waits until animation ends
                    Thread.sleep(animationTime);
                    backgroundWaiting();

                    //loads next image to show
                    nextBitmap = loadImage(imageIndex);
                    //indicates error
                    if (nextBitmap == null)
                        return null;

                    //changes bitmap of the oder imageView
                    publishProgress(new Runnable() { @Override public void run() {
                        ImageView next = (ImageView)flipper.getChildAt(copyJ);
                        next.setImageBitmap(nextBitmap);
                    }});

                    backgroundWaiting();
                    //waits until change to next image is performed
                    Thread.sleep(imageTime - 2 * animationTime);
                    backgroundWaiting();

                    //changes images
                    publishProgress(new Runnable() { @Override public void run() {
                        flipper.setDisplayedChild(copyJ);
                    }});

                    //waits until animation has happened
                    Thread.sleep(animationTime);

                    //recycles old bitmap
                    publishProgress(new Runnable() { @Override public void run() {
                        if (copyI != copyJ) {
                            try {
                                ImageView imageView = ((ImageView) flipper.getChildAt(copyI));
                                //((BitmapDrawable) imageView.getDrawable()).getBitmap().recycle();
                                imageView.setImageBitmap(null);
                            } catch (Exception e) {}
                        }
                    }});

                }
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        protected void onPostExecute(Object o) {
            //if null is return, something happened with loading images
            //in that case shows warning message
            if (o == null) {
                new AlertDialog.Builder(main)
                        .setMessage(R.string.img_deleted)
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                            }
                        })
                        .setCancelable(false)
                        .setOnDismissListener(new DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(DialogInterface dialog) {
                                System.exit(1);
                            }
                        })
                        .show();
            }
        }

        //runs Runnable at index 0 in values array on Main thread
        @Override
        protected void onProgressUpdate(Runnable... values) {
            values[0].run();
        }


        //checks if app is not in background, and if it is, wait until change
        private void backgroundWaiting() {
            while (main.isVisible() == false) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
