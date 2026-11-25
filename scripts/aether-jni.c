/*
 * aether-jni.c — Rethink's OWN JNI bridge to the vendored Aether engine.
 *
 * The engine (native/aether, AGPL-3.0, CluvexStudio) is a Rust cdylib that
 * already exports a plain-C, JSON-over-FFI API (aether/src/ffi.rs):
 *   aether_version / aether_identity_open / aether_scan_start /
 *   aether_verify_start / aether_tunnel_start / aether_core_start /
 *   aether_job_poll / aether_job_cancel / aether_job_free / aether_team_* …
 * Every call returns a malloc'd UTF-8 JSON string ("{\"ok\":true,…}" or
 * "{\"ok\":false,\"error\":\"…\"}") that the caller frees with
 * aether_string_free(). This bridge only forwards those calls to the
 * matching Kotlin `external fun` in AetherCore.kt and converts between
 * jstring and char* — it contains no engine logic of its own.
 *
 * THREADING: the FFI calls return immediately — the engine spawns each job
 * on its own Tokio runtime and the Kotlin layer polls it with
 * aether_job_poll. No engine code ever runs on an ART-managed thread and
 * nothing here swaps stacks, so there is no equivalent of the hev
 * SIGSEGV hazard. The tunnel's SOCKS5 listener runs on Rust threads.
 *
 * ENV: the engine reads its AETHER_* options (transport, scan, noise,
 * Tor/Psiphon modes, routing, in-tunnel DNS, …) from the process
 * environment at call time, so the bridge also exports setenv/unsetenv
 * (nativeSetEnv/nativeUnsetEnv). The Kotlin layer applies the whole
 * AetherConfig as env vars immediately before opening/scan/tunnel calls.
 * Env is process-global; Rethink runs exactly one Aether engine, so that
 * is fine — but never call nativeSetEnv concurrently with a start call
 * from another thread (AetherEngine serialises all engine calls).
 *
 * JNI_OnLoad: defined here and registers NOTHING (conventional Java_*
 * symbols are used). It MUST exist: ART resolves JNI_OnLoad with dlsym()
 * on this library's handle, and dlsym() also searches DT_NEEDED
 * dependencies — without our own, a dependency's JNI_OnLoad could be
 * found and executed instead.
 *
 * BUILD: compiled by scripts/build-aether.sh with the NDK clang, linked
 * against libaether.so (-Wl,--no-undefined, so a missing FFI symbol fails
 * the build instead of crashing at runtime). AETHER_HAS_TOR is set by the
 * build script from AETHER_FEATURES (1 when the engine was built with the
 * `tor` cargo feature, else 0); nativeHasTor() reports it so the UI can
 * hide the Tor section on a no-Tor build instead of offering a mode the
 * engine cannot run.
 */
#include <jni.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

/* Aether engine FFI (aether/src/ffi.rs). All return a malloc'd JSON string.
 * NOTE: aether_team_token_set exists in the engine but is deliberately NOT
 * bridged: the app offers the email-code sign-in only and never handles
 * raw enrolment tokens (see AetherCore.kt). */
extern char *aether_version(void);
extern void aether_string_free(char *raw);
extern char *aether_identity_open(const char *payload);
extern char *aether_identity_summary(unsigned long long id);
extern char *aether_identity_free(unsigned long long id);
extern char *aether_scan_start(unsigned long long identity, const char *payload);
extern char *aether_verify_start(unsigned long long identity, const char *payload);
extern char *aether_tunnel_start(unsigned long long identity, const char *payload);
extern char *aether_core_start(const char *arguments);
extern char *aether_job_poll(unsigned long long id);
extern char *aether_job_cancel(unsigned long long id);
extern char *aether_job_free(unsigned long long id);
extern char *aether_team_sign_in(const char *payload);
extern char *aether_team_code_request(const char *payload);
extern char *aether_team_code_resend(unsigned long long session);
extern char *aether_team_code_submit(unsigned long long session, const char *code);
extern char *aether_team_session_free(unsigned long long id);
extern char *aether_team_token_clear(void);

#ifndef AETHER_HAS_TOR
#define AETHER_HAS_TOR 0
#endif

#define TAG "aetherjni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)vm;
    (void)reserved;
    LOGI("libaetherjni loaded: conventional Java_* bindings to libaether.so");
    return JNI_VERSION_1_6;
}

