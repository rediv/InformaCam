package org.witness.informacam;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Vector;

import org.witness.informacam.crypto.AesUtility;
import org.witness.informacam.crypto.SignatureService;
import org.witness.informacam.storage.IOService;
import org.witness.informacam.transport.UploaderService;
import org.witness.informacam.utils.Constants.Actions;
import org.witness.informacam.utils.Constants.App;
import org.witness.informacam.utils.Constants.Codes;
import org.witness.informacam.utils.Constants.IManifest;
import org.witness.informacam.utils.Constants.InformaCamEventListener;
import org.witness.informacam.utils.Constants.Models;
import org.witness.informacam.utils.Constants.App.Storage.Type;
import org.witness.informacam.utils.models.ICredentials;
import org.witness.informacam.utils.models.IUser;
import org.witness.informacam.utils.models.Model;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

public class InformaCam extends Service {	
	public final LocalBinder binder = new LocalBinder();
	private final static String LOG = App.LOG;

	private List<BroadcastReceiver> broadcasters = new Vector<BroadcastReceiver>();

	public IUser user;
	private Bundle update;

	Intent ioServiceIntent, signatureServiceIntent, uploaderServiceIntent;

	public UploaderService uploaderService = null;
	public IOService ioService = null;
	public SignatureService signatureService = null;

	private static InformaCam informaCam;
	public Activity a;
	public Handler h = new Handler();

	SharedPreferences.Editor ed;
	private SharedPreferences sp;

	public class LocalBinder extends Binder {
		public InformaCam getService() {
			return InformaCam.this;
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		Log.d(LOG, "InformaCam service started via intent");

		ioServiceIntent = new Intent(this, IOService.class);
		signatureServiceIntent = new Intent(this, SignatureService.class);
		uploaderServiceIntent = new Intent(this, UploaderService.class);

		broadcasters.add(new IBroadcaster(new IntentFilter(Actions.SHUTDOWN)));
		broadcasters.add(new IBroadcaster(new IntentFilter(Actions.ASSOCIATE_SERVICE)));
		broadcasters.add(new IBroadcaster(new IntentFilter(Actions.UPLOADER_UPDATE)));

		for(BroadcastReceiver br : broadcasters) {
			registerReceiver(br, ((IBroadcaster) br).intentFilter);
		}

		sp = getSharedPreferences(IManifest.PREF, MODE_PRIVATE);
		ed = sp.edit();

		new Thread(new Runnable() {
			@Override
			public void run() {
				startService(signatureServiceIntent);
				startService(uploaderServiceIntent);
				startService(ioServiceIntent);
			}
		}).start();

		informaCam = this;
	}

	public void startup() {
		Log.d(LOG, "NOW we init!");
		user = new IUser();
		boolean init = false;
		boolean login = false;

		try {
			FileInputStream fis = this.openFileInput(IManifest.PATH);			
			if(fis.available() == 0) {
				init = true;
			} else {
				byte[] ubytes = new byte[fis.available()];
				fis.read(ubytes);
				user.inflate(ubytes);

				if(user.isLoggedIn) {
					if(!attemptLogin()) {
						login = true;
					}

				} else {
					login = true;
				}
			}
		} catch (FileNotFoundException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
			init = true;

		} catch (IOException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
			init = true;
		}

		if(!user.hasCompletedWizard) {
			init = true;
		}

		Bundle data = new Bundle();

		if(init) {
			// we launch our wizard!			
			data.putInt(Codes.Extras.MESSAGE_CODE, Codes.Messages.Wizard.INIT);
		}

		if(login) {
			// we log in!
			user.isLoggedIn = false;
			ioService.saveBlob(user, new java.io.File(IManifest.PATH));
			data.putInt(Codes.Extras.MESSAGE_CODE, Codes.Messages.Login.DO_LOGIN);
		}

		putUpdate(data);

	}
	
	private void putUpdate(Bundle update) {
		this.update = update;
	}
	
	public Message getUpdate() {
		Message message = new Message();
		message.setData(update);
		
		return message;
	}

	private void shutdown() {
		for(BroadcastReceiver br : broadcasters) {
			unregisterReceiver(br);
		}

		ioService.unmount();

		saveStates();

		stopService(ioServiceIntent);
		stopService(signatureServiceIntent);
		stopService(uploaderServiceIntent);

		stopSelf();
	}

	private void saveStates() {
		saveState(user, new java.io.File(IManifest.PATH));
	}

	public void saveState(Model model, java.io.File cache) {
		ioService.saveBlob(model.asJson().toString().getBytes(), cache);
	}

	public void saveState(Model model, info.guardianproject.iocipher.File cache) {
		ioService.saveBlob(model.asJson().toString().getBytes(), cache);
	}

	public void associateActivity(Activity a) {
		this.a = a;
	}
	
