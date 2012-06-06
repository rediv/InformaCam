/*
 * 
 * To Do:
 * 
 * Handles on SeekBar - Not quite right
 * Editing region, not quite right
 * RegionBarArea will be graphical display of region tracks, no editing, just selecting
 * 
 */

package org.witness.ssc.video;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Vector;

import net.londatiga.android.ActionItem;
import net.londatiga.android.QuickAction;
import net.londatiga.android.QuickAction.OnActionItemClickListener;

import org.witness.ssc.image.detect.GoogleFaceDetection;
import org.witness.ssc.utils.ObscuraConstants;
import org.witness.ssc.video.InOutPlayheadSeekBar.InOutPlayheadSeekBarChangeListener;
import org.witness.ssc.video.ShellUtils.ShellCallback;
import org.witness.ssc.R;

import android.R.color;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.media.MediaPlayer.OnBufferingUpdateListener;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnInfoListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.MediaPlayer.OnSeekCompleteListener;
import android.media.MediaPlayer.OnVideoSizeChangedListener;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.provider.MediaStore.Images;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

public class VideoEditor extends Activity implements
						OnCompletionListener, OnErrorListener, OnInfoListener,
						OnBufferingUpdateListener, OnPreparedListener, OnSeekCompleteListener,
						OnVideoSizeChangedListener, SurfaceHolder.Callback,
						MediaController.MediaPlayerControl, OnTouchListener, OnClickListener,
						InOutPlayheadSeekBarChangeListener, OnActionItemClickListener {

	public static final String LOGTAG = ObscuraConstants.TAG;

	public static final int SHARE = 1;

    private final static float REGION_CORNER_SIZE = 26;
    
    private final static String MIME_TYPE_MP4 = "video/mp4";
    private final static int FACE_TIME_BUFFER = 2000;
	
	ProgressDialog progressDialog;
	int completeActionFlag = -1;
	
	Uri originalVideoUri;

	File fileExternDir;
	File redactSettingsFile;
	File saveFile;
	File recordingFile;
	
	Display currentDisplay;

	VideoView videoView;
	SurfaceHolder surfaceHolder;
	MediaPlayer mediaPlayer;	
	
	ImageView regionsView;
	Bitmap obscuredBmp;
    Canvas obscuredCanvas;
	Paint obscuredPaint;
	Paint selectedPaint;
	
	Bitmap bitmapCornerUL;
	Bitmap bitmapCornerUR;
	Bitmap bitmapCornerLL;
	Bitmap bitmapCornerLR;
	
	InOutPlayheadSeekBar progressBar;
	//RegionBarArea regionBarArea;
	
	int videoWidth = 0;
	int videoHeight = 0;
	
	ImageButton playPauseButton;
	
	private ArrayList<RegionTrail> obscureTrails = new ArrayList<RegionTrail>();
	private RegionTrail activeRegionTrail;
	private ObscureRegion activeRegion;
	
	boolean mAutoDetectEnabled = false;
	
	FFMPEGWrapper ffmpeg;
	
	int timeNudgeOffset = 2;
	
	float vRatio;
	
	int outFrameRate = -1;
	int outBitRate = -1;
	String outFormat = null;
	String outAcodec = null;
	String outVcodec = null;
	int outVWidth = -1;
	int outVHeight = -1;
	
	private final static String DEFAULT_OUT_FPS = "30";
	private final static String DEFAULT_OUT_RATE = "500";
	private final static String DEFAULT_OUT_FORMAT = "3gp";
	private final static String DEFAULT_OUT_VCODEC = "libx264";
	private final static String DEFAULT_OUT_ACODEC = "copy";

	private Handler mHandler = new Handler()
	{
		 public void handleMessage(Message msg) {
	            switch (msg.what) {
		            case 0: //status
	
	                    progressDialog.dismiss();
	                    
	                 break;
	                case 1: //status

	                       progressDialog.setMessage(msg.getData().getString("status"));
	                       progressDialog.setProgress(msg.getData().getInt("progress"));
	                    break;
	               
	                case 2: //cancelled
	                	mCancelled = true;
	                	mAutoDetectEnabled = false;
	                		killVideoProcessor();
	                	
	                	break;
	                	
	                case 3: //completed
	                	progressDialog.dismiss();
	                	askPostProcessAction();    			
	                	
	                	break;
	                
	                case 5:	                	
	                	updateRegionDisplay(mediaPlayer.getCurrentPosition());	        			
	                	break;
	                default:
	                    super.handleMessage(msg);
	            }
	        }
	};
	
	private boolean mCancelled = false;
	
	QuickAction popupMenu;
	ActionItem[] popupMenuItems;
	
	/*
	public static final int CORNER_NONE = 0;
	public static final int CORNER_UPPER_LEFT = 1;
	public static final int CORNER_LOWER_LEFT = 2;
	public static final int CORNER_UPPER_RIGHT = 3;
	public static final int CORNER_LOWER_RIGHT = 4;
	*/
	
	private int mDuration;
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.videoeditor);

		if (getIntent() != null)
		{
			// Passed in from ObscuraApp
			originalVideoUri = getIntent().getData();
			
			if (originalVideoUri == null)
			{
				if (getIntent().hasExtra(Intent.EXTRA_STREAM)) 
				{
					originalVideoUri = (Uri) getIntent().getExtras().get(Intent.EXTRA_STREAM);
				}
			}
			
			if (originalVideoUri == null)
			{
				if (savedInstanceState.getString("path")!=null)
				{
					originalVideoUri = Uri.fromFile(new File(savedInstanceState.getString("path")));
					recordingFile = new File (savedInstanceState.getString("path"));
				}
				else
				{
					finish();
					return;
				}
			}
			else
			{
			
				recordingFile = new File(pullPathFromUri(originalVideoUri));
			}
		}
		
		fileExternDir = new File(Environment.getExternalStorageDirectory(),getString(R.string.app_name));
		if (!fileExternDir.exists())
			fileExternDir.mkdirs();

		regionsView = (ImageView) this.findViewById(R.id.VideoEditorImageView);
		regionsView.setOnTouchListener(this);
		createCleanSavePath();


		videoView = (VideoView) this.findViewById(R.id.SurfaceView);
		
		surfaceHolder = videoView.getHolder();

		surfaceHolder.addCallback(this);
		surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

		mediaPlayer = new MediaPlayer();
		mediaPlayer.setOnCompletionListener(this);
		mediaPlayer.setOnErrorListener(this);
		mediaPlayer.setOnInfoListener(this);
		mediaPlayer.setOnPreparedListener(this);
		mediaPlayer.setOnSeekCompleteListener(this);
		mediaPlayer.setOnVideoSizeChangedListener(this);
		mediaPlayer.setOnBufferingUpdateListener(this);

		mediaPlayer.setLooping(false);
		mediaPlayer.setScreenOnWhilePlaying(true);		
		
		try {
			mediaPlayer.setDataSource(originalVideoUri.toString());
		} catch (IllegalArgumentException e) {
			Log.v(LOGTAG, e.getMessage());
			finish();
		} catch (IllegalStateException e) {
			Log.v(LOGTAG, e.getMessage());
			finish();
		} catch (IOException e) {
			Log.v(LOGTAG, e.getMessage());
			finish();
		}
		
			
		progressBar = (InOutPlayheadSeekBar) this.findViewById(R.id.InOutPlayheadSeekBar);

		progressBar.setIndeterminate(false);
		progressBar.setSecondaryProgress(0);
		progressBar.setProgress(0);
		progressBar.setInOutPlayheadSeekBarChangeListener(this);
		progressBar.setThumbsInactive();
		progressBar.setOnTouchListener(this);

		playPauseButton = (ImageButton) this.findViewById(R.id.PlayPauseImageButton);
		playPauseButton.setOnClickListener(this);
		
		currentDisplay = getWindowManager().getDefaultDisplay();
				
		redactSettingsFile = new File(fileExternDir,"redact_unsort.txt");
		
		//regionBarArea = (RegionBarArea) this.findViewById(R.id.RegionBarArea);
		//regionBarArea.obscureRegions = obscureRegions;
		
		obscuredPaint = new Paint();   
        obscuredPaint.setColor(Color.WHITE);
	    obscuredPaint.setStyle(Style.STROKE);
	    obscuredPaint.setStrokeWidth(10f);
	    
	    selectedPaint = new Paint();
	    selectedPaint.setColor(Color.GREEN);
	    selectedPaint.setStyle(Style.STROKE);
	    selectedPaint.setStrokeWidth(10f);
	    
		bitmapCornerUL = BitmapFactory.decodeResource(getResources(), R.drawable.edit_region_corner_ul);
		bitmapCornerUR = BitmapFactory.decodeResource(getResources(), R.drawable.edit_region_corner_ur);
		bitmapCornerLL = BitmapFactory.decodeResource(getResources(), R.drawable.edit_region_corner_ll);
		bitmapCornerLR = BitmapFactory.decodeResource(getResources(), R.drawable.edit_region_corner_lr);
	
		mAutoDetectEnabled = true; //first time do autodetect
		
		setPrefs();
	}
	
	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
	  
	  savedInstanceState.putString("path",recordingFile.getAbsolutePath());
	  
	  super.onSaveInstanceState(savedInstanceState);
	}
	
	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		
		Log.v(LOGTAG, "surfaceCreated Called");
		if (mediaPlayer != null)
		{

			mediaPlayer.setDisplay(holder);
			try {
				mediaPlayer.prepare();
				mDuration = mediaPlayer.getDuration();
	
			} catch (Exception e) {
				Log.v(LOGTAG, "IllegalStateException " + e.getMessage());
				finish();
			}
			 
			
			 updateVideoLayout ();
			
		}
	
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		
		
		
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		//if (mediaPlayer != null)
			//mediaPlayer.stop();
	}

	@Override
	public void onCompletion(MediaPlayer mp) {
		Log.i(LOGTAG, "onCompletion Called");
		
	
		playPauseButton.setImageDrawable(this.getResources().getDrawable(android.R.drawable.ic_media_play));
	}
	
	@Override
	public boolean onError(MediaPlayer mp, int whatError, int extra) {
		Log.e(LOGTAG, "onError Called");
		if (whatError == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
			Log.e(LOGTAG, "Media Error, Server Died " + extra);
		} else if (whatError == MediaPlayer.MEDIA_ERROR_UNKNOWN) {
			Log.e(LOGTAG, "Media Error, Error Unknown " + extra);
		}
		return false;
	}

	@Override
	public boolean onInfo(MediaPlayer mp, int whatInfo, int extra) {
		if (whatInfo == MediaPlayer.MEDIA_INFO_BAD_INTERLEAVING) {
			Log.v(LOGTAG, "Media Info, Media Info Bad Interleaving " + extra);
		} else if (whatInfo == MediaPlayer.MEDIA_INFO_NOT_SEEKABLE) {
			Log.v(LOGTAG, "Media Info, Media Info Not Seekable " + extra);
		} else if (whatInfo == MediaPlayer.MEDIA_INFO_UNKNOWN) {
			Log.v(LOGTAG, "Media Info, Media Info Unknown " + extra);
		} else if (whatInfo == MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING) {
			Log.v(LOGTAG, "MediaInfo, Media Info Video Track Lagging " + extra);
		} else if (whatInfo == MediaPlayer.MEDIA_INFO_METADATA_UPDATE) { 
			Log.v(LOGTAG, "MediaInfo, Media Info Metadata Update " + extra); 
		}
		
		return false;
	}

	public void onPrepared(MediaPlayer mp) {
		Log.v(LOGTAG, "onPrepared Called");

		updateVideoLayout ();
		mediaPlayer.seekTo(1);
		
		DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
		    @Override
		    public void onClick(DialogInterface dialog, int which) {
		        switch (which){
		        case DialogInterface.BUTTON_POSITIVE:
		        	beginAutoDetect();
		            break;

		        case DialogInterface.BUTTON_NEGATIVE:
		            start();
		            break;
		        }
		    }
		};

		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setMessage("Would you like to detect faces in this video?").setPositiveButton("Yes", dialogClickListener)
		    .setNegativeButton("No", dialogClickListener).show();
		
		
		
	}
	
	private void beginAutoDetect ()
	{
		mAutoDetectEnabled = true;
		
		progressDialog = new ProgressDialog(this);
		progressDialog = ProgressDialog.show(this, "", "Detecting faces...", true, true);
    	progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        progressDialog.setCancelable(true);
        Message msg = mHandler.obtainMessage(2);
        msg.getData().putString("status","cancelled");
        progressDialog.setCancelMessage(msg);
   	
         progressDialog.show();
		
         new Thread (doAutoDetect).start();
		
	}

	public void onSeekComplete(MediaPlayer mp) {
		
		if (!mediaPlayer.isPlaying()) {			
			mediaPlayer.start();
			mediaPlayer.pause();
			playPauseButton.setImageDrawable(this.getResources().getDrawable(android.R.drawable.ic_media_play));
		}
	}

	public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
		Log.v(LOGTAG, "onVideoSizeChanged Called");

		videoWidth = mp.getVideoWidth();
		videoHeight = mp.getVideoHeight();

		updateVideoLayout ();
		
	}
	
    /*
     * Handling screen configuration changes ourselves, we don't want the activity to restart on rotation
     */
    @Override
    public void onConfigurationChanged(Configuration conf) 
    {
        super.onConfigurationChanged(conf);
    
      
    }   
	
	private boolean updateVideoLayout ()
	{
		//Get the dimensions of the video
	    int videoWidth = mediaPlayer.getVideoWidth();
	    int videoHeight = mediaPlayer.getVideoHeight();
	    Log.v(LOGTAG, "video size: " + videoWidth + "x" + videoHeight);
	   
	    if (videoWidth > 0 && videoHeight > 0)
	    {
		    //Get the width of the screen
		    int screenWidth = getWindowManager().getDefaultDisplay().getWidth();
	
		    //Get the SurfaceView layout parameters
		    android.view.ViewGroup.LayoutParams lp = videoView.getLayoutParams();
	
		    //Set the width of the SurfaceView to the width of the screen
		    lp.width = screenWidth;
	
		    //Set the height of the SurfaceView to match the aspect ratio of the video 
		    //be sure to cast these as floats otherwise the calculation will likely be 0
		   
		    int videoScaledHeight = (int) (((float)videoHeight) / ((float)videoWidth) * (float)screenWidth);
	
		    lp.height = videoScaledHeight;
		   
		    //Commit the layout parameters
		    videoView.setLayoutParams(lp);    
		    regionsView.setLayoutParams(lp);    
		    
		    Log.v(LOGTAG, "view size: " + screenWidth + "x" + videoScaledHeight);
		    
			vRatio = ((float)screenWidth) / ((float)videoWidth);
			
			Log.v(LOGTAG, "video/screen ration: " + vRatio);

			return true;
	    }
	    else
	    	return false;
	}

	public void onBufferingUpdate(MediaPlayer mp, int bufferedPercent) {
		Log.v(LOGTAG, "MediaPlayer Buffering: " + bufferedPercent + "%");
	}

	public boolean canPause() {
		return true;
	}

	public boolean canSeekBackward() {
		return true;
	}

	public boolean canSeekForward() {
		return true;
	}

	@Override
	public int getBufferPercentage() {
		return 0;
	}

	@Override
	public int getCurrentPosition() {
		return mediaPlayer.getCurrentPosition();
	}

	@Override
	public int getDuration() {
		Log.v(LOGTAG,"Calling our getDuration method");
		return mediaPlayer.getDuration();
	}

	@Override
	public boolean isPlaying() {
		Log.v(LOGTAG,"Calling our isPlaying method");
		return mediaPlayer.isPlaying();
	}

	@Override
	public void pause() {
		Log.v(LOGTAG,"Calling our pause method");
		if (mediaPlayer.isPlaying()) {
			mediaPlayer.pause();
			playPauseButton.setImageDrawable(this.getResources().getDrawable(android.R.drawable.ic_media_play));
		}
	}

	@Override
	public void seekTo(int pos) {
		Log.v(LOGTAG,"Calling our seekTo method");
		mediaPlayer.seekTo(pos);
	}

	@Override
	public void start() {
		Log.v(LOGTAG,"Calling our start method");
		mediaPlayer.start();
		
		playPauseButton.setImageDrawable(this.getResources().getDrawable(android.R.drawable.ic_media_pause));
		
		mHandler.post(updatePlayProgress);
		

	}
	
	private Runnable doAutoDetect = new Runnable() {
		   public void run() {
			   
			   try
			   {
				   int timeInc = 500;
				   
				   if (mediaPlayer != null && mAutoDetectEnabled) 
				   {						   
					   mediaPlayer.start();
					   mediaPlayer.setVolume(0f, 0f);
					   String rPath = recordingFile.getAbsolutePath();
					   MediaMetadataRetriever retriever = new MediaMetadataRetriever();
				       retriever.setDataSource(rPath);
				       
				       
				            
					   for (int f = 0; f < mDuration && mAutoDetectEnabled; f += timeInc)
					   {
						   mediaPlayer.seekTo(f);	
						   progressBar.setProgress((int)(((float)mediaPlayer.getCurrentPosition()/(float)mDuration)*100));
						   //Bitmap bmp = getVideoFrame(rPath,f*1000);
						   Bitmap bmp = retriever.getFrameAtTime(f*1000, MediaMetadataRetriever.OPTION_CLOSEST);
						   
						   if (bmp != null)
							   autoDetectFrame(bmp,f, FACE_TIME_BUFFER, mDuration);
						   
					   }
					   
					   mediaPlayer.setVolume(1f, 1f);
					   mediaPlayer.seekTo(0);
					   progressBar.setProgress((int)(((float)mediaPlayer.getCurrentPosition()/(float)mDuration)*100));
					   mediaPlayer.pause();
					   
					   
					   
				   }   
			   }
			   catch (Exception e)
			   {
				   Log.e(LOGTAG,"autodetect errored out", e);
			   }
			   
			   finally
			   {
				   if (mAutoDetectEnabled)
					{
						mAutoDetectEnabled = false;
						Message msg = mHandler.obtainMessage(0);
						mHandler.sendMessage(msg);
					}
			   }
			   
		   }
		};
		
	private Runnable updatePlayProgress = new Runnable() {
	   public void run() {
		   
		   try
		   {
			   if (mediaPlayer != null && mediaPlayer.isPlaying()) {
				   
				   int curr = mediaPlayer.getCurrentPosition();
					   progressBar.setProgress((int)(((float)curr/(float)mDuration)*100));
					   updateRegionDisplay(curr);
					   mHandler.post(this);				   
			   }
			   
		   }
		   catch (Exception e)
		   {
			   Log.e(LOGTAG,"autoplay errored out", e);
		   }
	   }
	};		
	
	private void updateRegionDisplay(int currentTime) {

		//Log.v(LOGTAG,"Position: " + mediaPlayer.getCurrentPosition());
		
		validateRegionView();
		clearRects();
		
		for (RegionTrail regionTrail:obscureTrails)
		{
		
			int currentColor = Color.WHITE;
			boolean selected = regionTrail == activeRegionTrail;
			
			if (selected)
			{
				currentColor = Color.GREEN;
				displayRegionTrail(regionTrail, selected, currentColor, currentTime);
			}
			
			ObscureRegion region = regionTrail.getCurrentRegion(currentTime);
			
			if (region != null)
				displayRegion(region, selected, currentColor);
		
		}
		
		
		regionsView.invalidate();
		//seekBar.invalidate();
	}
	
	private void validateRegionView() {
		if (obscuredBmp == null && regionsView.getWidth() > 0 && regionsView.getHeight() > 0) {
			Log.v(LOGTAG,"obscuredBmp is null, creating it now");
			obscuredBmp = Bitmap.createBitmap(regionsView.getWidth(), regionsView.getHeight(), Bitmap.Config.ARGB_8888);
			obscuredCanvas = new Canvas(obscuredBmp); 
		    regionsView.setImageBitmap(obscuredBmp);			
		}
	}

	private void displayRegionTrail(RegionTrail trail, boolean selected, int color, int currentTime) {

		
		RectF lastRect = null;
	    
		obscuredPaint.setStyle(Style.FILL);
		obscuredPaint.setColor(color);
		obscuredPaint.setStrokeWidth(10f);
	    
		for (Integer regionKey:trail.getRegionKeys())
		{

			ObscureRegion region = trail.getRegion(regionKey);
			
			int alpha = 150;//Math.min(255,Math.max(0, ((currentTime - region.timeStamp)/1000)));
			
			RectF nRect = new RectF();
			nRect.set(region.getBounds());    	
			nRect.left *= vRatio;
			nRect.right *= vRatio;
			nRect.top *= vRatio;
			nRect.bottom *= vRatio;

			obscuredPaint.setAlpha(alpha);

			if (lastRect != null)
			{
				obscuredCanvas.drawLine(lastRect.centerX(), lastRect.centerY(), nRect.centerX(), nRect.centerY(), obscuredPaint);
			}
			
			lastRect = nRect;
    		//obscuredCanvas.drawRect(paintingRect, obscuredPaint);    

			//obscuredCanvas.drawPoint(paintingRect.centerX(), paintingRect.centerY(), obscuredPaint);
			//obscuredCanvas.drawLines(points,obscuredPaint);	
		}
		
		
	}

	
	private void displayRegion(ObscureRegion region, boolean selected, int color) {

		RectF paintingRect = new RectF();
    	paintingRect.set(region.getBounds());    	
    	paintingRect.left *= vRatio;
    	paintingRect.right *= vRatio;
    	paintingRect.top *= vRatio;
    	paintingRect.bottom *= vRatio;
    	
    	obscuredPaint.setStyle(Style.FILL);
		obscuredPaint.setColor(Color.BLACK);
		obscuredPaint.setAlpha(150);
		obscuredCanvas.drawRect(paintingRect, obscuredPaint);    
		
		obscuredPaint.setStyle(Style.STROKE);	
    	obscuredPaint.setStrokeWidth(3f);
		obscuredPaint.setColor(color);
    	
		obscuredCanvas.drawRect(paintingRect, obscuredPaint);
		
    
	}
	
	private void clearRects() {
		Paint clearPaint = new Paint();
		clearPaint.setXfermode(new PorterDuffXfermode(Mode.CLEAR));
		
		if (obscuredCanvas != null)
			obscuredCanvas.drawPaint(clearPaint);
	}

	int fingerCount = 0;
	int regionCornerMode = 0;
	
	public static final int NONE = 0;
	public static final int DRAG = 1;
	//int mode = NONE;

	public ObscureRegion findRegion(float x, float y) 
	{
		ObscureRegion region, returnRegion = null;
		
		for (RegionTrail regionTrail:obscureTrails)
		{
		
			for (Integer regionKey : regionTrail.getRegionKeys())
			{
				region = regionTrail.getRegion(regionKey);
				
				if (region.getBounds().contains(x,y))
				{
					returnRegion = region;
					break;
				}
			}			
		}
		
		return returnRegion;
	}
	
	/*
	long startTime = 0;
	float startX = 0;
	float startY = 0;
	*/

	boolean showingMenu = false;
	
	@Override
	public boolean onTouch(View v, MotionEvent event) {
		
		boolean handled = false;

		if (v == progressBar) {
			// It's the progress bar/scrubber
			if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
			    mediaPlayer.start();
		    } else if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
		    	mediaPlayer.pause();
		    	
		    }
			/*
			Log.v(LOGTAG,"" + event.getX() + " " + event.getX()/progressBar.getWidth());
			Log.v(LOGTAG,"Seeking To: " + (int)(mDuration*(float)(event.getX()/progressBar.getWidth())));
			Log.v(LOGTAG,"MediaPlayer Position: " + mediaPlayer.getCurrentPosition());
			*/
			int newTime = (int)(mediaPlayer.getDuration()*(float)(event.getX()/progressBar.getWidth()));
			mediaPlayer.seekTo(newTime);
			updateRegionDisplay(newTime);
			// Attempt to get the player to update it's view - NOT WORKING
			
			handled = false; // The progress bar doesn't get it if we have true here
		}
		else
		{
			// Region Related
			//float x = event.getX()/(float)currentDisplay.getWidth() * videoWidth;
			//float y = event.getY()/(float)currentDisplay.getHeight() * videoHeight;
			float x = event.getX() / vRatio;
			float y = event.getY() / vRatio;

			fingerCount = event.getPointerCount();
			
			switch (event.getAction() & MotionEvent.ACTION_MASK) {
			
				case MotionEvent.ACTION_DOWN:

					
					ObscureRegion newActiveRegion = findRegion(x,y);
					
					if (newActiveRegion != null && (!mediaPlayer.isPlaying()))
					{
						activeRegionTrail = newActiveRegion.getRegionTrail();
						
						updateProgressBar(activeRegionTrail);
						
						if (fingerCount == 1)
						{
							
							showingMenu = true;
							inflatePopup(false, (int)x, (int)y);
						}
						
						activeRegion = newActiveRegion;
					}
					else 
					{
						
						if (fingerCount > 1)
						{
		                	float[] points = {event.getX(0)/vRatio, event.getY(0)/vRatio, event.getX(1)/vRatio, event.getY(1)/vRatio}; 
		                	
		                	float startX = Math.min(points[0], points[2]);
		                	float endX = Math.max(points[0], points[2]);
		                	float startY = Math.min(points[1], points[3]);
		                	float endY = Math.max(points[1], points[3]);
		                	
		                	newActiveRegion = new ObscureRegion(mediaPlayer.getCurrentPosition(),startX,startY,endX,endY);
					
						}
						else
						{
							newActiveRegion = new ObscureRegion(mediaPlayer.getCurrentPosition(),x,y);
							
							if (activeRegion != null && activeRegion.getBounds().intersect(newActiveRegion.getBounds()))
							{
									//newActiveRegion.ex = newActiveRegion.sx + (activeRegion.ex-activeRegion.sx);
									//newActiveRegion.ey = newActiveRegion.sy + (activeRegion.ey-activeRegion.sy);
									float arWidth = activeRegion.ex-activeRegion.sx;
									float arHeight = activeRegion.ey-activeRegion.sy;
									
									float sx = x - arWidth/2;
									float ex = sx + arWidth;
									
									float sy = y - arHeight/2;
									float ey = sy + arHeight;

									newActiveRegion = new ObscureRegion(mediaPlayer.getCurrentPosition(),sx,sy,ex,ey);
									
									
							}
								
								
							
						}
		
						if (activeRegionTrail == null)
						{
							activeRegionTrail = new RegionTrail(0,mDuration);
							obscureTrails.add(activeRegionTrail);
						}
						else if (activeRegion != null && (!activeRegion.getBounds().intersect(newActiveRegion.getBounds())))
						{
							activeRegionTrail = new RegionTrail(0,mDuration);
							obscureTrails.add(activeRegionTrail);
						}
						
					

						activeRegion = newActiveRegion;
						
						activeRegionTrail.addRegion(activeRegion);
						
						updateProgressBar(activeRegionTrail);
						

					}
					

					handled = true;

					break;
					
				case MotionEvent.ACTION_UP:
									
					showingMenu = false;
					
					break;
										
				case MotionEvent.ACTION_MOVE:
					// Calculate distance moved

					
					if (fingerCount > 1)
					{
	                	float[] points = {event.getX(0)/vRatio, event.getY(0)/vRatio, event.getX(1)/vRatio, event.getY(1)/vRatio}; 
	                	
	                	float startX = Math.min(points[0], points[2]);
	                	float endX = Math.max(points[0], points[2]);
	                	float startY = Math.min(points[1], points[3]);
	                	float endY = Math.max(points[1], points[3]);
	                	
						newActiveRegion = new ObscureRegion(mediaPlayer.getCurrentPosition(),startX,startY,endX,endY);
						
					}
					else
					{
					
						newActiveRegion = new ObscureRegion(mediaPlayer.getCurrentPosition(),x,y);
						
						if (activeRegion != null && activeRegion.getBounds().intersect(newActiveRegion.getBounds()))
						{
								float arWidth = activeRegion.ex-activeRegion.sx;
								float arHeight = activeRegion.ey-activeRegion.sy;
								
								float sx = x - arWidth/2;
								float ex = sx + arWidth;
								
								float sy = y - arHeight/2;
								float ey = sy + arHeight;

								newActiveRegion = new ObscureRegion(mediaPlayer.getCurrentPosition(),sx,sy,ex,ey);
								
								
						}
						
					}
					
					activeRegion = newActiveRegion;
					
					activeRegionTrail.addRegion(activeRegion);
					
					handled = true;
					
					
					break;
				
			}
		}
		
		updateRegionDisplay(mediaPlayer.getCurrentPosition());
		
		return handled; // indicate event was handled	
	}
	
	private void updateProgressBar (RegionTrail rTrail)
	{
		progressBar.setThumbsActive((int)((double)rTrail.getStartTime()/(double)mDuration*100), (int)((double)rTrail.getEndTime()/(double)mDuration*100));

	}
	/*
	public int getRegionCornerMode(ObscureRegion region, float x, float y)
	{    			
    	if (Math.abs(region.getBounds().left-x)<REGION_CORNER_SIZE
    			&& Math.abs(region.getBounds().top-y)<REGION_CORNER_SIZE)
    	{
    		Log.v(LOGTAG,"CORNER_UPPER_LEFT");
    		return CORNER_UPPER_LEFT;
    	}
    	else if (Math.abs(region.getBounds().left-x)<REGION_CORNER_SIZE
    			&& Math.abs(region.getBounds().bottom-y)<REGION_CORNER_SIZE)
    	{
    		Log.v(LOGTAG,"CORNER_LOWER_LEFT");
    		return CORNER_LOWER_LEFT;
		}
    	else if (Math.abs(region.getBounds().right-x)<REGION_CORNER_SIZE
    			&& Math.abs(region.getBounds().top-y)<REGION_CORNER_SIZE)
    	{
        		Log.v(LOGTAG,"CORNER_UPPER_RIGHT");
    			return CORNER_UPPER_RIGHT;
		}
    	else if (Math.abs(region.getBounds().right-x)<REGION_CORNER_SIZE
        			&& Math.abs(region.getBounds().bottom-y)<REGION_CORNER_SIZE)
    	{
    		Log.v(LOGTAG,"CORNER_LOWER_RIGHT");
    		return CORNER_LOWER_RIGHT;
    	}
    	
		Log.v(LOGTAG,"CORNER_NONE");    	
    	return CORNER_NONE;
	}
	*/
	
	@Override
	public void onClick(View v) {
		if (v == playPauseButton) {
			if (mediaPlayer.isPlaying()) {
				mediaPlayer.pause();
				playPauseButton.setImageDrawable(this.getResources().getDrawable(android.R.drawable.ic_media_play));
				mAutoDetectEnabled = false;
			} else {
				start();
				

			}
		}
	}	

	public String pullPathFromUri(Uri originalUri) {
    	String originalVideoFilePath = null;
    	String[] columnsToSelect = { MediaStore.Video.Media.DATA };
    	Cursor videoCursor = getContentResolver().query(originalUri, columnsToSelect, null, null, null );
    	if ( videoCursor != null && videoCursor.getCount() == 1 ) {
	        videoCursor.moveToFirst();
	        originalVideoFilePath = videoCursor.getString(videoCursor.getColumnIndex(MediaStore.Images.Media.DATA));
    	}

    	return originalVideoFilePath;
    }
	
	private void createCleanSavePath() {
		
		try {
			saveFile = File.createTempFile("output", ".mp4", fileExternDir);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	
	public final static int PLAY = 1;
	public final static int STOP = 2;
	public final static int PROCESS = 3;
	
	@Override
    public boolean onCreateOptionsMenu(Menu menu) {
    	MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.video_editor_menu, menu);

        return true;
	}
	
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

    	switch (item.getItemId()) {
    	
    		case R.id.menu_new_region:
    			
    			beginAutoDetect();

    			return true;
    			
        	case R.id.menu_save:

        		completeActionFlag = 3;
        		processVideo();
        		
        		return true;   
        		
        	case R.id.menu_prefs:

        		showPrefs();
        		
        		return true;  
        	
        	case R.id.menu_clear_regions:
        		
        		obscureTrails.clear();
        		
        		updateRegionDisplay(mediaPlayer.getCurrentPosition());
        		
        		return true;
        		
/*
 			case R.id.menu_delete_original:
        		// Delete Original Image
        		handleDelete();
        		
        		return true;
*/        		
        	case R.id.menu_about:
        		// Pull up about screen
        		displayAbout();
        		
        		return true;
        	
        	case R.id.menu_preview:
        		playVideo();
        		
        		return true;
        		
    		default:
    			return false;
    	}
    }

    /*
	 * Display the about screen
	 */
	private void displayAbout() {
		
		StringBuffer msg = new StringBuffer();
		
		msg.append(getString(R.string.app_name));
		
        String versNum = "";
        
        try {
            String pkg = getPackageName();
            versNum = getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (Exception e) {
        	versNum = "";
        }
        
        msg.append(" v" + versNum);
        msg.append('\n');
        msg.append('\n');
        
        msg.append(getString(R.string.about));
	        
        msg.append('\n');
        msg.append('\n');
        
        msg.append(getString(R.string.about2));
        
        msg.append('\n');
        msg.append('\n');
        
        msg.append(getString(R.string.about3));
        
		showDialog(msg.toString());
	}
	

	private void showDialog (String msg)
	{
		 new AlertDialog.Builder(this)
         .setTitle(getString(R.string.app_name))
         .setMessage(msg)
         .create().show();
	}
    
    private void processVideo() {
    	
    	mCancelled = false;
    	
    	mediaPlayer.pause();
    	//mediaPlayer.release();
    	
    	progressDialog = new ProgressDialog(this);
    	progressDialog.setMessage("Processing. Please wait...");
    	progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
    	progressDialog.setMax(100);
        progressDialog.setCancelable(true);
       
    	 Message msg = mHandler.obtainMessage(2);
         msg.getData().putString("status","cancelled");
         progressDialog.setCancelMessage(msg);
    	
         progressDialog.show();
     	
		// Convert to video
		Thread thread = new Thread (runProcessVideo);
		thread.setPriority(Thread.MAX_PRIORITY);
		thread.start();
    }
    
	Runnable runProcessVideo = new Runnable () {
		
		public void run ()
		{

			PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
			PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "My Tag");
			wl.acquire();
	        
			try
			{
				if (ffmpeg == null)
					ffmpeg = new FFMPEGWrapper(VideoEditor.this.getBaseContext());
	
					
				ShellUtils.ShellCallback sc = new ShellUtils.ShellCallback ()
				{
					int total = 0;
					int current = 0;
					
					@Override
					public void shellOut(char[] shellout) {
						
						String line = new String(shellout);
						
						//Log.d(LOGTAG, line);
						
						//progressDialog.setMessage(new String(msg));
						//Duration: 00:00:00.99,
						//time=00:00:00.00
						
						int idx1;
						String newStatus = null;
						int progress = 0;
						
						if ((idx1 = line.indexOf("Duration:"))!=-1)
						{
							int idx2 = line.indexOf(",", idx1);
							String time = line.substring(idx1+10,idx2);
							
							int hour = Integer.parseInt(time.substring(0,2));
							int min = Integer.parseInt(time.substring(3,5));
							int sec = Integer.parseInt(time.substring(6,8));
							
							total = (hour * 60 * 60) + (min * 60) + sec;
							
							newStatus = line;
							progress = 0;
						}
						else if ((idx1 = line.indexOf("time="))!=-1)
						{
							int idx2 = line.indexOf(" ", idx1);
							String time = line.substring(idx1+5,idx2);
							newStatus = line;
							
							int hour = Integer.parseInt(time.substring(0,2));
							int min = Integer.parseInt(time.substring(3,5));
							int sec = Integer.parseInt(time.substring(6,8));
							
							current = (hour * 60 * 60) + (min * 60) + sec;
							
							progress = (int)( ((float)current) / ((float)total) *100f );
						}
						
						if (newStatus != null)
						{
						 Message msg = mHandler.obtainMessage(1);
				         msg.getData().putInt("progress", progress);
				         msg.getData().putString("status", newStatus);
				         
				         mHandler.sendMessage(msg);
						}
					}
					
				};
				
				int processVWidth = videoWidth;
				int processVHeight = videoHeight;
				
				if (outVWidth != -1)
					processVWidth = outVWidth;
				
				if (outVHeight != -1)
					processVHeight = outVHeight;
				
				// Could make some high/low quality presets	
				ffmpeg.processVideo(redactSettingsFile, obscureTrails, recordingFile, saveFile, outFormat, 
						videoWidth, videoHeight, processVWidth, processVHeight, outFrameRate, outBitRate, outVcodec, outAcodec, sc);
			}
			catch (Exception e)
			{
				Log.e(LOGTAG,"error with ffmpeg",e);
			}
			
			wl.release();
		     
			if (!mCancelled)
			{
				addVideoToGallery(saveFile);
				
				Message msg = mHandler.obtainMessage(completeActionFlag);
				msg.getData().putString("status","complete");
				mHandler.sendMessage(msg);
			}
	         
		}
		
		
	};
	
	private void addVideoToGallery (File videoToAdd)
	{
		/*
		   // Save the name and description of a video in a ContentValues map.  
        ContentValues values = new ContentValues(2);
        values.put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE_MP4);
        // values.put(MediaStore.Video.Media.DATA, f.getAbsolutePath()); 

        // Add a new record (identified by uri) without the video, but with the values just set.
        Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

        // Now get a handle to the file for that record, and save the data into it.
        try {
            InputStream is = new FileInputStream(videoToAdd);
            OutputStream os = getContentResolver().openOutputStream(uri);
            byte[] buffer = new byte[4096]; // tweaking this number may increase performance
            int len;
            while ((len = is.read(buffer)) != -1){
                os.write(buffer, 0, len);
            }
            os.flush();
            is.close();
            os.close();
        } catch (Exception e) {
            Log.e(LOGTAG, "exception while writing video: ", e);
        } 
        */
		
	
     // force mediascanner to update file
     		MediaScannerConnection.scanFile(
     				this,
     				new String[] {videoToAdd.getAbsolutePath()},
     				new String[] {MIME_TYPE_MP4},
     				null);

//        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
	}
	
	private void askPostProcessAction ()
	{

		final AlertDialog.Builder b = new AlertDialog.Builder(this);
		b.setIcon(android.R.drawable.ic_dialog_alert);
		b.setTitle(R.string.app_name);
		b.setMessage("What do you want to do with the video?");
		b.setPositiveButton(R.string.play_video, new DialogInterface.OnClickListener ()
		{

			@Override
			public void onClick(DialogInterface dialog, int which) {
				playVideo();
				
			}

			
		});
		b.setNegativeButton(R.string.share_video,  new DialogInterface.OnClickListener ()
		{

			@Override
			public void onClick(DialogInterface dialog, int which) {
				shareVideo();
				
			}

			
		});
		b.show();

	}
	private void playVideo() {
		
    	Intent intent = new Intent(android.content.Intent.ACTION_VIEW);
    	intent.setDataAndType(Uri.parse(saveFile.getPath()), MIME_TYPE_MP4);    	
   	 	startActivityForResult(intent,0);
   	 	
	}
	
	private void shareVideo() {
    	Intent intent = new Intent(Intent.ACTION_SEND);
    	intent.setType(MIME_TYPE_MP4);
    	intent.putExtra(Intent.EXTRA_STREAM, Uri.parse(saveFile.getPath()));
    	startActivityForResult(Intent.createChooser(intent, "Share Video"),0);     
	}
	
	protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
		super.onActivityResult(requestCode, resultCode, intent);
		
	}

	@Override
	public void inOutValuesChanged(int thumbInValue, int thumbOutValue) {
		if (activeRegionTrail != null) {
			
			
			activeRegionTrail.setStartTime(thumbInValue);
			activeRegionTrail.setStartTime(thumbOutValue);
		}
	}
	
	public void inflatePopup(boolean showDelayed, int x, int y) {
		if (popupMenu == null)
			initPopup();

		popupMenu.show(regionsView, x, y);
	}
	
	private void initPopup ()
	{
		popupMenu = new QuickAction(this);

		popupMenuItems = new ActionItem[5];
		
		popupMenuItems[0] = new ActionItem();
		popupMenuItems[0].setTitle("Set In Point");
		popupMenuItems[0].setActionId(0);
		
		//popupMenuItems[0].setIcon(getResources().getDrawable(R.drawable.icon));			

		popupMenuItems[1] = new ActionItem();
		popupMenuItems[1].setActionId(1);
		
		popupMenuItems[1].setTitle("Set Out Point");
				
		popupMenuItems[2] = new ActionItem();
		popupMenuItems[2].setActionId(2);		
		popupMenuItems[2].setTitle("Remove Region");				

		popupMenuItems[3] = new ActionItem();
		popupMenuItems[3].setActionId(3);		
		popupMenuItems[3].setTitle("Remove Trail");				

		for (int i=0; i < popupMenuItems.length; i++) {
			if (popupMenuItems[i] != null) {
				popupMenu.addActionItem(popupMenuItems[i]);
			}
		}
			
		popupMenu.setOnActionItemClickListener(this);
	}

	
	@Override
	protected void onPause() {

		super.onPause();
		mediaPlayer.reset();
		
	}

	@Override
	protected void onStop() {
		super.onStop();
		this.mAutoDetectEnabled = false;
	}	
	
	private void killVideoProcessor ()
	{
		int killDelayMs = 300;

		String ffmpegBin = new File(getDir("bin",0),"ffmpeg").getAbsolutePath();

		int procId = -1;
		
		while ((procId = ShellUtils.findProcessId(ffmpegBin)) != -1)
		{
			
			Log.d(LOGTAG, "Found PID=" + procId + " - killing now...");
			
			String[] cmd = { ShellUtils.SHELL_CMD_KILL + ' ' + procId + "" };
			
			try { 
			ShellUtils.doShellCommand(cmd,new ShellCallback ()
			{

				@Override
				public void shellOut(char[] msg) {
					// TODO Auto-generated method stub
					
				}
				
			}, false, false);
			Thread.sleep(killDelayMs); }
			catch (Exception e){}
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		setPrefs();
		
	}
	
	private void setPrefs ()
	{
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

		
		outFrameRate = Integer.parseInt(prefs.getString("pref_out_fps", DEFAULT_OUT_FPS).trim());
		outBitRate = Integer.parseInt(prefs.getString("pref_out_rate", DEFAULT_OUT_RATE).trim());
		outFormat = prefs.getString("pref_out_format", DEFAULT_OUT_FORMAT).trim();
		outAcodec =  prefs.getString("pref_out_acodec", DEFAULT_OUT_ACODEC).trim();
		outVcodec =  prefs.getString("pref_out_vcodec", DEFAULT_OUT_VCODEC).trim();

		outVWidth =   Integer.parseInt(prefs.getString("pref_out_vwidth", "480").trim());
		outVHeight =   Integer.parseInt(prefs.getString("pref_out_vheight", "320").trim());
		
	}
	
	/*
	private void doAutoDetectionThread()
	{
		Thread thread = new Thread ()
		{
			public void run ()
			{
				long cTime = mediaPlayer.getCurrentPosition();
				Bitmap bmp = getVideoFrame(recordingFile.getAbsolutePath(),cTime);
				doAutoDetection(bmp, cTime, 500);

			//	Message msg = mHandler.obtainMessage(3);
		     //   mHandler.sendMessage(msg);
			}
		};
		thread.start();
	}*/
	
	/*
	public static Bitmap getVideoFrame(String videoPath,long frameTime) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoPath);                   
            return retriever.getFrameAtTime(frameTime, MediaMetadataRetriever.OPTION_CLOSEST);
        } catch (IllegalArgumentException ex) {
            ex.printStackTrace();
        } catch (RuntimeException ex) {
            ex.printStackTrace();
        } finally {
            try {
                retriever.release();
            } catch (RuntimeException ex) {
            }
        }
        return null;
    }*/
	
	/*
	 * Do actual auto detection and create regions
	 * 
	 * public void createImageRegion(int _scaledStartX, int _scaledStartY, 
			int _scaledEndX, int _scaledEndY, 
			int _scaledImageWidth, int _scaledImageHeight, 
			int _imageWidth, int _imageHeight, 
			int _backgroundColor) {
	 */
	
	private int autoDetectFrame(Bitmap bmp, int cTime, int cBuffer, int cDuration) {
		
		if (bmp == null)
			return 0;
		
		RectF[] autodetectedRects = runFaceDetection(bmp);
		for (RectF autodetectedRect : autodetectedRects)
		{

			//float faceBuffer = -1 * (autodetectedRect.right-autodetectedRect.left)/15;			
			//autodetectedRect.inset(faceBuffer, faceBuffer);
			
			ObscureRegion newRegion = new ObscureRegion(cTime,autodetectedRect.left,
					autodetectedRect.top,
					autodetectedRect.right,
					autodetectedRect.bottom,
					ObscureRegion.DEFAULT_MODE);
			
			
			if (activeRegion == null)
			{
				activeRegionTrail = new RegionTrail(0,mDuration);
				obscureTrails.add(activeRegionTrail);
			}
			else if (!activeRegion.getRectF().intersect(newRegion.getRectF()))
			{
				activeRegionTrail = new RegionTrail(0,mDuration);
				obscureTrails.add(activeRegionTrail);
			}
			
			activeRegionTrail.addRegion(newRegion);
			
			activeRegion = newRegion;
		}	

		Message msg = mHandler.obtainMessage(5);
		mHandler.sendMessage(msg);
		
		return autodetectedRects.length;
	}
	
	/*
	 * The actual face detection calling method
	 */
	private RectF[] runFaceDetection(Bitmap bmp) {
		RectF[] possibleFaceRects;
		
		try {
			//Bitmap bProc = toGrayscale(bmp);
			GoogleFaceDetection gfd = new GoogleFaceDetection(bmp);
			int numFaces = gfd.findFaces();
	        Log.d(ObscuraConstants.TAG,"Num Faces Found: " + numFaces); 
	        possibleFaceRects = gfd.getFaces();
		} catch(NullPointerException e) {
			possibleFaceRects = null;
		}
		return possibleFaceRects;				
	}
	
	public Bitmap toGrayscale(Bitmap bmpOriginal)
	{        
	    int width, height;
	    height = bmpOriginal.getHeight();
	    width = bmpOriginal.getWidth();    

	    Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
	    Canvas c = new Canvas(bmpGrayscale);
	    Paint paint = new Paint();
	    ColorMatrix cm = new ColorMatrix();
	    cm.setSaturation(0);
	    
	    ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
	    
	    paint.setColorFilter(f);
	 
	    c.drawBitmap(bmpOriginal, 0, 0, paint);
	    
	    
	    
	    return bmpGrayscale;
	}
	
	public static Bitmap createContrast(Bitmap src, double value) {
		// image size
		int width = src.getWidth();
		int height = src.getHeight();
		// create output bitmap
		Bitmap bmOut = Bitmap.createBitmap(width, height, src.getConfig());
		// color information
		int A, R, G, B;
		int pixel;
		// get contrast value
		double contrast = Math.pow((100 + value) / 100, 2);

		// scan through all pixels
		for(int x = 0; x < width; ++x) {
			for(int y = 0; y < height; ++y) {
				// get pixel color
				pixel = src.getPixel(x, y);
				A = Color.alpha(pixel);
				// apply filter contrast for every channel R, G, B
				R = Color.red(pixel);
				R = (int)(((((R / 255.0) - 0.5) * contrast) + 0.5) * 255.0);
				if(R < 0) { R = 0; }
				else if(R > 255) { R = 255; }

				G = Color.red(pixel);
				G = (int)(((((G / 255.0) - 0.5) * contrast) + 0.5) * 255.0);
				if(G < 0) { G = 0; }
				else if(G > 255) { G = 255; }

				B = Color.red(pixel);
				B = (int)(((((B / 255.0) - 0.5) * contrast) + 0.5) * 255.0);
				if(B < 0) { B = 0; }
				else if(B > 255) { B = 255; }

				// set new pixel color to output bitmap
				bmOut.setPixel(x, y, Color.argb(A, R, G, B));
			}
		}

		// return final image
		return bmOut;
	}

	public void showPrefs ()
	{
		Intent intent = new Intent(this, VideoPreferences.class);
		startActivityForResult(intent,0);
		
	}

	@Override
	public void onItemClick(QuickAction source, int pos, int actionId) {
		
		switch (actionId) {
		case 0:
			// set in point
			activeRegionTrail.setStartTime(mediaPlayer.getCurrentPosition());
			updateProgressBar(activeRegionTrail);
			updateRegionDisplay(mediaPlayer.getCurrentPosition());
			
			
			break;
		case 1:
			// set out point
			activeRegionTrail.setEndTime(mediaPlayer.getCurrentPosition());
			updateProgressBar(activeRegionTrail);
			updateRegionDisplay(mediaPlayer.getCurrentPosition());
			
			break;
		case 2:
			// Remove region
			activeRegionTrail.removeRegion(activeRegion);
			activeRegion = null;
			updateRegionDisplay(mediaPlayer.getCurrentPosition());
			
			break;
			
		case 3:
			// Remove region
			obscureTrails.remove(activeRegionTrail);
			activeRegionTrail = null;
			activeRegion = null;
			updateRegionDisplay(mediaPlayer.getCurrentPosition());
			
			break;
	}
		
	}

}
