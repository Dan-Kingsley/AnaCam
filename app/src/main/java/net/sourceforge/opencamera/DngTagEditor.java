package net.sourceforge.opencamera;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Post-processes a DNG/TIFF file to add a PixelAspectRatio tag for anamorphic desqueeze.
 *  Both the TIFF tag (50289) and the embedded XMP metadata (tag 700) are updated so that
 *  viewers which prefer XMP over TIFF tags will also honour the aspect ratio.
 */
public class DngTagEditor {
    private static final String TAG = "DngTagEditor";

    private static final int TAG_IMAGE_WIDTH = 256;
    private static final int TAG_IMAGE_LENGTH = 257;
    private static final int TAG_XMP = 700;
    private static final int TAG_PIXEL_ASPECT_RATIO = 50289;

    private static final int TYPE_BYTE = 1;
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
     *  Updates both the PixelAspectRatio TIFF tag (50289) and the embedded XMP metadata (tag 700).
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

        int oldIfdSize = 2 + numEntries * IFD_ENTRY_SIZE + 4;

        int imageWidth = 0;
        int imageLength = 0;
        int pixelAspectRatioEntryIndex = -1;
        int xmpEntryIndex = -1;

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
            else if( tagId == TAG_XMP ) {
                xmpEntryIndex = i;
                if( MyDebug.LOG )
                    Log.d(TAG, "XMP tag 700 found at entry " + i);
            }
        }

        if( imageWidth == 0 || imageLength == 0 ) {
            throw new IOException("Could not read ImageWidth/ImageLength from DNG");
        }

        int[] rational = floatToRational(desqueezeFactor);

        // Phase 1: Read and modify the XMP data if present
        byte[] modifiedXmp = null;
        int xmpDataOffset = -1;
        int xmpDataCount = 0;

        if( xmpEntryIndex >= 0 ) {
            int entryPos = ifdOffset + 2 + (xmpEntryIndex * IFD_ENTRY_SIZE);
            buf.position(entryPos + 4);
            xmpDataCount = buf.getInt();
            xmpDataOffset = buf.getInt();
            if( xmpDataOffset > 0 && xmpDataOffset + xmpDataCount <= data.length ) {
                byte[] xmpBytes = new byte[xmpDataCount];
                buf.position(xmpDataOffset);
                buf.get(xmpBytes);
                modifiedXmp = modifyXmpForDesqueeze(xmpBytes, desqueezeFactor);
                if( MyDebug.LOG )
                    Log.d(TAG, "XMP modified: " + xmpDataCount + " -> " + modifiedXmp.length + " bytes");
            }
        }

        int xmpSizeDelta = (modifiedXmp != null) ? (modifiedXmp.length - xmpDataCount) : 0;
        int ifdEntryDelta = (pixelAspectRatioEntryIndex < 0) ? IFD_ENTRY_SIZE : 0;
        int totalShift = ifdEntryDelta + xmpSizeDelta;

        // If the tag already exists and XMP didn't change size, update in place
        if( pixelAspectRatioEntryIndex >= 0 && totalShift == 0 ) {
            int entryPos = ifdOffset + 2 + (pixelAspectRatioEntryIndex * IFD_ENTRY_SIZE);
            buf.position(entryPos + 8);
            int dataOffset = buf.getInt();
            buf.position(dataOffset);
            buf.putInt(rational[0]);
            buf.putInt(rational[1]);
            if( MyDebug.LOG )
                Log.d(TAG, "Updated PixelAspectRatio to " + rational[0] + "/" + rational[1] + " at offset " + dataOffset);

            // Update XMP in place if same size
            if( modifiedXmp != null && xmpSizeDelta == 0 ) {
                buf.position(xmpDataOffset);
                buf.put(modifiedXmp);
                if( MyDebug.LOG )
                    Log.d(TAG, "Updated XMP in place at offset " + xmpDataOffset);
            }
            return data;
        }

        // Need to rebuild the file
        int newIfdSize = 2 + (numEntries + (pixelAspectRatioEntryIndex < 0 ? 1 : 0)) * IFD_ENTRY_SIZE + 4;
        int rationalDataOffset = data.length + totalShift;
        int newFileSize = data.length + totalShift + 8;

        byte[] result = new byte[newFileSize];
        ByteBuffer resultBuf = ByteBuffer.wrap(result).order(byteOrder);

        // 1. Copy header (unchanged)
        System.arraycopy(data, 0, result, 0, TIFF_HEADER_SIZE);

        // 2. Find insertion point in IFD (sorted by tag ID)
        int insertIndex = numEntries;
        if( pixelAspectRatioEntryIndex < 0 ) {
            for( int i = 0; i < numEntries; i++ ) {
                int entryPos = ifdOffset + 2 + (i * IFD_ENTRY_SIZE);
                int existingTag = buf.getShort(entryPos) & 0xFFFF;
                if( TAG_PIXEL_ASPECT_RATIO < existingTag ) {
                    insertIndex = i;
                    break;
                }
            }
        }
        int insertPos = ifdOffset + 2 + (insertIndex * IFD_ENTRY_SIZE);
        if( MyDebug.LOG )
            Log.d(TAG, "Inserting PixelAspectRatio at IFD index " + insertIndex + ", position " + insertPos);

        // 3. Copy data before the insertion point (unchanged)
        System.arraycopy(data, 0, result, 0, insertPos);

        // 4. Write new PixelAspectRatio IFD entry (if needed)
        if( pixelAspectRatioEntryIndex < 0 ) {
            resultBuf.position(insertPos);
            resultBuf.putShort((short) TAG_PIXEL_ASPECT_RATIO);
            resultBuf.putShort((short) TYPE_RATIONAL);
            resultBuf.putInt(1);
            resultBuf.putInt(rationalDataOffset);
        }

        // 5. Copy original IFD entries after insertion point + next-ifd-offset
        int afterInsertSrc = insertPos;
        int afterInsertDst = insertPos + ifdEntryDelta;
        int afterInsertLen = (ifdOffset + oldIfdSize) - afterInsertSrc;
        System.arraycopy(data, afterInsertSrc, result, afterInsertDst, afterInsertLen);

        // 6. Update IFD entry count
        if( pixelAspectRatioEntryIndex < 0 ) {
            resultBuf.position(ifdOffset);
            resultBuf.putShort((short) (numEntries + 1));
        }

        // 7. Copy everything after the old IFD, inserting modified XMP at the correct position
        int afterIfdSrc = ifdOffset + oldIfdSize;
        int afterIfdDst = ifdOffset + newIfdSize;

        if( modifiedXmp != null && xmpDataOffset >= afterIfdSrc ) {
            // Data before XMP
            int xmpRelativeOffset = xmpDataOffset - afterIfdSrc;
            if( xmpRelativeOffset > 0 ) {
                System.arraycopy(data, afterIfdSrc, result, afterIfdDst, xmpRelativeOffset);
            }

            // Write modified XMP at the shifted position
            int xmpDstOffset = afterIfdDst + xmpRelativeOffset;
            resultBuf.position(xmpDstOffset);
            resultBuf.put(modifiedXmp);
            if( MyDebug.LOG )
                Log.d(TAG, "Wrote modified XMP at offset " + xmpDstOffset + " (" + modifiedXmp.length + " bytes)");

            // Update the XMP tag entry's count and offset in the result IFD
            if( xmpEntryIndex >= 0 ) {
                int newXmpEntryIndex = xmpEntryIndex + (xmpEntryIndex >= insertIndex && pixelAspectRatioEntryIndex < 0 ? 1 : 0);
                int xmpEntryPos = ifdOffset + 2 + (newXmpEntryIndex * IFD_ENTRY_SIZE);
                resultBuf.position(xmpEntryPos + 4);
                resultBuf.putInt(modifiedXmp.length);
            }

            // Data after XMP
            int afterXmpSrc = xmpDataOffset + xmpDataCount;
            int afterXmpDst = xmpDstOffset + modifiedXmp.length;
            int afterXmpLen = data.length - afterXmpSrc;
            if( afterXmpLen > 0 ) {
                System.arraycopy(data, afterXmpSrc, result, afterXmpDst, afterXmpLen);
            }
        }
        else {
            // No XMP modification, just copy everything
            int afterIfdLen = data.length - afterIfdSrc;
            if( afterIfdLen > 0 ) {
                System.arraycopy(data, afterIfdSrc, result, afterIfdDst, afterIfdLen);
            }
        }

        // 8. Write the rational value at the end
        resultBuf.position(rationalDataOffset);
        resultBuf.putInt(rational[0]);
        resultBuf.putInt(rational[1]);
        if( MyDebug.LOG )
            Log.d(TAG, "Wrote PixelAspectRatio " + rational[0] + "/" + rational[1] + " at offset " + rationalDataOffset);

        // 9. Fix up offsets in the main IFD entries
        for( int i = 0; i < numEntries + (pixelAspectRatioEntryIndex < 0 ? 1 : 0); i++ ) {
            int entryPos = ifdOffset + 2 + (i * IFD_ENTRY_SIZE);
            int tagId = resultBuf.getShort(entryPos) & 0xFFFF;
            int type = resultBuf.getShort(entryPos + 2) & 0xFFFF;
            int count = resultBuf.getInt(entryPos + 4);
            int offsetVal = resultBuf.getInt(entryPos + 8);

            if( tagId == TAG_PIXEL_ASPECT_RATIO ) {
                continue;
            }

            // Calculate the effective shift for this offset
            int effectiveShift = totalShift;
            if( modifiedXmp != null && xmpDataOffset >= afterIfdSrc && offsetVal > xmpDataOffset ) {
                // Offset points past the XMP data — include the XMP size delta
                effectiveShift = totalShift;
            }

            if( !isInline(type, count) && offsetVal >= insertPos ) {
                resultBuf.position(entryPos + 8);
                resultBuf.putInt(offsetVal + effectiveShift);
                if( MyDebug.LOG )
                    Log.d(TAG, "Fixed up offset for tag " + tagId + ": " + offsetVal + " -> " + (offsetVal + effectiveShift));
            }
        }

        // 10. Fix up sub-IFD offsets
        for( int i = 0; i < numEntries + (pixelAspectRatioEntryIndex < 0 ? 1 : 0); i++ ) {
            int entryPos = ifdOffset + 2 + (i * IFD_ENTRY_SIZE);
            int tagId = resultBuf.getShort(entryPos) & 0xFFFF;
            int count = resultBuf.getInt(entryPos + 4);
            int valueOrOffset = resultBuf.getInt(entryPos + 8);

            int[] subIFDOffsets = null;

            if( tagId == 330 ) { // SubIFD
                if( count == 1 ) {
                    // Inline pointer — step 9 did NOT shift it (isInline returns true)
                    if( valueOrOffset >= insertPos ) {
                        resultBuf.position(entryPos + 8);
                        resultBuf.putInt(valueOrOffset + totalShift);
                        if( MyDebug.LOG )
                            Log.d(TAG, "Fixed inline SubIFD pointer: " + valueOrOffset + " -> " + (valueOrOffset + totalShift));
                        subIFDOffsets = new int[]{ valueOrOffset + totalShift };
                    }
                    else {
                        subIFDOffsets = new int[]{ valueOrOffset };
                    }
                }
                else if( count > 1 ) {
                    // Non-inline: step 9 already shifted the array pointer
                    int arrayOffset = valueOrOffset;
                    subIFDOffsets = new int[count];
                    for( int j = 0; j < count; j++ ) {
                        int subOffset = resultBuf.getInt(arrayOffset + j * 4);
                        if( subOffset >= insertPos ) {
                            int newSubOffset = subOffset + totalShift;
                            resultBuf.putInt(arrayOffset + j * 4, newSubOffset);
                            subIFDOffsets[j] = newSubOffset;
                            if( MyDebug.LOG )
                                Log.d(TAG, "Fixed SubIFD[" + j + "] offset: " + subOffset + " -> " + newSubOffset);
                        }
                        else {
                            subIFDOffsets[j] = subOffset;
                        }
                    }
                }
            }
            else if( tagId == 34665 || tagId == 34853 || tagId == 34856 ) { // ExifIFD / GPSInfo / InteropIFD
                if( valueOrOffset >= insertPos ) {
                    resultBuf.position(entryPos + 8);
                    resultBuf.putInt(valueOrOffset + totalShift);
                    if( MyDebug.LOG )
                        Log.d(TAG, "Fixed inline " + (tagId == 34665 ? "ExifIFD" : tagId == 34853 ? "GPSInfo" : "InteropIFD") + " pointer: "
                            + valueOrOffset + " -> " + (valueOrOffset + totalShift));
                    subIFDOffsets = new int[]{ valueOrOffset + totalShift };
                }
                else {
                    subIFDOffsets = new int[]{ valueOrOffset };
                }
            }

            if( subIFDOffsets != null ) {
                for( int subOffset : subIFDOffsets ) {
                    if( subOffset < TIFF_HEADER_SIZE || subOffset + 2 >= result.length ) {
                        continue;
                    }
                    int subNumEntries = resultBuf.getShort(subOffset) & 0xFFFF;
                    if( subNumEntries > 0 && subOffset + 2 + subNumEntries * IFD_ENTRY_SIZE + 4 <= result.length ) {
                        fixupSubIFDEntries(resultBuf, subOffset, subNumEntries, insertPos, totalShift);
                    }
                }
            }
        }

        if( MyDebug.LOG )
            Log.d(TAG, "Rebuilt DNG with PixelAspectRatio and XMP, new size: " + newFileSize);

        return result;
    }

    /** Modifies the XMP packet to add or update aux:PixelAspectRatio for anamorphic desqueeze.
     *  @return Modified XMP bytes (may be different length from input).
     */
    private static byte[] modifyXmpForDesqueeze(byte[] xmpBytes, float desqueezeFactor) {
        String xmp = new String(xmpBytes, StandardCharsets.UTF_8);
        int[] rational = floatToRational(desqueezeFactor);
        String newValue = rational[0] + "/" + rational[1];

        // Try to find and replace existing aux:PixelAspectRatio attribute
        // Format: aux:PixelAspectRatio="1/1" or aux:PixelAspectRatio="4/3"
        String attrPattern = "aux:PixelAspectRatio=\"";
        int attrIdx = xmp.indexOf(attrPattern);
        if( attrIdx >= 0 ) {
            int valueStart = attrIdx + attrPattern.length();
            int valueEnd = xmp.indexOf('"', valueStart);
            if( valueEnd > valueStart ) {
                String newXmp = xmp.substring(0, valueStart) + newValue + xmp.substring(valueEnd);
                if( MyDebug.LOG )
                    Log.d(TAG, "Updated XMP aux:PixelAspectRatio attribute to " + newValue);
                return newXmp.getBytes(StandardCharsets.UTF_8);
            }
        }

        // Try to find and replace existing aux:PixelAspectRatio element
        // Format: <aux:PixelAspectRatio>1/1</aux:PixelAspectRatio>
        String elemOpen = "<aux:PixelAspectRatio>";
        String elemClose = "</aux:PixelAspectRatio>";
        int elemIdx = xmp.indexOf(elemOpen);
        if( elemIdx >= 0 ) {
            int valueStart = elemIdx + elemOpen.length();
            int valueEnd = xmp.indexOf(elemClose, valueStart);
            if( valueEnd > valueStart ) {
                String newXmp = xmp.substring(0, valueStart) + newValue + xmp.substring(valueEnd);
                if( MyDebug.LOG )
                    Log.d(TAG, "Updated XMP aux:PixelAspectRatio element to " + newValue);
                return newXmp.getBytes(StandardCharsets.UTF_8);
            }
        }

        // Not found — add aux:PixelAspectRatio as an attribute on the first rdf:Description
        String descTag = "<rdf:Description";
        int descIdx = xmp.indexOf(descTag);
        if( descIdx >= 0 ) {
            // Check if there's already a closing '>' for this tag
            int tagEnd = xmp.indexOf('>', descIdx);
            if( tagEnd > descIdx ) {
                // Insert the attribute before the closing '>'
                String attr = "\n    xmlns:aux=\"http://ns.adobe.com/exif/1.0/\"\n    aux:PixelAspectRatio=\"" + newValue + "\"";
                String newXmp = xmp.substring(0, tagEnd) + attr + xmp.substring(tagEnd);
                if( MyDebug.LOG )
                    Log.d(TAG, "Added XMP aux:PixelAspectRatio attribute to " + newValue);
                return newXmp.getBytes(StandardCharsets.UTF_8);
            }
        }

        // Fallback: add as a new element inside rdf:RDF
        String rdfClose = "</rdf:RDF>";
        int rdfCloseIdx = xmp.indexOf(rdfClose);
        if( rdfCloseIdx >= 0 ) {
            String newElement = "  <rdf:Description xmlns:aux=\"http://ns.adobe.com/exif/1.0/\">\n" +
                "    <aux:PixelAspectRatio>" + newValue + "</aux:PixelAspectRatio>\n" +
                "  </rdf:Description>\n";
            String newXmp = xmp.substring(0, rdfCloseIdx) + newElement + xmp.substring(rdfCloseIdx);
            if( MyDebug.LOG )
                Log.d(TAG, "Added XMP aux:PixelAspectRatio element to " + newValue);
            return newXmp.getBytes(StandardCharsets.UTF_8);
        }

        // Last resort: return original unchanged
        if( MyDebug.LOG )
            Log.d(TAG, "Could not find suitable location in XMP to add PixelAspectRatio");
        return xmpBytes;
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

    private static void fixupSubIFDEntries(ByteBuffer buf, int ifdOffset, int numEntries,
                                            int insertPos, int shift) {
        for( int i = 0; i < numEntries; i++ ) {
            int entryPos = ifdOffset + 2 + (i * IFD_ENTRY_SIZE);
            int tagId = buf.getShort(entryPos) & 0xFFFF;
            int type = buf.getShort(entryPos + 2) & 0xFFFF;
            int count = buf.getInt(entryPos + 4);
            int offsetVal = buf.getInt(entryPos + 8);

            if( !isInline(type, count) && offsetVal >= insertPos ) {
                buf.position(entryPos + 8);
                buf.putInt(offsetVal + shift);
                if( MyDebug.LOG )
                    Log.d(TAG, "Fixed up sub-IFD offset for tag " + tagId + ": " + offsetVal + " -> " + (offsetVal + shift));
            }
        }
    }
}
