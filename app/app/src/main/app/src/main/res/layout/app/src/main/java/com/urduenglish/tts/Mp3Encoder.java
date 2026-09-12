package com.urduenglish.tts;

import java.io.File;
import java.io.IOException;

public final class Mp3Encoder {

    private Mp3Encoder() {
    }

    private static native int encodeWav(
            String inputPath,
            String outputPath
    );

    public static void convert(File wavFile, File mp3File)
            throws IOException {

        if (!wavFile.isFile() || wavFile.length() == 0) {
            throw new IOException("Audio file nahi mili.");
        }

        try {
            System.loadLibrary("ttsmp3");

            int result = encodeWav(
                    wavFile.getAbsolutePath(),
                    mp3File.getAbsolutePath()
            );

            if (result != 0) {
                throw new IOException(
                        "MP3 conversion fail hui. Code: " + result
                );
            }
        } catch (UnsatisfiedLinkError error) {
            throw new IOException(
                    "MP3 encoder load nahi ho saka.", error
            );
        }

        if (!mp3File.isFile() || mp3File.length() == 0) {
            throw new IOException("MP3 file nahi ban saki.");
        }
    }
}
