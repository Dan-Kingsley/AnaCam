package net.sourceforge.opencamera;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.EditText;

import net.sourceforge.opencamera.cameracontroller.CameraControllerManager2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PreferenceSubGUI extends PreferenceSubScreen {
    private static final String TAG = "PreferenceSubGUI";

    private List<CameraControllerManager2.LensInfo> cachedLenses;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences_sub_gui);

        final Bundle bundle = getArguments();

        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.getActivity());

        final boolean camera_open = bundle.getBoolean("camera_open");
        if( MyDebug.LOG )
            Log.d(TAG, "camera_open: " + camera_open);

        final boolean supports_face_detection = bundle.getBoolean("supports_face_detection");
        if( MyDebug.LOG )
            Log.d(TAG, "supports_face_detection: " + supports_face_detection);

        final boolean supports_flash = bundle.getBoolean("supports_flash");
        if( MyDebug.LOG )
            Log.d(TAG, "supports_flash: " + supports_flash);

        final boolean supports_preview_bitmaps = bundle.getBoolean("supports_preview_bitmaps");
        if( MyDebug.LOG )
            Log.d(TAG, "supports_preview_bitmaps: " + supports_preview_bitmaps);

        final boolean supports_auto_stabilise = bundle.getBoolean("supports_auto_stabilise");
        if( MyDebug.LOG )
            Log.d(TAG, "supports_auto_stabilise: " + supports_auto_stabilise);

        final boolean supports_raw = bundle.getBoolean("supports_raw");
        if( MyDebug.LOG )
            Log.d(TAG, "supports_raw: " + supports_raw);

        final boolean supports_white_balance_lock = bundle.getBoolean("supports_white_balance_lock");
        if( MyDebug.LOG )
            Log.d(TAG, "supports_white_balance_lock: " + supports_white_balance_lock);

        final boolean supports_exposure_lock = bundle.getBoolean("supports_exposure_lock");
        if( MyDebug.LOG )
            Log.d(TAG, "supports_exposure_lock: " + supports_exposure_lock);

        final boolean supports_preshots = bundle.getBoolean("supports_preshots");
        if( MyDebug.LOG )
            Log.d(TAG, "supports_preshots: " + supports_preshots);

        final boolean is_multi_cam = bundle.getBoolean("is_multi_cam");
        if( MyDebug.LOG )
            Log.d(TAG, "is_multi_cam: " + is_multi_cam);

        final boolean has_physical_cameras = bundle.getBoolean("has_physical_cameras");
        if( MyDebug.LOG )
            Log.d(TAG, "has_physical_cameras: " + has_physical_cameras);

        if( !supports_face_detection  && ( camera_open || sharedPreferences.getBoolean(PreferenceKeys.FaceDetectionPreferenceKey, false) == false ) ) {
            Preference pref = findPreference("preference_show_face_detection");
            PreferenceGroup pg = (PreferenceGroup)this.findPreference("preferences_root");
            pg.removePreference(pref);
        }

        if( !supports_flash ) {
            Preference pref = findPreference("preference_show_cycle_flash");
            PreferenceGroup pg = (PreferenceGroup)this.findPreference("preferences_root");
            pg.removePreference(pref);
        }

        if( !supports_preview_bitmaps ) {
            Preference pref = findPreference("preference_show_focus_peaking");
            PreferenceGroup pg = (PreferenceGroup)this.findPreference("preferences_root");
            pg.removePreference(pref);
        }

        if( !supports_auto_stabilise ) {
            Preference pref = findPreference("preference_show_auto_level");
            PreferenceGroup pg = (PreferenceGroup)this.findPreference("preferences_root");
            pg.removePreference(pref);
        }

        if( !supports_raw ) {
            Preference pref = findPreference("preference_show_cycle_raw");
            PreferenceGroup pg = (PreferenceGroup)this.findPreference("preferences_root");
            pg.removePreference(pref);
        }

        if( !supports_white_balance_lock ) {
            Preference pref = findPreference("preference_show_white_balance_lock");
            PreferenceGroup pg = (PreferenceGroup)this.findPreference("preferences_root");
            pg.removePreference(pref);
        }

        if( !supports_exposure_lock ) {
            Preference pref = findPreference("preference_show_exposure_lock");
            PreferenceGroup pg = (PreferenceGroup)this.findPreference("preferences_root");
            pg.removePreference(pref);
        }

        if( !supports_preshots ) {
            PreferenceGroup pg = (PreferenceGroup)this.findPreference("preferences_root");
            Preference pref = findPreference("preference_show_preview_shots");
            pg.removePreference(pref);
        }

        if( !is_multi_cam && !has_physical_cameras ) {
            Preference pref = findPreference("preference_multi_cam_mode");
            PreferenceGroup pg = (PreferenceGroup)this.findPreference("preferences_root");
            pg.removePreference(pref);
        }

        // Handle individual camera sub-preferences dynamically
        final boolean canShowMultiCam = is_multi_cam || has_physical_cameras;
        final ListPreference multiCamModePref = (ListPreference) findPreference("preference_multi_cam_mode");
        final Preference displayFormatPref = findPreference("preference_individual_cam_display_format");
        final MultiSelectListPreference favoritesPref = (MultiSelectListPreference) findPreference("preference_individual_cam_favorites");
        final Preference customNamesHeaderPref = findPreference("preference_individual_cam_custom_names_placeholder");
        final PreferenceGroup pg = (PreferenceGroup)this.findPreference("preferences_root");

        // Populate favorites entries from available lenses
        if( canShowMultiCam ) {
            cachedLenses = getAvailableLenses();
            populateFavoritesEntries(favoritesPref, cachedLenses);
        }

        // Set initial visibility
        String currentMode = canShowMultiCam ? sharedPreferences.getString(PreferenceKeys.MultiCamModePreferenceKey, "menu") : "off";
        boolean showIndividual = currentMode.equals("individual") && canShowMultiCam;
        setIndividualCamPreferencesVisible(pg, displayFormatPref, favoritesPref, customNamesHeaderPref, showIndividual);
        if( showIndividual && cachedLenses != null ) {
            populateCustomNameEntries(pg, cachedLenses);
        }

        // Listen for mode changes to dynamically show/hide sub-preferences
        if( canShowMultiCam && multiCamModePref != null ) {
            multiCamModePref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    String newMode = (String) newValue;
                    boolean showIndividual = "individual".equals(newMode);
                    if( MyDebug.LOG )
                        Log.d(TAG, "multi-cam mode changed to: " + newMode);
                    setIndividualCamPreferencesVisible(pg, displayFormatPref, favoritesPref, customNamesHeaderPref, showIndividual);
                    if( showIndividual && cachedLenses != null ) {
                        populateCustomNameEntries(pg, cachedLenses);
                    } else {
                        removeCustomNameEntries(pg);
                    }
                    return true;
                }
            });
        }

        // Set summary for favorites to show current selection count
        if( favoritesPref != null ) {
            updateFavoritesSummary(favoritesPref);
            favoritesPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    // Summary will be updated after the preference is saved
                    return true;
                }
            });
        }

        if( MyDebug.LOG )
            Log.d(TAG, "onCreate done");
    }

    /** Show or hide the individual camera sub-preferences (display format, favorites, custom names header). */
    private void setIndividualCamPreferencesVisible(PreferenceGroup pg, Preference displayFormatPref,
            MultiSelectListPreference favoritesPref, Preference customNamesHeaderPref, boolean visible) {
        if( MyDebug.LOG )
            Log.d(TAG, "setIndividualCamPreferencesVisible: " + visible);
        if( visible ) {
            if( displayFormatPref != null && pg.findPreference(PreferenceKeys.IndividualCamDisplayFormatKey) == null )
                pg.addPreference(displayFormatPref);
            if( favoritesPref != null && pg.findPreference(PreferenceKeys.IndividualCamFavoritesKey) == null )
                pg.addPreference(favoritesPref);
            if( customNamesHeaderPref != null && pg.findPreference("preference_individual_cam_custom_names_placeholder") == null )
                pg.addPreference(customNamesHeaderPref);
        } else {
            if( displayFormatPref != null )
                pg.removePreference(displayFormatPref);
            if( favoritesPref != null )
                pg.removePreference(favoritesPref);
            if( customNamesHeaderPref != null )
                pg.removePreference(customNamesHeaderPref);
        }
    }

    /** Get available lenses from the device. */
    private List<CameraControllerManager2.LensInfo> getAvailableLenses() {
        if( getActivity() == null )
            return new ArrayList<>();
        try {
            CameraManager manager = (CameraManager) getActivity().getSystemService(android.content.Context.CAMERA_SERVICE);
            if( manager == null )
                return new ArrayList<>();
            CameraControllerManager2 camManager2 = new CameraControllerManager2(getActivity());
            String[] cameraIdList = manager.getCameraIdList();
            if( cameraIdList == null || cameraIdList.length == 0 )
                return new ArrayList<>();

            int currentLogicalCameraId = 0;

            Set<String> physicalCameraIds = null;
            try {
                android.hardware.camera2.CameraCharacteristics logicalChars = manager.getCameraCharacteristics(cameraIdList[0]);
                Set<String> physIds = logicalChars.getPhysicalCameraIds();
                if( physIds != null && physIds.size() > 1 )
                    physicalCameraIds = physIds;
            } catch(Throwable e) {
                // ignore
            }

            return camManager2.getAvailableLenses(getActivity(), currentLogicalCameraId, physicalCameraIds);
        } catch(Throwable e) {
            if( MyDebug.LOG )
                Log.e(TAG, "Failed to get available lenses", e);
            return new ArrayList<>();
        }
    }

    /** Populate the favorites MultiSelectListPreference with available lenses from the device. */
    private void populateFavoritesEntries(MultiSelectListPreference favoritesPref, List<CameraControllerManager2.LensInfo> lenses) {
        if( favoritesPref == null || lenses == null || lenses.isEmpty() )
            return;
        try {
            List<String> entries = new ArrayList<>();
            List<String> values = new ArrayList<>();

            for (CameraControllerManager2.LensInfo lens : lenses) {
                entries.add(lens.defaultLabel);
                values.add(lens.cameraKey);
            }

            favoritesPref.setEntries(entries.toArray(new CharSequence[0]));
            favoritesPref.setEntryValues(values.toArray(new CharSequence[0]));

            // Load saved favorites and validate they still exist
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
            Set<String> savedJsonSet = prefs.getStringSet(PreferenceKeys.IndividualCamFavoritesKey, null);
            Set<String> savedFavorites = new HashSet<>();
            if( savedJsonSet != null ) {
                Set<String> validKeys = new HashSet<>(values);
                for (String key : savedJsonSet) {
                    if( validKeys.contains(key) )
                        savedFavorites.add(key);
                }
            }

            // Set default: if no saved favorites, select all
            if( savedFavorites.isEmpty() ) {
                favoritesPref.setValues(new HashSet<String>());
            } else {
                favoritesPref.setValues(savedFavorites);
            }

            if( MyDebug.LOG )
                Log.d(TAG, "populateFavoritesEntries: found " + lenses.size() + " lenses");
        } catch(Throwable e) {
            if( MyDebug.LOG )
                Log.e(TAG, "Failed to populate favorites entries", e);
        }
    }

    /** Remove all dynamically created custom name entries. */
    private void removeCustomNameEntries(PreferenceGroup pg) {
        List<Preference> toRemove = new ArrayList<>();
        for (int i = 0; i < pg.getPreferenceCount(); i++) {
            Preference pref = pg.getPreference(i);
            String key = pref.getKey();
            if( key != null && key.startsWith("preference_individual_cam_custom_name_") ) {
                toRemove.add(pref);
            }
        }
        for (Preference pref : toRemove) {
            pg.removePreference(pref);
        }
    }

    /** Create per-lens custom name preferences, one for each available lens. */
    private void populateCustomNameEntries(PreferenceGroup pg, List<CameraControllerManager2.LensInfo> lenses) {
        if( lenses == null || getActivity() == null )
            return;

        // Remove any existing custom name entries first
        removeCustomNameEntries(pg);

        // Load existing custom names
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        JSONObject existingNames = loadCustomNames(prefs);

        for (CameraControllerManager2.LensInfo lens : lenses) {
            final String cameraKey = lens.cameraKey;
            String safeKey = "preference_individual_cam_custom_name_" + cameraKey.replaceAll("[^a-zA-Z0-9]", "_");

            Preference pref = new Preference(getActivity());
            pref.setKey(safeKey);
            pref.setTitle(getString(R.string.individual_cam_custom_name_for, lens.defaultLabel));

            // Get current custom name for this lens
            String currentName = "";
            try {
                if( existingNames != null && existingNames.has(cameraKey) ) {
                    currentName = existingNames.getString(cameraKey);
                }
            } catch(JSONException e) {
                // ignore
            }

            if( !currentName.isEmpty() ) {
                pref.setSummary(currentName);
            } else {
                pref.setSummary(R.string.individual_cam_custom_name_hint);
            }

            pref.setPersistent(false);

            pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    showCustomNameDialog(pg, lenses, cameraKey, lens.defaultLabel);
                    return true;
                }
            });

            pg.addPreference(pref);
        }
    }

    /** Show a dialog to edit the custom name for a specific lens. */
    private void showCustomNameDialog(final PreferenceGroup pg, final List<CameraControllerManager2.LensInfo> lenses,
            final String cameraKey, String lensLabel) {
        if( getActivity() == null )
            return;

        // Load current custom name
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        JSONObject existingNames = loadCustomNames(prefs);
        String currentName = "";
        try {
            if( existingNames != null && existingNames.has(cameraKey) ) {
                currentName = existingNames.getString(cameraKey);
            }
        } catch(JSONException e) {
            // ignore
        }

        final EditText editText = new EditText(getActivity());
        editText.setText(currentName);
        editText.setHint(R.string.individual_cam_custom_name_hint);
        editText.setSelectAllOnFocus(true);

        new AlertDialog.Builder(getActivity())
            .setTitle(getString(R.string.individual_cam_custom_name_for, lensLabel))
            .setView(editText)
            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String newName = editText.getText().toString().trim();
                    saveCustomName(pg, lenses, cameraKey, newName);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Save a custom name for a lens and update the consolidated JSON in SharedPreferences. */
    private void saveCustomName(PreferenceGroup pg, List<CameraControllerManager2.LensInfo> lenses,
            String cameraKey, String newName) {
        if( getActivity() == null )
            return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        JSONObject existingNames = loadCustomNames(prefs);

        if( existingNames == null )
            existingNames = new JSONObject();

        try {
            if( newName.isEmpty() ) {
                existingNames.remove(cameraKey);
            } else {
                existingNames.put(cameraKey, newName);
            }
        } catch(JSONException e) {
            if( MyDebug.LOG )
                Log.e(TAG, "Failed to save custom name", e);
            return;
        }

        // Write back to SharedPreferences
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PreferenceKeys.IndividualCamCustomNamesKey, existingNames.toString());
        editor.apply();

        // Update summaries of the per-lens preferences
        for (int i = 0; i < pg.getPreferenceCount(); i++) {
            Preference pref = pg.getPreference(i);
            String key = pref.getKey();
            if( key != null && key.startsWith("preference_individual_cam_custom_name_") ) {
                // Find the lens this preference corresponds to
                for (CameraControllerManager2.LensInfo lens : lenses) {
                    String safeKey = "preference_individual_cam_custom_name_" + lens.cameraKey.replaceAll("[^a-zA-Z0-9]", "_");
                    if( key.equals(safeKey) ) {
                        String name = "";
                        try {
                            if( existingNames.has(lens.cameraKey) ) {
                                name = existingNames.getString(lens.cameraKey);
                            }
                        } catch(JSONException e) {
                            // ignore
                        }
                        if( !name.isEmpty() ) {
                            pref.setSummary(name);
                        } else {
                            pref.setSummary(R.string.individual_cam_custom_name_hint);
                        }
                        break;
                    }
                }
            }
        }

        if( MyDebug.LOG )
            Log.d(TAG, "saveCustomName: " + cameraKey + " -> " + newName);
    }

    /** Load custom names from SharedPreferences as a JSONObject. */
    private JSONObject loadCustomNames(SharedPreferences prefs) {
        String json = prefs.getString(PreferenceKeys.IndividualCamCustomNamesKey, null);
        if( json != null && !json.isEmpty() ) {
            try {
                return new JSONObject(json);
            } catch(JSONException e) {
                if( MyDebug.LOG )
                    Log.e(TAG, "Failed to parse custom names JSON", e);
            }
        }
        return null;
    }

    /** Update the favorites summary to show the number of selected lenses. */
    private void updateFavoritesSummary(MultiSelectListPreference favoritesPref) {
        if( favoritesPref == null )
            return;
        Set<String> selected = favoritesPref.getValues();
        if( selected == null || selected.isEmpty() ) {
            favoritesPref.setSummary(R.string.preference_individual_cam_favorites_summary);
        } else {
            favoritesPref.setSummary("Showing " + selected.size() + " favorite lens(es)");
        }
    }
}
