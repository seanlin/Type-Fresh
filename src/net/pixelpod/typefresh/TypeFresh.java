/*
 * Copyright (C) 2010 Pixelpod INTERNATIONAL, Inc.
 * 
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package net.pixelpod.typefresh;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.Arrays;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;

/**
 * Insert comments.
 * 
 * @author Timothy Caraballo
 * @version 0.8
 */
public class TypeFresh extends ListActivity {
    // Tag for Log use
    public static final String TAG = "Type Fresh";
    // activity requestCode
    public static final int PICK_REQUEST_CODE = 0;
    // menu
    public static final int MENU_APPLY   = 0;
    public static final int MENU_BACKUP  = 1;
    public static final int MENU_RESTORE = 2;
    public static final int MENU_RESET   = 3;
    public static final int MENU_ABOUT   = 4;
    // Dialogs
    public static final int DIALOG_FIRSTRUN         =  1;
    public static final int DIALOG_ABOUT            =  2;
    public static final int DIALOG_NEED_AND         =  3;
    public static final int DIALOG_NEED_REBOOT      =  4;
    public static final int DIALOG_REBOOT           =  5;
    public static final int DIALOG_REBOOT_FAILED    =  6;
    public static final int DIALOG_NEED_ROOT        =  7;
    public static final int DIALOG_MKDIR_FAILED     =  8;
    public static final int DIALOG_NO_MARKET        =  9;
    public static final int DIALOG_REMOUNT_FAILED   = 10;
    public static final int DIALOG_PROGRESS         = 11;
    public static final int PDIALOG_DISMISS         = 12;
    // for reboot()
    public static final int READ_ONLY  = 0;
    public static final int READ_WRITE = 1;
    
    private String[] fonts;
    private String[] sysFontPaths;
    private int mListPosition;
    private final Runtime mRuntime = Runtime.getRuntime();
    public ProgressDialog mPDialog = null;
    private FontListAdapter mAdapter = null;
    private static AsyncTask<Object, Object, Void> mFileCopier = null;
    // TODO: use extStorage everywhere
    public static String extStorage = Environment.getExternalStorageDirectory().getPath();
    
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        File fontsDir = new File("/system/fonts");
        fonts = fontsDir.list();
        // remove all file references for the sake of remounting
        fontsDir = null;

        Arrays.sort(fonts);
        sysFontPaths = new String[fonts.length];
        
        File sdFonts = new File("/sdcard/Fonts");
        if (!sdFonts.exists()) {
            try {
                sdFonts.mkdir();
            } catch (Exception e) {
                Log.e(TAG,e.toString());
                showDialog(DIALOG_MKDIR_FAILED);
            }
        }

        for (int i = 0; i < fonts.length; i++) {
            // check if any existing fonts are not backed up
            sysFontPaths[i] = "/system/fonts/" + fonts[i];
        }
        
        setListAdapter(new FontListAdapter(this, fonts));
        registerForContextMenu(getListView());
        mAdapter = (FontListAdapter) this.getListAdapter();
        
        // restore paths on rotate
        if ((savedInstanceState != null) && savedInstanceState.containsKey("paths")) {
            mAdapter.setFontPaths(savedInstanceState.getStringArray("paths"));
        }

        if ((mFileCopier != null) && (mFileCopier.getStatus() != AsyncTask.Status.FINISHED)) {
            // we have a running thread, so tell it about our new activity
            ((FileCopier) mFileCopier).setActivity(this);
            return;
        }

        // do we need to show the welcome screen?
        SharedPreferences settings = getPreferences(Activity.MODE_PRIVATE);
        if (settings.getBoolean("firstrun", true)) {

            // Not firstrun anymore, so store that
            SharedPreferences.Editor editor = settings.edit();
            editor.putBoolean("firstrun", false);
            editor.commit();

            showDialog(DIALOG_FIRSTRUN);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle bundle) {
        // store the selected fonts
        bundle.putStringArray("paths", mAdapter.fontPaths);
    }

    @Override
    public void onListItemClick(ListView parent, View v, int position, long id) {
        mListPosition = position; 
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_PICK);
        Uri startDir = Uri.fromFile(new File("/sdcard/Fonts"));
        intent.setDataAndType(startDir, "vnd.android.cursor.dir/lysesoft.andexplorer.file");
        intent.putExtra("explorer_title", "Select a font");
        intent.putExtra("browser_title_background_color", "440000AA");
        intent.putExtra("browser_title_foreground_color", "FFFFFFFF");
        intent.putExtra("browser_list_background_color", "00000066");
        intent.putExtra("browser_list_fontscale", "120%");
        intent.putExtra("browser_list_layout", "0");
        intent.putExtra("browser_filter_extension_whitelist", "*.ttf");
        
