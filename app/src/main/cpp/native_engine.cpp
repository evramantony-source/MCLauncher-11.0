#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cctype>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <filesystem>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace fs = std::filesystem;

extern "C" int mclauncher_window_width();
extern "C" int mclauncher_window_height();

namespace {
constexpr const char* TAG = "MCLauncherNative";
std::mutex gLogMutex;
std::deque<std::string> gLogLines;
std::mutex gWindowMutex;
ANativeWindow* gWindow = nullptr;
JavaVM* gMinecraftVm = nullptr;
JavaVM* gAndroidVm = nullptr;
void* gJvmHandle = nullptr;
bool gUpstreamAndroidJniInitialized = false;
std::mutex gJniInitMutex;
std::vector<void*> gJniInitializedHandles;
jclass gCallbackBridgeClass = nullptr;
jmethodID gCallbackSendKey = nullptr;
jmethodID gCallbackSendChar = nullptr;
jmethodID gCallbackSendMouseButton = nullptr;
jmethodID gCallbackSendCursorPos = nullptr;
jmethodID gCallbackSendScroll = nullptr;

// MojoLauncher/dnbootstrap GLFW native entry points. These are invoked directly,
// so MCLauncher does not need Mojo's Android UI classes or APK at runtime.
using MojoInitialize = void (*)(JNIEnv*, jclass);
using MojoSurfaceCreated = void (*)(JNIEnv*, jclass, jobject);
using MojoSurfaceDestroyed = void (*)(JNIEnv*, jclass);
using MojoSurfaceUpdated = void (*)(JNIEnv*, jclass);
using MojoSendKey = void (*)(JNIEnv*, jclass, jint, jint, jint);
using MojoSendRawKey = void (*)(JNIEnv*, jclass, jint, jint, jint, jchar);
using MojoSendMouse = void (*)(JNIEnv*, jclass, jint, jint, jint);
using MojoSendUnicode = void (*)(JNIEnv*, jclass, jstring, jint);
using MojoSendScroll = void (*)(JNIEnv*, jclass, jdouble, jdouble);
using MojoSendMousePosition = void (*)(JNIEnv*, jclass, jdouble, jdouble);
using MojoSendMouseAt = void (*)(JNIEnv*, jclass, jint, jint, jint, jdouble, jdouble);
MojoInitialize gMojoInitialize = nullptr;
MojoSurfaceCreated gMojoSurfaceCreated = nullptr;
MojoSurfaceDestroyed gMojoSurfaceDestroyed = nullptr;
MojoSurfaceUpdated gMojoSurfaceUpdated = nullptr;
MojoSendKey gMojoSendKey = nullptr;
MojoSendRawKey gMojoSendRawKey = nullptr;
MojoSendMouse gMojoSendMouse = nullptr;
MojoSendUnicode gMojoSendUnicode = nullptr;
MojoSendScroll gMojoSendScroll = nullptr;
MojoSendMousePosition gMojoSendMousePosition = nullptr;
MojoSendMouseAt gMojoSendMouseAt = nullptr;
bool gMojoGlfwAvailable = false;
bool gMojoSurfaceAttached = false;
std::atomic<bool> gLoggedMouseButtonInput{false};
std::atomic<bool> gLoggedAbsolutePointerInput{false};
std::atomic<bool> gLoggedDirectTouchInput{false};

std::atomic<bool> gLaunchRunning{false};
std::atomic<bool> gPipeReaderRunning{false};
int gOriginalStdout = -1;
int gOriginalStderr = -1;
int gPipeRead = -1;
int gPipeWrite = -1;
std::mutex gCursorMutex;
double gForwardCursorX = 0.0;
double gForwardCursorY = 0.0;
bool gForwardCursorGrabbed = false;


enum class InputEventType : int {
    Key = 1,
    Character = 2,
    MouseButton = 3,
    CursorDelta = 4,
    Scroll = 5,
    GamepadAxis = 6
};

struct InputEvent {
    InputEventType type{};
    int code = 0;
    int action = 0;
    int modifiers = 0;
    double x = 0.0;
    double y = 0.0;
    std::uint32_t character = 0;
};

std::mutex gInputMutex;
std::deque<InputEvent> gInputEvents;

void pushInput(InputEvent event) {
    std::lock_guard<std::mutex> lock(gInputMutex);
    gInputEvents.push_back(event);
    while (gInputEvents.size() > 2048) gInputEvents.pop_front();
}

void pushLog(const std::string& message) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", message.c_str());
    if (const char* path = std::getenv("MCLAUNCHER_SESSION_LOG"); path != nullptr && path[0] != '\0') {
        const int descriptor = open(path, O_WRONLY | O_APPEND | O_CLOEXEC);
        if (descriptor >= 0) {
            const std::string line = "NATIVE: " + message + "\n";
            const ssize_t ignored = write(descriptor, line.data(), line.size());
            (void) ignored;
            close(descriptor);
        }
    }
    std::lock_guard<std::mutex> lock(gLogMutex);
    gLogLines.push_back(message);
    while (gLogLines.size() > 600) gLogLines.pop_front();
}

void pushError(const std::string& message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message.c_str());
    if (const char* path = std::getenv("MCLAUNCHER_SESSION_LOG"); path != nullptr && path[0] != '\0') {
        const int descriptor = open(path, O_WRONLY | O_APPEND | O_CLOEXEC);
        if (descriptor >= 0) {
            const std::string line = "NATIVE ERROR: " + message + "\n";
            const ssize_t ignored = write(descriptor, line.data(), line.size());
            (void) ignored;
            close(descriptor);
        }
    }
    std::lock_guard<std::mutex> lock(gLogMutex);
    gLogLines.push_back("ERROR: " + message);
    while (gLogLines.size() > 600) gLogLines.pop_front();
}

std::string jstringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* utf = env->GetStringUTFChars(value, nullptr);
    if (utf == nullptr) return {};
    std::string result(utf);
    env->ReleaseStringUTFChars(value, utf);
    return result;
}

