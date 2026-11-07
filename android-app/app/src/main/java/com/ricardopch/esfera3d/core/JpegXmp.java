package com.ricardopch.esfera3d.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Añade a un JPEG los metadatos XMP "GPano" para que Google Fotos y otros visores
 * reconozcan la imagen como foto esférica de 360°.
 */
public final class JpegXmp {
    private JpegXmp() {}

    public static String gpano(int w, int h, String extra) {
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Esfera 3D\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\""
                + " GPano:UsePanoramaViewer=\"True\""
                + " GPano:CaptureSoftware=\"Esfera 3D\""
                + " GPano:StitchingSoftware=\"Esfera 3D\""
                + " GPano:ProjectionType=\"equirectangular\""
                + " GPano:PoseHeadingDegrees=\"0.0\""
                + " GPano:InitialViewHeadingDegrees=\"0\""
                + " GPano:InitialViewPitchDegrees=\"0\""
                + " GPano:InitialViewRollDegrees=\"0\""
                + " GPano:InitialHorizontalFOVDegrees=\"75.0\""
                + " GPano:CroppedAreaLeftPixels=\"0\""
                + " GPano:CroppedAreaTopPixels=\"0\""
                + " GPano:CroppedAreaImageWidthPixels=\"" + w + "\""
                + " GPano:CroppedAreaImageHeightPixels=\"" + h + "\""
                + " GPano:FullPanoWidthPixels=\"" + w + "\""
                + " GPano:FullPanoHeightPixels=\"" + h + "\""
                + (extra == null ? "" : extra)
                + "/></rdf:RDF></x:xmpmeta>";
    }

    /** Inserta el bloque XMP (segmento APP1) tras SOI y, si existe, tras el APP0 (JFIF). */
    public static byte[] insertXmp(byte[] jpeg, String xmp) {
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) return jpeg;
        byte[] ns = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = xmp.getBytes(StandardCharsets.UTF_8);
        int segLen = 2 + ns.length + body.length;
        if (segLen > 65535) return jpeg;
        int pos = 2;
        if ((jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0 && jpeg.length > 6) {
            int len = ((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF);
            pos = 4 + len;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + segLen + 2);
        out.write(jpeg, 0, pos);
        out.write(0xFF);
        out.write(0xE1);
        out.write((segLen >> 8) & 0xFF);
        out.write(segLen & 0xFF);
        out.write(ns, 0, ns.length);
        out.write(body, 0, body.length);
        out.write(jpeg, pos, jpeg.length - pos);
        return out.toByteArray();
    }
}
