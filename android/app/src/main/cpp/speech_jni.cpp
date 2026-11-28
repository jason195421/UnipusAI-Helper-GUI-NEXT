#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

namespace {
constexpr int RATE = 16000;
std::atomic<bool> cancelled{false};
std::mutex operation;
whisper_context *model = nullptr;
std::string loaded_model;

struct Utf {
    JNIEnv *env; jstring value; const char *data;
    Utf(JNIEnv *e, jstring s) : env(e), value(s), data(s ? e->GetStringUTFChars(s, nullptr) : nullptr) {
        if (!data) throw std::runtime_error("Missing native audio argument");
    }
    ~Utf() { if (data) env->ReleaseStringUTFChars(value, data); }
};
void check_cancel() { if (cancelled.load()) throw std::runtime_error("Audio operation cancelled"); }
void check(int value, const char *what) {
    if (value >= 0) return;
    char message[AV_ERROR_MAX_STRING_SIZE]; av_strerror(value, message, sizeof(message));
    throw std::runtime_error(std::string(what) + ": " + message);
}
int interrupt(void *) { return cancelled.load() ? 1 : 0; }
void error(JNIEnv *env, const std::exception &failure) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), failure.what());
}
std::string quote(const std::string &text) {
    std::string out = "\"";
    const char hex[] = "0123456789abcdef";
    for (unsigned char c : text) {
        if (c == '"' || c == '\\') { out += '\\'; out += static_cast<char>(c); }
        else if (c < 32) { out += "\\u00"; out += hex[c >> 4]; out += hex[c & 15]; }
        else out += static_cast<char>(c);
    }
    return out + '"';
}