std::vector<std::string> jobjectArrayToStrings(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> result;
    if (array == nullptr) return result;
    const jsize size = env->GetArrayLength(array);
    result.reserve(static_cast<size_t>(size));
    for (jsize i = 0; i < size; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        result.push_back(jstringToString(env, value));
        env->DeleteLocalRef(value);
    }
    return result;
}

bool regularFile(const fs::path& path) {
    std::error_code ec;
    return fs::is_regular_file(path, ec);
}

void* loadAbsolute(const std::string& path, bool required) {
    if (path.empty() || !regularFile(path)) {
        if (required) pushError("Required native library is missing: " + path);
        return nullptr;
    }
    pushLog("Loading " + path);
    dlerror();
    void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        const char* error = dlerror();
        const std::string message = "dlopen failed for " + path + ": " + (error ? error : "unknown error");
        if (required) pushError(message); else pushLog(message);
    } else {
        pushLog("Loaded " + path);
    }
    return handle;
}

bool unsupportedProcessHookLibrary(const std::string& filename) {
    std::string lower = filename;
    std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return lower.find("bytehook") != std::string::npos ||
           lower.find("shadowhook") != std::string::npos ||
           lower.find("exithook") != std::string::npos;
}

void initializeMojoGlfw(JNIEnv* env, const std::string& path, void* handle) {
    if (handle == nullptr || gMojoGlfwAvailable) return;
    const std::string filename = fs::path(path).filename().string();
    if (filename != "libglfw.so") return;
    gMojoInitialize = reinterpret_cast<MojoInitialize>(
        dlsym(handle, "Java_git_artdeell_dnbootstrap_glfw_GLFW_initialize"));
    gMojoSurfaceCreated = reinterpret_cast<MojoSurfaceCreated>(
        dlsym(handle, "Java_git_artdeell_dnbootstrap_glfw_GLFW_nativeSurfaceCreated"));
    gMojoSurfaceDestroyed = reinterpret_cast<MojoSurfaceDestroyed>(
        dlsym(handle, "Java_git_artdeell_dnbootstrap_glfw_GLFW_nativeSurfaceDestroyed"));
    gMojoSurfaceUpdated = reinterpret_cast<MojoSurfaceUpdated>(
        dlsym(handle, "Java_git_artdeell_dnbootstrap_glfw_GLFW_nativeSurfaceUpdated"));
    gMojoSendKey = reinterpret_cast<MojoSendKey>(
        dlsym(handle, "Java_git_artdeell_dnbootstrap_glfw_GLFW_sendKeyEvent"));
    gMojoSendRawKey = reinterpret_cast<MojoSendRawKey>(
        dlsym(handle, "Java_git_artdeell_dnbootstrap_glfw_GLFW_sendRawKeyEvent"));
    gMojoSendMouse = reinterpret_cast<MojoSendMouse>(
        dlsym(handle, "Java_git_artdeell_dnbootstrap_glfw_GLFW_sendMouseEvent"));
    gMojoSendUnicode = reinterpret_cast<MojoSendUnicode>(
        dlsym(handle, "Java_git_artdeell_dnbootstrap_glfw_GLFW_sendBulkUnicodeEvent"));
    gMojoSendScroll = reinterpret_cast<MojoSendScroll>(
        dlsym(handle, "Java_git_artdeell_dnbootstrap_glfw_GLFW_sendScrollEvent"));
    gMojoSendMousePosition = reinterpret_cast<MojoSendMousePosition>(
        dlsym(handle, "Java_git_artdeell_dnbootstrap_glfw_GLFW_sendMousePosition0__DD"));
    gMojoSendMouseAt = reinterpret_cast<MojoSendMouseAt>(
        dlsym(handle, "Java_git_artdeell_dnbootstrap_glfw_GLFW_sendMouseEventAt0"));
    gMojoGlfwAvailable = gMojoInitialize && gMojoSurfaceCreated && gMojoSurfaceDestroyed &&
                         gMojoSendKey && gMojoSendRawKey &&
                         gMojoSendMouse && gMojoSendUnicode && gMojoSendScroll &&
                         gMojoSendMousePosition && gMojoSendMouseAt;
    if (gMojoGlfwAvailable) {
        jclass glfwClass = env->FindClass("git/artdeell/dnbootstrap/glfw/GLFW");
        if (glfwClass == nullptr) {
            env->ExceptionClear();
            gMojoGlfwAvailable = false;
            pushError("MCLauncher GLFW Android compatibility class is missing");
            return;
        }
        gMojoInitialize(env, glfwClass);
        env->DeleteLocalRef(glfwClass);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            gMojoGlfwAvailable = false;
            pushError("Bundled dnbootstrap GLFW initialization failed");
            return;
        }
        pushLog("Initialized bundled dnbootstrap GLFW bridge");
    }
    else pushError("Bundled libglfw.so is missing required Android entry points");
}

