package org.thoughtcrime.securesms.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.google.android.gms.gcm.GoogleCloudMessaging;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil;
import org.thoughtcrime.securesms.crypto.PreKeyUtil;
import org.thoughtcrime.securesms.crypto.SessionUtil;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.IdentityDatabase;
import org.thoughtcrime.securesms.jobs.GcmRefreshJob;
import org.thoughtcrime.securesms.push.AccountManagerFactory;
import org.thoughtcrime.securesms.util.DirectoryHelper;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.KeyHelper;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.push.exceptions.ExpectationFailedException;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The RegisterationService handles the process of PushService registration and verification.
 * If it receives an intent with a REGISTER_NUMBER_ACTION, it does the following through
 * an executor:
 *
 * 1) Generate secrets.
 * 2) Register the specified number and those secrets with the server.
 * 3) Wait for a challenge SMS.
 * 4) Verify the challenge with the server.
 * 5) Start the GCM registration process.
 * 6) Retrieve the current directory.
 *
 * The RegistrationService broadcasts its state throughout this process, and also makes its
 * state available through service binding.  This enables a View to display progress.
 *
 * @author Moxie Marlinspike
 *
 */

public class RegistrationService extends Service {

  public static final String REGISTER_NUMBER_ACTION = "org.thoughtcrime.securesms.RegistrationService.REGISTER_NUMBER";
  public static final String VOICE_REQUESTED_ACTION = "org.thoughtcrime.securesms.RegistrationService.VOICE_REQUESTED";
  public static final String VOICE_REGISTER_ACTION  = "org.thoughtcrime.securesms.RegistrationService.VOICE_REGISTER";

  public static final String NOTIFICATION_TITLE     = "org.thoughtcrime.securesms.NOTIFICATION_TITLE";
  public static final String NOTIFICATION_TEXT      = "org.thoughtcrime.securesms.NOTIFICATION_TEXT";
  public static final String CHALLENGE_EVENT        = "org.thoughtcrime.securesms.CHALLENGE_EVENT";
  public static final String REGISTRATION_EVENT     = "org.thoughtcrime.securesms.REGISTRATION_EVENT";

  public static final String NUMBER_EXTRA        = "e164number";
  public static final String MASTER_SECRET_EXTRA = "master_secret";
  public static final String GCM_SUPPORTED_EXTRA = "gcm_supported";
  public static final String PASSWORD_EXTRA      = "password";
  public static final String SIGNALING_KEY_EXTRA = "signaling_key";
  public static final String CHALLENGE_EXTRA     = "CAAChallenge";

  private static final long REGISTRATION_TIMEOUT_MILLIS = 120000;

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Binder          binder   = new RegistrationServiceBinder();

  private volatile RegistrationState registrationState = new RegistrationState(RegistrationState.STATE_IDLE);

  private volatile WeakReference<Handler>  registrationStateHandler;
  private volatile ChallengeReceiver       challengeReceiver;
  private          String                  challenge;
  private          long                    verificationStartTime;
  private          boolean                 generatingPreKeys;

