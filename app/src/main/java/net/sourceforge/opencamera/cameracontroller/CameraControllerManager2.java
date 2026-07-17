package net.sourceforge.opencamera.cameracontroller;

import net.sourceforge.opencamera.MyDebug;
import net.sourceforge.opencamera.R;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.util.Log;
import android.util.SizeF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Provides support using Android 5's Camera 2 API
 *  android.hardware.camera2.*.
 */
public class CameraControllerManager2 extends CameraControllerManager {
    private static final String TAG = "CControllerManager2";

    private final Context context;

    public CameraControllerManager2(Context context) {
        this.context = context;
    }

    @Override
    public int getNumberOfCameras() {
        CameraManager manager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        try {
            return manager.getCameraIdList().length;
        }
        catch(Throwable e) {
            // in theory we should only get CameraAccessException, but Google Play shows we can get a variety of exceptions
            // from some devices, e.g., AssertionError, IllegalArgumentException, RuntimeException, so just catch everything!
            // We don't want users to experience a crash just because of buggy camera2 drivers - instead the user can switch
            // back to old camera API.
            MyDebug.logStackTrace(TAG, "exception trying to get camera ids", e);
        }
        return 0;
    }

    @Override
    public CameraController.Facing getFacing(int cameraId) {
        CameraManager manager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraIdS = manager.getCameraIdList()[cameraId];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraIdS);
            switch( characteristics.get(CameraCharacteristics.LENS_FACING) ) {
                case CameraMetadata.LENS_FACING_FRONT:
                    return CameraController.Facing.FACING_FRONT;
                case CameraMetadata.LENS_FACING_BACK:
                    return CameraController.Facing.FACING_BACK;
                case CameraMetadata.LENS_FACING_EXTERNAL:
                    return CameraController.Facing.FACING_EXTERNAL;
            }
            Log.e(TAG, "unknown camera_facing: " + characteristics.get(CameraCharacteristics.LENS_FACING));
        }
        catch(Throwable e) {
            // in theory we should only get CameraAccessException, but Google Play shows we can get a variety of exceptions
            // from some devices, e.g., AssertionError, IllegalArgumentException, RuntimeException, so just catch everything!
            // We don't want users to experience a crash just because of buggy camera2 drivers - instead the user can switch
            // back to old camera API.
            MyDebug.logStackTrace(TAG, "exception trying to get camera characteristics", e);
        }
        return CameraController.Facing.FACING_UNKNOWN;
    }

    @Override
    public String getDescription(Context context, int cameraId) {
        CameraManager manager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        String description = null;
        try {
            String cameraIdS = manager.getCameraIdList()[cameraId];
            description = getDescription(null, context, cameraIdS, true, false);
        }
        catch(Throwable e) {
            // see note under isFrontFacing() why we catch anything, not just CameraAccessException
            MyDebug.logStackTrace(TAG, "exception trying to get camera characteristics", e);
        }
        return description;
    }

    @Override
    public String getDescription(CameraInfo info, Context context, String cameraIdS, boolean include_type, boolean include_angles) {
        long debug_time = 0;
        if( MyDebug.LOG ) {
            debug_time = System.currentTimeMillis();
        }
        CameraManager manager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        String description = "";
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraIdS);
            if( MyDebug.LOG )
                Log.d(TAG, "getDescription: time after getCameraCharacteristics: " + (System.currentTimeMillis() - debug_time));

            if( include_type ) {
                switch( characteristics.get(CameraCharacteristics.LENS_FACING) ) {
                    case CameraMetadata.LENS_FACING_FRONT:
                        description = context.getResources().getString(R.string.front_camera);
                        break;
                    case CameraMetadata.LENS_FACING_BACK:
                        description = context.getResources().getString(R.string.back_camera);
                        break;
                    case CameraMetadata.LENS_FACING_EXTERNAL:
                        description = context.getResources().getString(R.string.external_camera);
                        break;
                    default:
                        Log.e(TAG, "unknown camera type");
                        return null;
                }
            }

            SizeF view_angle = CameraControllerManager2.computeViewAngles(characteristics);
            if( info != null )
                info.view_angle = view_angle;
            if( MyDebug.LOG )
                Log.d(TAG, "getDescription: time after computeViewAngles: " + (System.currentTimeMillis() - debug_time));
            if( view_angle.getWidth() > 90.5f ) {
                // count as ultra-wide
                if( !description.isEmpty() )
                    description += ", ";
                description += context.getResources().getString(R.string.ultrawide);
            }
            else if( view_angle.getWidth() < 29.5f ) {
                // count as telephoto
                // Galaxy S24+ telephoto is 29x22 degrees
                if( !description.isEmpty() )
                    description += ", ";
                description += context.getResources().getString(R.string.telephoto);
            }

            if( include_angles ) {
                if( !description.isEmpty() )
                    description += ", ";
                description += ((int)(view_angle.getWidth()+0.5f)) + String.valueOf((char)0x00B0) + " x " + ((int)(view_angle.getHeight()+0.5f)) + (char) 0x00B0;
            }
        }
        catch(Throwable e) {
            // see note under isFrontFacing() why we catch anything, not just CameraAccessException
            MyDebug.logStackTrace(TAG, "exception trying to get camera characteristics", e);
        }
        return description;
    }

    /** Helper class to compute view angles from the CameraCharacteristics.
     * @return The width and height of the returned size represent the x and y view angles in
     *         degrees.
     */
    static SizeF computeViewAngles(CameraCharacteristics characteristics) {
        // Note this is an approximation (see http://stackoverflow.com/questions/39965408/what-is-the-android-camera2-api-equivalent-of-camera-parameters-gethorizontalvie ).
        // This does not take into account the aspect ratio of the preview or camera, it's up to the caller to do this (e.g., see Preview.getViewAngleX(), getViewAngleY()).
        Rect active_size = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        SizeF physical_size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        android.util.Size pixel_size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        float [] focal_lengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if( active_size == null || physical_size == null || pixel_size == null || focal_lengths == null || focal_lengths.length == 0 ) {
            // in theory this should never happen according to the documentation, but I've had a report of physical_size (SENSOR_INFO_PHYSICAL_SIZE)
            // being null on an EXTERNAL Camera2 device, see https://sourceforge.net/p/opencamera/tickets/754/
            if( MyDebug.LOG ) {
                Log.e(TAG, "can't get camera view angles");
            }
            // fall back to a default
            return new SizeF(55.0f, 43.0f);
        }
        //camera_features.view_angle_x = (float)Math.toDegrees(2.0 * Math.atan2(physical_size.getWidth(), (2.0 * focal_lengths[0])));
        //camera_features.view_angle_y = (float)Math.toDegrees(2.0 * Math.atan2(physical_size.getHeight(), (2.0 * focal_lengths[0])));
        float frac_x = ((float)active_size.width())/(float)pixel_size.getWidth();
        float frac_y = ((float)active_size.height())/(float)pixel_size.getHeight();
        float view_angle_x = (float)Math.toDegrees(2.0 * Math.atan2(physical_size.getWidth() * frac_x, (2.0 * focal_lengths[0])));
        float view_angle_y = (float)Math.toDegrees(2.0 * Math.atan2(physical_size.getHeight() * frac_y, (2.0 * focal_lengths[0])));
        if( MyDebug.LOG ) {
            Log.d(TAG, "frac_x: " + frac_x);
            Log.d(TAG, "frac_y: " + frac_y);
            Log.d(TAG, "view_angle_x: " + view_angle_x);
            Log.d(TAG, "view_angle_y: " + view_angle_y);
        }
        return new SizeF(view_angle_x, view_angle_y);
    }

    /* Returns true if the device supports the required hardware level, or better.
     * See https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL .
     * From Android N, higher levels than "FULL" are possible, that will have higher integer values.
     * Also see https://sourceforge.net/p/opencamera/tickets/141/ .
     */
    static boolean isHardwareLevelSupported(CameraCharacteristics c, int requiredLevel) {
        int deviceLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if( MyDebug.LOG ) {
            switch (deviceLevel) {
                case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                    Log.d(TAG, "Camera has LEGACY Camera2 support");
                    break;
                case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL:
                    Log.d(TAG, "Camera has EXTERNAL Camera2 support");
                    break;
                case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                    Log.d(TAG, "Camera has LIMITED Camera2 support");
                    break;
                case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                    Log.d(TAG, "Camera has FULL Camera2 support");
                    break;
                case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                    Log.d(TAG, "Camera has Level 3 Camera2 support");
                    break;
                default:
                    Log.d(TAG, "Camera has unknown Camera2 support: " + deviceLevel);
                    break;
            }
        }

        // need to treat legacy and external as special cases; otherwise can then use numerical comparison

        if( deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY ) {
            return requiredLevel == deviceLevel;
        }

        if( deviceLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL ) {
            deviceLevel = CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED;
        }
        if( requiredLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL ) {
            requiredLevel = CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED;
        }

        return requiredLevel <= deviceLevel;
    }

    /* Rather than allowing Camera2 API on all Android 5+ devices, we restrict it to certain cases.
     * This returns whether the specified camera has at least LIMITED support.
     */
    public boolean allowCamera2Support(int cameraId) {
        CameraManager manager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraIdS = manager.getCameraIdList()[cameraId];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraIdS);
            //return isHardwareLevelSupported(characteristics, CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY);
            return isHardwareLevelSupported(characteristics, CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED);
        }
        catch(Throwable e) {
            // in theory we should only get CameraAccessException, but Google Play shows we can get a variety of exceptions
            // from some devices, e.g., AssertionError, IllegalArgumentException, RuntimeException, so just catch everything!
            // We don't want users to experience a crash just because of buggy camera2 drivers - instead the user can switch
            // back to old camera API.
            MyDebug.logStackTrace(TAG, "exception trying to get camera characteristics", e);
        }
        return false;
    }

    /** Holds metadata about a camera lens for individual camera icons. */
    public static class LensInfo {
        public int logicalCameraId;
        public String physicalCameraId; // null for logical cameras
        public String cameraKey;       // "0" or "0_0" format
        public float focalLengthMm;    // actual focal length in mm
        public float equivFocalLengthMm; // 35mm-equivalent focal length
        public float viewAngleX;       // horizontal view angle in degrees
        public String defaultLabel;    // auto-detected label
        public boolean isPhysical;     // true if this is a physical sub-camera

        /** Generate a stable key for this camera entry. */
        public static String makeKey(int logicalCameraId, String physicalCameraId) {
            if (physicalCameraId != null)
                return logicalCameraId + "_" + physicalCameraId;
            else
                return String.valueOf(logicalCameraId);
        }
    }

    /** Computes focal length and 35mm-equivalent focal length for a camera (logical or physical).
     *  @return float[2] where [0] = actual focal length in mm, [1] = 35mm-equivalent. Returns null if unavailable.
     */
    public float[] getFocalLengthInfo(String cameraIdS) {
        CameraManager manager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraIdS);
            float[] focal_lengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF physical_size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (focal_lengths == null || focal_lengths.length == 0 || physical_size == null)
                return null;
            float focalLengthMm = focal_lengths[0];
            float sensorWidthMm = physical_size.getWidth();
            float equivFocalLengthMm = (float)(focalLengthMm * (36.0 / sensorWidthMm));
            return new float[]{focalLengthMm, equivFocalLengthMm};
        }
        catch (Throwable e) {
            MyDebug.logStackTrace(TAG, "exception getting focal length info", e);
        }
        return null;
    }

    /** Get all available lenses (logical + physical) for the given facing, with metadata.
     *  Results are sorted by focal length (widest first).
     */
    public List<LensInfo> getAvailableLenses(Context context, int currentLogicalCameraId, Set<String> physicalCameraIds) {
        List<LensInfo> lenses = new ArrayList<>();
        CameraManager manager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);

        try {
            String[] cameraIdList = manager.getCameraIdList();
            CameraController.Facing currentFacing = getFacing(currentLogicalCameraId);

            // Collect logical cameras with same facing
            List<Integer> sameFacingLogicalIds = new ArrayList<>();
            for (int i = 0; i < cameraIdList.length; i++) {
                if (getFacing(i) == currentFacing) {
                    sameFacingLogicalIds.add(i);
                }
            }

            for (int logicalId : sameFacingLogicalIds) {
                String logicalIdS = cameraIdList[logicalId];
                float[] focalInfo = getFocalLengthInfo(logicalIdS);
                CameraControllerManager.CameraInfo info = new CameraControllerManager.CameraInfo();
                String desc = getDescription(info, context, logicalIdS, true, false);

                // Compute view angle for this logical camera
                try {
                    CameraCharacteristics chars = manager.getCameraCharacteristics(logicalIdS);
                    SizeF viewAngle = computeViewAngles(chars);
                    info.view_angle = viewAngle;
                } catch (Throwable e) {
                    // ignore
                }

                LensInfo lensInfo = new LensInfo();
                lensInfo.logicalCameraId = logicalId;
                lensInfo.physicalCameraId = null;
                lensInfo.cameraKey = LensInfo.makeKey(logicalId, null);
                lensInfo.isPhysical = false;
                lensInfo.viewAngleX = (info.view_angle != null) ? info.view_angle.getWidth() : 55.0f;

                if (focalInfo != null) {
                    lensInfo.focalLengthMm = focalInfo[0];
                    lensInfo.equivFocalLengthMm = focalInfo[1];
                }

                // Build default label: "logicalId: description"
                lensInfo.defaultLabel = logicalId + ": " + desc;

                // Add physical cameras for the current logical camera
                if (logicalId == currentLogicalCameraId && physicalCameraIds != null) {
                    // First add the logical camera itself (as "Auto Lens")
                    lensInfo.defaultLabel = logicalId + ": " + desc + " (Auto Lens)";
                    lenses.add(lensInfo);

                    // Sort physical cameras by view angle (widest first)
                    List<LensInfo> physicalLenses = new ArrayList<>();
                    int j = 0;
                    for (String physicalId : physicalCameraIds) {
                        String physicalIdS = physicalId;
                        float[] physFocalInfo = getFocalLengthInfo(physicalIdS);
                        CameraControllerManager.CameraInfo physInfo = new CameraControllerManager.CameraInfo();
                        String physDesc = getDescription(physInfo, context, physicalIdS, false, true);

                        try {
                            CameraCharacteristics physChars = manager.getCameraCharacteristics(physicalIdS);
                            SizeF physViewAngle = computeViewAngles(physChars);
                            physInfo.view_angle = physViewAngle;
                        } catch (Throwable e) {
                            // ignore
                        }

                        LensInfo physLens = new LensInfo();
                        physLens.logicalCameraId = logicalId;
                        physLens.physicalCameraId = physicalId;
                        physLens.cameraKey = LensInfo.makeKey(logicalId, physicalId);
                        physLens.isPhysical = true;
                        physLens.viewAngleX = (physInfo.view_angle != null) ? physInfo.view_angle.getWidth() : 55.0f;

                        if (physFocalInfo != null) {
                            physLens.focalLengthMm = physFocalInfo[0];
                            physLens.equivFocalLengthMm = physFocalInfo[1];
                        }

                        physLens.defaultLabel = "Lens " + j + ": " + physDesc;
                        physicalLenses.add(physLens);
                        j++;
                    }

                    // Sort physical lenses by view angle descending (widest first)
                    Collections.sort(physicalLenses, new Comparator<LensInfo>() {
                        @Override
                        public int compare(LensInfo o1, LensInfo o2) {
                            float diff = o2.viewAngleX - o1.viewAngleX;
                            if (Math.abs(diff) < 1.0e-5f) return 0;
                            return diff > 0.0f ? 1 : -1;
                        }
                    });

                    lenses.addAll(physicalLenses);
                } else if (logicalId != currentLogicalCameraId) {
                    lenses.add(lensInfo);
                }
            }
        }
        catch (Throwable e) {
            MyDebug.logStackTrace(TAG, "exception getting available lenses", e);
        }

        // Sort all lenses by equiv focal length (widest first)
        Collections.sort(lenses, new Comparator<LensInfo>() {
            @Override
            public int compare(LensInfo o1, LensInfo o2) {
                // Physical cameras under the current logical camera should stay in their sub-group order
                // So only sort at the top level (logical cameras and their physical sub-cameras)
                float diff = o1.equivFocalLengthMm - o2.equivFocalLengthMm;
                if (Math.abs(diff) < 0.1f) return 0;
                return diff > 0.0f ? 1 : -1;
            }
        });

        return lenses;
    }

    /** Compute the 1x multiplier reference focal length (the "main" camera = widest lens). */
    public float getMainCameraEquivFocalLength(List<LensInfo> lenses) {
        float minFocal = Float.MAX_VALUE;
        for (LensInfo lens : lenses) {
            if (!lens.isPhysical && lens.equivFocalLengthMm > 0 && lens.equivFocalLengthMm < minFocal) {
                minFocal = lens.equivFocalLengthMm;
            }
        }
        if (minFocal == Float.MAX_VALUE) minFocal = 1.0f; // fallback
        return minFocal;
    }
}
