#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"
#include "ggml-backend.h"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ogba", __VA_ARGS__)

static std::string g_backends;

extern "C" JNIEXPORT jstring JNICALL
Java_com_ogba_app_llm_LlamaNative_init(JNIEnv *env, jobject, jstring jdir) {
    static bool done = false;
    if (!done) {
        const char *dir = env->GetStringUTFChars(jdir, nullptr);
        ggml_backend_load_all_from_path(dir);
        env->ReleaseStringUTFChars(jdir, dir);
        llama_backend_init();
        done = true;
        g_backends.clear();
        for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
            ggml_backend_dev_t d = ggml_backend_dev_get(i);
            if (!g_backends.empty()) g_backends += "; ";
            g_backends += std::string(ggml_backend_dev_name(d)) + " (" + ggml_backend_dev_description(d) + ")";
        }
        g_backends += " | " + std::string(llama_print_system_info());
        LOGI("backends: %s", g_backends.c_str());
    }
    return env->NewStringUTF(g_backends.c_str());
}

struct Handle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;          // persistent context -> KV cache reuse across turns
    llama_context_params cparams;
    std::vector<llama_token> cached;       // tokens currently held in the KV cache (seq 0)
};

static size_t utf8_complete_len(const std::string &s) {
    size_t n = s.size();
    for (size_t i = 1; i <= 4 && i <= n; ++i) {
        unsigned char c = (unsigned char) s[n - i];
        if ((c & 0xC0) == 0x80) continue;          // continuation byte
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return (i >= need) ? n : n - i;
    }
    return n;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ogba_app_llm_LlamaNative_load(JNIEnv *env, jobject, jstring jpath, jint nCtx, jint nThreads, jint nGpuLayers, jboolean flash) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = nGpuLayers;   // 0 = CPU only; >0 offloads to Vulkan if a GPU backend loaded
    mp.use_mmap = true;
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) return 0;
    auto *h = new Handle();
    h->model = model;
    h->cparams = llama_context_default_params();
    h->cparams.n_ctx = nCtx;
    h->cparams.n_batch = 512;
    h->cparams.n_threads = nThreads;
    h->cparams.n_threads_batch = nThreads;
    h->cparams.n_ubatch = 512;
    h->cparams.flash_attn_type = flash ? LLAMA_FLASH_ATTN_TYPE_AUTO : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    h->ctx = llama_init_from_model(model, h->cparams);
    if (!h->ctx) { llama_model_free(model); delete h; return 0; }
    LOGI("loaded model n_ctx=%d threads=%d", nCtx, nThreads);
    return (jlong) h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ogba_app_llm_LlamaNative_free(JNIEnv *, jobject, jlong ptr) {
    auto *h = (Handle *) ptr;
    if (!h) return;
    if (h->ctx) llama_free(h->ctx);
    llama_model_free(h->model);
    delete h;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ogba_app_llm_LlamaNative_generate(JNIEnv *env, jobject, jlong ptr, jobjectArray roles,
                                           jobjectArray contents, jint maxTokens, jfloat temp, jobject cb) {
    auto *h = (Handle *) ptr;
    if (!h) return -1;
    jclass cbCls = env->GetObjectClass(cb);
    jmethodID onBytes = env->GetMethodID(cbCls, "onBytes", "([B)Z");

    int n = env->GetArrayLength(roles);
    std::vector<std::string> rs(n), cs(n);
    for (int i = 0; i < n; i++) {
        auto r = (jstring) env->GetObjectArrayElement(roles, i);
        auto c = (jstring) env->GetObjectArrayElement(contents, i);
        const char *rp = env->GetStringUTFChars(r, nullptr);
        const char *cp = env->GetStringUTFChars(c, nullptr);
        rs[i] = rp; cs[i] = cp;
        env->ReleaseStringUTFChars(r, rp); env->ReleaseStringUTFChars(c, cp);
    }
    std::vector<llama_chat_message> msgs(n);
    for (int i = 0; i < n; i++) msgs[i] = {rs[i].c_str(), cs[i].c_str()};

    const char *tmpl = llama_model_chat_template(h->model, nullptr);
    std::vector<char> buf(16384);
    int len = llama_chat_apply_template(tmpl, msgs.data(), n, true, buf.data(), buf.size());
    if (len < 0) { tmpl = "chatml"; len = llama_chat_apply_template(tmpl, msgs.data(), n, true, buf.data(), buf.size()); }
    if (len > (int) buf.size()) { buf.resize(len + 1); len = llama_chat_apply_template(tmpl, msgs.data(), n, true, buf.data(), buf.size()); }
    if (len < 0) return -2;
    std::string prompt(buf.data(), len);

    const llama_vocab *vocab = llama_model_get_vocab(h->model);
    int nt = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(nt);
    llama_tokenize(vocab, prompt.c_str(), prompt.size(), toks.data(), nt, true, true);
    int limit = (int) h->cparams.n_ctx - maxTokens - 8;
    if (limit < 64) limit = 64;
    if ((int) toks.size() > limit) toks.erase(toks.begin(), toks.end() - limit);

    llama_context *ctx = h->ctx;
    llama_memory_t mem = llama_get_memory(ctx);
    // KV cache reuse: keep the longest common prefix with what is already decoded.
    size_t keep = 0;
    while (keep < h->cached.size() && keep < toks.size() && h->cached[keep] == toks[keep]) keep++;
    if (keep == toks.size() && keep > 0) keep--;   // always re-decode at least the last prompt token
    if (!llama_memory_seq_rm(mem, 0, keep, -1)) { llama_memory_clear(mem, true); keep = 0; }
    h->cached.resize(keep);
    LOGI("prompt %zu tokens, reused %zu from KV cache", toks.size(), keep);
    for (size_t i = keep; i < toks.size(); i += 512) {
        int cnt = std::min<int>(512, toks.size() - i);
        if (llama_decode(ctx, llama_batch_get_one(toks.data() + i, cnt)) != 0) { llama_memory_clear(mem, true); h->cached.clear(); return -4; }
        h->cached.insert(h->cached.end(), toks.begin() + i, toks.begin() + i + cnt);
    }
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string pending;
    int produced = 0;
    for (; produced < maxTokens; produced++) {
        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        char piece[256];
        int pl = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (pl > 0) pending.append(piece, pl);
        size_t ok = utf8_complete_len(pending);
        if (ok > 0) {
            jbyteArray arr = env->NewByteArray(ok);
            env->SetByteArrayRegion(arr, 0, ok, (const jbyte *) pending.data());
            jboolean cont = env->CallBooleanMethod(cb, onBytes, arr);
            env->DeleteLocalRef(arr);
            pending.erase(0, ok);
            if (!cont) break;
        }
        llama_token t = tok;
        if ((int) h->cached.size() + 1 >= (int) h->cparams.n_ctx) break;
        if (llama_decode(ctx, llama_batch_get_one(&t, 1)) != 0) break;
        h->cached.push_back(t);
    }
    llama_sampler_free(smpl);
    return produced;
}
