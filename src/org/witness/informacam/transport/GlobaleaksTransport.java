package org.witness.informacam.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.witness.informacam.R;
import org.witness.informacam.models.Model;
import org.witness.informacam.utils.Constants.Logger;
import org.witness.informacam.utils.Constants.Models;

import android.app.NotificationManager;
import android.content.Context;
import android.support.v4.app.NotificationCompat;

public class GlobaleaksTransport extends Transport {
	GLSubmission submission = null;
	
	public final static String FULL_DESCRIPTION = "Full description";
	public final static String FILES_DESCRIPTION = "Files description";
	public final static String SHORT_TITLE = "Short title";
	public final static String DEFAULT_SHORT_TITLE = "InformaCam submission from mobile client %s";
	public final static String DEFAULT_FULL_DESCRIPTION = "PGP Fingerprint %s";

	public GlobaleaksTransport() {
		super(Models.ITransportStub.Globaleaks.TAG);
	}

	@Override
	protected boolean init() {
		if(!super.init()) {
			return false;
		}

		NotificationManager mNotifyManager =
		        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this);
		mBuilder.setContentTitle(getString(R.string.app_name) + " Upload")
		    .setContentText("Upload in progress to: " + repoName)
		    .setTicker("Upload in progress")
		    .setSmallIcon(android.R.drawable.ic_menu_upload);
		  mBuilder.setProgress(100, 0, false);
          // Displays the progress bar for the first time.
          mNotifyManager.notify(0, mBuilder.build());
          
		submission = new GLSubmission();
		submission.context_gus = repository.asset_id;

		transportStub.asset.key = "files";	// (?)

		Logger.d(LOG, submission.asJson().toString());

		// init submission
		JSONObject subResponse = (JSONObject) doPost(submission, repository.asset_root + "/submission");
		
		if(subResponse == null) {
			
	
			
			resend();
		} else {
			try {
				submission.inflate(subResponse);

				  mBuilder.setProgress(100, 30, false);
					// Displays the progress bar for the first time.
			          mNotifyManager.notify(0, mBuilder.build());
				
				if(submission.submission_gus != null) {
					if(doPost(transportStub.asset, repository.asset_root + "/submission/" + submission.submission_gus + "/file") != null) {
						submission.finalize = true;
						try {
							submission.wb_fields.put(SHORT_TITLE, String.format(DEFAULT_SHORT_TITLE, informaCam.user.alias));
							submission.wb_fields.put(FULL_DESCRIPTION, String.format(DEFAULT_FULL_DESCRIPTION, informaCam.user.pgpKeyFingerprint));
						} catch (JSONException e) {
							Logger.e(LOG, e);
						}
						

						  mBuilder.setProgress(100, 60, false);
							// Displays the progress bar for the first time.
					          mNotifyManager.notify(0, mBuilder.build());

						JSONArray receivers = (JSONArray) doGet(repository.asset_root + "/receivers");
						if(receivers != null) {
							if(receivers.length() > 0) {
								submission.receivers = new ArrayList<String>();

								for(int r=0; r<receivers.length(); r++) {
									try {
										JSONObject receiver = receivers.getJSONObject(r);
										submission.receivers.add(receiver.getString(GLSubmission.RECEIVER_GUS));
									} catch (JSONException e) {
										Logger.e(LOG, e);
									}
								}
							}
						} else {
							resend();
						}

						Logger.d(LOG, "ABOUT TO PUT SUBMISSION:\n" + submission.asJson().toString());
						/*
						 * {
						 * 		"files":[],
						 * 		"wb_fields":{
						 * 			"Short title":"InformaCam submission from mobile client jetta pre-14"
						 * 		},
						 * 		"submission_gus":"94c74825-acaa-426a-b2e9-b9ac3c18caff",
						 * 		"receipt":"",
						 * 		"mark":"submission",
						 * 		"download_limit":"3",		#SHOULD BE INT!
						 * 		"context_gus":"19aae9c8-93eb-44ce-9652-46c73a541f83",
						 * 		"access_limit":"50",		#SHOULD BE INT!
						 * 		"escalation_threshold":"0",
						 * 		"receivers":[
						 * 			"5bf0f9de-e64b-4a6e-901c-104009501a7f",
						 * 			"070d828f-c690-4006-89c9-8e2b1cb7c97c",
						 * 			"7bdf2f1e-9b53-4f56-a099-a748cdb78b4f",
						 * 			"0d11e41f-7bb5-4eaf-a735-258b680b1e8f"
						 * 		],
						 * 		"id":"94c74825-acaa-426a-b2e9-b9ac3c18caff",
						 * 		"creation_date":"2013-08-20T14:56:17.053271",
						 * 		"pertinence":"0",
						 * 		"expiration_date":"2013-09-04T14:56:17.053228",
						 * 		"finalize":true
						 * }
						 */
						
						try {
							JSONObject submissionResult = (JSONObject) doPut(submission, repository.asset_root + "/submission/" + submission.submission_gus);
							if(submissionResult != null) {
								submission.inflate(submissionResult);
								Logger.d(LOG, "OMG HOORAY:\n" + submission.asJson().toString());
								
								mBuilder
							    .setContentText("Successful upload to: " + repository.asset_root)
							    .setTicker("Successful upload to: " + repository.asset_root);
							    mBuilder.setAutoCancel(true);
								  mBuilder.setProgress(0, 0, false);
									// Displays the progress bar for the first time.
							          mNotifyManager.notify(0, mBuilder.build());
								
							}
						} catch(Exception e) {
							Logger.e(LOG, e);
						}
						
						finishSuccessfully();
					} else {
						
						resend();
						
					}

				}

			} catch(NullPointerException e) {
				Logger.e(LOG, e);
			}
		}
		