/* Take ownership of an FFI reply: copy it into a jstring, then free it. */
static jstring reply_to_jstring(JNIEnv *env, char *raw)
{
    jstring out;
    if (raw == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"the engine returned null\"}");
    }
    out = (*env)->NewStringUTF(env, raw);
    aether_string_free(raw);
    if (out == NULL) {
        /* NewStringUTF failed (OOM or invalid UTF-8); the raw reply is freed. */
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"the engine reply was not usable utf-8\"}");
    }
    return out;
}

/* -- simple calls ------------------------------------------------------- */

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeVersion(JNIEnv *env, jobject thiz)
{
    (void)thiz;
    return reply_to_jstring(env, aether_version());
}

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeTeamTokenClear(JNIEnv *env, jobject thiz)
{
    (void)thiz;
    return reply_to_jstring(env, aether_team_token_clear());
}

JNIEXPORT jboolean JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeHasTor(JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
    return (jboolean)AETHER_HAS_TOR;
}

/* -- env ----------------------------------------------------------------- */

JNIEXPORT jboolean JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeSetEnv(JNIEnv *env, jobject thiz,
                                                         jstring key, jstring value)
{
    (void)thiz;
    const char *k = NULL, *v = NULL;
    if (key == NULL || value == NULL) return JNI_FALSE;
    k = (*env)->GetStringUTFChars(env, key, NULL);
    v = (*env)->GetStringUTFChars(env, value, NULL);
    if (k == NULL || v == NULL) {
        if (k != NULL) (*env)->ReleaseStringUTFChars(env, key, k);
        if (v != NULL) (*env)->ReleaseStringUTFChars(env, value, v);
        return JNI_FALSE;
    }
    int rc = setenv(k, v, 1);
    (*env)->ReleaseStringUTFChars(env, key, k);
    (*env)->ReleaseStringUTFChars(env, value, v);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeUnsetEnv(JNIEnv *env, jobject thiz,
                                                           jstring key)
{
    (void)thiz;
    const char *k = NULL;
    if (key == NULL) return JNI_FALSE;
    k = (*env)->GetStringUTFChars(env, key, NULL);
    if (k == NULL) return JNI_FALSE;
    int rc = unsetenv(k);
    (*env)->ReleaseStringUTFChars(env, key, k);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

/* -- identity ------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeIdentityOpen(JNIEnv *env, jobject thiz,
                                                               jstring payload)
{
    (void)thiz;
    const char *p = NULL;
    if (payload == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"identity payload was null\"}");
    }
    p = (*env)->GetStringUTFChars(env, payload, NULL);
    if (p == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"identity payload was not readable\"}");
    }
    char *raw = aether_identity_open(p);
    (*env)->ReleaseStringUTFChars(env, payload, p);
    return reply_to_jstring(env, raw);
}

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeIdentitySummary(JNIEnv *env, jobject thiz,
                                                                  jlong id)
{
    (void)thiz;
    return reply_to_jstring(env, aether_identity_summary((unsigned long long)id));
}

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeIdentityFree(JNIEnv *env, jobject thiz,
                                                              jlong id)
{
    (void)thiz;
    return reply_to_jstring(env, aether_identity_free((unsigned long long)id));
}

/* -- scan / verify / tunnel ---------------------------------------------- */

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeScanStart(JNIEnv *env, jobject thiz,
                                                            jlong identity, jstring payload)
{
    (void)thiz;
    const char *p = NULL;
    if (payload == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"scan payload was null\"}");
    }
    p = (*env)->GetStringUTFChars(env, payload, NULL);
    if (p == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"scan payload was not readable\"}");
    }
    char *raw = aether_scan_start((unsigned long long)identity, p);
    (*env)->ReleaseStringUTFChars(env, payload, p);
    return reply_to_jstring(env, raw);
}

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeVerifyStart(JNIEnv *env, jobject thiz,
                                                              jlong identity, jstring payload)
{
    (void)thiz;
    const char *p = NULL;
    if (payload == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"verify payload was null\"}");
    }
    p = (*env)->GetStringUTFChars(env, payload, NULL);
    if (p == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"verify payload was not readable\"}");
    }
    char *raw = aether_verify_start((unsigned long long)identity, p);
    (*env)->ReleaseStringUTFChars(env, payload, p);
    return reply_to_jstring(env, raw);
}

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeTunnelStart(JNIEnv *env, jobject thiz,
                                                              jlong identity, jstring payload)
{
    (void)thiz;
    const char *p = NULL;
    if (payload == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"tunnel payload was null\"}");
    }
    p = (*env)->GetStringUTFChars(env, payload, NULL);
    if (p == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"tunnel payload was not readable\"}");
    }
    char *raw = aether_tunnel_start((unsigned long long)identity, p);
    (*env)->ReleaseStringUTFChars(env, payload, p);
    return reply_to_jstring(env, raw);
}

