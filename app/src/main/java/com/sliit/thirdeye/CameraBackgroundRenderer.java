package com.sliit.thirdeye;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Minimal OpenGL ES 2 renderer that draws the ARCore camera image full screen.
 * Use only on the GL thread.
 */
final class CameraBackgroundRenderer {

    private static final String TAG = "CameraBackground";

    private static final String VERTEX_SHADER =
            "attribute vec4 a_Position;\n"
                    + "attribute vec2 a_TexCoord;\n"
                    + "varying vec2 v_TexCoord;\n"
                    + "void main() {\n"
                    + "  gl_Position = a_Position;\n"
                    + "  v_TexCoord = a_TexCoord;\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 v_TexCoord;\n"
                    + "uniform samplerExternalOES u_Texture;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = texture2D(u_Texture, v_TexCoord);\n"
                    + "}\n";

    /** Full-screen quad as a triangle strip, in normalised device coordinates. */
    private static final float[] QUAD = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};

    private final FloatBuffer quadCoords = floatBuffer(QUAD.length);
    private final FloatBuffer texCoords = floatBuffer(QUAD.length);
    private final float[] texArray = new float[QUAD.length];

    private int textureId = -1;
    private int program;
    private int positionAttrib;
    private int texCoordAttrib;

    CameraBackgroundRenderer() {
        quadCoords.put(QUAD).position(0);
    }

    /** Creates the camera texture and shader. Call from onSurfaceCreated. */
    void create() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        int target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
        GLES20.glBindTexture(target, textureId);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER));
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER));
        GLES20.glLinkProgram(program);
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position");
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord");
    }

    int textureId() {
        return textureId;
    }

    /** Draws the latest camera image, cropped to fill the view the way ARCore expects. */
    void draw(Frame frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, QUAD,
                    Coordinates2d.TEXTURE_NORMALIZED, texArray);
            texCoords.position(0);
            texCoords.put(texArray).position(0);
        }
        // No camera image yet (the first frames after resume).
        if (frame.getTimestamp() == 0) return;

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, quadCoords);
        GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, texCoords);
        GLES20.glEnableVertexAttribArray(positionAttrib);
        GLES20.glEnableVertexAttribArray(texCoordAttrib);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(positionAttrib);
        GLES20.glDisableVertexAttribArray(texCoordAttrib);
        GLES20.glDepthMask(true);
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static FloatBuffer floatBuffer(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }
}