void initializeUpstreamAndroidJni(JNIEnv* env, const std::string& path, void* handle) {
    if (gAndroidVm == nullptr || handle == nullptr) return;
    const std::string filename = fs::path(path).filename().string();
    if (filename.find("pojav") == std::string::npos) return;

    {
        std::lock_guard<std::mutex> lock(gJniInitMutex);
        if (std::find(gJniInitializedHandles.begin(), gJniInitializedHandles.end(), handle) !=
            gJniInitializedHandles.end()) return;
        // Record before invoking JNI_OnLoad to protect against recursive loads.
        gJniInitializedHandles.push_back(handle);
    }

    using JniOnLoad = jint (*)(JavaVM*, void*);
    auto onLoad = reinterpret_cast<JniOnLoad>(dlsym(handle, "JNI_OnLoad"));
    if (onLoad == nullptr) return;

    const jint version = onLoad(gAndroidVm, nullptr);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        pushError("JNI_OnLoad raised an exception for " + filename);
        return;
    }
    if (version < JNI_VERSION_1_4) {
        pushError("Upstream JNI_OnLoad rejected Android VM for " + filename);
        return;
    }
    pushLog("Initialized Android JNI for " + filename);

    // The AWT bridge resolves MCLauncher-owned net.kdt compatibility classes in
    // its JNI_OnLoad. It does not provide the legacy CallbackBridge input ABI.
    if (filename.find("_awt") != std::string::npos) return;
    if (gUpstreamAndroidJniInitialized) return;

    jclass localBridge = env->FindClass("org/lwjgl/glfw/CallbackBridge");
    if (localBridge != nullptr) {
        gCallbackBridgeClass = static_cast<jclass>(env->NewGlobalRef(localBridge));
        env->DeleteLocalRef(localBridge);
        gCallbackSendKey = env->GetStaticMethodID(gCallbackBridgeClass, "nativeSendKey", "(IIII)V");
        gCallbackSendChar = env->GetStaticMethodID(gCallbackBridgeClass, "nativeSendChar", "(C)Z");
        gCallbackSendMouseButton = env->GetStaticMethodID(gCallbackBridgeClass, "nativeSendMouseButton", "(III)V");
        gCallbackSendCursorPos = env->GetStaticMethodID(gCallbackBridgeClass, "nativeSendCursorPos", "(FF)V");
        gCallbackSendScroll = env->GetStaticMethodID(gCallbackBridgeClass, "nativeSendScroll", "(DD)V");
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        pushError("Could not cache legacy CallbackBridge native methods");
        return;
    }
    gUpstreamAndroidJniInitialized = gCallbackBridgeClass != nullptr;
    if (gUpstreamAndroidJniInitialized) {
        pushLog("Initialized legacy CallbackBridge input ABI from " + filename);
    }
}

fs::path findByName(const fs::path& root, const std::string& filename) {
    std::error_code ec;
    if (!fs::exists(root, ec)) return {};
    for (fs::recursive_directory_iterator it(root, fs::directory_options::skip_permission_denied, ec), end;
         it != end && !ec; it.increment(ec)) {
        if (it->is_regular_file(ec) && it->path().filename() == filename) return it->path();
    }
    return {};
}

void pipeReaderLoop() {
    gPipeReaderRunning = true;
    std::string pending;
    char buffer[1024];
    while (gPipeRead >= 0) {
        const ssize_t count = read(gPipeRead, buffer, sizeof(buffer));
        if (count > 0) {
            pending.append(buffer, static_cast<size_t>(count));
            size_t newline;
            while ((newline = pending.find('\n')) != std::string::npos) {
                std::string line = pending.substr(0, newline);
                pending.erase(0, newline + 1);
                if (!line.empty()) pushLog(line);
            }
        } else if (count == 0) {
            break;
        } else if (errno != EINTR) {
            break;
        }
    }
    if (!pending.empty()) pushLog(pending);
    gPipeReaderRunning = false;
}

void beginOutputCapture() {
    int fds[2];
    if (pipe(fds) != 0) {
        pushLog("Could not create stdout/stderr pipe");
        return;
    }
    gPipeRead = fds[0];
    gPipeWrite = fds[1];
    gOriginalStdout = dup(STDOUT_FILENO);
    gOriginalStderr = dup(STDERR_FILENO);
    dup2(gPipeWrite, STDOUT_FILENO);
    dup2(gPipeWrite, STDERR_FILENO);
    std::thread(pipeReaderLoop).detach();
}

void endOutputCapture() {
    fflush(stdout);
    fflush(stderr);
    if (gOriginalStdout >= 0) {
        dup2(gOriginalStdout, STDOUT_FILENO);
        close(gOriginalStdout);
        gOriginalStdout = -1;
    }
    if (gOriginalStderr >= 0) {
        dup2(gOriginalStderr, STDERR_FILENO);
        close(gOriginalStderr);
        gOriginalStderr = -1;
    }
    if (gPipeWrite >= 0) {
        close(gPipeWrite);
        gPipeWrite = -1;
    }
    for (int i = 0; i < 100 && gPipeReaderRunning; ++i) usleep(10'000);
    if (gPipeRead >= 0) {
        close(gPipeRead);
        gPipeRead = -1;
    }
}

void replaceWindow(JNIEnv* env, jobject surface) {
    std::lock_guard<std::mutex> lock(gWindowMutex);
    if (gWindow != nullptr) {
        ANativeWindow_release(gWindow);
        gWindow = nullptr;
    }
    if (surface != nullptr) {
        gWindow = ANativeWindow_fromSurface(env, surface);
        if (gWindow != nullptr) {
            pushLog("Native window attached: " + std::to_string(ANativeWindow_getWidth(gWindow)) + "x" +
                    std::to_string(ANativeWindow_getHeight(gWindow)));
        }
    }
}

jobjectArray buildJavaStringArray(JNIEnv* env, const std::vector<std::string>& arguments) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(arguments.size()), stringClass, nullptr);
    for (size_t i = 0; i < arguments.size(); ++i) {
        jstring value = env->NewStringUTF(arguments[i].c_str());
        env->SetObjectArrayElement(array, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(stringClass);
    return array;
}

void describeAndClear(JNIEnv* env, const std::string& stage) {
    if (!env->ExceptionCheck()) return;
    pushError("Java exception during " + stage + "; stack trace follows in output");
    env->ExceptionDescribe();
    env->ExceptionClear();
}

using GetCreatedJavaVms = jint (*)(JavaVM**, jsize, jsize*);
using MojoNativeLoadJvm = jboolean (*)(
    JNIEnv*, jclass, jstring, jobjectArray, jstring, jobjectArray, jboolean
);
using MojoNativeSetupExit = void (*)(JNIEnv*, jclass, jobject);

JavaVM* locateCreatedVm() {
    if (gMinecraftVm != nullptr) return gMinecraftVm;
    if (gJvmHandle == nullptr) return nullptr;
    auto getter = reinterpret_cast<GetCreatedJavaVms>(dlsym(gJvmHandle, "JNI_GetCreatedJavaVMs"));
    if (getter == nullptr) return nullptr;
    JavaVM* candidate = nullptr;
    jsize count = 0;
    if (getter(&candidate, 1, &count) == JNI_OK && count > 0) {
        gMinecraftVm = candidate;
        return candidate;
    }
    return nullptr;
}

using MojoSetRendererPath = void (*)(JNIEnv*, jclass, jstring);
using MojoConfigureDisplay = void (*)(JNIEnv*, jclass, jint, jint, jint);
using MojoConfigureRenderer = jboolean (*)(JNIEnv*, jclass, jstring, jboolean, jboolean, jint);

std::string findPreloadByTokens(const std::vector<std::string>& libraries,
                                const std::vector<std::string>& tokens) {
    for (const auto& path : libraries) {
        std::string lower = fs::path(path).filename().string();
        std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char ch) {
            return static_cast<char>(std::tolower(ch));
        });
        for (const auto& token : tokens) {
            if (lower.find(token) != std::string::npos) return path;
        }
    }
    return {};
}