        try {
            startActivityForResult(intent, PICK_REQUEST_CODE);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, e.toString());
            showDialog(DIALOG_NEED_AND);
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == PICK_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                Uri uri = intent.getData();
                if (uri != null) {
                    String path = uri.toString();
                    if (path.toLowerCase().startsWith("file://")) {
                        path = (new File(URI.create(path))).getAbsolutePath();
                        mAdapter.setFontPath(mListPosition, path);
                    }
                }
            }
        }
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_APPLY,   0, "Apply fonts");
        menu.add(0, MENU_BACKUP,  0, "Backup fonts");
        menu.add(0, MENU_RESTORE, 0, "Restore fonts").setIcon(android.R.drawable.ic_menu_revert);
        menu.add(0, MENU_RESET,   0, "Reset paths").setIcon(R.drawable.ic_menu_clear_playlist);
        menu.add(0, MENU_ABOUT,   0, "About").setIcon(android.R.drawable.ic_menu_help);
        return true;
    }
    
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // if the user hasn't selected any fonts, the next two menu items are useless
        boolean pathsSet = !Arrays.equals(sysFontPaths, mAdapter.getPaths());
        menu.findItem(MENU_APPLY).setEnabled(pathsSet);
        menu.findItem(MENU_RESET).setEnabled(pathsSet);

        // Check for a backup to see if we should enable the restore option
        boolean backupExists = true;
        for (int i = 0; i < mAdapter.getFonts().length; i++) {
            // check if any existing fonts are not backed up
            if (!(new File("/sdcard/Fonts/" + fonts[i]).exists())) {
                backupExists = false;
                break;
            }
        }
        menu.findItem(MENU_RESTORE).setEnabled(backupExists);
        return true;
    }
    
    /* Handles Menu item selections */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case MENU_APPLY:
            applySelections();
            return true;
        case MENU_BACKUP:
            backupFonts();
            return true;
        case MENU_RESTORE:
            restoreFonts();
            return true;
        case MENU_RESET:
            resetSelections();
            return true;
        case MENU_ABOUT:
               showDialog(DIALOG_ABOUT);
            return true;
        }
        return false;
    }
    
    /**
     * Copies all system fonts to /sdcard/Fonts
     */
    protected void backupFonts() {
        String[] dPaths = new String[fonts.length];
        for(int i = 0; i < fonts.length; i++) {
            dPaths[i] = "/sdcard/Fonts/" + fonts[i];
        }

        copyFiles("Backing up Fonts", "Fonts backed up to /sdcard/Fonts", sysFontPaths, dPaths);
    }
    
    /**
     * Restores backed up fonts from /sdcard/Fonts/
     */
    protected void restoreFonts() {
        String[] sPaths = new String[fonts.length];
        for(int i = 0; i < sPaths.length; i++) {
            sPaths[i] = "/sdcard/Fonts/" + fonts[i];
        }

        copyFiles("Restoring Fonts", "Fonts restored from SD card", sPaths, sysFontPaths);
        resetSelections();
    }
    
    /**
     * Resets all selected fonts to empty.
     */
    protected void resetSelections() {
        mAdapter.setFontPaths(sysFontPaths);        
    }    

    /**
     * Initiate copying of selected fonts to the system.
     */
    protected void applySelections() {
        String[] sPaths = mAdapter.getPaths();
        copyFiles("Applying Fonts", "Your fonts have been applied", sPaths, sysFontPaths);
        
    }    

    /**
     * Copies files from each element in src to the corresponding dst.
     * 
     * @param dialogTitle    <code>String</code> for the displayed <code>ProgressDialog</code>.
     * @param completedToast <code>String</code> to show in <code>Toast</code> when process is
     *                           done.
     * @param src            <code>String[]</code> of src paths.
     * @param dst            <code>String</code> of destination paths, same length as src.
     */
    protected void copyFiles(String dialogTitle, String completedToast,
                               String[] src, String[] dst) {
        if (src.length != dst.length) {
            Log.e(TAG,"copyFonts: src and destination lenght mismatch. Quitting copy.");
            return;
        }
        
        // No need to make a new FileCopier each time.
        if (mFileCopier == null) {
            mFileCopier = new FileCopier(this);
        }
        mFileCopier.execute(src, dst, completedToast);
    }

    

    @Override
    protected Dialog onCreateDialog(int id) {
        AlertDialog dialog;

        switch (id) {
        case DIALOG_FIRSTRUN:
            dialog = makeSimpleAlertDialog(android.R.drawable.ic_dialog_info,
                    R.string.firstrun_title, R.string.firstrun_message);
            break;
        case DIALOG_ABOUT:
            dialog = makeSimpleAlertDialog(android.R.drawable.ic_dialog_info,
                    R.string.about_title,R.string.about_message);
            break;
        case DIALOG_NEED_AND:
            dialog = (new AlertDialog.Builder(this))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(R.string.need_and_title)
                .setMessage(R.string.need_and_message)
                .setPositiveButton(R.string.need_and_ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();

                        try {
                            Intent marketIntent = new Intent(android.content.Intent.ACTION_VIEW,
                                    Uri.parse("market://search?q=pname:lysesoft.andexplorer"));
                            startActivity(marketIntent);
                        } catch (ActivityNotFoundException e) {
                            showDialog(DIALOG_NO_MARKET);
                        }
                    }
                })
                .setNegativeButton(R.string.need_and_cancel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                    }
                }
            ).create();
            break;
        case DIALOG_NEED_ROOT:
            dialog = makeSimpleAlertDialog(android.R.drawable.ic_dialog_alert,
                    R.string.need_root_message, R.string.need_root_title);
            break;
        case DIALOG_NO_MARKET:
            dialog = makeSimpleAlertDialog(android.R.drawable.ic_dialog_alert,
                    R.string.no_market, R.string.market_alert_message);
            break;
        case DIALOG_REBOOT_FAILED:
            dialog = makeSimpleAlertDialog(android.R.drawable.ic_dialog_alert,
                    R.string.reboot_failed_title, R.string.reboot_failed_message);
            break;
        case DIALOG_MKDIR_FAILED:
            dialog = makeSimpleAlertDialog(android.R.drawable.ic_dialog_alert,
                    R.string.mkdir_failed_title, R.string.mkdir_failed_message);
            break;
        case DIALOG_REMOUNT_FAILED:
            dialog = makeSimpleAlertDialog(android.R.drawable.ic_dialog_alert,
                    R.string.remount_failed_title, R.string.remount_failed_message);
            break;
        case DIALOG_NEED_REBOOT:
            dialog = (new AlertDialog.Builder(this))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setMessage(R.string.reboot_message)
                .setTitle(R.string.reboot_title)
                .setPositiveButton(R.string.reboot_ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                        try {
                            reboot();
                        } catch (IOException e) {
                            Log.e(TAG, e.toString());
                            showDialog(TypeFresh.DIALOG_REBOOT_FAILED);
                        }
                    }
                })
                .setNegativeButton(R.string.reboot_cancel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                    }
                }
            ).create();
            break;
        case DIALOG_REBOOT:
            mPDialog = new ProgressDialog(this);
            mPDialog.setTitle("Rebooting");
            mPDialog.setMessage("Please Wait...");
            mPDialog.setCancelable(false);
            mPDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            dialog = mPDialog;
            break;
        case DIALOG_PROGRESS:
            mPDialog = new ProgressDialog(this);
            mPDialog.setTitle("Copying fonts");
            mPDialog.setCancelable(false);
            mPDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            dialog = mPDialog;
            break;
        default:
            dialog = null;
        }
        
        return dialog;
    }

    /**
     * Returns an AlertDialog with one dismiss button
     * 
     * @param icon <code>Drawable</code> resource id.
     * @param title <code>String</code> resource id.
     * @param message <code>String</code> resource id.
     * @return A fully built <code>AlertDialog</code>.
     */
    private AlertDialog makeSimpleAlertDialog(int icon, int title, int message) {
        return (new AlertDialog.Builder(this))
                .setIcon(icon)
                .setTitle(title)
                .setMessage(message)
                .setNeutralButton(R.string.dismiss, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                    }
                }
                ).create();
    }

    // TODO: Figure out why reboot is random
    // reboot works, but can happen any time from 10 seconds to 5 minutes after being called
    /**
     * Reboots the system.
     * 
     * @throws IOException If our su process has a problem.
     */
    protected void reboot() throws IOException {
        showDialog(DIALOG_REBOOT);

        if (mFileCopier != null) {
            // there should be no way the FileCopier thread is still running, so just kill it
            mFileCopier = null;
        }
        
        try {
            Log.i(TAG,"Calling reboot");
            Process su = mRuntime.exec("/system/bin/su");
            su.getOutputStream().write("reboot".getBytes());
        } catch (IOException e) {
            // get rid of our dialog first and then throw the exception back
            dismissDialog(DIALOG_PROGRESS);
            throw e;
        }
        
        if (mPDialog.isShowing()) {
            dismissDialog(DIALOG_PROGRESS);
        }
    }

    // TODO: Error remounting: "mount: mounting /dev/block/mtdblock3 on /system failed:
    // Device or resource busy"
    /**
     * Remounts /system read/write.
     * 
     * @param readwrite one of <code>TypeFresh.READ_WRITE</code> or
     *         <code>TypeFresh.READ_ONLY</code>.
     * 
     * @throws InterruptedException If our su process has a problem.
     * @throws IOException If our su process has a problem.
     * @return <code>boolean</code> of whether it succeeded.
     */
    public static boolean remount(int readwrite) throws IOException,InterruptedException {
        String type;

        if (readwrite == READ_WRITE) {
            type = "rw";
        } else {
            type = "ro";
        }

        Process su = Runtime.getRuntime().exec("/system/bin/su");
        Log.i(TAG,"Remounting /system " + type);
        String cmd = "mount -o " + type + ",remount /system\nexit\n";
        su.getOutputStream().write(cmd.getBytes());
        
        if (su.waitFor() != 0) {
            BufferedReader br
                    = new BufferedReader(new InputStreamReader(su.getErrorStream()), 200);
            String line;
            while((line = br.readLine()) != null) {
                Log.e(TAG,"Error remounting: \"" + line + "\"");
            }
            Log.e(TAG, "Could not remount, returning");
            return false;
        } else {
            Log.i(TAG,"Remounted /system " + type);
        }
        return true;
    }
}
