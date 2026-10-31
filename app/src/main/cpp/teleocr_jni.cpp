#include <jni.h>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
// No model input, document text, or generated output is written to Android logs.
void discard_log(ggml_log_level, const char *, void *) {}

struct Session {
    std::atomic<bool> cancelled{false};
    std::mutex inference_mutex;
    llama_model * model = nullptr;
    mtmd_context * vision = nullptr;
    int threads = 2;
    ~Session() {
        if (vision) mtmd_free(vision);
        if (model) llama_model_free(model);
    }
};
std::mutex sessions_mutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
std::atomic<jlong> next_handle{1};
std::once_flag backend_once;

void initialize_backend() {
    std::call_once(backend_once, [] {
        llama_log_set(discard_log, nullptr);
        mtmd_helper_log_set(discard_log, nullptr);
        llama_backend_init();
    });
}

bool abort_compute(void * data) {
    return static_cast<Session *>(data)->cancelled.load(std::memory_order_relaxed);
}
bool load_progress(float, void * data) { return !abort_compute(data); }
// CPU graph checkpoints: request a callback after each matrix operation, allowing
// cancellation while the vision encoder is running as well as during decoding.
bool eval_checkpoint(ggml_tensor * tensor, bool ask, void * data) {
    return ask ? tensor->op == GGML_OP_MUL_MAT : !abort_compute(data);
}
void check_cancelled(const Session & s) {
    if (s.cancelled.load(std::memory_order_relaxed)) throw std::runtime_error("TeleOCR cancelled.");
}

std::shared_ptr<Session> get_session(jlong id) {
    std::lock_guard<std::mutex> lock(sessions_mutex);
    const auto found = sessions.find(id);
    if (found == sessions.end()) throw std::runtime_error("TeleOCR session is closed.");
    return found->second;
}

class UtfChars {
    JNIEnv * env;
    jstring source;
    const char * value;
public:
    UtfChars(JNIEnv * e, jstring s): env(e), source(s), value(e->GetStringUTFChars(s, nullptr)) {
        if (!value) throw std::runtime_error("TeleOCR could not read its configuration.");
    }
    ~UtfChars() { env->ReleaseStringUTFChars(source, value); }
    const char * get() const { return value; }
};

