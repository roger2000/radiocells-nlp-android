/*
	Radiobeacon - Openbmap Unified Network Location Provider
    Copyright (C) 2013  wish7

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.openbmap.unifiedNlp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.widget.Toast;

import org.openbmap.unifiedNlp.utils.DirectoryChooserDialog;
import org.openbmap.unifiedNlp.utils.FileHelpers;
import org.openbmap.unifiedNlp.utils.MediaScanner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.microg.nlp.api.Constants.LOCATION_EXTRA_BACKEND_COMPONENT;

/**
 * Preferences activity.
 */
public class SettingsActivity extends PreferenceActivity implements ICatalogChooser, LocationListener {

    private static final String TAG = SettingsActivity.class.getSimpleName();
    private static final int MY_PERMISSIONS_REQUEST_COARSE_LOCATION = 121212;

    private DownloadManager mDownloadManager;
    private BroadcastReceiver mReceiver = null;

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_COARSE_LOCATION: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Location permission granted");
                } else {
                    // permission not granted (or revoked) - ask user again?
                    requestPermission();
                }
            }
        }
    }

    private void requestPermission() {
        AlertDialog.Builder bld = new AlertDialog.Builder(this);
        bld.setMessage(this.getString(R.string.app_name) + " won't work without location permission.\nGrant permission now?");
        bld.setCancelable(true);
        bld.setPositiveButton(
                "Yes",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                        ActivityCompat.requestPermissions(SettingsActivity.this,
                                new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                                MY_PERMISSIONS_REQUEST_COARSE_LOCATION);
                    }
                });
        bld.setNegativeButton(
                "No",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                        // permission denied, boo! Disable the
                        // functionality that depends on this permission.
                    }
                });
        AlertDialog alertDlg = bld.create();
        alertDlg.show();
    }

    @Override
    protected final void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Coarse Location permission is missing");
            requestPermission();
        }

        registerDownloadManager();

        initFolderChooser();

        refreshCatalogList(PreferenceManager.getDefaultSharedPreferences(this).getString(Preferences.KEY_DATA_FOLDER, this.getExternalFilesDir(null).getAbsolutePath()));

        initOperationModeChooser();
        initSourceChooser();

        Log.d(TAG, "Selected wifi catalog: " + getSelectedCatalog());
        if (getSelectedCatalog().equals(Preferences.CATALOG_NONE)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(getString(R.string.question_download_catalog));
            builder.setTitle(getString(R.string.offline_catalog_n_a));
            // Add the buttons
            builder.setPositiveButton(getString(R.string.yes), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    showCatalogDownloadDialog();
                }
            });
            builder.setNegativeButton(getString(R.string.no), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    Preference pref = findPreference(Preferences.KEY_OPERATION_MODE);
                    pref.getEditor().putString(Preferences.KEY_OPERATION_MODE, Preferences.OPERATION_MODE_ONLINE).apply();
                    Toast.makeText(SettingsActivity.this, getString(R.string.using_online_mode), Toast.LENGTH_LONG).show();
                }
            });

            AlertDialog dialog = builder.create();
            dialog.show();
        }

        Preference button = findPreference("test");
        button.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                if (ActivityCompat.checkSelfPermission(SettingsActivity.this,
                        Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                {
                    Log.i(TAG, "Coarse location permission missing - abort");
                    requestPermission();
                    return true;
                }

                final LocationManager locationManager = (LocationManager) getSystemService(
                        Context.LOCATION_SERVICE);

                boolean networkEnabled = locationManager.getProviders(true).contains(
                        LocationManager.NETWORK_PROVIDER);
                Log.i(TAG, "Network provider enabled: " + networkEnabled);
                if (!networkEnabled) {
                    Toast.makeText(SettingsActivity.this,
                            SettingsActivity.this.getString(R.string.warning_no_network_location),
                            Toast.LENGTH_LONG).show();
                }
                Log.i(TAG, "Requesting network location");

                // TODO use check as in https://github.com/microg/android_packages_apps_UnifiedNlp/blob/bf8682e00fa829d3c6041a3646afce9a264696da/unifiednlp-base/src/main/java/org/microg/tools/selfcheck/NlpStatusChecks.java
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER,
                        SettingsActivity.this, null);
                Runnable myRunnable = new Runnable() {
                    public void run() {
                        Log.i(TAG, "Timeout reached");
                        locationManager.removeUpdates(SettingsActivity.this);
                        //Toast.makeText(TAG, "Timeout - no response received", Toast.LENGTH_LONG).show();
                    }
                };

                Handler handler = new Handler();
                handler.postDelayed(myRunnable, 10000);
                return true;
            }
        });

        Preference pref = findPreference(Preferences.KEY_VERSION_INFO);
        String version = "n/a";
        try {
            version = getPackageManager().getPackageInfo(this.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        pref.setSummary(version + " (" + readBuildInfo() + ")");
    }

    @Override
    public void onResume()  {
        super.onResume();
        registerDownloadManager();
    }


    @Override
    protected final void onDestroy() {
        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
        }
        super.onDestroy();
    }

    private void showCatalogDownloadDialog() {
        DialogPreferenceCatalogs catalogs = (DialogPreferenceCatalogs) findPreference(Preferences.KEY_CATALOGS_DIALOG);
        catalogs.showDialog(null);
    }

    private String getSelectedCatalog() {
        return PreferenceManager.getDefaultSharedPreferences(this).getString(Preferences.KEY_OFFLINE_CATALOG_FILE, Preferences.CATALOG_NONE);
    }

    private String getSelectedOperationMode() {
        return PreferenceManager.getDefaultSharedPreferences(this).getString(Preferences.KEY_OPERATION_MODE, Preferences.OPERATION_MODE_OFFLINE);
    }

    /**
     * Initializes data directory preference.
     */
    private void initFolderChooser() {
        Preference button = findPreference("data.dir");
        button.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            private String mChosenDir = PreferenceManager.getDefaultSharedPreferences(SettingsActivity.this).getString(Preferences.KEY_DATA_FOLDER,
                    SettingsActivity.this.getExternalFilesDir(null).getAbsolutePath());

            @Override
            public boolean onPreferenceClick(Preference arg0) {
                DirectoryChooserDialog directoryChooserDialog =
                        new DirectoryChooserDialog(SettingsActivity.this, new DirectoryChooserDialog.ChosenDirectoryListener() {
                            @Override
                            public void onChosenDir(String folder) {
                                mChosenDir = folder;
                                SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(SettingsActivity.this);
                                settings.edit().putString("data.dir", folder).apply();

                                // Rescan folder, in case user has copy file manually
                                MediaScanner m = new MediaScanner(SettingsActivity.this, new File(mChosenDir));
                                refreshCatalogList(folder);
                                Toast.makeText(SettingsActivity.this, folder, Toast.LENGTH_LONG).show();
                            }
                        });
                directoryChooserDialog.setNewFolderEnabled(false);
                directoryChooserDialog.chooseDirectory(mChosenDir);
                return true;
            }
        });
    }

    /**
     * Populates the wifi catalog list preference by scanning catalog folder.
     * @param catalogFolder Root folder for WIFI_CATALOG_SUBDIR
     */
    private void refreshCatalogList(final String catalogFolder) {
        String[] entries;
        String[] values;

        // rescan
        new MediaScanner(this, new File(catalogFolder));

        // Check for presence of database directory
        File folder = new File(catalogFolder);
        if (folder.exists() && folder.canRead()) {
            // List each map file
            String[] dbFiles = folder.list(new FilenameFilter() {
                @Override
                public boolean accept(final File dir, final String filename) {
                    return filename.endsWith(Preferences.CATALOG_FILE_EXTENSION);
                }
            });

            // Create array of values for each map file + one for not selected
            entries = new String[dbFiles.length + 1];
            values = new String[dbFiles.length + 1];

            // Create default / none entry
            entries[0] = getResources().getString(R.string.prefs_none);
            values[0] = Preferences.CATALOG_NONE;

            for (int i = 0; i < dbFiles.length; i++) {
                entries[i + 1] = dbFiles[i].substring(0, dbFiles[i].length() - Preferences.CATALOG_FILE_EXTENSION.length());
                values[i + 1] = dbFiles[i];
            }
        } else {
            // No wifi catalog found, populate values with just the default entry.
            entries = new String[]{getResources().getString(R.string.prefs_none)};
            values = new String[]{Preferences.CATALOG_NONE};
        }

        ListPreference lf = (ListPreference) findPreference(Preferences.KEY_OFFLINE_CATALOG_FILE);
        lf.setEntries(entries);
        lf.setEntryValues(values);
    }

    /**
     * Lets user select operation mode (online/offline)
     */
    private void initOperationModeChooser() {
        String[] entries = getResources().getStringArray(R.array.operation_mode_entries);
        String[] values = getResources().getStringArray(R.array.operation_mode_values);

        ListPreference lf = (ListPreference) findPreference(Preferences.KEY_OPERATION_MODE);
        lf.setEntries(entries);
        lf.setEntryValues(values);

        lf.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {

                String listValue = (String) newValue;
                Log.d(TAG, "New operation mode :" + listValue);
                if (listValue.equals(Preferences.OPERATION_MODE_OFFLINE) && getSelectedCatalog().equals(Preferences.CATALOG_NONE)) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(SettingsActivity.this);
                    builder.setMessage(getString(R.string.question_download_catalog));
                    builder.setTitle(getString(R.string.offline_catalog_n_a));
                    // Add the buttons
                    builder.setPositiveButton(getString(R.string.yes), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            showCatalogDownloadDialog();
                            //Toast.makeText(SettingsActivity.this, getString(R.string.download_started), Toast.LENGTH_LONG).show();
                        }
                    });
                    builder.setNegativeButton(getString(R.string.no), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            Toast.makeText(SettingsActivity.this, getString(R.string.warning_catalog_missing), Toast.LENGTH_LONG).show();
                        }
                    });

                    AlertDialog dialog = builder.create();
                    dialog.show();
                }
                return true;
            }
        });
    }

    /**
     * Lets user select source for geolocation (wifis, cells, combined)
     */
    private void initSourceChooser() {
        String[] entries = getResources().getStringArray(R.array.source_entries);
        String[] values = getResources().getStringArray(R.array.source_values);

        ListPreference lf = (ListPreference) findPreference(Preferences.KEY_SOURCE);
        lf.setEntries(entries);
        lf.setEntryValues(values);
    }

    /**
     * Initialises download manager for GINGERBREAD and newer
     */
    private void registerDownloadManager() {
        mDownloadManager = (DownloadManager) this.getSystemService(Context.DOWNLOAD_SERVICE);

        if(mReceiver == null) {
            mReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(final Context context, final Intent intent) {
                    final String action = intent.getAction();
                    if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
                        final long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0);
                        final DownloadManager.Query query = new DownloadManager.Query();
                        query.setFilterById(downloadId);
                        final Cursor c = mDownloadManager.query(query);
                        if (c.moveToFirst()) {
                            final int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                            if (DownloadManager.STATUS_SUCCESSFUL == c.getInt(columnIndex)) {
                                final String uriString = c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                                Log.i(TAG, "Download completed: " + uriString);
                                onDownloadCompleted(uriString);
                            } else {
                                final int reason = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON));
                                Toast.makeText(SettingsActivity.this,
                                        String.format(getString(R.string.download_failed),
                                                String.valueOf(reason)), Toast.LENGTH_LONG).show();
                                Log.e(TAG, "Download failed: " + reason);
                            }
                        }
                    }
                }
            };

            registerReceiver(mReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }

    /**
     * Selects downloaded file either as wifi catalog / active Catalog (based on file extension).
     * @param file
     */
    public final void onDownloadCompleted(String file) {
        // get current file extension
        final String[] filenameArray = file.split("\\.");
        final String extension = "." + filenameArray[filenameArray.length - 1];

        // TODO verify on newer Android versions (>4.2)
        // replace prefix file:// in filename string
        file = file.replace("file://", "");

        if (extension.equals(Preferences.CATALOG_FILE_EXTENSION)) {
            final File targetFolder = new File(PreferenceManager.getDefaultSharedPreferences(this).getString(Preferences.KEY_DATA_FOLDER, this.getExternalFilesDir(null).getAbsolutePath()));
            new MediaScanner(this, new File(targetFolder.getAbsolutePath()));

            // if file has been downloaded to cache folder, move to target folder
            if (file.contains(this.getExternalCacheDir().getPath())) {
                try {
                    Log.i(TAG, "Moving file to" + targetFolder.getAbsolutePath());
                    String target = targetFolder.getAbsolutePath()+ File.separator + file;
                    FileHelpers.moveFile(file, target);
                } catch (IOException e) {
                    Log.e(TAG, "Error moving file to " + targetFolder.getAbsolutePath());
                }
            }
            refreshCatalogList(targetFolder.getAbsolutePath());
            activateCatalog(file);
        }
    }

    /**
     * Changes catalog preference item to given filename.
     * Helper method to activate wifi catalog following successful download
     * @param absoluteFile absolute filename (including path)
     */
    public void activateCatalog(final String absoluteFile) {

        if (!new File(absoluteFile).exists()) {
            Toast.makeText(this, String.format("File %s doesn't exists!", absoluteFile),
                    Toast.LENGTH_LONG).show();
            return;
        }

        ListPreference lf = (ListPreference) findPreference(Preferences.KEY_OFFLINE_CATALOG_FILE);
        // get filename
        String[] filenameArray = absoluteFile.split("/");
        String file = filenameArray[filenameArray.length - 1];

        CharSequence[] values = lf.getEntryValues();
        for (int i = 0; i < values.length; i++) {
            if (file.equals(values[i].toString())) {
                lf.setValueIndex(i);
            }
        }

        if (getSelectedOperationMode().equals(Preferences.OPERATION_MODE_ONLINE)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(SettingsActivity.this);
            builder.setMessage(getString(R.string.question_activate_offline_mode));
            builder.setTitle(getString(R.string.download_completed));
            // Add the buttons
            builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    ListPreference pref = (ListPreference) findPreference(Preferences.KEY_OPERATION_MODE);
                    pref.setValue(Preferences.OPERATION_MODE_OFFLINE);
                    Toast.makeText(SettingsActivity.this, getString(R.string.using_offline_mode), Toast.LENGTH_LONG).show();
                }
            });
            builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    //Toast.makeText(SettingsActivity.this, "Keep on using online  mode", Toast.LENGTH_LONG).show();
                }
            });

            AlertDialog dialog = builder.create();
            dialog.show();
        }
    }

    /**
     * Read build information from ressources
     *
     * @return build version
     */
    private String readBuildInfo() {
        final InputStream buildInStream = getResources().openRawResource(R.raw.build);
        final ByteArrayOutputStream buildOutStream = new ByteArrayOutputStream();

        int i;

        try {
            i = buildInStream.read();
            while (i != -1) {
                buildOutStream.write(i);
                i = buildInStream.read();
            }

            buildInStream.close();
        } catch (final IOException e) {
            e.printStackTrace();
        }

        return buildOutStream.toString();
        // use buildOutStream.toString() to get the data
    }

    /**
     * Downloads selected catalog
     * @param url absolute url
     */
    @Override
    public void catalogSelected(String url) {
        if (url == null) {
            Toast.makeText(this, R.string.invalid_download, Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, getString(R.string.downloading) + " " + url, Toast.LENGTH_LONG).show();

        final File folder = new File(PreferenceManager.getDefaultSharedPreferences(this).getString(Preferences.KEY_DATA_FOLDER,
                getExternalFilesDir(null).getAbsolutePath()));

        Log.d(TAG, "Download destination" + folder.getAbsolutePath());

        boolean folderAccessible = false;
        if (folder.exists() && folder.canWrite()) {
            folderAccessible = true;
        } else {
            Log.e(TAG, "Folder not accessible: " + folder);
        }

        if (!folder.exists()) {
            Log.i(TAG, "Creating new folder " + folder);
            folderAccessible = folder.mkdirs();
        }

        if (folderAccessible) {
            final String filename = url.substring(url.lastIndexOf('/') + 1);

            final File target = new File(folder.getAbsolutePath() + File.separator + filename);
            if (target.exists()) {
                Log.i(TAG, "Catalog file " + filename + " already exists. Overwriting..");
                target.delete();
            }

            Log.i(TAG, String.format("Saving %s @ %s", url, folder.getAbsolutePath() + File.separator + filename));

            final DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            long mCurrentCatalogDownloadId;
            try {
                // try to download to target. If target isn't below Environment.getExternalStorageDirectory(),
                // e.g. on second SD card a security exception is thrown
                final DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setDestinationUri(Uri.fromFile(target));
                mCurrentCatalogDownloadId = dm.enqueue(request);
            } catch (final SecurityException sec) {
                // download to temp dir and try to move to target later
                Log.w(TAG, "Security exception, can't write to " + target + ", using " + getExternalCacheDir());
                final File tempFile = new File(getExternalCacheDir() + File.separator + filename);
                final DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setDestinationUri(Uri.fromFile(tempFile));
                mCurrentCatalogDownloadId = dm.enqueue(request);
            }
        } else {
            Toast.makeText(this, R.string.error_save_file_failed, Toast.LENGTH_SHORT).show();
        }
    }


    @Override
    public void onLocationChanged(Location location) {
        Toast.makeText(this, location.toString(), Toast.LENGTH_LONG).show();
        //Toast.makeText(this,location.getExtras().toString(), Toast.LENGTH_SHORT).show();
        //boolean hasKnown = location != null && location.getExtras() != null &&
        //        location.getExtras().containsKey("SERVICE_BACKEND_COMPONENT");
    }

    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {

    }

    @Override
    public void onProviderEnabled(String s) {

    }

    @Override
    public void onProviderDisabled(String s) {

    }
}