	public void promptForLogin(OnDismissListener odl) {
		AlertDialog.Builder ad = new AlertDialog.Builder(this);
		final Dialog d = ad.create();
		d.setOnCancelListener(new OnCancelListener() {

			@Override
			public void onCancel(DialogInterface dialog) {
				a.finish();
			}
			
		});
		
		
		if(odl != null) {
			d.setOnDismissListener(odl);
		}
		
		View view = LayoutInflater.from(this).inflate(R.layout.alert_login, null);
		final EditText password = (EditText) view.findViewById(R.id.login_password);
		final ProgressBar waiter = (ProgressBar) view.findViewById(R.id.login_waiter);
		
		final Button commit = (Button) view.findViewById(R.id.login_commit);
		commit.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				waiter.setVisibility(View.VISIBLE);
				commit.setVisibility(View.GONE);
				
				if(attemptLogin(password.getText().toString())) {
					d.dismiss();
				} else {
					waiter.setVisibility(View.GONE);
					commit.setVisibility(View.VISIBLE);
				}
				
			}
			
		});
		
		final Button cancel = (Button) view.findViewById(R.id.login_cancel);
		cancel.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				d.cancel();
			}
			
		});
		
		ad.setView(view);
		ad.show();
	}
	
	public void promptForLogin() {
		promptForLogin(null);
	}
	
	public void promptForLogin(final int resumeCode, final byte[] data, final info.guardianproject.iocipher.File file) {
		OnDismissListener odl = new OnDismissListener() {

			@Override
			public void onDismiss(DialogInterface dialog) {
				switch(resumeCode) {
				case Codes.Routes.RETRY_SAVE:
					ioService.saveBlob(data, file);
					break;
				}
				
			}
			
		};
		promptForLogin(odl);
	}

	public void persistLogin(String password) {
		ed.putString(Models.IUser.PASSWORD, password).commit();
		ed.putBoolean(Models.IUser.AUTH_TOKEN, true).commit();
	}

	public boolean attemptLogout() {
		user.isLoggedIn = false;
		user.lastLogOut = System.currentTimeMillis();

		ed.remove(Models.IUser.PASSWORD).commit();
		ed.remove(Models.IUser.AUTH_TOKEN).commit();

		shutdown();

		return true;
	}

	public boolean attemptLogin() {
		String password = sp.getString(Models.IUser.PASSWORD, null);
		return password == null ? false : attemptLogin(password);

	}

	public boolean attemptLogin(String password) {
		ICredentials credentials = new ICredentials();
		credentials.inflate(ioService.getBytes(Models.IUser.CREDENTIALS, Type.INTERNAL_STORAGE));

		String authToken = AesUtility.DecryptWithPassword(password, credentials.iv.getBytes(), credentials.passwordBlock.getBytes());
		if(authToken != null && ioService.initIOCipher(authToken)) {

			user.inflate(ioService.getBytes(IManifest.PATH, Type.INTERNAL_STORAGE));

			user.isLoggedIn = true;
			user.lastLogIn = System.currentTimeMillis();
			ioService.saveBlob(user.asJson().toString().getBytes(), new java.io.File(IManifest.PATH));

			persistLogin(password);
			return true;
		}

		return false;
	}

	public void update(Bundle data) {
		Message message = new Message();
		message.setData(data);

		((InformaCamEventListener) a).onUpdate(message);
	}

	public static InformaCam getInstance() {
		Log.d(LOG, "no activity association, just returning instance");
		return informaCam;
	}
	
	public static InformaCam getInstance(Activity a) {
		informaCam.associateActivity(a);
		Log.d(LOG, "associating to activity " + a.getClass().getName());
		return informaCam;
	}
	
	public static InformaCam getInstance(FragmentActivity a) {
		informaCam.associateActivity(a);
		Log.d(LOG, "associating to activity " + a.getClass().getName());
		return informaCam;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		Log.d(LOG, "INFORMA CAM SERVICE HAS BEEN DESTROYED");
	}

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	private class IBroadcaster extends BroadcastReceiver {
		private final static String LOG = App.LOG;

		IntentFilter intentFilter;

		public IBroadcaster(IntentFilter intentFilter) {
			this.intentFilter = intentFilter;
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			// XXX: maybe not?
			if(intent.getAction().equals(Actions.SHUTDOWN)) {
				Log.d(LOG, "KILLING IC?");
				shutdown();
			} else if(intent.getAction().equals(Actions.ASSOCIATE_SERVICE)) {				
				switch(intent.getIntExtra(Codes.Keys.SERVICE, 0)) {
				case Codes.Routes.SIGNATURE_SERVICE:
					signatureService = SignatureService.getInstance();
					break;
				case Codes.Routes.IO_SERVICE:
					ioService = IOService.getInstance();
					break;
				case Codes.Routes.UPLOADER_SERVICE:
					uploaderService = UploaderService.getInstance();
					break;
				}
				
				if(signatureService == null) {
					Log.d(LOG, "cannot init yet (signature) ... trying again");
					return;
				}
				
				if(uploaderService == null) {
					Log.d(LOG, "cannot init yet (uploader) ... trying again");
					return;
				}
				
				if(ioService == null) {
					Log.d(LOG, "cannot init yet (io) ... trying again");
					return;
				}
				
				new Thread(new Runnable() {
					@Override
					public void run() {
						startup();
					}
				}).start();
				
			} else if(intent.getAction().equals(Actions.UPLOADER_UPDATE)) {
				switch(intent.getIntExtra(Codes.Keys.UPLOADER, 0)) {
				case Codes.Transport.MUST_INSTALL_TOR:
					break;
				case Codes.Transport.MUST_START_TOR:
					break;
				}
			}

		}

	}
}
