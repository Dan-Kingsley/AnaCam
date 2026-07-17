package net.sourceforge.opencamera;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Post-processes a DNG/TIFF file to modify tags for anamorphic desqueeze.
 *  Modifies DefaultCropOrigin, DefaultCropSize, and PixelAspectRatio tags so that
 *  RAW processing software displays the image with the correct desqueezed aspect ratio.
 */
public class DngTagEditor {
    private static final String TAG = "DngTagEditor";

    private static final int TAG_IMAGE_WIDTH = 256;
    private static final int TAG_IMAGE_LENGTH = 257;
    private static final int TAG_BITS_PER_SAMPLE = 258;
    private static final int TAG_COMPRESSION = 259;
    private static final int TAG_STRIP_OFFSETS = 273;
    private static final int TAG_ORIENTATION = 274;
    private static final int TAG_SAMPLES_PER_PIXEL = 277;
    private static final int TAG_ROWS_PER_STRIP = 278;
    private static final int TAG_STRIP_BYTE_COUNTS = 279;
    private static final int TAG_X_RESOLUTION = 282;
    private static final int TAG_Y_RESOLUTION = 283;
    private static final int TAG_PLANAR_CONFIGURATION = 284;
    private static final int TAG_RESOLUTION_UNIT = 296;
    private static final int TAG_SAMPLE_FORMAT = 339;

    // DNG tags
    private static final int TAG_DEFAULT_CROP_ORIGIN = 50292;
    private static final int TAG_DEFAULT_CROP_SIZE = 50293;
    private static final int TAG_PIXEL_ASPECT_RATIO = 50289;

    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_RATIONAL = 5;

    private static final int TAG_SIZE = 12;

    /** Returns the byte size of a TIFF type. */
    private static int typeSize(int type) {
        switch( type ) {
            case 1: return 1; // BYTE
            case 2: return 1; // ASCII
            case 3: return 2; // SHORT
            case 4: return 4; // LONG
            case 5: return 8; // RATIONAL
            case 6: return 1; // SBYTE
            case 7: return 1; // UNDEFINED
            case 8: return 2; // SSHORT
            case 9: return 4; // SLONG
            case 10: return 8; // SRATIONAL
            case 11: return 4; // FLOAT
            case 12: return 8; // DOUBLE
            default: return 4;
        }
    }