bool configureMojoRenderer(JNIEnv* env, const std::vector<std::string>& preloadLibraries) {
    auto setPath = reinterpret_cast<MojoSetRendererPath>(
        dlsym(RTLD_DEFAULT, "Java_net_kdt_pojavlaunch_utils_JREUtils_nsetRendererLibraryPath"));
    auto configureDisplay = reinterpret_cast<MojoConfigureDisplay>(
        dlsym(RTLD_DEFAULT, "Java_net_kdt_pojavlaunch_utils_JREUtils_configureRenderspecDisplay"));
    auto configureRenderer = reinterpret_cast<MojoConfigureRenderer>(
        dlsym(RTLD_DEFAULT, "Java_net_kdt_pojavlaunch_utils_JREUtils_configureRenderspec"));
    if (!setPath || !configureDisplay || !configureRenderer) {
        pushError("Bundled engine renderspec API is unavailable");
        return false;
    }

    const std::string nativePath = getenv("LD_LIBRARY_PATH") ? getenv("LD_LIBRARY_PATH") : "";
    jstring nativePathValue = env->NewStringUTF(nativePath.c_str());
    setPath(env, nullptr, nativePathValue);
    env->DeleteLocalRef(nativePathValue);

    const int width = std::max(1, mclauncher_window_width());
    const int height = std::max(1, mclauncher_window_height());
    configureDisplay(env, nullptr, width, height, 60);

    const char* rendererToken = getenv("MCLAUNCHER_RENDERER_TOKEN");
    if (rendererToken == nullptr || rendererToken[0] == '\0') {
        // Compatibility fallback for third-party plans created before Alpha 03.
        rendererToken = getenv("POJAV_RENDERER");
    }
    std::string renderer = rendererToken && rendererToken[0] != '\0'
        ? rendererToken
        : "opengles3";
    std::transform(renderer.begin(), renderer.end(), renderer.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    std::string rendererLibrary;
    bool useLoaderBypass = false;
    bool useGles = true;
    int glesVersion = 3;
    if (const char* configuredGles = getenv("LIBGL_ES")) {
        char* end = nullptr;
        const long parsed = std::strtol(configuredGles, &end, 10);
        if (end != configuredGles && *end == '\0') {
            glesVersion = parsed >= 3 ? 3 : 2;
        }
    }
    if (renderer == "vulkan") {
        const bool configured = configureRenderer(env, nullptr, nullptr, JNI_FALSE, JNI_FALSE, 3) == JNI_TRUE;
        if (configured) pushLog("Configured native Vulkan surface mode");
        else pushError("Native Vulkan renderer configuration failed");
        return configured;
    }
    if (renderer.find("mobileglues") != std::string::npos) {
        rendererLibrary = findPreloadByTokens(preloadLibraries, {"libmobileglues.so"});
    } else if (renderer.find("ltw") != std::string::npos) {
        rendererLibrary = findPreloadByTokens(preloadLibraries, {"libltw"});
    } else if (renderer.find("ng-gl4es") != std::string::npos ||
               renderer.find("ng_gl4es") != std::string::npos ||
               renderer.find("nggl4es") != std::string::npos) {
        rendererLibrary = findPreloadByTokens(
            preloadLibraries, {"ng-gl4es", "ng_gl4es", "nggl4es"});
    } else if (renderer.find("krypton") != std::string::npos) {
        rendererLibrary = findPreloadByTokens(preloadLibraries, {"krypton"});
    } else if (renderer.find("zink") != std::string::npos || renderer.find("freedreno") != std::string::npos) {
        rendererLibrary = findPreloadByTokens(preloadLibraries, {"libegl_mesa", "libosmesa", "mesa"});
        useLoaderBypass = true;
        useGles = false;
    } else if (renderer.find("angle") != std::string::npos) {
        rendererLibrary = findPreloadByTokens(preloadLibraries, {"libegl_angle", "libegl"});
        useGles = true;
        glesVersion = 3;
    } else if (renderer.find("virgl") != std::string::npos) {
        pushError("VirGL is not supported by the bundled dnbootstrap GLFW engine; install a compatible custom engine to use it");
        return false;
    } else if (renderer.find("opengles") != std::string::npos ||
               renderer.find("gl4es") != std::string::npos) {
        rendererLibrary = findPreloadByTokens(preloadLibraries, {"libgl4es"});
    } else {
        rendererLibrary = findPreloadByTokens(preloadLibraries, {renderer});
    }
    if (rendererLibrary.empty()) {
        pushError("No renderer library matched MCLAUNCHER_RENDERER_TOKEN=" + renderer);
        return false;
    }
    jstring rendererPath = env->NewStringUTF(rendererLibrary.c_str());
    const bool configured = configureRenderer(env, nullptr, rendererPath,
                                              useLoaderBypass ? JNI_TRUE : JNI_FALSE,
                                              useGles ? JNI_TRUE : JNI_FALSE,
                                              glesVersion) == JNI_TRUE;
    env->DeleteLocalRef(rendererPath);
    if (configured) {
        pushLog("Configured bundled renderer " + rendererLibrary +
                " with GLES " + std::to_string(glesVersion));
    }
    else pushError("Bundled renderer configuration failed for " + rendererLibrary);
    return configured;
}

void setupUpstreamBridge(JNIEnv* env, jobject surface) {
    if (gMojoGlfwAvailable && gMojoSurfaceCreated && surface != nullptr) {
        gMojoSurfaceCreated(env, nullptr, surface);
        gMojoSurfaceAttached = true;
        if (gMojoSurfaceUpdated) gMojoSurfaceUpdated(env, nullptr);
        pushLog("Attached Surface to bundled dnbootstrap GLFW");
        return;
    }
    if (surface != nullptr) pushLog("Using MCLauncher native-window compatibility aliases");
}

void releaseUpstreamBridge(JNIEnv* env) {
    if (gMojoGlfwAvailable && gMojoSurfaceDestroyed && gMojoSurfaceAttached) {
        gMojoSurfaceDestroyed(env, nullptr);
        gMojoSurfaceAttached = false;
    }
}

struct LaunchGuard {
    ~LaunchGuard() { gLaunchRunning = false; }
};
}

