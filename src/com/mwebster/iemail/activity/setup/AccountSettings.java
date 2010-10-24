/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mwebster.iemail.activity.setup;

import com.mwebster.iemail.Email;
import com.mwebster.iemail.R;
import com.mwebster.iemail.activity.Welcome;
import com.mwebster.iemail.mail.MessagingException;
import com.mwebster.iemail.mail.Sender;
import com.mwebster.iemail.mail.Store;
import com.mwebster.iemail.provider.EmailContent.Account;
import com.mwebster.iemail.provider.EmailContent.AccountColumns;
import com.mwebster.iemail.provider.EmailContent.HostAuth;

import com.mwebster.iemail.Controller;
import com.mwebster.iemail.Preferences;
import com.mwebster.iemail.activity.AccountFolderList;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.RingtonePreference;
import android.provider.Calendar;
import android.provider.ContactsContract;
import android.util.Log;
import android.view.KeyEvent;

public class AccountSettings extends PreferenceActivity {
    private static final String PREFERENCE_TOP_CATEGORY = "account_settings";
    private static final String PREFERENCE_DESCRIPTION = "account_description";
    private static final String PREFERENCE_NAME = "account_name";
    private static final String PREFERENCE_COLOR = "color";
    private static final String PREFERENCE_SIGNATURE = "account_signature";
    private static final String PREFERENCE_FREQUENCY = "account_check_frequency";
    private static final String PREFERENCE_DEFAULT = "account_default";
    private static final String PREFERENCE_NOTIFY = "account_notify";
    private static final String PREFERENCE_VIBRATE_WHEN = "account_settings_vibrate_when";
    private static final String PREFERENCE_RINGTONE = "account_ringtone";
    private static final String PREFERENCE_SERVER_CATERGORY = "account_servers";
    private static final String PREFERENCE_INCOMING = "incoming";
    private static final String PREFERENCE_OUTGOING = "outgoing";
    private static final String PREFERENCE_SYNC_CONTACTS = "account_sync_contacts";
    private static final String PREFERENCE_SYNC_CALENDAR = "account_sync_calendar";
    private static final String PREFERENCE_MSG_LIST_ON_DELETE = "msg_list_on_delete";
    private static final String PREFERENCE_SHOW_ALL_FOLDERS_UNREAD = "show_all_folders_unread";
    private static final String PREFERENCE_DEFAULT_FOLDER_LIST = "default_folder_list";
    private static final String PREFERENCE_SIGNATURE_REPLIES_FORWARDS =
            "signature_replies_forwards";
    private static final String PREFERENCE_SHOW_ALL_MAILBOXES_COMBINED =
            "show_all_mailboxes_combined";
    private static final String PREFERENCE_SHOW_ONLY_UNREAD_COMBINED = "show_only_unread_combined";

    // These strings must match account_settings_vibrate_when_* strings in strings.xml
    private static final String PREFERENCE_VALUE_VIBRATE_WHEN_ALWAYS = "always";
    private static final String PREFERENCE_VALUE_VIBRATE_WHEN_SILENT = "silent";
    private static final String PREFERENCE_VALUE_VIBRATE_WHEN_NEVER = "never";

    // NOTE: This string must match the one in res/xml/account_preferences.xml
    public static final String ACTION_ACCOUNT_MANAGER_ENTRY =
        "com.mwebster.iemail.activity.setup.ACCOUNT_MANAGER_ENTRY";
    // NOTE: This constant should eventually be defined in android.accounts.Constants, but for
    // now we define it here
    private static final String ACCOUNT_MANAGER_EXTRA_ACCOUNT = "account";
    private static final String EXTRA_ACCOUNT_ID = "account_id";

    private long mAccountId = -1;
    private Account mAccount;
    private boolean mAccountDirty;

    private EditTextPreference mAccountDescription;
    private EditTextPreference mAccountName;
    private EditTextPreference mAccountSignature;
    private ListPreference mCheckFrequency;
    private ListPreference mSyncWindow;
    private CheckBoxPreference mAccountDefault;
    private CheckBoxPreference mAccountNotify;
    private ListPreference mAccountVibrateWhen;
    private RingtonePreference mAccountRingtone;
    private CheckBoxPreference mSyncContacts;
    private CheckBoxPreference mSyncCalendar;
    private CheckBoxPreference mMsgListOnDelete;
    private CheckBoxPreference mUnreadCountAll;
    private CheckBoxPreference mDefaultFolderList;
    private ColorPreference mColor;
    private CheckBoxPreference mSignatureToggle;
    private CheckBoxPreference mShowAllMailboxesCombined;
    private CheckBoxPreference mShowOnlyUnreadCombined;

