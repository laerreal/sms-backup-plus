/* Copyright (c) 2009 Christoph Studer <chstuder@gmail.com>
 * Copyright (c) 2010 Jan Berkel <jan.berkel@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zegoggles.smssync.service;

import android.app.Notification;
import android.content.Intent;
import android.net.NetworkInfo;
import android.text.format.DateFormat;
import android.util.Log;
import com.fsck.k9.mail.MessagingException;
import com.squareup.otto.Produce;
import com.squareup.otto.Subscribe;
import com.zegoggles.smssync.App;
import com.zegoggles.smssync.Consts;
import com.zegoggles.smssync.R;
import com.zegoggles.smssync.mail.DataType;
import com.zegoggles.smssync.service.exception.BackupDisabledException;
import com.zegoggles.smssync.service.exception.ConnectivityException;
import com.zegoggles.smssync.service.exception.NoConnectionException;
import com.zegoggles.smssync.service.exception.RequiresBackgroundDataException;
import com.zegoggles.smssync.service.exception.RequiresLoginException;
import com.zegoggles.smssync.service.exception.RequiresWifiException;
import com.zegoggles.smssync.service.state.BackupState;
import com.zegoggles.smssync.service.state.SmsSyncState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Date;

import static com.zegoggles.smssync.App.LOCAL_LOGV;
import static com.zegoggles.smssync.App.TAG;
import static com.zegoggles.smssync.service.BackupType.MANUAL;
import static com.zegoggles.smssync.service.state.SmsSyncState.ERROR;
import static com.zegoggles.smssync.service.state.SmsSyncState.FINISHED_BACKUP;
import static com.zegoggles.smssync.service.state.SmsSyncState.INITIAL;

public class SmsBackupService extends ServiceBase {
    private static final int BACKUP_ID = 1;

    @Nullable private static SmsBackupService service;
    @NotNull private BackupState mState = new BackupState();

    @Override @NotNull
    public BackupState getState() {
        return mState;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        service = this;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (LOCAL_LOGV) Log.v(TAG, "SmsBackupService#onDestroy(state=" + getState() + ")");
        service = null;
    }

    @Override
    protected void handleIntent(final Intent intent) {
        if (intent == null) return; // NB: should not happen with START_NOT_STICKY
        final BackupType backupType = BackupType.fromIntent(intent);
        if (LOCAL_LOGV) Log.v(TAG, "handleIntent(" + intent +
                ", " + (intent.getExtras() == null ? "null" : intent.getExtras().keySet()) +
                ", type="+backupType+")");

        appLog(R.string.app_log_backup_requested, getString(backupType.resId));

        // Only start a backup if there's no other operation going on at this time.
        if (!isWorking() && !SmsRestoreService.isServiceWorking()) {
            backup(backupType, intent.getBooleanExtra(Consts.KEY_SKIP_MESSAGES, false));
        } else {
            appLog(R.string.app_log_skip_backup_already_running);
        }
    }

    private void backup(BackupType backupType, boolean skip) {
        try {
            // set initial state
            mState = new BackupState(INITIAL, 0, 0, backupType, null, null);
            checkBackupEnabled();
            if (!skip) {
                checkCredentials();
                checkBackgroundDataSettings(backupType);
                checkConnectivity();
            }

            appLog(R.string.app_log_start_backup, backupType);
            getBackupTask().execute(getBackupConfig(backupType, skip));
        } catch (MessagingException e) {
            Log.w(TAG, e);
            moveToState(mState.transition(ERROR, e));
        } catch (RequiresBackgroundDataException e) {
            moveToState(mState.transition(ERROR, e));
        } catch (ConnectivityException e) {
            moveToState(mState.transition(ERROR, e));
        } catch (RequiresLoginException e) {
            appLog(R.string.app_log_missing_credentials);
            moveToState(mState.transition(ERROR, e));
        } catch (BackupDisabledException e) {
            moveToState(mState.transition(FINISHED_BACKUP, e));
        }
    }

    private BackupConfig getBackupConfig(BackupType backupType, boolean skip) throws MessagingException {
        return new BackupConfig(
            getBackupImapStore(),
            0,
            skip,
            getPreferences().getMaxItemsPerSync(),
            getPreferences().getBackupContactGroup(),
            backupType,
            DataType.enabled(this),
            getPreferences().isAppLogDebug()
        );
    }

    private void checkBackupEnabled() throws BackupDisabledException {
        if (DataType.enabled(this).isEmpty()) {
            throw new BackupDisabledException();
        }
    }

    private void checkCredentials() throws RequiresLoginException {
        if (!getAuthPreferences().isLoginInformationSet()) {
            throw new RequiresLoginException();
        }
    }

    private void checkBackgroundDataSettings(BackupType backupType) throws RequiresBackgroundDataException {
        if (backupType.isBackground() && !getConnectivityManager().getBackgroundDataSetting()) {
            throw new RequiresBackgroundDataException();
        }
    }

    private void checkConnectivity() throws ConnectivityException {
        NetworkInfo active = getConnectivityManager().getActiveNetworkInfo();
        if (active == null || !active.isConnectedOrConnecting()) {
            throw new NoConnectionException();
        }
        if (getPreferences().isWifiOnly() && isBackgroundTask() && !isConnectedViaWifi()) {
            throw new RequiresWifiException();
        }
    }

    protected BackupTask getBackupTask() {
        return new BackupTask(this);
    }

    private void moveToState(BackupState state) {
        backupStateChanged(state);
        App.bus.post(state);
    }

    @Override
    protected boolean isBackgroundTask() {
        return mState.backupType.isBackground();
    }

    @Produce public BackupState produceLastState() {
        return mState;
    }

    @Subscribe public void backupStateChanged(BackupState state) {
        if (mState == state) return;

        mState = state;
        if (mState.isInitialState()) return;

        if (state.isError()) {
            handleErrorState(state);
        }

        if (state.isRunning()) {
            if (state.backupType == MANUAL) {
                notifyAboutBackup(state);
            }
        } else {
            appLog(state.isCanceled() ? R.string.app_log_backup_canceled : R.string.app_log_backup_finished);

            if (state.backupType == BackupType.REGULAR) {
                Log.d(TAG, "scheduling next backup");
                scheduleNextBackup();
            }
            stopForeground(true);
            stopSelf();
        }
    }

    private void handleErrorState(BackupState state) {
        if (state.isAuthException()) {
            appLog(R.string.app_log_backup_failed_authentication, state.getDetailedErrorMessage(getResources()));

            if (shouldNotifyUser(state)) {
                notifyUser(android.R.drawable.stat_sys_warning,
                    getString(R.string.notification_auth_failure),
                    getString(getAuthPreferences().useXOAuth() ? R.string.status_auth_failure_details_xoauth : R.string.status_auth_failure_details_plain));
            }
        } else if (state.isConnectivityError()) {
            appLog(R.string.app_log_backup_failed_connectivity, state.getDetailedErrorMessage(getResources()));
        } else {
            appLog(R.string.app_log_backup_failed_general_error, state.getDetailedErrorMessage(getResources()));

            if (shouldNotifyUser(state)) {
                notifyUser(android.R.drawable.stat_sys_warning,
                    getString(R.string.notification_general_error),
                    state.getErrorMessage(getResources()));
            }
        }
    }

    private boolean shouldNotifyUser(BackupState state) {
        return state.backupType == MANUAL ||
               (getPreferences().isNotificationEnabled() && !state.isConnectivityError());
    }

    private void notifyAboutBackup(BackupState state) {
        if (notification == null) {
            notification = createNotification(R.string.status_backup);
        }
        notification.setLatestEventInfo(this,
            getString(R.string.status_backup),
            state.getNotificationLabel(getResources()),
            getPendingIntent());

        startForeground(BACKUP_ID, notification);
    }

    private void scheduleNextBackup() {
        final long nextSync = getAlarms().scheduleRegularBackup();
        if (nextSync >= 0) {
            appLog(R.string.app_log_scheduled_next_sync,
                    DateFormat.format("kk:mm", new Date(nextSync)));
        } else {
            appLog(R.string.app_log_no_next_sync);
        }
    }

    protected void notifyUser(int icon, String title, String text) {
        Notification n = new Notification(icon,
                getString(R.string.app_name),
                System.currentTimeMillis());
        n.flags = Notification.FLAG_ONLY_ALERT_ONCE | Notification.FLAG_AUTO_CANCEL;
        n.setLatestEventInfo(this,
                title,
                text,
                getPendingIntent());

        getNotifier().notify(0, n);
    }

    protected Alarms getAlarms() {
        return new Alarms(this);
    }

    public static boolean isServiceWorking() {
        return service != null && service.isWorking();
    }

    public BackupState transition(SmsSyncState newState, Exception e) {
        return mState.transition(newState, e);
    }
}