extern "C" ANativeWindow* mclauncher_get_native_window() {
    std::lock_guard<std::mutex> lock(gWindowMutex);
    return gWindow;
}

// Compatibility aliases used by some Android GLFW/renderer bridges.
extern "C" ANativeWindow* pojav_get_native_window() { return mclauncher_get_native_window(); }
extern "C" ANativeWindow* getNativeWindow() { return mclauncher_get_native_window(); }
extern "C" int mclauncher_window_width() {
    std::lock_guard<std::mutex> lock(gWindowMutex);
    return gWindow ? ANativeWindow_getWidth(gWindow) : 0;
}
extern "C" int mclauncher_window_height() {
    std::lock_guard<std::mutex> lock(gWindowMutex);
    return gWindow ? ANativeWindow_getHeight(gWindow) : 0;
}


/**
 * Renderer/LWJGL bridge entry point. A patched GLFW implementation can poll the
 * Android input queue from its event loop without depending on Kotlin classes.
 */
extern "C" int mclauncher_poll_input_event(
        int* type,
        int* code,
        int* action,
        int* modifiers,
        double* x,
        double* y,
        std::uint32_t* character) {
    std::lock_guard<std::mutex> lock(gInputMutex);
    if (gInputEvents.empty()) return 0;
    const InputEvent event = gInputEvents.front();
    gInputEvents.pop_front();
    if (type) *type = static_cast<int>(event.type);
    if (code) *code = event.code;
    if (action) *action = event.action;
    if (modifiers) *modifiers = event.modifiers;
    if (x) *x = event.x;
    if (y) *y = event.y;
    if (character) *character = event.character;
    return 1;
}

extern "C" int pojav_poll_input_event(
        int* type,
        int* code,
        int* action,
        int* modifiers,
        double* x,
        double* y,
        std::uint32_t* character) {
    return mclauncher_poll_input_event(type, code, action, modifiers, x, y, character);
}