    SharedPreferences mSharedPrefs;
    /**
     * Display (and edit) settings for a specific account
     */
    public static void actionSettings(Activity fromActivity, long accountId) {
        Intent i = new Intent(fromActivity, AccountSettings.class);
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        fromActivity.startActivity(i);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent i = getIntent();
        mSharedPrefs = getPreferenceManager().getSharedPreferences();
        if (ACTION_ACCOUNT_MANAGER_ENTRY.equals(i.getAction())) {
            // This case occurs if we're changing account settings from Settings -> Accounts
            setAccountIdFromAccountManagerIntent();
        } else {
            // Otherwise, we're called from within the Email app and look for our extra
            mAccountId = i.getLongExtra(EXTRA_ACCOUNT_ID, -1);
        }

        // If there's no accountId, we're done
        if (mAccountId == -1) {
            finish();
            return;
        }

        mAccount = Account.restoreAccountWithId(this, mAccountId);
        // Similarly, if the account has been deleted
        if (mAccount == null) {
            finish();
            return;
        }
        mAccount.mHostAuthRecv = HostAuth.restoreHostAuthWithId(this, mAccount.mHostAuthKeyRecv);
        mAccount.mHostAuthSend = HostAuth.restoreHostAuthWithId(this, mAccount.mHostAuthKeySend);
        // Or if HostAuth's have been deleted
        if (mAccount.mHostAuthRecv == null || mAccount.mHostAuthSend == null) {
            finish();
            return;
        }
        mAccountDirty = false;

        addPreferencesFromResource(R.xml.account_settings_preferences);

        PreferenceCategory topCategory = (PreferenceCategory) findPreference(PREFERENCE_TOP_CATEGORY);
        topCategory.setTitle(getString(R.string.account_settings_title_fmt));

        mAccountDescription = (EditTextPreference) findPreference(PREFERENCE_DESCRIPTION);
        mAccountDescription.setSummary(mAccount.getDisplayName());
        mAccountDescription.setText(mAccount.getDisplayName());
        mAccountDescription.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                final String summary = newValue.toString();
                mAccountDescription.setSummary(summary);
                mAccountDescription.setText(summary);
                return false;
            }
        });

        mAccountName = (EditTextPreference) findPreference(PREFERENCE_NAME);
        mAccountName.setSummary(mAccount.getSenderName());
        mAccountName.setText(mAccount.getSenderName());
        mAccountName.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                final String summary = newValue.toString();
                mAccountName.setSummary(summary);
                mAccountName.setText(summary);
                return false;
            }
        });
        
        mColor = (ColorPreference)findPreference(PREFERENCE_COLOR);
        int color = mAccount.getAccountColor();
        mColor.setSummary("0x" + Integer.toHexString(color).toUpperCase());
        mColor.setChipColor(color);
        mColor.setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                    	onColorSettings();
                        return true;
                    }
                });

        mAccountSignature = (EditTextPreference) findPreference(PREFERENCE_SIGNATURE);
        mAccountSignature.setSummary(mAccount.getSignature());
        mAccountSignature.setText(mAccount.getSignature());
        mAccountSignature.setOnPreferenceChangeListener(
                new Preference.OnPreferenceChangeListener() {
                    public boolean onPreferenceChange(Preference preference, Object newValue) {
                        String summary = newValue.toString();
                        if (summary == null || summary.length() == 0) {
                            mAccountSignature.setSummary(R.string.account_settings_signature_hint);
                        } else {
                            mAccountSignature.setSummary(summary);
                        }
                        mAccountSignature.setText(summary);
                        return false;
                    }
                });

        mCheckFrequency = (ListPreference) findPreference(PREFERENCE_FREQUENCY);

        // Before setting value, we may need to adjust the lists
        Store.StoreInfo info = Store.StoreInfo.getStoreInfo(mAccount.getStoreUri(this), this);
        if (info.mPushSupported) {
            mCheckFrequency.setEntries(R.array.account_settings_check_frequency_entries_push);
            mCheckFrequency.setEntryValues(R.array.account_settings_check_frequency_values_push);
        }

        mCheckFrequency.setValue(String.valueOf(mAccount.getSyncInterval()));
        mCheckFrequency.setSummary(mCheckFrequency.getEntry());
        mCheckFrequency.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                final String summary = newValue.toString();
                int index = mCheckFrequency.findIndexOfValue(summary);
                mCheckFrequency.setSummary(mCheckFrequency.getEntries()[index]);
                mCheckFrequency.setValue(summary);
                return false;
            }
        });

        // Add check window preference
        mSyncWindow = null;
        if (info.mVisibleLimitDefault == -1) {
            mSyncWindow = new ListPreference(this);
            mSyncWindow.setTitle(R.string.account_setup_options_mail_window_label);
            mSyncWindow.setEntries(R.array.account_settings_mail_window_entries);
            mSyncWindow.setEntryValues(R.array.account_settings_mail_window_values);
            mSyncWindow.setValue(String.valueOf(mAccount.getSyncLookback()));
            mSyncWindow.setSummary(mSyncWindow.getEntry());
            mSyncWindow.setOrder(4);
            mSyncWindow.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    final String summary = newValue.toString();
                    int index = mSyncWindow.findIndexOfValue(summary);
                    mSyncWindow.setSummary(mSyncWindow.getEntries()[index]);
                    mSyncWindow.setValue(summary);
                    return false;
                }
            });
            topCategory.addPreference(mSyncWindow);
        }

        mSignatureToggle = (CheckBoxPreference)
                findPreference(PREFERENCE_SIGNATURE_REPLIES_FORWARDS);
        mSignatureToggle.setChecked(0 != (mAccount.getFlags() &
                Account.FLAGS_SIGNATURE_TOGGLE));

        mUnreadCountAll = (CheckBoxPreference)
                findPreference(PREFERENCE_SHOW_ALL_FOLDERS_UNREAD);
        mUnreadCountAll.setChecked(Preferences.getPreferences(this)
                .getShowUnreadCountAll());
        mMsgListOnDelete = (CheckBoxPreference)
                findPreference(PREFERENCE_MSG_LIST_ON_DELETE);
        mMsgListOnDelete.setChecked(0 != (mAccount.getFlags() &
                Account.FLAGS_MSG_LIST_ON_DELETE));
        mDefaultFolderList = (CheckBoxPreference)
                findPreference(PREFERENCE_DEFAULT_FOLDER_LIST);
        mDefaultFolderList.setChecked(0 != (mAccount.getFlags() &
                Account.FLAGS_DEFAULT_FOLDER_LIST));
        mShowAllMailboxesCombined = (CheckBoxPreference)
                findPreference(PREFERENCE_SHOW_ALL_MAILBOXES_COMBINED);
        mShowAllMailboxesCombined.setChecked(Preferences.getPreferences(this)
                .getShowAllMailboxesCombined());
        mShowOnlyUnreadCombined = (CheckBoxPreference)
                findPreference(PREFERENCE_SHOW_ONLY_UNREAD_COMBINED);
        mShowOnlyUnreadCombined.setChecked(Preferences.getPreferences(this)
                .getShowOnlyUnreadCombined());
        mAccountDefault = (CheckBoxPreference) findPreference(PREFERENCE_DEFAULT);
        mAccountDefault.setChecked(mAccount.mId == Account.getDefaultAccountId(this));
        mAccountNotify = (CheckBoxPreference) findPreference(PREFERENCE_NOTIFY);
        mAccountNotify.setChecked(0 != (mAccount.getFlags() & Account.FLAGS_NOTIFY_NEW_MAIL));
        mAccountRingtone = (RingtonePreference) findPreference(PREFERENCE_RINGTONE);

        // XXX: The following two lines act as a workaround for the RingtonePreference
        //      which does not let us set/get the value programmatically
        SharedPreferences prefs = mAccountRingtone.getPreferenceManager().getSharedPreferences();
        prefs.edit().putString(PREFERENCE_RINGTONE, mAccount.getRingtone()).commit();

        mAccountVibrateWhen = (ListPreference) findPreference(PREFERENCE_VIBRATE_WHEN);
        boolean flagsVibrate = 0 != (mAccount.getFlags() & Account.FLAGS_VIBRATE_ALWAYS);
        boolean flagsVibrateSilent = 0 != (mAccount.getFlags() & Account.FLAGS_VIBRATE_WHEN_SILENT);
        mAccountVibrateWhen.setValue(
                flagsVibrate ? PREFERENCE_VALUE_VIBRATE_WHEN_ALWAYS :
                flagsVibrateSilent ? PREFERENCE_VALUE_VIBRATE_WHEN_SILENT :
                    PREFERENCE_VALUE_VIBRATE_WHEN_NEVER);

        findPreference(PREFERENCE_INCOMING).setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                        onIncomingSettings();
                        return true;
                    }
                });

        // Hide the outgoing account setup link if it's not activated
        Preference prefOutgoing = findPreference(PREFERENCE_OUTGOING);
        boolean showOutgoing = true;
        try {
            Sender sender = Sender.getInstance(getApplication(), mAccount.getSenderUri(this));
            if (sender != null) {
                Class<? extends android.app.Activity> setting = sender.getSettingActivityClass();
                showOutgoing = (setting != null);
            }
        } catch (MessagingException me) {
            // just leave showOutgoing as true - bias towards showing it, so user can fix it
        }
        if (showOutgoing) {
            prefOutgoing.setOnPreferenceClickListener(
                    new Preference.OnPreferenceClickListener() {
                        public boolean onPreferenceClick(Preference preference) {
                            onOutgoingSettings();
                            return true;
                        }
                    });
        } else {
            PreferenceCategory serverCategory = (PreferenceCategory) findPreference(
                    PREFERENCE_SERVER_CATERGORY);
            serverCategory.removePreference(prefOutgoing);
        }

        mSyncContacts = (CheckBoxPreference) findPreference(PREFERENCE_SYNC_CONTACTS);
        mSyncCalendar = (CheckBoxPreference) findPreference(PREFERENCE_SYNC_CALENDAR);
        if (mAccount.mHostAuthRecv.mProtocol.equals("eas")) {
            android.accounts.Account acct = new android.accounts.Account(mAccount.mEmailAddress,
                    Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
            mSyncContacts.setChecked(ContentResolver
                    .getSyncAutomatically(acct, ContactsContract.AUTHORITY));
            mSyncCalendar.setChecked(ContentResolver
                    .getSyncAutomatically(acct, Calendar.AUTHORITY));
        } else {
            PreferenceCategory serverCategory = (PreferenceCategory) findPreference(
                    PREFERENCE_SERVER_CATERGORY);
            serverCategory.removePreference(mSyncContacts);
            serverCategory.removePreference(mSyncCalendar);
        }
    }

    private void setAccountIdFromAccountManagerIntent() {
        // First, get the AccountManager account that we've been ask to handle
        android.accounts.Account acct =
            (android.accounts.Account)getIntent()
            .getParcelableExtra(ACCOUNT_MANAGER_EXTRA_ACCOUNT);
        // Find a HostAuth using eas and whose login is klthe name of the AccountManager account
        Cursor c = getContentResolver().query(Account.CONTENT_URI,
                new String[] {AccountColumns.ID}, AccountColumns.EMAIL_ADDRESS + "=?",
                new String[] {acct.name}, null);
        try {
            if (c.moveToFirst()) {
                mAccountId = c.getLong(0);
            }
        } finally {
            c.close();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Exit immediately if the accounts list has changed (e.g. externally deleted)
        if (Email.getNotifyUiAccountsChanged()) {
            Welcome.actionStart(this);
            finish();
            return;
        }

        if (mAccountDirty) {
            // if we are coming back from editing incoming or outgoing settings,
            // we need to refresh them here so we don't accidentally overwrite the
            // old values we're still holding here
            mAccount.mHostAuthRecv =
                HostAuth.restoreHostAuthWithId(this, mAccount.mHostAuthKeyRecv);
            mAccount.mHostAuthSend =
                HostAuth.restoreHostAuthWithId(this, mAccount.mHostAuthKeySend);
            // Because "delete policy" UI is on edit incoming settings, we have
            // to refresh that as well.
            Account refreshedAccount = Account.restoreAccountWithId(this, mAccount.mId);
            if (refreshedAccount == null || mAccount.mHostAuthRecv == null
                    || mAccount.mHostAuthSend == null) {
                finish();
                return;
            }
            mAccount.setDeletePolicy(refreshedAccount.getDeletePolicy());
            
            int newcol = refreshedAccount.getAccountColor();
            mColor.setChipColor(newcol);
            mColor.setSummary("0x" + Integer.toHexString(newcol).toUpperCase());
            
            mAccountDirty = false;
        }
    }

    private void saveSettings() {
        int newFlags = mAccount.getFlags() &
                ~(Account.FLAGS_NOTIFY_NEW_MAIL | Account.FLAGS_VIBRATE_ALWAYS |
                Account.FLAGS_VIBRATE_WHEN_SILENT | Account.FLAGS_MSG_LIST_ON_DELETE |
                Account.FLAGS_DEFAULT_FOLDER_LIST | Account.FLAGS_SIGNATURE_TOGGLE);
        mAccount.setDefaultAccount(mAccountDefault.isChecked());
        mAccount.setDisplayName(mAccountDescription.getText());
        mAccount.setSenderName(mAccountName.getText());
        mAccount.setSignature(mAccountSignature.getText());
        newFlags |= (mAccountNotify.isChecked() ? Account.FLAGS_NOTIFY_NEW_MAIL : 0) |
                    (mMsgListOnDelete.isChecked() ? Account.FLAGS_MSG_LIST_ON_DELETE : 0) |
                    (mDefaultFolderList.isChecked() ? Account.FLAGS_DEFAULT_FOLDER_LIST : 0) |
                    (mSignatureToggle.isChecked() ? Account.FLAGS_SIGNATURE_TOGGLE : 0);
        mAccount.setSyncInterval(Integer.parseInt(mCheckFrequency.getValue()));
        if (mSyncWindow != null) {
            mAccount.setSyncLookback(Integer.parseInt(mSyncWindow.getValue()));
        }
        if (mAccountVibrateWhen.getValue().equals(PREFERENCE_VALUE_VIBRATE_WHEN_ALWAYS)) {
            newFlags |= Account.FLAGS_VIBRATE_ALWAYS;
        } else if (mAccountVibrateWhen.getValue().equals(PREFERENCE_VALUE_VIBRATE_WHEN_SILENT)) {
            newFlags |= Account.FLAGS_VIBRATE_WHEN_SILENT;
        }
        SharedPreferences prefs = mAccountRingtone.getPreferenceManager().getSharedPreferences();
        mAccount.setRingtone(prefs.getString(PREFERENCE_RINGTONE, null));
        mAccount.setFlags(newFlags);

        if (mAccount.mHostAuthRecv.mProtocol.equals("eas")) {
            android.accounts.Account acct = new android.accounts.Account(mAccount.mEmailAddress,
                    Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
            ContentResolver.setSyncAutomatically(acct, ContactsContract.AUTHORITY,
                    mSyncContacts.isChecked());
            ContentResolver.setSyncAutomatically(acct, Calendar.AUTHORITY,
                    mSyncCalendar.isChecked());

        }
        AccountSettingsUtils.commitSettings(this, mAccount);
        try {
            Controller.getInstance(getApplication()).updateMailboxList(
                    mAccountId, null);
        } catch (Exception e) { }
        Email.setServicesEnabled(this);
        Preferences.getPreferences(this)
                .setShowUnreadCountAll(mUnreadCountAll.isChecked());
        Preferences.getPreferences(this)
                .setShowAllMailboxesCombined(mShowAllMailboxesCombined.isChecked());
        Preferences.getPreferences(this)
                .setShowOnlyUnreadCombined(mShowOnlyUnreadCombined.isChecked());
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            saveSettings();
        }
        return super.onKeyDown(keyCode, event);
    }

    private void onColorSettings () {
        try {
            java.lang.reflect.Method m = AccountSetupColor.class.getMethod("actionEditColorSettings",
                    android.app.Activity.class, Account.class);
            m.invoke(null, this, mAccount);
            mAccountDirty = true;
        } catch (Exception e) {
            Log.d(Email.LOG_TAG, "Error while trying to invoke store settings.", e);
        }
    }
    
    private void onIncomingSettings() {
        try {
            Store store = Store.getInstance(mAccount.getStoreUri(this), getApplication(), null);
            if (store != null) {
                Class<? extends android.app.Activity> setting = store.getSettingActivityClass();
                if (setting != null) {
                    java.lang.reflect.Method m = setting.getMethod("actionEditIncomingSettings",
                            android.app.Activity.class, Account.class);
                    m.invoke(null, this, mAccount);
                    mAccountDirty = true;
                }
            }
        } catch (Exception e) {
            Log.d(Email.LOG_TAG, "Error while trying to invoke store settings.", e);
        }
    }

    private void onOutgoingSettings() {
        try {
            Sender sender = Sender.getInstance(getApplication(), mAccount.getSenderUri(this));
            if (sender != null) {
                Class<? extends android.app.Activity> setting = sender.getSettingActivityClass();
                if (setting != null) {
                    java.lang.reflect.Method m = setting.getMethod("actionEditOutgoingSettings",
                            android.app.Activity.class, Account.class);
                    m.invoke(null, this, mAccount);
                    mAccountDirty = true;
                }
            }
        } catch (Exception e) {
            Log.d(Email.LOG_TAG, "Error while trying to invoke sender settings.", e);
        }
    }
}