		return true;
	}
	
	@Override
	protected HttpURLConnection buildConnection(String urlString, boolean useTorProxy) {
		HttpURLConnection http = super.buildConnection(urlString, useTorProxy);
		http.setRequestProperty("X-XSRF-TOKEN", "antani");
		http.setRequestProperty("Cookie", "XSRF-TOKEN=antani;");
		
		return http;
	}

	@Override
	public Object parseResponse(InputStream response) {
		super.parseResponse(response);
		try {
			response.close();
		} catch (IOException e) {
			Logger.e(LOG, e);
		}

		if(transportStub.lastResult.charAt(0) == '[') {
			try {
				return (JSONArray) new JSONTokener(transportStub.lastResult).nextValue();
			} catch (JSONException e) {
				Logger.e(LOG, e);
			}
		} else {
			try {
				return (JSONObject) new JSONTokener(transportStub.lastResult).nextValue();
			} catch (JSONException e) {
				Logger.e(LOG, e);
			}
		}

		Logger.d(LOG, "THIS POST DID NOT WORK");
		return null;
	}

	public class GLSubmission extends Model implements Serializable {
		private static final long serialVersionUID = -2831519338966909927L;
		
		public String context_gus = null;
		public String submission_gus = null;
		public boolean finalize = false;
		public List<String> files = new ArrayList<String>();
		public List<String> receivers = new ArrayList<String>();
		public JSONObject wb_fields = new JSONObject();
		public String pertinence = null;
		public String expiration_date = null;
		public String creation_date = null;
		public String receipt = null;
		public String escalation_threshold = null;
		public String mark = null;
		public String id = null;

		public String download_limit = null;
		public String access_limit = null;

		private final static String DOWNLOAD_LIMIT = "download_limit";
		private final static String ACCESS_LIMIT = "access_limit";
		private final static String RECEIVER_GUS = "receiver_gus";
		
		public GLSubmission() {
			super();
		}
		
		public GLSubmission(GLSubmission submission) {
			super();
			inflate(submission);
		}
		
		@Override
		public void inflate(JSONObject values) {
			try {
				if(values.has(DOWNLOAD_LIMIT)) {
					values = values.put(DOWNLOAD_LIMIT, Integer.toString(values.getInt(DOWNLOAD_LIMIT)));
				}
			} catch (JSONException e) {}

			try {
				if(values.has(ACCESS_LIMIT)) {
					values = values.put(ACCESS_LIMIT, Integer.toString(values.getInt(ACCESS_LIMIT)));
				}
			} catch (JSONException e) {}

			super.inflate(values);
		}

		@Override
		public JSONObject asJson() {
			JSONObject obj = super.asJson();

			try {
				obj = obj.put(DOWNLOAD_LIMIT, Integer.parseInt(obj.getString(DOWNLOAD_LIMIT)));
			} catch (NumberFormatException e) {}
			catch (JSONException e) {}

			try {
				obj = obj.put(ACCESS_LIMIT, Integer.parseInt(obj.getString(ACCESS_LIMIT)));
			} catch (NumberFormatException e) {}
			catch (JSONException e) {}

			return obj;
		}
	}
}