// Only local files in app-private storage reach this function. Disable playlist
// demuxers and network protocols even if a course serves a misleading filename.
std::vector<float> decode(const char *path, int maximum_seconds) {
    check_cancel();
    AVFormatContext *format = avformat_alloc_context();
    if (!format) throw std::bad_alloc();
    format->interrupt_callback = {interrupt, nullptr};
    struct FormatGuard { AVFormatContext **p; ~FormatGuard() { avformat_close_input(p); } } fg{&format};
    AVDictionary *options = nullptr;
    av_dict_set(&options, "protocol_whitelist", "file", 0);
    av_dict_set(&options, "format_whitelist", "aac,mp3,wav,flac,ogg,mov,matroska,aiff,au", 0);
    int opened = avformat_open_input(&format, path, nullptr, &options);
    av_dict_free(&options); check(opened, "Cannot open audio");
    check(avformat_find_stream_info(format, nullptr), "Cannot read audio streams");
    int stream = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    check(stream, "No supported audio stream");
    const AVCodec *codec = avcodec_find_decoder(format->streams[stream]->codecpar->codec_id);
    if (!codec) throw std::runtime_error("Audio codec is not available in FFmpeg");
    AVCodecContext *context = avcodec_alloc_context3(codec);
    if (!context) throw std::bad_alloc();
    struct CodecGuard { AVCodecContext **p; ~CodecGuard() { avcodec_free_context(p); } } cg{&context};
    check(avcodec_parameters_to_context(context, format->streams[stream]->codecpar), "Invalid audio metadata");
    context->thread_count = 2;
    check(avcodec_open2(context, codec, nullptr), "Cannot initialize audio decoder");
    AVPacket *packet = av_packet_alloc(); AVFrame *frame = av_frame_alloc();
    struct PacketGuard { AVPacket **p; ~PacketGuard() { av_packet_free(p); } } pg{&packet};
    struct FrameGuard { AVFrame **p; ~FrameGuard() { av_frame_free(p); } } frg{&frame};
    if (!packet || !frame) throw std::bad_alloc();
    SwrContext *resampler = nullptr;
    struct SwrGuard { SwrContext **p; ~SwrGuard() { swr_free(p); } } sg{&resampler};
    int input_rate = 0;
    AVSampleFormat input_format = AV_SAMPLE_FMT_NONE;
    AVChannelLayout input_layout{};
    struct LayoutGuard { AVChannelLayout *p; ~LayoutGuard() { av_channel_layout_uninit(p); } } lg{&input_layout};
    std::vector<float> samples;
    const size_t maximum = static_cast<size_t>(maximum_seconds) * RATE;
    auto append = [&](const float *data, int count) {
        if (count < 0) check(count, "Cannot resample audio");
        if (samples.size() + static_cast<size_t>(count) > maximum)
            throw std::runtime_error("Audio is too long; split it into sections of at most 20 minutes");
        samples.insert(samples.end(), data, data + count);
    };
    auto receive = [&]() {
        for (;;) {
            check_cancel();
            int received = avcodec_receive_frame(context, frame);
            if (received == AVERROR(EAGAIN) || received == AVERROR_EOF) break;
            check(received, "Cannot decode audio frame");
            int rate = frame->sample_rate > 0 ? frame->sample_rate : context->sample_rate;
            if (rate <= 0 || frame->ch_layout.nb_channels <= 0) throw std::runtime_error("Invalid audio sample layout");
            if (!resampler) {
                AVChannelLayout mono = AV_CHANNEL_LAYOUT_MONO;
                check(swr_alloc_set_opts2(&resampler, &mono, AV_SAMPLE_FMT_FLT, RATE,
                    &frame->ch_layout, static_cast<AVSampleFormat>(frame->format), rate, 0, nullptr), "Cannot allocate resampler");
                check(swr_init(resampler), "Cannot initialize audio resampler"); input_rate = rate;
                input_format = static_cast<AVSampleFormat>(frame->format);
                check(av_channel_layout_copy(&input_layout, &frame->ch_layout), "Cannot retain audio layout");
            } else if (rate != input_rate || frame->format != input_format
                    || av_channel_layout_compare(&input_layout, &frame->ch_layout) != 0)
                throw std::runtime_error("Changing audio sample rate, channels or sample format requires splitting the audio");
            int capacity = static_cast<int>(av_rescale_rnd(swr_get_delay(resampler, input_rate)
                + frame->nb_samples, RATE, input_rate, AV_ROUND_UP));
            std::vector<float> converted(std::max(1, capacity));
            uint8_t *output[] = {reinterpret_cast<uint8_t *>(converted.data())};
            int count = swr_convert(resampler, output, capacity,
                const_cast<const uint8_t **>(frame->extended_data), frame->nb_samples);
            append(converted.data(), count); av_frame_unref(frame);
        }
    };
    for (;;) {
        check_cancel();
        int read = av_read_frame(format, packet);
        if (read == AVERROR_EOF) break;
        check(read, "Cannot read audio packet");
        if (packet->stream_index == stream) {
            int sent = avcodec_send_packet(context, packet);
            if (sent == AVERROR(EAGAIN)) { receive(); sent = avcodec_send_packet(context, packet); }
            check(sent, "Cannot send audio packet"); receive();
        }
        av_packet_unref(packet);
    }
    check(avcodec_send_packet(context, nullptr), "Cannot flush audio decoder"); receive();
    if (resampler) for (;;) {
        float buffer[4096]; uint8_t *output[] = {reinterpret_cast<uint8_t *>(buffer)};
        int count = swr_convert(resampler, output, 4096, nullptr, 0);
        append(buffer, count); if (count == 0) break;
    }
    check_cancel();
    if (samples.empty()) throw std::runtime_error("Decoded audio is empty");
    return samples;
}
void le(std::ofstream &out, uint32_t value, int bytes) {
    for (int i = 0; i < bytes; ++i) out.put(static_cast<char>((value >> (8 * i)) & 255));
}
void wav(const char *path, const std::vector<float> &samples) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) throw std::runtime_error("Cannot write normalized WAV");
    uint32_t size = static_cast<uint32_t>(samples.size() * 2);
    out.write("RIFF", 4); le(out, size + 36, 4); out.write("WAVEfmt ", 8);
    le(out, 16, 4); le(out, 1, 2); le(out, 1, 2); le(out, RATE, 4);
    le(out, RATE * 2, 4); le(out, 2, 2); le(out, 16, 2);
    out.write("data", 4); le(out, size, 4);
    for (size_t index = 0; index < samples.size(); ++index) {
        if ((index & 4095U) == 0) check_cancel();
        le(out, static_cast<uint16_t>(static_cast<int16_t>(
            std::lround(std::clamp(samples[index], -1.0f, 1.0f) * 32767))), 2);
    }
    check_cancel();
    if (!out) throw std::runtime_error("WAV output was incomplete");
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_unipus_helper_AndroidSpeechNative_nativeVersions(JNIEnv *env, jclass) {
    std::string value = std::string("{\"whisperVersion\":\"1.9.6\",\"ffmpegVersion\":")
        + quote(av_version_info()) + ",\"ffmpegLicense\":" + quote(avformat_license()) + '}';
    return env->NewStringUTF(value.c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_unipus_helper_AndroidSpeechNative_nativeCancel(JNIEnv *, jclass) { cancelled.store(true); }
extern "C" JNIEXPORT void JNICALL
Java_io_github_unipus_helper_AndroidSpeechNative_nativeRelease(JNIEnv *, jclass) {
    cancelled.store(true); std::lock_guard<std::mutex> lock(operation);
    if (model) whisper_free(model); model = nullptr; loaded_model.clear();
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_unipus_helper_AndroidSpeechNative_nativeDecode(JNIEnv *env, jclass, jstring input, jstring output) {
    try {
        Utf in(env, input), out(env, output); std::lock_guard<std::mutex> lock(operation);
        cancelled.store(false); auto samples = decode(in.data, 1200); wav(out.data, samples);
        return static_cast<jlong>(samples.size());
    } catch (const std::exception &failure) { error(env, failure); return 0; }
}
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_unipus_helper_AndroidSpeechNative_nativeTranscribe(JNIEnv *env, jclass,
        jstring input, jstring model_file, jstring language, jint threads) {
    try {
        Utf in(env, input), path(env, model_file), lang(env, language);
        std::lock_guard<std::mutex> lock(operation); cancelled.store(false);
        auto samples = decode(in.data, 1200);
        if (!model || loaded_model != path.data) {
            if (model) whisper_free(model); model = nullptr; loaded_model.clear();
            auto params = whisper_context_default_params(); params.use_gpu = false;
            model = whisper_init_from_file_with_params(path.data, params);
            if (!model) throw std::runtime_error("Whisper could not load the verified model");
            loaded_model = path.data;
        }
        check_cancel();
        std::string selected = lang.data;
        if (selected.empty() || selected == "auto") selected = "en";
        if (selected != "en" && !whisper_is_multilingual(model))
            throw std::runtime_error("The installed Whisper tiny.en model supports English audio only");
        if (whisper_lang_id(selected.c_str()) < 0) throw std::runtime_error("Unknown Whisper language");
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = std::clamp(static_cast<int>(threads), 1, 4);
        params.language = selected.c_str(); params.translate = false; params.no_context = true;
        params.print_realtime = false; params.print_progress = false;
        params.print_timestamps = false; params.print_special = false;
        params.abort_callback = [](void *) { return cancelled.load(); };
        params.abort_callback_user_data = nullptr;
        int result = whisper_full(model, params, samples.data(), static_cast<int>(samples.size()));
        check_cancel();
        if (result != 0) throw std::runtime_error("Whisper transcription failed");
        std::string text;
        for (int i = 0; i < whisper_full_n_segments(model); ++i) text += whisper_full_get_segment_text(model, i);
        std::ostringstream value; value << "{\"text\":" << quote(text) << ",\"seconds\":"
            << static_cast<double>(samples.size()) / RATE << ",\"sampleRate\":16000}";
        return env->NewStringUTF(value.str().c_str());
    } catch (const std::exception &failure) { error(env, failure); return nullptr; }
}
