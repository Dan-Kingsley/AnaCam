package net.sourceforge.opencamera;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Post-processes a DNG/TIFF file to add a PixelAspectRatio tag for anamorphic desqueeze.
 *  The PixelAspectRatio tag tells RAW processing software to stretch the image horizontally
 *  by the given factor, effectively desqueezing it.
 */
public class DngTagEditor {
    private static final String TAG = "DngTagEditor";

    private static final int TAG_IMAGE_WIDTH = 256;
    private static final int TAG_IMAGE_LENGTH = 257;
    private static final int TAG_PIXEL_ASPECT_RATIO = 50289;

    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_RATIONAL = 5;

    private static final int IFD_ENTRY_SIZE = 12;
    private static final int TIFF_HEADER_SIZE = 8;

    private static int typeSize(int type) {
        switch( type ) {
            case 1: return 1; // BYTE
            case 2: return 1; // ASCII
            case 3: return 2; // SHORT
            case 4: return 4; // LONG
            case 5: return 8; // SRATIONAL
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

    private static long valueSize(int type, int count) {
        return (long) count * typeSize(type);
    }

    private static boolean isInline(int type, int count) {
        return valueSize(type, count) <= 4;
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
     *  @return The modified DNG data.
     *  @throws IOException if the data is not a valid DNG/TIFF file.
     */
    public static byte[] applyDesqueezeToBytes(byte[] data, float desqueezeFactor) throws IOException {
        if( MyDebug.LOG )
            Log.d(TAG, "applyDesqueezeToBytes, size: " + data.length + " factor: " + desqueezeFactor);

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

        int oldIfdSize = 2 + numEntries * IFD_ENTRY_SIZE + 4; // count + entries + next-ifd-offset

        int imageWidth = 0;
        int imageLength = 0;
        int pixelAspectRatioEntryIndex = -1;

        for( int i = 0; i < numEntries; i++ ) {
            int entryPos = ifdOffset + 2 + (i * IFD_ENTRY_SIZE);
            buf.position(entryPos);

            int tagId = buf.getShort() & 0xFFFF;
            int type = buf.getShort() & 0xFFFF;
            int count = buf.getInt();
            int valueOrOffset = buf.getInt();

            if( tagId == TAG_IMAGE_WIDTH ) {
                imageWidth = isInline(type, count) ? valueOrOffset : readIntFromOffset(buf, valueOrOffset, type);
                if( MyDebug.LOG )
                    Log.d(TAG, "ImageWidth: " + imageWidth);
            }
            else if( tagId == TAG_IMAGE_LENGTH ) {
                imageLength = isInline(type, count) ? valueOrOffset : readIntFromOffset(buf, valueOrOffset, type);
                if( MyDebug.LOG )
                    Log.d(TAG, "ImageLength: " + imageLength);
            }
            else if( tagId == TAG_PIXEL_ASPECT_RATIO ) {
                pixelAspectRatioEntryIndex = i;
                if( MyDebug.LOG )
                    Log.d(TAG, "PixelAspectRatio already exists at entry " + i);
            }
        }

        if( imageWidth == 0 || imageLength == 0 ) {
            throw new IOException("Could not read ImageWidth/ImageLength from DNG");
        }

        int[] rational = floatToRational(desqueezeFactor);

        if( pixelAspectRatioEntryIndex >= 0 ) {
            // Tag already exists — update its value in place (8 bytes at the data offset)
            int entryPos = ifdOffset + 2 + (pixelAspectRatioEntryIndex * IFD_ENTRY_SIZE);
            buf.position(entryPos + 8);
            int dataOffset = buf.getInt();
            // dataOffset is where the 8-byte RATIONAL value lives in the file
            buf.position(dataOffset);
            buf.putInt(rational[0]);
            buf.putInt(rational[1]);
            if( MyDebug.LOG )
                Log.d(TAG, "Updated PixelAspectRatio to " + rational[0] + "/" + rational[1] + " at offset " + dataOffset);
            return data;
        }

        // PixelAspectRatio tag doesn't exist — need to add it to the IFD.
        // Rebuild the file: insert a new IFD entry and append the rational value at the end.

        int newIfdSize = 2 + (numEntries + 1) * IFD_ENTRY_SIZE + 4;
        int shift = newIfdSize - oldIfdSize; // always 12
        int rationalDataOffset = data.length + shift; // where the new rational value will be in the new file
        int newFileSize = data.length + shift + 8;

        byte[] result = new byte[newFileSize];
        ByteBuffer resultBuf = ByteBuffer.wrap(result).order(byteOrder);

        // 1. Copy header (unchanged)
        System.arraycopy(data, 0, result, 0, TIFF_HEADER_SIZE);

        // 2. Find insertion point in IFD (sorted by tag ID)
        int insertIndex = numEntries;
        for( int i = 0; i < numEntries; i++ ) {
            int entryPos = ifdOffset + 2 + (i * IFD_ENTRY_SIZE);
            int existingTag = buf.getShort(entryPos) & 0xFFFF;
            if( TAG_PIXEL_ASPECT_RATIO < existingTag ) {
                insertIndex = i;
                break;
            }
        }
        int insertPos = ifdOffset + 2 + (insertIndex * IFD_ENTRY_SIZE);
        if( MyDebug.LOG )
            Log.d(TAG, "Inserting PixelAspectRatio at IFD index " + insertIndex + ", position " + insertPos);

        // 3. Copy data before the insertion point (unchanged)
        System.arraycopy(data, 0, result, 0, insertPos);

        // 4. Write new PixelAspectRatio IFD entry
        resultBuf.position(insertPos);
        resultBuf.putShort((short) TAG_PIXEL_ASPECT_RATIO);
        resultBuf.putShort((short) TYPE_RATIONAL);
        resultBuf.putInt(1); // count = 1 rational
        resultBuf.putInt(rationalDataOffset);

        // 5. Copy original IFD entries after insertion point + next-ifd-offset
        //    These go at insertPos + IFD_ENTRY_SIZE in the new file
        int afterInsertSrc = insertPos;
        int afterInsertDst = insertPos + IFD_ENTRY_SIZE;
        int afterInsertLen = (ifdOffset + oldIfdSize) - afterInsertSrc;
        System.arraycopy(data, afterInsertSrc, result, afterInsertDst, afterInsertLen);

        // 6. Update IFD entry count
        resultBuf.position(ifdOffset);
        resultBuf.putShort((short) (numEntries + 1));

        // 7. Copy everything after the old IFD (tag data, sub-IFDs, image data)
        //    These are shifted right by 'shift' bytes
        int afterIfdSrc = ifdOffset + oldIfdSize;
        int afterIfdDst = ifdOffset + newIfdSize;
        int afterIfdLen = data.length - afterIfdSrc;
        System.arraycopy(data, afterIfdSrc, result, afterIfdDst, afterIfdLen);

        // 8. Write the rational value at the end
        resultBuf.position(rationalDataOffset);
        resultBuf.putInt(rational[0]);
        resultBuf.putInt(rational[1]);
        if( MyDebug.LOG )
            Log.d(TAG, "Wrote PixelAspectRatio " + rational[0] + "/" + rational[1] + " at offset " + rationalDataOffset);

        // 9. Fix up offsets in the new IFD entries
        //    Any offset that pointed to data at or after the insertion point needs +shift
        //    (because everything at or after insertPos was shifted right by 'shift' bytes)
        for( int i = 0; i < numEntries + 1; i++ ) {
            int entryPos = ifdOffset + 2 + (i * IFD_ENTRY_SIZE);
            int tagId = resultBuf.getShort(entryPos) & 0xFFFF;
            int type = resultBuf.getShort(entryPos + 2) & 0xFFFF;
            int count = resultBuf.getInt(entryPos + 4);
            int offsetVal = resultBuf.getInt(entryPos + 8);

            if( tagId == TAG_PIXEL_ASPECT_RATIO ) {
                // This is our new entry — its offset was set to the correct position
                continue;
            }

            if( !isInline(type, count) && offsetVal >= insertPos ) {
                resultBuf.position(entryPos + 8);
                resultBuf.putInt(offsetVal + shift);
                if( MyDebug.LOG )
                    Log.d(TAG, "Fixed up offset for tag " + tagId + ": " + offsetVal + " -> " + (offsetVal + shift));
            }
        }

        if( MyDebug.LOG )
            Log.d(TAG, "Rebuilt DNG with PixelAspectRatio, new size: " + newFileSize);

        return result;
    }

    private static int readIntFromOffset(ByteBuffer buf, int offset, int type) {
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