extern "C" void mclauncher_clear_input_events() {
    std::lock_guard<std::mutex> lock(gInputMutex);
    gInputEvents.clear();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mclauncher_app_engine_NativeLaunchBridge_nativeStart(
        JNIEnv* env,
        jobject,
        jobject context,
        jstring javaHomeValue,
        jstring workingDirectoryValue,
        jobjectArray jvmArgumentsValue,
        jstring mainClassValue,
        jobjectArray gameArgumentsValue,
        jobjectArray environmentKeysValue,
        jobjectArray environmentValuesValue,
        jobjectArray preloadLibrariesValue,
        jobject surface) {
    if (gLaunchRunning.exchange(true)) {
        pushError("A Minecraft launch is already running in this process");
        return 20;
    }
    LaunchGuard launchGuard;
    mclauncher_clear_input_events();

    const std::string javaHome = jstringToString(env, javaHomeValue);
    const std::string workingDirectory = jstringToString(env, workingDirectoryValue);
    const std::string mainClass = jstringToString(env, mainClassValue);
    const auto jvmArguments = jobjectArrayToStrings(env, jvmArgumentsValue);
    const auto gameArguments = jobjectArrayToStrings(env, gameArgumentsValue);
    const auto environmentKeys = jobjectArrayToStrings(env, environmentKeysValue);
    const auto environmentValues = jobjectArrayToStrings(env, environmentValuesValue);
    const auto preloadLibraries = jobjectArrayToStrings(env, preloadLibrariesValue);
    env->GetJavaVM(&gAndroidVm);

    if (javaHome.empty() || workingDirectory.empty() || mainClass.empty()) {
        pushError("Launch specification is incomplete");
        return 21;
    }

    replaceWindow(env, surface);
    {
        std::lock_guard<std::mutex> cursorLock(gCursorMutex);
        gForwardCursorX = 0.5;
        gForwardCursorY = 0.5;
        gForwardCursorGrabbed = false;
    }
    beginOutputCapture();

    for (size_t i = 0; i < std::min(environmentKeys.size(), environmentValues.size()); ++i) {
        if (!environmentKeys[i].empty()) setenv(environmentKeys[i].c_str(), environmentValues[i].c_str(), 1);
    }
    setenv("JAVA_HOME", javaHome.c_str(), 1);
    pushLog("Native startup environment configured");

    if (chdir(workingDirectory.c_str()) != 0) {
        pushError("Could not change working directory to " + workingDirectory + ": " + std::strerror(errno));
        endOutputCapture();
        return 22;
    }

    std::vector<std::string> deferredGlfw;
    std::vector<std::string> retryPreloads;
    for (const auto& library : preloadLibraries) {
        const std::string filename = fs::path(library).filename().string();
        if (filename == "libglfw.so") {
            deferredGlfw.push_back(library);
            continue;
        }
        if (unsupportedProcessHookLibrary(filename)) {
            pushLog("Skipping unrelated process-hook library " + filename);
            continue;
        }
        // Renderer/driver libraries are opened by the configured engine after its
        // environment and namespace have been set. Preloading every backend at once
        // causes symbol collisions between GL4ES, Mesa, ANGLE and LTW.
        if (library.find("/renderers/") != std::string::npos ||
            library.find("/drivers/") != std::string::npos) {
            continue;
        }
        void* handle = loadAbsolute(library, false);
        if (handle == nullptr) {
            // A third-party payload may gain a new DT_NEEDED relationship that
            // the Kotlin ordering table does not know about yet. Retry once
            // after the rest of the namespace has been populated.
            retryPreloads.push_back(library);
        } else {
            initializeUpstreamAndroidJni(env, library, handle);
        }
    }
    for (const auto& library : retryPreloads) {
        void* handle = loadAbsolute(library, false);
        initializeUpstreamAndroidJni(env, library, handle);
    }
    if (!configureMojoRenderer(env, preloadLibraries)) {
        replaceWindow(env, nullptr);
        endOutputCapture();
        return 31;
    }
    // The renderer is fully selected through the native renderspec API above.
    // Do not leak legacy launcher markers into the Minecraft JVM: Sodium 0.8.x
    // deliberately aborts when POJAV_RENDERER is present.
    unsetenv("POJAV_RENDERER");
    unsetenv("POJAV_LAUNCHER");
    pushLog("Sanitized legacy renderer markers before Minecraft JVM startup");
    for (const auto& library : deferredGlfw) {
        void* handle = loadAbsolute(library, true);
        initializeMojoGlfw(env, library, handle);
    }
    if (!gMojoGlfwAvailable) {
        pushError("Bundled libglfw.so could not initialize its Android bridge");
        replaceWindow(env, nullptr);
        endOutputCapture();
        return 32;
    }
    setupUpstreamBridge(env, surface);

    const fs::path jvmPath = findByName(javaHome, "libjvm.so");
    if (jvmPath.empty()) {
        pushError("libjvm.so was not found inside " + javaHome);
        releaseUpstreamBridge(env);
        replaceWindow(env, nullptr);
        endOutputCapture();
        return 23;
    }
    gJvmHandle = loadAbsolute(jvmPath.string(), true);
    if (gJvmHandle == nullptr) {
        releaseUpstreamBridge(env);
        replaceWindow(env, nullptr);
        endOutputCapture();
        return 24;
    }

    for (const char* name : {"libverify.so", "libjava.so", "libzip.so", "libnet.so", "libnio.so",
                             "libmanagement.so", "libmanagement_ext.so", "libmanagement_agent.so",
                             "libinstrument.so", "libawt.so", "libawt_headless.so", "libjawt.so",
                             "libfontmanager.so", "libfreetype.so", "libjimage.so", "libprefs.so",
                             "libjavajpeg.so", "libextnet.so", "librmi.so", "libjaas.so",
                             "libj2pkcs11.so", "libj2gss.so", "libdt_socket.so", "libsyslookup.so"}) {
        const fs::path found = findByName(javaHome, name);
        if (!found.empty()) loadAbsolute(found.string(), false);
    }

    auto nativeLoadJvm = reinterpret_cast<MojoNativeLoadJvm>(
        dlsym(RTLD_DEFAULT, "Java_net_kdt_pojavlaunch_utils_jre_JavaRunner_nativeLoadJVM")
    );
    auto nativeSetupExit = reinterpret_cast<MojoNativeSetupExit>(
        dlsym(RTLD_DEFAULT, "Java_net_kdt_pojavlaunch_utils_jre_JavaRunner_nativeSetupExit")
    );
    if (nativeLoadJvm == nullptr || nativeSetupExit == nullptr) {
        pushError("Pinned MojoLauncher embedded JVM entry points are unavailable");
        releaseUpstreamBridge(env);
        replaceWindow(env, nullptr);
        endOutputCapture();
        return 25;
    }
    if (context == nullptr) {
        pushError("Android context is unavailable for the embedded JVM exit hook");
        releaseUpstreamBridge(env);
        replaceWindow(env, nullptr);
        endOutputCapture();
        return 26;
    }

    nativeSetupExit(env, nullptr, context);
    if (env->ExceptionCheck()) {
        describeAndClear(env, "embedded JVM exit-hook setup");
        releaseUpstreamBridge(env);
        replaceWindow(env, nullptr);
        endOutputCapture();
        return 27;
    }

    jobjectArray javaArguments = buildJavaStringArray(env, jvmArguments);
    jobjectArray applicationArguments = buildJavaStringArray(env, gameArguments);
    jstring jvmPathValue = env->NewStringUTF(jvmPath.string().c_str());
    jstring mainClassValueCopy = env->NewStringUTF(mainClass.c_str());
    if (javaArguments == nullptr || applicationArguments == nullptr ||
        jvmPathValue == nullptr || mainClassValueCopy == nullptr || env->ExceptionCheck()) {
        describeAndClear(env, "embedded JVM argument preparation");
        if (javaArguments != nullptr) env->DeleteLocalRef(javaArguments);
        if (applicationArguments != nullptr) env->DeleteLocalRef(applicationArguments);
        if (jvmPathValue != nullptr) env->DeleteLocalRef(jvmPathValue);
        if (mainClassValueCopy != nullptr) env->DeleteLocalRef(mainClassValueCopy);
        releaseUpstreamBridge(env);
        replaceWindow(env, nullptr);
        endOutputCapture();
        return 28;
    }

    const bool hasJavaAgents = std::any_of(
        jvmArguments.begin(),
        jvmArguments.end(),
        [](const std::string& argument) { return argument.rfind("-javaagent:", 0) == 0; }
    );
    for (const auto& argument : jvmArguments) pushLog("JVM: " + argument);
    for (const auto& argument : gameArguments) pushLog("GAME: " + argument);
    pushLog(
        "Launching " + mainClass +
        " with the pinned MojoLauncher JNI_CreateJavaVM engine"
    );

    const jboolean launchResult = nativeLoadJvm(
        env,
        nullptr,
        jvmPathValue,
        javaArguments,
        mainClassValueCopy,
        applicationArguments,
        hasJavaAgents ? JNI_TRUE : JNI_FALSE
    );
    const bool hadException = env->ExceptionCheck();
    describeAndClear(env, "pinned MojoLauncher embedded JVM");
    env->DeleteLocalRef(mainClassValueCopy);
    env->DeleteLocalRef(jvmPathValue);
    env->DeleteLocalRef(applicationArguments);
    env->DeleteLocalRef(javaArguments);

    pushLog(
        launchResult == JNI_TRUE
            ? "Embedded Minecraft main method returned normally"
            : "Embedded Minecraft launch returned a failure"
    );
    gMinecraftVm = nullptr;
    releaseUpstreamBridge(env);
    replaceWindow(env, nullptr);
    endOutputCapture();
    return (launchResult == JNI_TRUE && !hadException) ? 0 : 30;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mclauncher_app_engine_NativeLaunchBridge_nativeSetSurface(JNIEnv* env, jobject, jobject surface) {
    if (surface == nullptr) {
        releaseUpstreamBridge(env);
        replaceWindow(env, nullptr);
        return;
    }
    replaceWindow(env, surface);
    if (gMojoGlfwAvailable) {
        if (gMojoSurfaceAttached) {
            if (gMojoSurfaceUpdated) gMojoSurfaceUpdated(env, nullptr);
        } else {
            setupUpstreamBridge(env, surface);
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mclauncher_app_engine_NativeLaunchBridge_nativeStop(JNIEnv* env, jobject) {
    JavaVM* vm = locateCreatedVm();
    if (vm == nullptr) {
        releaseUpstreamBridge(env);
        replaceWindow(env, nullptr);
        return;
    }

    JNIEnv* minecraftEnv = nullptr;
    bool attached = false;
    const jint getEnvResult = vm->GetEnv(reinterpret_cast<void**>(&minecraftEnv), JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&minecraftEnv, nullptr) == JNI_OK) attached = true;
    }
    if (minecraftEnv != nullptr) {
        jclass systemClass = minecraftEnv->FindClass("java/lang/System");
        if (systemClass != nullptr) {
            jmethodID exitMethod = minecraftEnv->GetStaticMethodID(systemClass, "exit", "(I)V");
            if (exitMethod != nullptr) minecraftEnv->CallStaticVoidMethod(systemClass, exitMethod, 0);
            minecraftEnv->DeleteLocalRef(systemClass);
        }
        describeAndClear(minecraftEnv, "stop request");
    }
    if (attached) vm->DetachCurrentThread();
    releaseUpstreamBridge(env);
    replaceWindow(env, nullptr);
}



extern "C" JNIEXPORT void JNICALL
Java_com_mclauncher_app_engine_NativeLaunchBridge_nativeSendKey(
        JNIEnv* env, jobject, jint key, jint action, jint modifiers) {
    if (gMojoGlfwAvailable && gMojoSendKey != nullptr) {
        gMojoSendKey(env, nullptr, key, action, modifiers);
        return;
    }
    if (gUpstreamAndroidJniInitialized && gCallbackSendKey != nullptr) {
        env->CallStaticVoidMethod(gCallbackBridgeClass, gCallbackSendKey, key, 0, action, modifiers);
        if (!env->ExceptionCheck()) return;
        env->ExceptionClear();
    }
    pushInput(InputEvent{InputEventType::Key, key, action, modifiers});
}

extern "C" JNIEXPORT void JNICALL
Java_com_mclauncher_app_engine_NativeLaunchBridge_nativeSendRawKey(
        JNIEnv* env, jobject, jint androidKey, jint glfwKey, jint action, jint modifiers, jint unicode) {
    if (gMojoGlfwAvailable && gMojoSendRawKey != nullptr) {
        gMojoSendRawKey(
            env,
            nullptr,
            androidKey,
            action,
            modifiers,
            static_cast<jchar>(unicode)
        );
        return;
    }
    if (gMojoGlfwAvailable && gMojoSendKey != nullptr) {
        gMojoSendKey(env, nullptr, glfwKey, action, modifiers);
        return;
    }
    pushInput(InputEvent{InputEventType::Key, glfwKey, action, modifiers});
}

extern "C" JNIEXPORT void JNICALL
Java_com_mclauncher_app_engine_NativeLaunchBridge_nativeSendChar(
        JNIEnv* env, jobject, jint codePoint) {
    if (gMojoGlfwAvailable && gMojoSendUnicode != nullptr) {
        const jchar chars[] = { static_cast<jchar>(codePoint) };
        jstring text = env->NewString(chars, 1);
        gMojoSendUnicode(env, nullptr, text, 0);
        env->DeleteLocalRef(text);
        return;
    }
    if (gUpstreamAndroidJniInitialized && gCallbackSendChar != nullptr) {
        env->CallStaticBooleanMethod(gCallbackBridgeClass, gCallbackSendChar, static_cast<jchar>(codePoint));
        if (!env->ExceptionCheck()) return;
        env->ExceptionClear();
    }
    InputEvent event{};
    event.type = InputEventType::Character;
    event.character = static_cast<std::uint32_t>(codePoint);
    pushInput(event);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mclauncher_app_engine_NativeLaunchBridge_nativeSendMouseButton(
        JNIEnv* env, jobject, jint button, jint action, jint modifiers) {
    if (!gLoggedMouseButtonInput.exchange(true)) {
        pushLog("First Android mouse-button event reached the native GLFW bridge");
    }
    if (gMojoGlfwAvailable && gMojoSendMouse != nullptr) {
        gMojoSendMouse(env, nullptr, button, action, modifiers);
        return;
    }
    if (gUpstreamAndroidJniInitialized && gCallbackSendMouseButton != nullptr) {
        env->CallStaticVoidMethod(gCallbackBridgeClass, gCallbackSendMouseButton, button, action, modifiers);
        if (!env->ExceptionCheck()) return;
        env->ExceptionClear();
    }
    pushInput(InputEvent{InputEventType::MouseButton, button, action, modifiers});
}

extern "C" JNIEXPORT void JNICALL
Java_com_mclauncher_app_engine_NativeLaunchBridge_nativeSendCursorDelta(
        JNIEnv* env, jobject, jfloat dx, jfloat dy) {
    const double width = std::max(1, mclauncher_window_width());
    const double height = std::max(1, mclauncher_window_height());
    double cursorX;
    double cursorY;
    {
        std::lock_guard<std::mutex> cursorLock(gCursorMutex);
        gForwardCursorX += dx / width;
        gForwardCursorY += dy / height;
        if (!gForwardCursorGrabbed) {
            gForwardCursorX = std::clamp(gForwardCursorX, 0.0, 1.0);
            gForwardCursorY = std::clamp(gForwardCursorY, 0.0, 1.0);
        }
        cursorX = gForwardCursorX;
        cursorY = gForwardCursorY;
    }
    if (gMojoGlfwAvailable && gMojoSendMousePosition != nullptr) {
        gMojoSendMousePosition(env, nullptr, cursorX, cursorY);
        return;
    }
    if (gUpstreamAndroidJniInitialized && gCallbackSendCursorPos != nullptr) {
        env->CallStaticVoidMethod(
            gCallbackBridgeClass, gCallbackSendCursorPos,
            static_cast<jfloat>(cursorX), static_cast<jfloat>(cursorY));
        if (!env->ExceptionCheck()) return;
        env->ExceptionClear();
    }
    InputEvent event{};
    event.type = InputEventType::CursorDelta;
    event.x = dx;
    event.y = dy;
    pushInput(event);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mclauncher_app_engine_NativeLaunchBridge_nativeSendCursorPosition(
        JNIEnv* env, jobject, jdouble x, jdouble y) {
    if (!gLoggedAbsolutePointerInput.exchange(true)) {
        pushLog("First absolute Android pointer event reached the native GLFW bridge");
    }
    double cursorX;
    double cursorY;
    {
        std::lock_guard<std::mutex> cursorLock(gCursorMutex);
        gForwardCursorX = std::clamp(x, 0.0, 1.0);
        gForwardCursorY = std::clamp(y, 0.0, 1.0);
        cursorX = gForwardCursorX;
        cursorY = gForwardCursorY;
    }
    if (gMojoGlfwAvailable && gMojoSendMousePosition != nullptr) {
        gMojoSendMousePosition(env, nullptr, cursorX, cursorY);
        return;
    }
    if (gUpstreamAndroidJniInitialized && gCallbackSendCursorPos != nullptr) {
        env->CallStaticVoidMethod(
            gCallbackBridgeClass,
            gCallbackSendCursorPos,
            static_cast<jfloat>(cursorX),
            static_cast<jfloat>(cursorY)
        );
        if (!env->ExceptionCheck()) return;
        env->ExceptionClear();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mclauncher_app_engine_NativeLaunchBridge_nativeSendTouchButton(
        JNIEnv* env, jobject, jdouble x, jdouble y,
        jint button, jint action, jint modifiers) {
    const double cursorX = std::clamp(x, 0.0, 1.0);
    const double cursorY = std::clamp(y, 0.0, 1.0);
    {
        std::lock_guard<std::mutex> cursorLock(gCursorMutex);
        gForwardCursorX = cursorX;
        gForwardCursorY = cursorY;
    }
    if (!gLoggedDirectTouchInput.exchange(true)) {
        char detail[160];
        std::snprintf(
            detail,
            sizeof(detail),
            "First atomic direct-touch event reached GLFW at normalized %.4f,%.4f",
            cursorX,
            cursorY
        );
        pushLog(detail);
    }
    if (gMojoGlfwAvailable && gMojoSendMouseAt != nullptr) {
        gMojoSendMouseAt(env, nullptr, button, action, modifiers, cursorX, cursorY);
        return;
    }
    if (gUpstreamAndroidJniInitialized &&
        gCallbackSendCursorPos != nullptr &&
        gCallbackSendMouseButton != nullptr) {
        env->CallStaticVoidMethod(
            gCallbackBridgeClass,
            gCallbackSendCursorPos,
            static_cast<jfloat>(cursorX),
            static_cast<jfloat>(cursorY)
        );
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        } else {
            env->CallStaticVoidMethod(
                gCallbackBridgeClass,
                gCallbackSendMouseButton,
                button,
                action,
                modifiers
            );
            if (!env->ExceptionCheck()) return;
            env->ExceptionClear();
        }
    }
    InputEvent event{};
    event.type = InputEventType::MouseButton;
    event.code = button;
    event.action = action;
    event.modifiers = modifiers;
    event.x = cursorX;
    event.y = cursorY;
    pushInput(event);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mclauncher_app_engine_NativeLaunchBridge_nativeSyncPointerState(
        JNIEnv*, jobject, jdouble x, jdouble y, jboolean grabbing) {
    std::lock_guard<std::mutex> cursorLock(gCursorMutex);
    gForwardCursorX = x;
    gForwardCursorY = y;
    gForwardCursorGrabbed = grabbing == JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mclauncher_app_engine_NativeLaunchBridge_nativeSendScroll(
        JNIEnv* env, jobject, jfloat dx, jfloat dy) {
    if (gMojoGlfwAvailable && gMojoSendScroll != nullptr) {
        gMojoSendScroll(env, nullptr, static_cast<jdouble>(dx), static_cast<jdouble>(dy));
        return;
    }
    if (gUpstreamAndroidJniInitialized && gCallbackSendScroll != nullptr) {
        env->CallStaticVoidMethod(
            gCallbackBridgeClass, gCallbackSendScroll,
            static_cast<jdouble>(dx), static_cast<jdouble>(dy));
        if (!env->ExceptionCheck()) return;
        env->ExceptionClear();
    }
    InputEvent event{};
    event.type = InputEventType::Scroll;
    event.x = dx;
    event.y = dy;
    pushInput(event);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mclauncher_app_engine_NativeLaunchBridge_nativeSendGamepadAxis(
        JNIEnv*, jobject, jint axis, jfloat value) {
    InputEvent event{};
    event.type = InputEventType::GamepadAxis;
    event.code = axis;
    event.x = value;
    pushInput(event);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mclauncher_app_engine_NativeLaunchBridge_nativeDrainLogs(JNIEnv* env, jobject) {
    std::string output;
    {
        std::lock_guard<std::mutex> lock(gLogMutex);
        const size_t count = std::min<size_t>(gLogLines.size(), 30);
        for (size_t i = 0; i < count; ++i) {
            if (!output.empty()) output.push_back('\n');
            output += gLogLines.front();
            gLogLines.pop_front();
        }
    }
    if (output.empty()) return nullptr;
    return env->NewStringUTF(output.c_str());
}