void throw_error(JNIEnv * env, const char * message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

std::string infer(Session & s, const std::vector<unsigned char> & rgb,
                  int width, int height, const char * prompt, int max_output) {
    std::unique_lock<std::mutex> inference_lock(s.inference_mutex, std::try_to_lock);
    if (!inference_lock.owns_lock()) throw std::runtime_error("TeleOCR session is already processing a region.");
    check_cancelled(s);
    if (!s.model || !s.vision) throw std::runtime_error("TeleOCR session has not loaded its models.");
    using Bitmap = std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)>;
    using Chunks = std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)>;
    using Context = std::unique_ptr<llama_context, decltype(&llama_free)>;
    using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
    Bitmap bitmap(mtmd_bitmap_init(width, height, rgb.data()), mtmd_bitmap_free);
    if (!bitmap) throw std::runtime_error("TeleOCR could not prepare the image.");
    Chunks chunks(mtmd_input_chunks_init(), mtmd_input_chunks_free);
    if (!chunks) throw std::runtime_error("TeleOCR could not allocate its input.");
    // TeleOCR's upstream system/user template, with its image marker expanded by mtmd.
    std::string chat = "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n<|im_start|>user\n";
    chat += mtmd_default_marker();
    chat += prompt;
    chat += "<|im_end|>\n<|im_start|>assistant\n";
    mtmd_input_text input{chat.data(), chat.size(), true, true};
    const mtmd_bitmap * image = bitmap.get();
    if (mtmd_tokenize(s.vision, chunks.get(), &input, &image, 1) != 0) {
        throw std::runtime_error("TeleOCR could not tokenize the image.");
    }
    check_cancelled(s);
    const size_t input_tokens = mtmd_helper_get_n_tokens(chunks.get());
    const size_t needed = input_tokens + static_cast<size_t>(max_output) + 16;
    if (needed > 8192) throw std::runtime_error("TeleOCR region exceeds its context limit; use a smaller crop.");
    auto context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(((needed + 255) / 256) * 256);
    context_params.n_batch = 256;
    context_params.n_ubatch = 128;
    context_params.n_threads = s.threads;
    context_params.n_threads_batch = s.threads;
    context_params.offload_kqv = false;
    context_params.op_offload = false;
    context_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    context_params.no_perf = true;
    context_params.abort_callback = abort_compute;
    context_params.abort_callback_data = &s;
    Context context(llama_init_from_model(s.model, context_params), llama_free);
    if (!context) throw std::runtime_error("TeleOCR could not allocate its context; free memory and retry.");
    llama_pos position = 0;
    const auto status = mtmd_helper_eval_chunks(s.vision, context.get(), chunks.get(), 0, 0,
                                               context_params.n_batch, true, &position);
    check_cancelled(s);
    if (status != 0) throw std::runtime_error("TeleOCR could not evaluate this image.");
    Sampler sampler(llama_sampler_init_greedy(), llama_sampler_free);
    const llama_vocab * vocab = llama_model_get_vocab(s.model);
    std::string result;
    bool completed = false;
    for (int n = 0; n < max_output; ++n) {
        check_cancelled(s);
        const llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
        llama_sampler_accept(sampler.get(), token);
        if (llama_vocab_is_eog(vocab, token)) { completed = true; break; }
        char piece[512];
        int length = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (length < 0) {
            std::vector<char> large_piece(static_cast<size_t>(-length));
            length = llama_token_to_piece(vocab, token, large_piece.data(), large_piece.size(), 0, false);
            if (length < 0) throw std::runtime_error("TeleOCR could not decode a text token.");
            result.append(large_piece.data(), length);
        } else {
            result.append(piece, length);
        }
        if (result.size() > 131072) throw std::runtime_error("TeleOCR output exceeds the page safety limit.");
        // Explicit positions are required after multimodal M-RoPE prefill.
        llama_token next = token;
        llama_pos positions[4] = {position, position, position, 0};
        int32_t n_seq = 1;
        llama_seq_id seq_id = 0;
        llama_seq_id * seq_ids = &seq_id;
        int8_t logits = 1;
        llama_batch batch{1, &next, nullptr, positions, &n_seq, &seq_ids, &logits};
        const int decoded = llama_decode(context.get(), batch);
        check_cancelled(s);
        if (decoded != 0) throw std::runtime_error("TeleOCR stopped before completing this region.");
        ++position;
    }
    if (!completed) throw std::runtime_error("TeleOCR output was truncated; use a smaller region or a higher output budget.");
    if (result.find_first_not_of(" \r\n\t") == std::string::npos) throw std::runtime_error("TeleOCR returned no text for this region.");
    return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_readflow_ingest_TeleOcrNative_nativeCreate(JNIEnv * env, jobject, jint threads) {
    try {
        if (threads < 1 || threads > 8) throw std::runtime_error("Invalid TeleOCR thread limit.");
        initialize_backend();
        auto session = std::make_shared<Session>();
        session->threads = threads;
        const jlong id = next_handle.fetch_add(1);
        std::lock_guard<std::mutex> lock(sessions_mutex);
        sessions.emplace(id, session);
        return id;
    } catch (const std::exception & e) { throw_error(env, e.what()); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_app_readflow_ingest_TeleOcrNative_nativeLoad(JNIEnv * env, jobject, jlong handle,
    jstring model_path, jstring projector_path, jint image_tokens) {
    try {
        if (image_tokens < 64 || image_tokens > 2048)
            throw std::runtime_error("Invalid TeleOCR image limit.");
        const auto session = get_session(handle);
        std::unique_lock<std::mutex> lock(session->inference_mutex, std::try_to_lock);
        if (!lock.owns_lock()) throw std::runtime_error("TeleOCR session is busy.");
        check_cancelled(*session);
        if (session->model || session->vision) throw std::runtime_error("TeleOCR models are already loaded.");
        UtfChars model(env, model_path), projector(env, projector_path);
        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
        model_params.progress_callback = load_progress;
        model_params.progress_callback_user_data = session.get();
        session->model = llama_model_load_from_file(model.get(), model_params);
        check_cancelled(*session);
        if (!session->model) throw std::runtime_error("TeleOCR text model could not be loaded. Verify the model download.");
        auto vision_params = mtmd_context_params_default();
        vision_params.use_gpu = false;
        vision_params.n_threads = session->threads;
        vision_params.print_timings = false;
        vision_params.warmup = false;
        vision_params.image_min_tokens = 64;
        vision_params.image_max_tokens = image_tokens;
        vision_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        vision_params.progress_callback = load_progress;
        vision_params.progress_callback_user_data = session.get();
        vision_params.cb_eval = eval_checkpoint;
        vision_params.cb_eval_user_data = session.get();
        session->vision = mtmd_init_from_file(projector.get(), session->model, vision_params);
        check_cancelled(*session);
        if (!session->vision || !mtmd_support_vision(session->vision))
            throw std::runtime_error("TeleOCR vision model could not be loaded. Verify the model download.");
    } catch (const std::exception & e) { throw_error(env, e.what()); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_readflow_ingest_TeleOcrNative_nativeRecognize(JNIEnv * env, jobject, jlong handle,
    jbyteArray data, jint width, jint height, jstring prompt, jint max_output) {
    try {
        const auto session = get_session(handle);
        const auto count = env->GetArrayLength(data);
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192 ||
            int64_t(width) * height * 3 != count || count > 24 * 1024 * 1024 || max_output < 1 || max_output > 4096)
            throw std::runtime_error("Invalid TeleOCR image or output limit.");
        std::vector<unsigned char> rgb(count);
        env->GetByteArrayRegion(data, 0, count, reinterpret_cast<jbyte *>(rgb.data()));
        if (env->ExceptionCheck()) return nullptr;
        UtfChars instruction(env, prompt);
        const auto result = infer(*session, rgb, width, height, instruction.get(), max_output);
        jbyteArray output = env->NewByteArray(static_cast<jsize>(result.size()));
        if (output) env->SetByteArrayRegion(output, 0, static_cast<jsize>(result.size()),
                                          reinterpret_cast<const jbyte *>(result.data()));
        return output;
    } catch (const std::exception & e) { throw_error(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_app_readflow_ingest_TeleOcrNative_nativeCancel(JNIEnv *, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(sessions_mutex);
    auto found = sessions.find(handle);
    if (found != sessions.end()) found->second->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_app_readflow_ingest_TeleOcrNative_nativeClose(JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<Session> released;
    {
        std::lock_guard<std::mutex> lock(sessions_mutex);
        auto found = sessions.find(handle);
        if (found == sessions.end()) return;
        found->second->cancelled.store(true);
        released = std::move(found->second);
        sessions.erase(found);
    }
    // Any in-flight call retains its shared ownership until it exits safely.
}
