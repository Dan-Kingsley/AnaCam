package net.sourceforge.opencamera;

import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.util.Log;

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
        final EditTextPreference customNamesPref = (EditTextPreference) findPreference("preference_individual_cam_custom_names");
        final PreferenceGroup pg = (PreferenceGroup)this.findPreference("preferences_root");

        // Populate favorites entries from available lenses
        if( canShowMultiCam ) {
            populateFavoritesEntries(favoritesPref);
        }

        // Set initial visibility
        String currentMode = canShowMultiCam ? sharedPreferences.getString(PreferenceKeys.MultiCamModePreferenceKey, "menu") : "off";
        setIndividualCamPreferencesVisible(pg, displayFormatPref, favoritesPref, customNamesPref, currentMode.equals("individual") && canShowMultiCam);

        // Listen for mode changes to dynamically show/hide sub-preferences
        if( canShowMultiCam && multiCamModePref != null ) {
            multiCamModePref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    String newMode = (String) newValue;
                    boolean showIndividual = "individual".equals(newMode);
                    if( MyDebug.LOG )
                        Log.d(TAG, "multi-cam mode changed to: " + newMode);
                    setIndividualCamPreferencesVisible(pg, displayFormatPref, favoritesPref, customNamesPref, showIndividual);
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

    /** Show or hide the individual camera sub-preferences (display format, favorites, custom names). */
    private void setIndividualCamPreferencesVisible(PreferenceGroup pg, Preference displayFormatPref,
            MultiSelectListPreference favoritesPref, EditTextPreference customNamesPref, boolean visible) {
        if( MyDebug.LOG )
            Log.d(TAG, "setIndividualCamPreferencesVisible: " + visible);
        if( visible ) {
            if( displayFormatPref != null && pg.findPreference(PreferenceKeys.IndividualCamDisplayFormatKey) == null )
                pg.addPreference(displayFormatPref);
            if( favoritesPref != null && pg.findPreference(PreferenceKeys.IndividualCamFavoritesKey) == null )
                pg.addPreference(favoritesPref);
            if( customNamesPref != null && pg.findPreference(PreferenceKeys.IndividualCamCustomNamesKey) == null )
                pg.addPreference(customNamesPref);
        } else {
            if( displayFormatPref != null )
                pg.removePreference(displayFormatPref);
            if( favoritesPref != null )
                pg.removePreference(favoritesPref);
            if( customNamesPref != null )
                pg.removePreference(customNamesPref);
        }
    }

    /** Populate the favorites MultiSelectListPreference with available lenses from the device. */
    private void populateFavoritesEntries(MultiSelectListPreference favoritesPref) {
        if( favoritesPref == null || getActivity() == null )
            return;
        try {
            CameraManager manager = (CameraManager) getActivity().getSystemService(android.content.Context.CAMERA_SERVICE);
            if( manager == null )
                return;
            CameraControllerManager2 camManager2 = new CameraControllerManager2(getActivity());
            String[] cameraIdList = manager.getCameraIdList();
            if( cameraIdList == null || cameraIdList.length == 0 )
                return;

            // Determine current facing to get same-facing cameras
            // For simplicity, use camera 0's facing as the "current" facing
            int currentLogicalCameraId = 0;
            android.hardware.camera2.CameraCharacteristics chars0 = manager.getCameraCharacteristics(cameraIdList[0]);
            int facing0 = chars0.get(CameraCharacteristics.LENS_FACING);

            // Get physical cameras if available
            Set<String> physicalCameraIds = null;
            try {
                android.hardware.camera2.CameraCharacteristics logicalChars = manager.getCameraCharacteristics(cameraIdList[0]);
                Set<String> physIds = logicalChars.getPhysicalCameraIds();
                if( physIds != null && physIds.size() > 1 )
                    physicalCameraIds = physIds;
            } catch(Throwable e) {
                // ignore
            }

            List<CameraControllerManager2.LensInfo> lenses = camManager2.getAvailableLenses(getActivity(), currentLogicalCameraId, physicalCameraIds);

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
            String savedJson = prefs.getString(PreferenceKeys.IndividualCamFavoritesKey, null);
            Set<String> savedFavorites = new HashSet<>();
            if( savedJson != null && !savedJson.isEmpty() ) {
                try {
                    JSONArray arr = new JSONArray(savedJson);
                    Set<String> validKeys = new HashSet<>(values);
                    for (int i = 0; i < arr.length(); i++) {
                        String key = arr.getString(i);
                        if( validKeys.contains(key) )
                            savedFavorites.add(key);
                    }
                } catch(JSONException e) {
                    // ignore
                }
            }

            // Set default: if no saved favorites, select all
            if( savedFavorites.isEmpty() ) {
                // Set all as selected (empty set in MultiSelectListPreference means none selected, not all)
                // We'll store an empty JSON array, which means "show all" in our logic
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