/* -- whole-engine (CLI-args) mode ----------------------------------------- */
/* Runs the full CLI engine (run_with): the only path that supports nested
 * transports (gool / gool-classic / mim), Tor-only/reverse and
 * Psiphon-only/reverse. `arguments` is a JSON array of CLI argument
 * strings, e.g. ["--masque","--gool","--scan","turbo"]. */

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeCoreStart(JNIEnv *env, jobject thiz,
                                                            jstring arguments)
{
    (void)thiz;
    const char *p = NULL;
    if (arguments == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"core arguments were null\"}");
    }
    p = (*env)->GetStringUTFChars(env, arguments, NULL);
    if (p == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"core arguments were not readable\"}");
    }
    char *raw = aether_core_start(p);
    (*env)->ReleaseStringUTFChars(env, arguments, p);
    return reply_to_jstring(env, raw);
}

/* -- jobs ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeJobPoll(JNIEnv *env, jobject thiz,
                                                         jlong id)
{
    (void)thiz;
    return reply_to_jstring(env, aether_job_poll((unsigned long long)id));
}

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeJobCancel(JNIEnv *env, jobject thiz,
                                                           jlong id)
{
    (void)thiz;
    return reply_to_jstring(env, aether_job_cancel((unsigned long long)id));
}

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeJobFree(JNIEnv *env, jobject thiz,
                                                         jlong id)
{
    (void)thiz;
    return reply_to_jstring(env, aether_job_free((unsigned long long)id));
}

/* -- Zero Trust team -------------------------------------------------------- */

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeTeamSignIn(JNIEnv *env, jobject thiz,
                                                             jstring payload)
{
    (void)thiz;
    const char *p = NULL;
    if (payload == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"team payload was null\"}");
    }
    p = (*env)->GetStringUTFChars(env, payload, NULL);
    if (p == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"team payload was not readable\"}");
    }
    char *raw = aether_team_sign_in(p);
    (*env)->ReleaseStringUTFChars(env, payload, p);
    return reply_to_jstring(env, raw);
}

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeTeamCodeRequest(JNIEnv *env, jobject thiz,
                                                                  jstring payload)
{
    (void)thiz;
    const char *p = NULL;
    if (payload == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"team payload was null\"}");
    }
    p = (*env)->GetStringUTFChars(env, payload, NULL);
    if (p == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"team payload was not readable\"}");
    }
    char *raw = aether_team_code_request(p);
    (*env)->ReleaseStringUTFChars(env, payload, p);
    return reply_to_jstring(env, raw);
}

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeTeamCodeResend(JNIEnv *env, jobject thiz,
                                                                 jlong session)
{
    (void)thiz;
    return reply_to_jstring(env, aether_team_code_resend((unsigned long long)session));
}

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeTeamCodeSubmit(JNIEnv *env, jobject thiz,
                                                                 jlong session, jstring code)
{
    (void)thiz;
    const char *p = NULL;
    if (code == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"team code was null\"}");
    }
    p = (*env)->GetStringUTFChars(env, code, NULL);
    if (p == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"team code was not readable\"}");
    }
    char *raw = aether_team_code_submit((unsigned long long)session, p);
    (*env)->ReleaseStringUTFChars(env, code, p);
    return reply_to_jstring(env, raw);
}

JNIEXPORT jstring JNICALL
Java_com_celzero_bravedns_aether_AetherCore_nativeTeamSessionFree(JNIEnv *env, jobject thiz,
                                                                  jlong id)
{
    (void)thiz;
    return reply_to_jstring(env, aether_team_session_free((unsigned long long)id));
}