    public static void applyDesqueeze(File file, float desqueezeFactor) throws IOException {
        if( MyDebug.LOG )
            Log.d(TAG, "applyDesqueeze: " + file.getAbsolutePath() + " factor: " + desqueezeFactor);

        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            byte[] allData = new byte[(int) raf.length()];
            raf.readFully(allData);

            byte[] modified = applyDesqueezeToBytes(allData, desqueezeFactor);

            raf.seek(0);
            raf.setLength(modified.length);
            raf.write(modified);
        }
        finally {
            raf.close();
        }
    }

    /** Applies desqueeze tags to raw DNG byte data.
     *  @param data The DNG file data.
     *  @param desqueezeFactor The desqueeze factor (1.33f or 1.55f).
     *  @return The modified DNG data (may be larger if PixelAspectRatio tag was added).
     *  @throws IOException if the data is not a valid DNG/TIFF file.
     */
    public static byte[] applyDesqueezeToBytes(byte[] data, float desqueezeFactor) throws IOException {
        if( MyDebug.LOG )
            Log.d(TAG, "applyDesqueezeToBytes, size: " + data.length + " factor: " + desqueezeFactor);

        // Determine byte order from header
        ByteOrder byteOrder;
        if( data[0] == 'I' && data[1] == 'I' ) {
            byteOrder = ByteOrder.LITTLE_ENDIAN;
        }
        else if( data[0] == 'M' && data[1] == 'M' ) {
            byteOrder = ByteOrder.BIG_ENDIAN;
        }
        else {
            throw new IOException("Invalid TIFF header");
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(byteOrder);

        buf.position(2);
        int magic = buf.getShort() & 0xFFFF;
        if( magic != 42 ) {
            throw new IOException("Invalid TIFF magic number: " + magic);
        }

        int ifdOffset = buf.getInt();
        if( MyDebug.LOG )
            Log.d(TAG, "IFD offset: " + ifdOffset);

        buf.position(ifdOffset);
        int numEntries = buf.getShort() & 0xFFFF;
        if( MyDebug.LOG )
            Log.d(TAG, "IFD entries: " + numEntries);

        // First pass: read all IFD entries to find tags
        int imageWidth = 0;
        int imageLength = 0;
        int defaultCropOriginOffset = -1;
        int defaultCropSizeOffset = -1;
        int pixelAspectRatioOffset = -1;

        for( int i = 0; i < numEntries; i++ ) {
            int entryPos = ifdOffset + 2 + (i * TAG_SIZE);
            buf.position(entryPos);

            int tagId = buf.getShort() & 0xFFFF;
            int type = buf.getShort() & 0xFFFF;
            int count = buf.getInt();
            int valueOrOffset = buf.getInt();

            boolean isInline = ((long) count * typeSize(type)) <= 4;
            int dataOffset = isInline ? -1 : valueOrOffset;

            if( tagId == TAG_IMAGE_WIDTH ) {
                imageWidth = isInline ? valueOrOffset : readIntValue(buf, dataOffset, type);
                if( MyDebug.LOG )
                    Log.d(TAG, "ImageWidth: " + imageWidth);
            }
            else if( tagId == TAG_IMAGE_LENGTH ) {
                imageLength = isInline ? valueOrOffset : readIntValue(buf, dataOffset, type);
                if( MyDebug.LOG )
                    Log.d(TAG, "ImageLength: " + imageLength);
            }
            else if( tagId == TAG_DEFAULT_CROP_ORIGIN ) {
                defaultCropOriginOffset = dataOffset;
                if( MyDebug.LOG )
                    Log.d(TAG, "DefaultCropOrigin at data offset: " + dataOffset);
            }
            else if( tagId == TAG_DEFAULT_CROP_SIZE ) {
                defaultCropSizeOffset = dataOffset;
                if( MyDebug.LOG )
                    Log.d(TAG, "DefaultCropSize at data offset: " + dataOffset);
            }
            else if( tagId == TAG_PIXEL_ASPECT_RATIO ) {
                pixelAspectRatioOffset = dataOffset;
                if( MyDebug.LOG )
                    Log.d(TAG, "PixelAspectRatio at data offset: " + dataOffset);
            }
        }

        if( imageWidth == 0 || imageLength == 0 ) {
            throw new IOException("Could not read ImageWidth/ImageLength from DNG");
        }

        int desqueezedWidth = (int)(imageWidth * desqueezeFactor);
        if( MyDebug.LOG ) {
            Log.d(TAG, "original dimensions: " + imageWidth + " x " + imageLength);
            Log.d(TAG, "desqueezed width: " + desqueezedWidth);
        }

        int[] rational = floatToRational(desqueezeFactor);
        byte[] result = data;
        int shift = 0; // how much data shifted due to IFD entry insertion

        if( pixelAspectRatioOffset < 0 ) {
            // Need to add PixelAspectRatio tag to the IFD
            // Append the rational value at the end of the file
            int newValueOffset = data.length;
            shift = TAG_SIZE; // new IFD entry shifts everything after insertion point

            int newLength = data.length + TAG_SIZE + 8; // TAG_SIZE for IFD entry + 8 for rational value
            byte[] newData = new byte[newLength];
            System.arraycopy(data, 0, newData, 0, data.length);

            buf = ByteBuffer.wrap(newData).order(byteOrder);

            // Find insertion point (keep IFD sorted by tag ID)
            int insertIndex = numEntries;
            for( int i = 0; i < numEntries; i++ ) {
                int entryStart = ifdOffset + 2 + (i * TAG_SIZE);
                int existingTag = ((newData[entryStart] & 0xFF) << 8) | (newData[entryStart + 1] & 0xFF);
                if( TAG_PIXEL_ASPECT_RATIO < existingTag ) {
                    insertIndex = i;
                    break;
                }
            }
            int insertPos = ifdOffset + 2 + (insertIndex * TAG_SIZE);

            // Shift bytes from insertPos to end by TAG_SIZE
            int dataToShift = data.length - insertPos;
            System.arraycopy(newData, insertPos, newData, insertPos + TAG_SIZE, dataToShift);

            // Write new IFD entry
            buf.position(insertPos);
            buf.putShort((short) TAG_PIXEL_ASPECT_RATIO);
            buf.putShort((short) TYPE_RATIONAL);
            buf.putInt(1);
            buf.putInt(newValueOffset);

            // Update IFD entry count
            buf.position(ifdOffset);
            buf.putShort((short) (numEntries + 1));

            // Write the rational value at the end
            buf.position(newValueOffset);
            buf.putInt(rational[0]);
            buf.putInt(rational[1]);

            // Fix up all tag data offsets that were shifted
            // Scan all IFD entries and adjust offsets
            for( int i = 0; i < numEntries + 1; i++ ) {
                int entryPos = ifdOffset + 2 + (i * TAG_SIZE);
                buf.position(entryPos);
                buf.getShort(); // tag
                int eType = buf.getShort() & 0xFFFF;
                int eCount = buf.getInt();
                int eValueOrOffset = buf.getInt();

                boolean eInline = ((long) eCount * typeSize(eType)) <= 4;
                if( !eInline && eValueOrOffset >= insertPos ) {
                    // This offset points to shifted data; adjust it
                    buf.position(entryPos + 8);
                    buf.putInt(eValueOrOffset + shift);
                    if( MyDebug.LOG )
                        Log.d(TAG, "Fixed up offset for tag at entry " + i + ": " + eValueOrOffset + " -> " + (eValueOrOffset + shift));
                }
            }

            // Also fix up the next-IFD-offset if it's nonzero and at the old position
            // (it's at ifdOffset + 2 + numEntries * TAG_SIZE in the old data, now at insertPos + TAG_SIZE if inserted at end)
            // Actually, we shifted everything from insertPos, so the next-IFD-offset is now at the right place.
            // Its value doesn't need adjustment since it's a file offset that was already correct.

            result = newData;

            if( MyDebug.LOG )
                Log.d(TAG, "Added PixelAspectRatio tag with value " + rational[0] + "/" + rational[1]);
        }
        else {
            // Tag exists, just update its value
            buf = ByteBuffer.wrap(data).order(byteOrder);
            buf.position(pixelAspectRatioOffset);
            buf.putInt(rational[0]);
            buf.putInt(rational[1]);
            if( MyDebug.LOG )
                Log.d(TAG, "Set PixelAspectRatio to " + rational[0] + "/" + rational[1]);
        }

        // Now modify DefaultCropOrigin and DefaultCropSize (offsets may have shifted)
        if( defaultCropOriginOffset >= 0 ) {
            int adjustedOffset = defaultCropOriginOffset + shift;
            buf = ByteBuffer.wrap(result).order(byteOrder);
            buf.position(adjustedOffset);
            buf.putInt(0); // x numerator
            buf.putInt(1); // x denominator
            buf.putInt(0); // y numerator
            buf.putInt(1); // y denominator
            if( MyDebug.LOG )
                Log.d(TAG, "Set DefaultCropOrigin to (0, 0)");
        }

        if( defaultCropSizeOffset >= 0 ) {
            int adjustedOffset = defaultCropSizeOffset + shift;
            buf = ByteBuffer.wrap(result).order(byteOrder);
            buf.position(adjustedOffset);
            buf.putInt(desqueezedWidth); // width numerator
            buf.putInt(1);               // width denominator
            buf.putInt(imageLength);     // height numerator
            buf.putInt(1);               // height denominator
            if( MyDebug.LOG )
                Log.d(TAG, "Set DefaultCropSize to (" + desqueezedWidth + ", " + imageLength + ")");
        }

        return result;
    }

    private static int readIntValue(ByteBuffer buf, int offset, int type) {
        int pos = buf.position();
        buf.position(offset);
        int value;
        if( type == TYPE_SHORT ) {
            value = buf.getShort() & 0xFFFF;
        }
        else {
            value = buf.getInt();
        }
        buf.position(pos);
        return value;
    }

    private static int[] floatToRational(float value) {
        if( Math.abs(value - 1.33f) < 0.001f ) {
            return new int[]{4, 3};
        }
        else if( Math.abs(value - 1.55f) < 0.001f ) {
            return new int[]{31, 20};
        }
        int denominator = 1000;
        int numerator = Math.round(value * denominator);
        int gcd = gcd(numerator, denominator);
        return new int[]{numerator / gcd, denominator / gcd};
    }

    private static int gcd(int a, int b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while( b != 0 ) {
            int temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }
}
