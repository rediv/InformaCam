package org.witness.informacam.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.Security;
import java.security.SignatureException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.spongycastle.bcpg.*;
import org.spongycastle.bcpg.sig.KeyFlags;
import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.spongycastle.openpgp.*;
import org.spongycastle.util.encoders.Hex;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.witness.informacam.InformaCam;
import org.witness.informacam.models.credentials.IKeyStore;
import org.witness.informacam.models.credentials.ISecretKey;
import org.witness.informacam.storage.IOUtility;
import org.witness.informacam.utils.Constants.App;
import org.witness.informacam.utils.Constants.Codes;
import org.witness.informacam.utils.Constants.IManifest;
import org.witness.informacam.utils.Constants.App.Storage;
import org.witness.informacam.utils.Constants.App.Storage.Type;
import org.witness.informacam.utils.Constants.Models.IUser;
import org.witness.informacam.utils.Constants.Models.ICredentials;

import android.os.Bundle;
import android.util.Base64;
import android.util.Log;

public class KeyUtility {

	private final static String LOG = App.Crypto.LOG;
	
	public static String getFingerprintFromKey(byte[] keyblock) throws IOException, PGPException {
		PGPPublicKey key = extractPublicKeyFromBytes(keyblock);
		return new String(Hex.encode(key.getFingerprint()));
	}

