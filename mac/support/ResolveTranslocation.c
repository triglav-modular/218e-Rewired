/* Print where a translocated app actually lives.
 *
 * macOS runs a quarantined app from a read-only copy of itself under
 * /AppTranslocation, and from inside that copy there is no way to reach the
 * folder the app was unzipped into - which is where the firmware sits.  The
 * system knows the mapping and will hand it over: SecTranslocateCreateOriginal
 * PathForURL, in Security.framework since 10.12.
 *
 * Reached through dlsym rather than a header, because SecTranslocate.h is not
 * in the SDK: the symbol has been exported for years but was never published
 * as a header to compile against.
 *
 *   resolve-translocation <path>   ->  the original path, or exit non-zero
 *
 * Build (universal, as shipped):
 *   clang -O2 -target arm64-apple-macos11  -framework CoreFoundation \
 *         -o /tmp/rt-arm64 mac/support/ResolveTranslocation.c
 *   clang -O2 -target x86_64-apple-macos11 -framework CoreFoundation \
 *         -o /tmp/rt-x86   mac/support/ResolveTranslocation.c
 *   lipo -create -output mac/support/resolve-translocation /tmp/rt-arm64 /tmp/rt-x86
 */
#include <CoreFoundation/CoreFoundation.h>
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>

typedef CFURLRef (*original_path_fn)(CFURLRef, CFErrorRef *);
typedef Boolean (*is_translocated_fn)(CFURLRef, bool *, CFErrorRef *);

static const char *SECURITY =
    "/System/Library/Frameworks/Security.framework/Security";

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: resolve-translocation <path>\n");
        return 2;
    }

    void *security = dlopen(SECURITY, RTLD_LAZY);
    if (!security) return 3;

    original_path_fn original =
        (original_path_fn)dlsym(security, "SecTranslocateCreateOriginalPathForURL");
    is_translocated_fn translocated =
        (is_translocated_fn)dlsym(security, "SecTranslocateIsTranslocatedURL");
    if (!original || !translocated) return 3;

    CFStringRef path = CFStringCreateWithCString(NULL, argv[1],
                                                 kCFStringEncodingUTF8);
    if (!path) return 4;
    CFURLRef url = CFURLCreateWithFileSystemPath(NULL, path, kCFURLPOSIXPathStyle,
                                                 true);
    CFRelease(path);
    if (!url) return 4;

    /* Asking about a path that was never translocated is not an error, but it
     * has no answer either; say so with an exit code rather than echoing the
     * path back and letting the caller think it resolved something. */
    bool is_translocated = false;
    CFErrorRef error = NULL;
    if (!translocated(url, &is_translocated, &error) || !is_translocated) {
        if (error) CFRelease(error);
        CFRelease(url);
        return 1;
    }

    CFURLRef real = original(url, &error);
    CFRelease(url);
    if (!real) {
        if (error) CFRelease(error);
        return 5;
    }

    char buffer[PATH_MAX];
    Boolean ok = CFURLGetFileSystemRepresentation(real, true, (UInt8 *)buffer,
                                                  sizeof buffer);
    CFRelease(real);
    if (!ok) return 6;

    printf("%s\n", buffer);
    return 0;
}
