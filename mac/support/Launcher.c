/* The app's entry point.
 *
 * It exists to be a real, native Mach-O.  The work is all in launch.sh next to
 * it, and that was the bundle's executable until macOS was asked what
 * architecture the app is: a shell script has none, so LaunchServices decided
 * the app might want Rosetta, offered "Open using Rosetta" in Get Info with the
 * box already ticked, and listed the app among those whose Intel support is
 * ending.  Every binary inside was universal the whole time; the thing being
 * judged was the one file that was not a binary at all.
 *
 * Build (universal, as shipped):
 *   clang -O2 -target arm64-apple-macos11  -o /tmp/l-arm64 mac/support/Launcher.c
 *   clang -O2 -target x86_64-apple-macos11 -o /tmp/l-x86   mac/support/Launcher.c
 *   lipo -create -output <bundle>/Contents/MacOS/launcher /tmp/l-arm64 /tmp/l-x86
 */
#include <libgen.h>
#include <limits.h>
#include <mach-o/dyld.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    (void)argc; (void)argv;

    char path[PATH_MAX];
    uint32_t size = sizeof path;
    if (_NSGetExecutablePath(path, &size) != 0) {
        fprintf(stderr, "cannot find my own path\n");
        return 1;
    }
    /* Not realpath(): under App Translocation the resolved path is the
     * read-only copy either way, and launch.sh is what asks the system where
     * the original is.  dirname() may write to its argument, so it gets one
     * it is allowed to keep. */
    char here[PATH_MAX];
    snprintf(here, sizeof here, "%s", path);
    const char *macos = dirname(here);

    char script[PATH_MAX];
    snprintf(script, sizeof script, "%s/../Resources/launch.sh", macos);

    execl("/bin/bash", "bash", script, (char *)NULL);
    perror("could not start launch.sh");
    return 1;
}
