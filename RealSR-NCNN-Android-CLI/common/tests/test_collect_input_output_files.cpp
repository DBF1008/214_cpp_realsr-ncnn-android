// Regression tests for collect_input_output_files() in common/image_processor.h
//
// Focus: directory (batch) input must treat the output path as a directory, and
// must REJECT an output path that clearly names a single image file (e.g.
// "-o out/result.png") instead of creating a directory literally named
// "result.png". Single-file input behaviour must stay unchanged.
//
// This is a hermetic unit test: it only exercises path bookkeeping, so the
// "images" are empty placeholder files (collect_input_output_files never decodes
// them). No models or built CLI binaries are required.
//
// Build & run:  ./run_tests.sh        (or see that script for the compile line)

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>
#include <sys/stat.h>

#include "../image_processor.h"

static int g_checks = 0;
static int g_failures = 0;

#define CHECK(cond, msg)                                   \
    do {                                                   \
        g_checks++;                                        \
        if (cond) {                                        \
            fprintf(stderr, "    [ok]   %s\n", (msg));      \
        } else {                                           \
            g_failures++;                                  \
            fprintf(stderr, "    [FAIL] %s\n", (msg));      \
        }                                                  \
    } while (0)

static bool path_exists(const std::string& p) {
    struct stat s;
    return stat(p.c_str(), &s) == 0;
}
static bool is_dir(const std::string& p) {
    struct stat s;
    return stat(p.c_str(), &s) == 0 && S_ISDIR(s.st_mode);
}
static void touch(const std::string& p) {
    FILE* f = fopen(p.c_str(), "wb");
    if (f) fclose(f);
}
static bool starts_with(const std::string& s, const std::string& prefix) {
    return s.size() >= prefix.size() && s.compare(0, prefix.size(), prefix) == 0;
}

int main() {
    char tmpl[] = "/tmp/iptest_XXXXXX";
    char* base = mkdtemp(tmpl);
    if (!base) {
        perror("mkdtemp");
        return 2;
    }
    const std::string root(base);

    // Input directory with two images, one nested in a sub-directory so that the
    // recursive mapping (and nested output-dir creation) is exercised too.
    const std::string in = root + "/in";
    const std::string sub = in + "/sub";
    mkdir(in.c_str(), 0755);
    mkdir(sub.c_str(), 0755);
    touch(in + "/a.png");
    touch(sub + "/b.jpg");

    const std::string single_in = in + "/a.png";
    const path_t prog = "realsr-ncnn";
    const path_t pattern = "{name}"; // keep original base names
    const path_t fmt = "";           // no forced output format

    // --- Case 1: dir input + file-looking output  => MUST error, MUST NOT create a dir.
    {
        fprintf(stderr, "Case 1: dir input + file-looking output (-o c1/result.png)\n");
        std::vector<path_t> ins, outs;
        const std::string out = root + "/c1/result.png";
        int ret = collect_input_output_files(in, out, fmt, pattern, prog, ins, outs);
        CHECK(ret != 0, "returns error for a file-looking output in batch mode");
        CHECK(!path_exists(out), "did NOT create a directory named result.png");
        CHECK(!path_exists(root + "/c1"), "did NOT create the intermediate directory either");
    }

    // --- Case 2: dir input + directory output (no extension) => ok, recursive mapping.
    {
        fprintf(stderr, "Case 2: dir input + directory output (-o c2_enhanced)\n");
        std::vector<path_t> ins, outs;
        const std::string out = root + "/c2_enhanced";
        int ret = collect_input_output_files(in, out, fmt, pattern, prog, ins, outs);
        CHECK(ret == 0, "returns success");
        CHECK(is_dir(out), "created the output directory");
        CHECK(ins.size() == 2 && outs.size() == 2, "collected both input images");

        bool all_under = !outs.empty();
        bool parents_ok = !outs.empty();
        for (size_t i = 0; i < outs.size(); i++) {
            if (!starts_with(outs[i], out + "/")) all_under = false;
            size_t slash = outs[i].find_last_of('/');
            if (slash == std::string::npos || !is_dir(outs[i].substr(0, slash)))
                parents_ok = false;
        }
        CHECK(all_under, "every output path is under the output directory");
        CHECK(parents_ok, "output parent directories were created (incl. sub/)");
    }

    // --- Case 3: dir input + already-existing directory output => ok.
    {
        fprintf(stderr, "Case 3: dir input + existing directory output\n");
        std::vector<path_t> ins, outs;
        const std::string out = root + "/c3_existing";
        mkdir(out.c_str(), 0755);
        int ret = collect_input_output_files(in, out, fmt, pattern, prog, ins, outs);
        CHECK(ret == 0, "returns success for an existing directory");
        CHECK(ins.size() == 2, "collected both input images");
    }

    // --- Case 4: single-file input + file-looking output => unchanged (used as file path).
    {
        fprintf(stderr, "Case 4: file input + file output (-o c4/result.png)\n");
        std::vector<path_t> ins, outs;
        const std::string out = root + "/c4/result.png";
        int ret = collect_input_output_files(single_in, out, fmt, pattern, prog, ins, outs);
        CHECK(ret == 0, "returns success in single-file mode");
        CHECK(outs.size() == 1 && outs[0] == out, "output equals the exact file path");
        CHECK(!is_dir(out), "did not turn the file path into a directory");
    }

    // --- Case 5: single-file input + extensionless output => extension appended.
    {
        fprintf(stderr, "Case 5: file input + extensionless output (-o c5/result)\n");
        std::vector<path_t> ins, outs;
        const std::string out = root + "/c5/result";
        int ret = collect_input_output_files(single_in, out, fmt, pattern, prog, ins, outs);
        CHECK(ret == 0, "returns success");
        CHECK(outs.size() == 1 && outs[0] == out + ".png",
              "extension appended from the input file (.png)");
    }

    // --- Case 6: dir input + non-image-extension output => still treated as a directory.
    //     This pins the narrowed rule: only image extensions count as "file-like".
    {
        fprintf(stderr, "Case 6: dir input + non-image-extension output (-o c6/data.bin)\n");
        std::vector<path_t> ins, outs;
        const std::string out = root + "/c6/data.bin";
        int ret = collect_input_output_files(in, out, fmt, pattern, prog, ins, outs);
        CHECK(ret == 0, "a non-image extension is treated as a directory name");
        CHECK(is_dir(out), "created a directory named data.bin");
    }

    // Cleanup the temp workspace.
    std::string rm = "rm -rf \"" + root + "\"";
    if (system(rm.c_str()) != 0)
        fprintf(stderr, "warning: failed to clean up %s\n", root.c_str());

    fprintf(stderr, "\n%d checks, %d failure(s)\n", g_checks, g_failures);
    if (g_failures) {
        fprintf(stderr, "RESULT: FAIL\n");
        return 1;
    }
    fprintf(stderr, "RESULT: PASS\n");
    return 0;
}
