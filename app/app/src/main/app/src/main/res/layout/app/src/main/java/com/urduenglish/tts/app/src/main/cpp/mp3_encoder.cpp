#include <jni.h>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <vector>
#include <memory>
#include <lame.h>

static bool read16(FILE* file, uint16_t& value) {
    unsigned char b[2];
    if (fread(b, 1, 2, file) != 2) return false;
    value = b[0] | (uint16_t(b[1]) << 8);
    return true;
}

static bool read32(FILE* file, uint32_t& value) {
    unsigned char b[4];
    if (fread(b, 1, 4, file) != 4) return false;
    value = uint32_t(b[0])
          | (uint32_t(b[1]) << 8)
          | (uint32_t(b[2]) << 16)
          | (uint32_t(b[3]) << 24);
    return true;
}

static int convertWav(const char* input, const char* output) {
    using FilePtr = std::unique_ptr<FILE, decltype(&fclose)>;

    FilePtr in(fopen(input, "rb"), fclose);
    if (!in) return 1;

    char tag[4];
    uint32_t riffSize;

    if (fread(tag, 1, 4, in.get()) != 4 ||
        memcmp(tag, "RIFF", 4) != 0 ||
        !read32(in.get(), riffSize) ||
        fread(tag, 1, 4, in.get()) != 4 ||
        memcmp(tag, "WAVE", 4) != 0) {
        return 2;
    }

    uint16_t format = 0, channels = 0, bits = 0;
    uint16_t blockAlign = 0;
    uint32_t sampleRate = 0, dataSize = 0;
    bool foundFormat = false, foundData = false;

    while (fread(tag, 1, 4, in.get()) == 4) {
        uint32_t size;
        if (!read32(in.get(), size)) return 3;

        if (memcmp(tag, "fmt ", 4) == 0) {
            uint32_t byteRate;

            if (size < 16 ||
                !read16(in.get(), format) ||
                !read16(in.get(), channels) ||
                !read32(in.get(), sampleRate) ||
                !read32(in.get(), byteRate) ||
                !read16(in.get(), blockAlign) ||
                !read16(in.get(), bits)) {
                return 3;
            }

            long skip = static_cast<long>(size - 16)
                      + static_cast<long>(size & 1);

            if (fseek(in.get(), skip, SEEK_CUR) != 0) return 3;
            foundFormat = true;

        } else if (memcmp(tag, "data", 4) == 0) {
            dataSize = size;
            foundData = true;
            break;

        } else {
            long skip = static_cast<long>(size)
                      + static_cast<long>(size & 1);
            if (fseek(in.get(), skip, SEEK_CUR) != 0) return 3;
        }
    }

    // Support standard 16-bit PCM WAV audio.
    if (!foundFormat || !foundData ||
        format != 1 || bits != 16 ||
        (channels != 1 && channels != 2) ||
        sampleRate < 8000 || sampleRate > 48000 ||
        blockAlign != channels * 2 ||
        dataSize == 0 || dataSize % blockAlign != 0) {
        return 4;
    }

    FilePtr out(fopen(output, "wb"), fclose);
    if (!out) return 5;

    lame_t encoder = lame_init();
    if (!encoder) return 6;

    if (lame_set_in_samplerate(encoder, sampleRate) < 0 ||
        lame_set_num_channels(encoder, channels) < 0 ||
        lame_set_brate(encoder, 128) < 0 ||
        lame_set_quality(encoder, 3) < 0) {
        lame_close(encoder);
        return 6;
    }

    lame_set_bWriteVbrTag(encoder, 0);

    if (lame_init_params(encoder) < 0) {
        lame_close(encoder);
        return 6;
    }

    const size_t framesPerBlock = 4096;
    std::vector<unsigned char> raw(framesPerBlock * blockAlign);
    std::vector<short> pcm(framesPerBlock * channels);
    std::vector<unsigned char> mp3(16384);

    uint32_t remaining = dataSize;
    int result = 0;

    while (remaining > 0) {
        size_t count = remaining < raw.size()
                     ? remaining : raw.size();

        if (fread(raw.data(), 1, count, in.get()) != count) {
            result = 7;
            break;
        }

        for (size_t i = 0; i < count / 2; ++i) {
            int value = int(raw[i * 2])
                      | (int(raw[i * 2 + 1]) << 8);
            if (value >= 32768) value -= 65536;
            pcm[i] = static_cast<short>(value);
        }

        int frames = static_cast<int>(count / blockAlign);
        int written;

        if (channels == 2) {
            written = lame_encode_buffer_interleaved(
                encoder, pcm.data(), frames,
                mp3.data(), static_cast<int>(mp3.size())
            );
        } else {
            written = lame_encode_buffer(
                encoder, pcm.data(), pcm.data(), frames,
                mp3.data(), static_cast<int>(mp3.size())
            );
        }

        if (written < 0) {
            result = 8;
            break;
        }

        if (fwrite(mp3.data(), 1, written, out.get()) !=
            static_cast<size_t>(written)) {
            result = 9;
            break;
        }

        remaining -= static_cast<uint32_t>(count);
    }

    if (result == 0) {
        int written = lame_encode_flush(
            encoder, mp3.data(), static_cast<int>(mp3.size())
        );

        if (written < 0) {
            result = 8;
        } else if (fwrite(mp3.data(), 1, written, out.get()) !=
                   static_cast<size_t>(written)) {
            result = 9;
        }
    }

    lame_close(encoder);

    if (fflush(out.get()) != 0 && result == 0) result = 9;
    return result;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_urduenglish_tts_Mp3Encoder_encodeWav(
    JNIEnv* env,
    jclass,
    jstring inputPath,
    jstring outputPath
) {
    if (!inputPath || !outputPath) return 10;

    const char* input = env->GetStringUTFChars(inputPath, nullptr);
    if (!input) return 10;

    const char* output = env->GetStringUTFChars(outputPath, nullptr);
    if (!output) {
        env->ReleaseStringUTFChars(inputPath, input);
        return 10;
    }

    int result;
    try {
        result = convertWav(input, output);
    } catch (...) {
        result = 11;
    }

    env->ReleaseStringUTFChars(inputPath, input);
    env->ReleaseStringUTFChars(outputPath, output);
    return result;
}