  @Override
  public int onStartCommand(final Intent intent, int flags, int startId) {
    if (intent != null) {
      executor.execute(new Runnable() {
        @Override
        public void run() {
          if      (REGISTER_NUMBER_ACTION.equals(intent.getAction())) handleSmsRegistrationIntent(intent);
          else if (VOICE_REQUESTED_ACTION.equals(intent.getAction())) handleVoiceRequestedIntent(intent);
          else if (VOICE_REGISTER_ACTION.equals(intent.getAction()))  handleVoiceRegistrationIntent(intent);
        }
      });
    }

    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    executor.shutdown();
    shutdown();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  public void shutdown() {
    shutdownChallengeListener();
    markAsVerifying(false);
    registrationState = new RegistrationState(RegistrationState.STATE_IDLE);
  }

  public synchronized int getSecondsRemaining() {
    long millisPassed;

    if (verificationStartTime == 0) millisPassed = 0;
    else                            millisPassed = System.currentTimeMillis() - verificationStartTime;

    return Math.max((int)(REGISTRATION_TIMEOUT_MILLIS - millisPassed) / 1000, 0);
  }

  public RegistrationState getRegistrationState() {
    return registrationState;
  }

  private void initializeChallengeListener() {
    this.challenge    = null;
    challengeReceiver = new ChallengeReceiver();
    IntentFilter filter = new IntentFilter(CHALLENGE_EVENT);
    registerReceiver(challengeReceiver, filter);
  }

  private synchronized void shutdownChallengeListener() {
    if (challengeReceiver != null) {
      unregisterReceiver(challengeReceiver);
      challengeReceiver = null;
    }
  }

  private void handleVoiceRequestedIntent(Intent intent) {
    setState(new RegistrationState(RegistrationState.STATE_VOICE_REQUESTED,
                                   intent.getStringExtra(NUMBER_EXTRA),
                                   intent.getStringExtra(PASSWORD_EXTRA)));
  }

  private void handleVoiceRegistrationIntent(Intent intent) {
    markAsVerifying(true);

    String  number       = intent.getStringExtra(NUMBER_EXTRA);
    String  password     = intent.getStringExtra(PASSWORD_EXTRA);
    String  signalingKey = intent.getStringExtra(SIGNALING_KEY_EXTRA);
    boolean supportsGcm  = intent.getBooleanExtra(GCM_SUPPORTED_EXTRA, true);

    try {
      SignalServiceAccountManager accountManager = AccountManagerFactory.createManager(this, number, password);

      handleCommonRegistration(accountManager, number, password, signalingKey, supportsGcm);

      markAsVerified(number, password, signalingKey);

      setState(new RegistrationState(RegistrationState.STATE_COMPLETE, number));
      broadcastComplete(true);
    } catch (UnsupportedOperationException uoe) {
      Log.w("RegistrationService", uoe);
      setState(new RegistrationState(RegistrationState.STATE_GCM_UNSUPPORTED, number));
      broadcastComplete(false);
    } catch (IOException e) {
      Log.w("RegistrationService", e);
      setState(new RegistrationState(RegistrationState.STATE_NETWORK_ERROR, number));
      broadcastComplete(false);
    }
  }

  private void handleSmsRegistrationIntent(Intent intent) {
    markAsVerifying(true);

    String  number         = intent.getStringExtra(NUMBER_EXTRA);
    boolean supportsGcm    = intent.getBooleanExtra(GCM_SUPPORTED_EXTRA, true);
    int     registrationId = KeyHelper.generateRegistrationId(false);
    TextSecurePreferences.setLocalRegistrationId(this, registrationId);
    SessionUtil.archiveAllSessions(this);

    String password     = Util.getSecret(18);
    String signalingKey = Util.getSecret(52);

    try {
      initializeChallengeListener();

      setState(new RegistrationState(RegistrationState.STATE_CONNECTING, number));
      SignalServiceAccountManager accountManager = AccountManagerFactory.createManager(this, number, password);
      accountManager.requestSmsVerificationCode();

      setState(new RegistrationState(RegistrationState.STATE_VERIFYING, number));
      String challenge = waitForChallenge();
      accountManager.verifyAccountWithCode(challenge, signalingKey, registrationId, !supportsGcm);

      TextSecurePreferences.setLocalNumber(this, number);
      handleCommonRegistration(accountManager, number, password, signalingKey, supportsGcm);
      markAsVerified(number, password, signalingKey);

      setState(new RegistrationState(RegistrationState.STATE_COMPLETE, number));
      broadcastComplete(true);
    } catch (ExpectationFailedException efe) {
      Log.w("RegistrationService", efe);
      setState(new RegistrationState(RegistrationState.STATE_MULTI_REGISTERED, number));
      broadcastComplete(false);
    } catch (UnsupportedOperationException uoe) {
      Log.w("RegistrationService", uoe);
      setState(new RegistrationState(RegistrationState.STATE_GCM_UNSUPPORTED, number));
      broadcastComplete(false);
    } catch (AccountVerificationTimeoutException avte) {
      Log.w("RegistrationService", avte);
      setState(new RegistrationState(RegistrationState.STATE_TIMEOUT, number, password));
      broadcastComplete(false);
    } catch (IOException e) {
      Log.w("RegistrationService", e);
      setState(new RegistrationState(RegistrationState.STATE_NETWORK_ERROR, number));
      broadcastComplete(false);
    } finally {
      shutdownChallengeListener();
    }
  }

  private void handleCommonRegistration(SignalServiceAccountManager accountManager, String number, String password, String signalingKey, boolean supportsGcm)
      throws IOException
  {
    setState(new RegistrationState(RegistrationState.STATE_GENERATING_KEYS, number));
    Address            self         = Address.fromSerialized(number);
    IdentityKeyPair    identityKey  = IdentityKeyUtil.getIdentityKeyPair(this);
    List<PreKeyRecord> records      = PreKeyUtil.generatePreKeys(this);
    SignedPreKeyRecord signedPreKey = PreKeyUtil.generateSignedPreKey(this, identityKey, true);
    accountManager.setPreKeys(identityKey.getPublicKey(), signedPreKey, records);

    setState(new RegistrationState(RegistrationState.STATE_GCM_REGISTERING, number));

    if (supportsGcm) {
      String gcmRegistrationId = GoogleCloudMessaging.getInstance(this).register(GcmRefreshJob.REGISTRATION_ID);
      accountManager.setGcmId(Optional.of(gcmRegistrationId));

      TextSecurePreferences.setGcmRegistrationId(this, gcmRegistrationId);
      TextSecurePreferences.setGcmDisabled(this, false);
    } else {
      TextSecurePreferences.setGcmDisabled(this, true);
    }

    TextSecurePreferences.setWebsocketRegistered(this, true);

    DatabaseFactory.getIdentityDatabase(this).saveIdentity(self, identityKey.getPublicKey(), IdentityDatabase.VerifiedStatus.VERIFIED, true, System.currentTimeMillis(), true);
    DirectoryHelper.refreshDirectory(this, accountManager);

    DirectoryRefreshListener.schedule(this);
    RotateSignedPreKeyListener.schedule(this);
  }

  private synchronized String waitForChallenge() throws AccountVerificationTimeoutException {
    this.verificationStartTime = System.currentTimeMillis();

    if (this.challenge == null) {
      try {
        wait(REGISTRATION_TIMEOUT_MILLIS);
      } catch (InterruptedException e) {
        throw new IllegalArgumentException(e);
      }
    }

    if (this.challenge == null)
      throw new AccountVerificationTimeoutException();

    return this.challenge;
  }

  private synchronized void challengeReceived(String challenge) {
    this.challenge = challenge;
    notifyAll();
  }

  private void markAsVerifying(boolean verifying) {
    TextSecurePreferences.setVerifying(this, verifying);

    if (verifying) {
      TextSecurePreferences.setPushRegistered(this, false);
    }
  }

  private void markAsVerified(String number, String password, String signalingKey) {
    TextSecurePreferences.setVerifying(this, false);
    TextSecurePreferences.setPushRegistered(this, true);
    TextSecurePreferences.setLocalNumber(this, number);
    TextSecurePreferences.setPushServerPassword(this, password);
    TextSecurePreferences.setSignalingKey(this, signalingKey);
    TextSecurePreferences.setSignedPreKeyRegistered(this, true);
    TextSecurePreferences.setPromptedPushRegistration(this, true);
  }

  private void setState(RegistrationState state) {
    this.registrationState = state;

    Handler registrationStateHandler = this.registrationStateHandler.get();

    if (registrationStateHandler != null) {
      registrationStateHandler.obtainMessage(state.state, state).sendToTarget();
    }
  }

  private void broadcastComplete(boolean success) {
    Intent intent = new Intent();
    intent.setAction(REGISTRATION_EVENT);

    if (success) {
      intent.putExtra(NOTIFICATION_TITLE, getString(R.string.RegistrationService_registration_complete));
      intent.putExtra(NOTIFICATION_TEXT, getString(R.string.RegistrationService_signal_registration_has_successfully_completed));
    } else {
      intent.putExtra(NOTIFICATION_TITLE, getString(R.string.RegistrationService_registration_error));
      intent.putExtra(NOTIFICATION_TEXT, getString(R.string.RegistrationService_signal_registration_has_encountered_a_problem));
    }

    this.sendOrderedBroadcast(intent, null);
  }

  public void setRegistrationStateHandler(Handler registrationStateHandler) {
    this.registrationStateHandler = new WeakReference<>(registrationStateHandler);
  }

  public class RegistrationServiceBinder extends Binder {
    public RegistrationService getService() {
      return RegistrationService.this;
    }
  }

  private class ChallengeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      Log.w("RegistrationService", "Got a challenge broadcast...");
      challengeReceived(intent.getStringExtra(CHALLENGE_EXTRA));
    }
  }

  public static class RegistrationState {

    public static final int STATE_IDLE                 =  0;
    public static final int STATE_CONNECTING           =  1;
    public static final int STATE_VERIFYING            =  2;
    public static final int STATE_TIMER                =  3;
    public static final int STATE_COMPLETE             =  4;
    public static final int STATE_TIMEOUT              =  5;
    public static final int STATE_NETWORK_ERROR        =  6;

    public static final int STATE_GCM_UNSUPPORTED      =  8;
    public static final int STATE_GCM_REGISTERING      =  9;
    public static final int STATE_GCM_TIMEOUT          = 10;

    public static final int STATE_VOICE_REQUESTED      = 12;
    public static final int STATE_GENERATING_KEYS      = 13;

    public static final int STATE_MULTI_REGISTERED     = 14;

    public final int    state;
    public final String number;
    public final String password;

    public RegistrationState(int state) {
      this(state, null);
    }

    public RegistrationState(int state, String number) {
      this(state, number, null);
    }

    public RegistrationState(int state, String number, String password) {
      this.state        = state;
      this.number       = number;
      this.password     = password;
    }
  }
}