	@SuppressWarnings("unchecked")
	public static PGPSecretKey extractSecretKey(byte[] keyblock) {
		PGPSecretKey secretKey = null;
		try {
			PGPSecretKeyRingCollection pkrc = new PGPSecretKeyRingCollection(PGPUtil.getDecoderStream(new ByteArrayInputStream(Base64.decode(keyblock, Base64.DEFAULT))));
			Iterator<PGPSecretKeyRing> rIt = pkrc.getKeyRings();
			while(rIt.hasNext()) {
				PGPSecretKeyRing pkr = (PGPSecretKeyRing) rIt.next();
				Iterator<PGPSecretKey> kIt = pkr.getSecretKeys();
				while(secretKey == null && kIt.hasNext()) {
					secretKey = kIt.next();
				}
			}
			return secretKey;
		} catch(IOException e) {
			return null;
		} catch(PGPException e) {
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	public static PGPPublicKey extractPublicKeyFromBytes(byte[] keyBlock) throws IOException, PGPException {
		PGPPublicKeyRingCollection keyringCol = new PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(new ByteArrayInputStream(Base64.decode(keyBlock, Base64.DEFAULT))));
		PGPPublicKey key = null;
		Iterator<PGPPublicKeyRing> rIt = keyringCol.getKeyRings();
		while(key == null && rIt.hasNext()) {
			PGPPublicKeyRing keyring = (PGPPublicKeyRing) rIt.next();
			Iterator<PGPPublicKey> kIt = keyring.getPublicKeys();
			while(key == null && kIt.hasNext()) {
				PGPPublicKey k = (PGPPublicKey) kIt.next();
				if(k.isEncryptionKey())
					key = k;
			}
		}
		
		if(key == null) {
			throw new IllegalArgumentException("there isn't an encryption key here.");
		}

		return key;
	}

	public static String generatePassword(byte[] baseBytes) throws NoSuchAlgorithmException {
		// initialize random bytes
		byte[] randomBytes = new byte[baseBytes.length];
		SecureRandom sr = SecureRandom.getInstance("SHA1PRNG");
		sr.nextBytes(randomBytes);

		// xor by baseImage
		byte[] product = new byte[baseBytes.length];
		for(int b = 0; b < baseBytes.length; b++) {
			product[b] = (byte) (baseBytes[b] ^ randomBytes[b]);
		}

		// digest to SHA1 string, voila password.
		MessageDigest md = MessageDigest.getInstance("SHA-1");
		return Base64.encodeToString(md.digest(product), Base64.DEFAULT);
	}

	@SuppressWarnings("deprecation")
	public static boolean initDevice() {
		int progress = 1;
		Bundle data = new Bundle();
		data.putInt(Codes.Extras.MESSAGE_CODE, Codes.Messages.UI.UPDATE);
		data.putInt(Codes.Keys.UI.PROGRESS, progress);

		final String authToken;
		String secretAuthToken, keyStorePassword;
		InformaCam informaCam = InformaCam.getInstance();
		informaCam.update(data);

		try {
			byte[] baseImageBytes = informaCam.ioService.getBytes(informaCam.user.getJSONArray(IUser.PATH_TO_BASE_IMAGE).getString(0), Storage.Type.INTERNAL_STORAGE);

			progress += 10;
			data.putInt(Codes.Keys.UI.PROGRESS, progress);
			informaCam.update(data);

			authToken = generatePassword(baseImageBytes);
			secretAuthToken = generatePassword(baseImageBytes);
			keyStorePassword = generatePassword(baseImageBytes);
			
			baseImageBytes = null;

			informaCam.ioService.initIOCipher(authToken);

			progress += 10;
			data.putInt(Codes.Keys.UI.PROGRESS, progress);
			informaCam.update(data);
			
			informaCam.setCredentialManager(new CredentialManager(informaCam, !informaCam.ioService.isMounted(), true) {
				@Override
				public void onCacheWordUninitialized() {
					if(firstUse) {
					
						Log.d(LOG, "INIT: onCacheWordUninitialized()");

						try {
							setMasterPassword(informaCam.user.getString(IUser.PASSWORD));
						} catch (JSONException e) {
							Log.e(LOG, e.toString());
							e.printStackTrace();
						}
					} else {
						super.onCacheWordUninitialized();
					}
				}
				
				@Override
				public void onCacheWordOpened() {
					// there is not credential block, so override this.
					if(firstUse) {
						Log.d(LOG, "INIT: onCacheWordOpened()");
						String authTokenBlobBytes = new String(setAuthToken(authToken));

						try {
							JSONObject authTokenBlob = (JSONObject) new JSONTokener(authTokenBlobBytes).nextValue();
							authTokenBlob.put(ICredentials.PASSWORD_BLOCK, authTokenBlob.getString("value"));
							authTokenBlob.remove("value");

							if(informaCam.ioService.saveBlob(authTokenBlob.toString().getBytes(), new java.io.File(IUser.CREDENTIALS))) {
								informaCam.user.setHasCredentials(true);
								
							}
						} catch (JSONException e) {
							Log.e(LOG, e.toString(),e);
						}
						catch (IOException e) {
							Log.e(LOG, e.toString(),e);
						}
					} else {
						super.onCacheWordOpened();
					}
				}
			});
			
			progress += 10;
			data.putInt(Codes.Keys.UI.PROGRESS, progress);
			informaCam.update(data);
						
			Map<String, InputStream> publicCredentials = new HashMap<String, InputStream>();
			JSONArray baseImages = informaCam.user.getJSONArray(IUser.PATH_TO_BASE_IMAGE);
			for(int j=0; j<baseImages.length(); j++) {
				
				InputStream baseImageStream = informaCam.ioService.getStream(baseImages.getString(j), Storage.Type.INTERNAL_STORAGE);
				
				info.guardianproject.iocipher.File baseImage = new info.guardianproject.iocipher.File(IUser.BASE_IMAGE + "_" + j);
				if(informaCam.ioService.saveBlob(baseImageStream, baseImage)) {
					informaCam.ioService.delete(baseImages.getString(j), Storage.Type.INTERNAL_STORAGE);
					publicCredentials.put(IUser.BASE_IMAGE + "_" + j, informaCam.ioService.getStream(baseImage.getAbsolutePath(), Storage.Type.IOCIPHER));
				}
			}
			
			informaCam.user.remove(IUser.PATH_TO_BASE_IMAGE);

			progress += 10;
			data.putInt(Codes.Keys.UI.PROGRESS, progress);
			informaCam.update(data);

			Security.addProvider(new BouncyCastleProvider());
			KeyPairGenerator kpg;

			kpg = KeyPairGenerator.getInstance("RSA","BC");
			kpg.initialize(4096);
			KeyPair keyPair = kpg.generateKeyPair();

			progress += 10;
			data.putInt(Codes.Keys.UI.PROGRESS, progress);
			informaCam.update(data);

			PGPSignatureSubpacketGenerator hashedGen = new PGPSignatureSubpacketGenerator();
			hashedGen.setKeyFlags(true, KeyFlags.ENCRYPT_STORAGE);
			hashedGen.setPreferredCompressionAlgorithms(false, new int[] {
					CompressionAlgorithmTags.ZLIB,
					CompressionAlgorithmTags.ZIP
			});
			hashedGen.setPreferredHashAlgorithms(false, new int[] {
					HashAlgorithmTags.SHA256,
					HashAlgorithmTags.SHA384,
					HashAlgorithmTags.SHA512
			});
			hashedGen.setPreferredSymmetricAlgorithms(false, new int[] {
					SymmetricKeyAlgorithmTags.AES_256,
					SymmetricKeyAlgorithmTags.AES_192,
					SymmetricKeyAlgorithmTags.AES_128,
					SymmetricKeyAlgorithmTags.CAST5,
					SymmetricKeyAlgorithmTags.DES
			});
			progress += 10;
			data.putInt(Codes.Keys.UI.PROGRESS, progress);
			informaCam.update(data);

			PGPSecretKey secret = new PGPSecretKey(
					PGPSignature.DEFAULT_CERTIFICATION,
					PublicKeyAlgorithmTags.RSA_GENERAL,
					keyPair.getPublic(),
					keyPair.getPrivate(),
					new Date(),
					"InformaCam OpenPGP Key: " + informaCam.user.getString(IUser.ALIAS),
					SymmetricKeyAlgorithmTags.AES_256,
					secretAuthToken.toCharArray(),
					hashedGen.generate(),
					null,
					new SecureRandom(),
					"BC");

			String pgpKeyFingerprint = new String(Hex.encode(secret.getPublicKey().getFingerprint()));
			informaCam.user.pgpKeyFingerprint = pgpKeyFingerprint;

			ISecretKey secretKeyPackage = new ISecretKey();
			secretKeyPackage.pgpKeyFingerprint = pgpKeyFingerprint;
			secretKeyPackage.secretAuthToken = secretAuthToken;
			secretKeyPackage.secretKey = Base64.encodeToString(secret.getEncoded(), Base64.DEFAULT);

			progress += 10;
			data.putInt(Codes.Keys.UI.PROGRESS, progress);
			informaCam.update(data);

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ArmoredOutputStream aos = new ArmoredOutputStream(baos);
			aos.write(secret.getPublicKey().getEncoded());
			aos.flush();
			aos.close();
			baos.flush();
			
			publicCredentials.put(IUser.PUBLIC_KEY, new ByteArrayInputStream(baos.toByteArray()));			
			baos.close();
			
			JSONObject credentials = new JSONObject();
			credentials.put(IUser.ALIAS, informaCam.user.getString(IUser.ALIAS));
			credentials.put(IUser.EMAIL, informaCam.user.getString(IUser.EMAIL));
			publicCredentials.put(IUser.CREDENTIALS, new ByteArrayInputStream(credentials.toString().getBytes()));

			IOUtility.zipFiles(publicCredentials, IUser.PUBLIC_CREDENTIALS, Type.IOCIPHER);

			progress += 10;
			data.putInt(Codes.Keys.UI.PROGRESS, progress);
			informaCam.update(data);

			if(informaCam.ioService.saveBlob(new byte[0], new info.guardianproject.iocipher.File(IManifest.KEY_STORE))) {
				// make keystore manifest
				IKeyStore keyStoreManifest = new IKeyStore();
				keyStoreManifest.password = keyStorePassword;
				keyStoreManifest.path = IManifest.KEY_STORE;
				keyStoreManifest.lastModified = System.currentTimeMillis();
				informaCam.saveState(keyStoreManifest);
				Log.d(LOG, "KEY STORE INITED");
			}
			progress += 10;
			data.putInt(Codes.Keys.UI.PROGRESS, progress);
			informaCam.update(data);

			if(informaCam.ioService.saveBlob(
					secretKeyPackage.asJson().toString().getBytes(), 
					new info.guardianproject.iocipher.File(IUser.SECRET))
					) {
				informaCam.user.alias = informaCam.user.getString(IUser.ALIAS);
				informaCam.user.email = informaCam.user.getString(IUser.EMAIL);

				informaCam.user.remove(IUser.AUTH_TOKEN);
				informaCam.user.remove(IUser.PATH_TO_BASE_IMAGE);
				informaCam.user.remove(IUser.ALIAS);
				informaCam.user.remove(IUser.EMAIL);
				informaCam.user.hasPrivateKey = true;

				progress += 9;
				data.putInt(Codes.Keys.UI.PROGRESS, progress);
				informaCam.update(data);
			}

			return true;
		} catch (NoSuchAlgorithmException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		} catch (JSONException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		} catch (NoSuchProviderException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		} catch (PGPException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		} catch (IOException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		}

		return false;

	}
	
	@SuppressWarnings("deprecation")
	public static boolean verifySig(byte[] signature, byte[] data, PGPPublicKey publicKey) {
		BouncyCastleProvider bc = new BouncyCastleProvider();
		Security.addProvider(bc);
		
		ByteArrayInputStream bais_sig = new ByteArrayInputStream(signature);
		ByteArrayInputStream bais_data = new ByteArrayInputStream(data);
		
		try {
			InputStream is = PGPUtil.getDecoderStream(bais_sig);
			PGPObjectFactory objFactory = new PGPObjectFactory(is);
			
			PGPCompressedData cData1 = (PGPCompressedData) objFactory.nextObject();
			objFactory = new PGPObjectFactory(cData1.getDataStream());
			
			PGPSignatureList sigList = (PGPSignatureList) objFactory.nextObject();
			PGPSignature sig = sigList.get(0);
			sig.initVerify(publicKey, bc);
			
			int ch;
			while((ch = bais_data.read()) >= 0) {
				sig.update((byte) ch);
			}
			
			if(sig.verify()) {
				return true;
			}
			
		} catch (IOException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		} catch (PGPException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		} catch (SignatureException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		}
		
		
		return false;
	}

	@SuppressWarnings({ "deprecation" })
	public static byte[] applySignature(byte[] data, PGPSecretKey secretKey, PGPPublicKey publicKey, PGPPrivateKey privateKey) {
		BouncyCastleProvider bc = new BouncyCastleProvider();
		Security.addProvider(bc);

		ByteArrayInputStream bais = new ByteArrayInputStream(data);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		
		OutputStream targetOut = new ArmoredOutputStream(baos);
		
		try {
			PGPSignatureGenerator sGen = new PGPSignatureGenerator(secretKey.getPublicKey().getAlgorithm(), PGPUtil.SHA1, bc);
			sGen.initSign(PGPSignature.BINARY_DOCUMENT, privateKey);
			
			PGPCompressedDataGenerator cGen = new PGPCompressedDataGenerator(PGPCompressedDataGenerator.ZLIB);
			BCPGOutputStream bOut = new BCPGOutputStream(cGen.open(targetOut));
			
			int ch;
			while((ch = bais.read()) >= 0) {
				sGen.update((byte) ch);
			}
			sGen.generate().encode(bOut);
			
			cGen.close();
			bOut.close();
			targetOut.close();
			
			Log.d(LOG, "NOW VERIFYING...");
			if(verifySig(baos.toByteArray(), data, secretKey.getPublicKey())) {			
				return baos.toByteArray();
			}
		} catch (NoSuchAlgorithmException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		} catch (PGPException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		} catch (SignatureException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		} catch (IOException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		}
		
		return null;
	}
}
