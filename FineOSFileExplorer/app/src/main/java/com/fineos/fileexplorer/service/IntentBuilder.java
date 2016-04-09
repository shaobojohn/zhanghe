/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * This file is part of FileExplorer.
 *
 * FileExplorer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * FileExplorer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SwiFTP.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.fineos.fileexplorer.service;

import android.app.Activity;
import fineos.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.fineos.fileexplorer.R;
import com.fineos.fileexplorer.util.MimeUtils;

import java.io.File;
import java.util.ArrayList;

public class IntentBuilder {
    private static final String TAG = "IntentBuilder";

    public static void viewFile(final Activity activity, final String filePath) {
        String type = getMimeType(filePath);
        if (!TextUtils.isEmpty(type) && !TextUtils.equals(type, "*/*")) {
            /* 设置intent的file与MimeType */
            Intent intent = new Intent();
//            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.setAction(android.content.Intent.ACTION_VIEW);
            Uri uri = Uri.fromFile(new File(filePath));
            intent.setDataAndType(uri, type);
            Log.d(TAG, "viewFile: uri : " + uri + " type : " + type);
            try{
                activity.startActivity(intent);
                activity.overridePendingTransition(0,0);
            }catch(Exception e){
            	e.printStackTrace();
            	Toast.makeText(activity,
            			activity.getResources().getString(R.string.open_file_no_activity_hint),
            			Toast.LENGTH_SHORT).show();       	
            }

        } else {
            // unknown MimeType
            AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(activity);
            dialogBuilder.setTitle(R.string.dialog_select_type);

            CharSequence[] menuItemArray = new CharSequence[] {
                    activity.getString(R.string.dialog_type_text),
                    activity.getString(R.string.dialog_type_audio),
                    activity.getString(R.string.dialog_type_video),
                    activity.getString(R.string.dialog_type_image) };
            dialogBuilder.setItems(menuItemArray,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            String selectType = "*/*";
                            switch (which) {
                            case 0:
                                selectType = "text/plain";
                                break;
                            case 1:
                                selectType = "audio/*";
                                break;
                            case 2:
                                selectType = "video/*";
                                break;
                            case 3:
                                selectType = "image/*";
                                break;
                            }
                            Intent intent = new Intent();
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                            intent.setAction(android.content.Intent.ACTION_VIEW);
                            intent.setDataAndType(Uri.fromFile(new File(filePath)), selectType);
                            activity.startActivity(intent);
                            //Log.d("com.fineos.fileexplorer.service.IntentBuilder", "onClick (line 92): show animation.");
                            activity.overridePendingTransition(0, 0);
                        }
                    });
            dialogBuilder.show();
        }
    }
    
    public static Intent buildSendFile(ArrayList<File> files) {
        ArrayList<Uri> uris = new ArrayList<Uri>();

        String mimeType = "unknown/unknown";
        for (File file : files) {
            if (file.isDirectory())
                continue;
            mimeType = getMimeType(file.getName());
            Log.d(TAG, "file mime type : " + mimeType);
            Uri u = Uri.fromFile(file);
            Log.d(TAG, "buildSendFile: file uri : " + u.toString());
            uris.add(u);
        }


        if (uris.size() == 0)
            return null;

        boolean multiple = uris.size() > 1;
        Intent intent = new Intent(multiple ? android.content.Intent.ACTION_SEND_MULTIPLE
                : android.content.Intent.ACTION_SEND);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);

        if (multiple) {
            intent.setType("*/*");
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        } else {
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        }
        Log.d(TAG, "buildSendFile: intent type : " + intent.getType());
        return intent;
    }

    private static String getMimeType(String filePath) {
        int dotPosition = filePath.lastIndexOf('.');
        if (dotPosition == -1)
            return "unknown/unknown";

        String ext = filePath.substring(dotPosition + 1, filePath.length()).toLowerCase();
        String mimeType = MimeUtils.guessMimeTypeFromExtension(ext);
        if (ext.equals("mtz")) {
            mimeType = "application/miui-mtz";
        }
        if (ext.equals("imy") || ext.equals("ape")) {
            mimeType = "audio/mpeg";
        }
        return mimeType != null ? mimeType : "unknown/unknown";
    }
}
