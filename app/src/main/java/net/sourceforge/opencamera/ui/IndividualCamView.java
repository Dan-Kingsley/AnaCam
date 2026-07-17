package net.sourceforge.opencamera.ui;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.preference.PreferenceManager;
import androidx.core.content.ContextCompat;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.sourceforge.opencamera.MyDebug;
import net.sourceforge.opencamera.PreferenceKeys;
import net.sourceforge.opencamera.R;
import net.sourceforge.opencamera.cameracontroller.CameraControllerManager2;
import net.sourceforge.opencamera.cameracontroller.CameraControllerManager2.LensInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Manages the row of individual camera/lens icons shown above the shutter button.
 *  Each lens gets a small circular button with a label (multiplier or mm or custom name).
 */
public class IndividualCamView {
    private static final String TAG = "IndividualCamView";

    private final android.app.Activity activity;
    private final LinearLayout containerRow;

    public IndividualCamView(android.app.Activity activity) {
        this.activity = activity;
        this.containerRow = activity.findViewById(R.id.individual_cam_row);
    }

    /** Rebuild the lens buttons for the current camera. */
    public void updateLenses(CameraControllerManager2 cameraManager, int currentLogicalCameraId, Set<String> physicalCameraIds) {
        if( MyDebug.LOG )
            Log.d(TAG, "updateLenses: currentLogicalCameraId=" + currentLogicalCameraId);

        containerRow.removeAllViews();

        List<LensInfo> allLenses = cameraManager.getAvailableLenses(activity, currentLogicalCameraId, physicalCameraIds);
        List<LensInfo> filteredLenses = filterByFavorites(allLenses);

        if( filteredLenses.isEmpty() ) {
            // no lenses to show
            return;
        }

        float mainEquivFocal = cameraManager.getMainCameraEquivFocalLength(filteredLenses);
        String displayFormat = getDisplayFormat();
        JSONObject customNames = getCustomNames();

        float scale = activity.getResources().getDisplayMetrics().density;
        int buttonSize = (int)(40 * scale + 0.5f);
        int marginPx = (int)(3 * scale + 0.5f);

        for (LensInfo lens : filteredLenses) {
            TextView btn = new TextView(activity);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(buttonSize, buttonSize);
            params.setMargins(marginPx, 0, marginPx, 0);
            btn.setLayoutParams(params);
            btn.setGravity(Gravity.CENTER);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            btn.setTypeface(null, Typeface.BOLD);
            btn.setTextColor(Color.WHITE);

            String label = getLensLabel(lens, displayFormat, customNames, mainEquivFocal);
            btn.setText(label);

            // Background: circular with icons_background_tint
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(ContextCompat.getColor(activity, R.color.icons_background));
            bg.setStroke((int)(1 * scale + 0.5f), Color.parseColor("#666666"));
            btn.setBackground(bg);

            btn.setPadding(0, 0, 0, 0);

            // Tag stores camera IDs for click handler
            btn.setTag(lens);

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    LensInfo clickedLens = (LensInfo) v.getTag();
                    if( MyDebug.LOG )
                        Log.d(TAG, "lens clicked: " + clickedLens.cameraKey);
                    if( activity instanceof net.sourceforge.opencamera.MainActivity ) {
                        ((net.sourceforge.opencamera.MainActivity) activity).onIndividualLensClicked(
                            clickedLens.logicalCameraId, clickedLens.physicalCameraId);
                    }
                }
            });

            containerRow.addView(btn);
        }
    }

    /** Highlight the active lens button. */
    public void setActiveLens(String activeCameraKey) {
        float scale = activity.getResources().getDisplayMetrics().density;
        int activeColor = Color.parseColor("#2F9CF2"); // blue accent, same as shutter_icon
        int normalStroke = (int)(1 * scale + 0.5f);
        int activeStroke = (int)(2 * scale + 0.5f);

        for (int i = 0; i < containerRow.getChildCount(); i++) {
            View child = containerRow.getChildAt(i);
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                LensInfo lens = (LensInfo) tv.getTag();
                if (lens != null) {
                    GradientDrawable bg = new GradientDrawable();
                    bg.setShape(GradientDrawable.OVAL);
                    if (lens.cameraKey.equals(activeCameraKey)) {
                        bg.setColor(Color.parseColor("#442F9CF2"));
                        bg.setStroke(activeStroke, activeColor);
                        tv.setTypeface(null, Typeface.BOLD);
                    } else {
                        bg.setColor(ContextCompat.getColor(activity, R.color.icons_background));
                        bg.setStroke(normalStroke, Color.parseColor("#666666"));
                        tv.setTypeface(null, Typeface.NORMAL);
                    }
                    tv.setBackground(bg);
                }
            }
        }
    }

    /** Compute the display label for a lens based on the current format and custom names. */
    private String getLensLabel(LensInfo lens, String displayFormat, JSONObject customNames, float mainEquivFocal) {
        // Check for custom name first
        try {
            if (customNames != null && customNames.has(lens.cameraKey)) {
                String custom = customNames.getString(lens.cameraKey);
                if (custom != null && !custom.isEmpty()) {
                    return custom;
                }
            }
        } catch (JSONException e) {
            // ignore
        }

        // Compute label from auto-detected data
        if (lens.equivFocalLengthMm > 0 && mainEquivFocal > 0) {
            if ("mm".equals(displayFormat)) {
                return String.valueOf(Math.round(lens.equivFocalLengthMm)) + "mm";
            } else {
                // multiplier format
                float multiplier = lens.equivFocalLengthMm / mainEquivFocal;
                if (Math.abs(multiplier - 1.0f) < 0.05f) {
                    return "1x";
                } else {
                    return String.format("%.1fx", multiplier);
                }
            }
        }

        // Fallback: use the default label or a simplified version
        return simplifyLabel(lens.defaultLabel);
    }

    /** Simplify a camera label for the small icon (e.g., "0: Back, Ultra-wide" -> "UW"). */
    private String simplifyLabel(String label) {
        if (label == null) return "?";
        String lower = label.toLowerCase();
        if (lower.contains("ultra") || lower.contains("wide")) return "UW";
        if (lower.contains("tele")) return "T";
        if (lower.contains("front")) return "F";
        if (lower.contains("auto lens")) return "A";
        // Extract just the lens number if it's "Lens N: ..."
        if (label.startsWith("Lens ")) {
            int colonIdx = label.indexOf(':');
            if (colonIdx > 0) {
                return label.substring(5, colonIdx).trim();
            }
        }
        // For "0: Back, ..." format, just show the ID
        int colonIdx = label.indexOf(':');
        if (colonIdx > 0) {
            return label.substring(0, colonIdx).trim();
        }
        return label.length() > 4 ? label.substring(0, 4) : label;
    }

    /** Filter lenses by favorites. If no favorites are set, returns all lenses. */
    private List<LensInfo> filterByFavorites(List<LensInfo> allLenses) {
        Set<String> favorites = getFavorites();
        if (favorites.isEmpty()) {
            return allLenses; // no favorites configured, show all
        }
        List<LensInfo> filtered = new ArrayList<>();
        for (LensInfo lens : allLenses) {
            if (favorites.contains(lens.cameraKey)) {
                filtered.add(lens);
            }
        }
        return filtered;
    }

    /** Read the display format preference ("multiplier" or "mm"). */
    private String getDisplayFormat() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        return prefs.getString(PreferenceKeys.IndividualCamDisplayFormatKey, "multiplier");
    }

    /** Read the favorites set from preferences (stored by MultiSelectListPreference as StringSet). */
    private Set<String> getFavorites() {
        Set<String> result = new HashSet<>();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        Set<String> savedSet = prefs.getStringSet(PreferenceKeys.IndividualCamFavoritesKey, null);
        if( savedSet != null ) {
            result.addAll(savedSet);
        }
        return result;
    }

    /** Read custom names from preferences (stored as JSON object). */
    private JSONObject getCustomNames() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        String json = prefs.getString(PreferenceKeys.IndividualCamCustomNamesKey, null);
        if (json != null && !json.isEmpty()) {
            try {
                return new JSONObject(json);
            } catch (JSONException e) {
                if (MyDebug.LOG)
                    Log.e(TAG, "Failed to parse custom names JSON", e);
            }
        }
        return null;
    }
}
