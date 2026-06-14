/*
 * Regression test for collect_input_output_files() directory-input validation.
 *
 * Verifies that when the input is a directory:
 *   - File-like output paths (with image extensions) are rejected
 *   - Existing regular file output paths are rejected
 *   - Valid directory output paths work correctly
 *
 * Also verifies single-file mode is unaffected.
 *
 * Build:  g++ -std=c++11 -I../common -o test_output_path_validation test_output_path_validation.cpp
 * Run:    ./test_output_path_validation
 */

#include "image_processor.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sys/stat.h>
#include <unistd.h>

static int tests_run = 0;
static int tests_passed = 0;
static int tests_failed = 0;

#define TEST_ASSERT(cond, msg)                                                     \
    do {                                                                           \
        tests_run++;                                                               \
        if (!(cond)) {                                                             \
            fprintf(stderr, "  FAIL: %s (line %d): %s\n", msg, __LINE__, #cond);  \
            tests_failed++;                                                        \
        } else {                                                                   \
            fprintf(stdout, "  PASS: %s\n", msg);                                 \
            tests_passed++;                                                        \
        }                                                                          \
    } while (0)

/* Helper: create a directory */
static void make_dir(const char* path)
{
    mkdir(path, 0755);
}

/* Helper: create an empty file */
static void make_file(const char* path)
{
    FILE* fp = fopen(path, "w");
    if (fp) fclose(fp);
}

/* Helper: remove a file */
static void remove_file(const char* path)
{
    unlink(path);
}

/* Helper: remove a directory (empty) */
static void remove_dir(const char* path)
{
    rmdir(path);
}

/* Helper: check if a path exists as a directory */
static bool is_dir(const char* path)
{
    struct stat st;
    if (stat(path, &st) != 0) return false;
    return S_ISDIR(st.st_mode);
}

/* Helper: check if a path exists */
static bool exists(const char* path)
{
    struct stat st;
    return stat(path, &st) == 0;
}

/* Helper: recursive cleanup of a directory tree */
static void cleanup_dir(const char* path)
{
    DIR* dir = opendir(path);
    if (!dir) return;
    struct dirent* ent;
    char subpath[512];
    while ((ent = readdir(dir))) {
        if (strcmp(ent->d_name, ".") == 0 || strcmp(ent->d_name, "..") == 0)
            continue;
        snprintf(subpath, sizeof(subpath), "%s/%s", path, ent->d_name);
        struct stat st;
        if (stat(subpath, &st) == 0) {
            if (S_ISDIR(st.st_mode))
                cleanup_dir(subpath);
            else
                unlink(subpath);
        }
    }
    closedir(dir);
    rmdir(path);
}

/* ======================================================================== */
/* Test: path_looks_like_image_file helper                                  */
/* ======================================================================== */
static void test_path_looks_like_image_file()
{
    fprintf(stdout, "\n[test_path_looks_like_image_file]\n");

    TEST_ASSERT(path_looks_like_image_file("result.png") == true,
                "result.png should look like image file");
    TEST_ASSERT(path_looks_like_image_file("result.PNG") == true,
                "result.PNG (uppercase) should look like image file");
    TEST_ASSERT(path_looks_like_image_file("out/result.jpg") == true,
                "out/result.jpg should look like image file");
    TEST_ASSERT(path_looks_like_image_file("out/result.jpeg") == true,
                "out/result.jpeg should look like image file");
    TEST_ASSERT(path_looks_like_image_file("out/result.bmp") == true,
                "out/result.bmp should look like image file");
    TEST_ASSERT(path_looks_like_image_file("out/result.webp") == true,
                "out/result.webp should look like image file");
    TEST_ASSERT(path_looks_like_image_file("out/result.tif") == true,
                "out/result.tif should look like image file");
    TEST_ASSERT(path_looks_like_image_file("out/result.tiff") == true,
                "out/result.tiff should look like image file");

    TEST_ASSERT(path_looks_like_image_file("output_dir") == false,
                "output_dir (no ext) should NOT look like image file");
    TEST_ASSERT(path_looks_like_image_file("out/subdir") == false,
                "out/subdir (no ext) should NOT look like image file");
    TEST_ASSERT(path_looks_like_image_file("out/result.txt") == false,
                "out/result.txt should NOT look like image file");
    TEST_ASSERT(path_looks_like_image_file("out/result.exe") == false,
                "out/result.exe should NOT look like image file");
    TEST_ASSERT(path_looks_like_image_file("") == false,
                "empty path should NOT look like image file");
    TEST_ASSERT(path_looks_like_image_file("noext") == false,
                "noext should NOT look like image file");
}

/* ======================================================================== */
/* Test: directory input + file-like output is REJECTED                     */
/* ======================================================================== */
static void test_dir_input_file_output_rejected()
{
    fprintf(stdout, "\n[test_dir_input_file_output_rejected]\n");

    /* Setup: create input dir with a dummy image */
    make_dir("/tmp/test_realsr_input");
    make_file("/tmp/test_realsr_input/test.png");

    std::vector<path_t> input_files, output_files;

    /* Case 1: output path has .png extension, does NOT exist */
    {
        /* Make sure the output doesn't exist */
        remove_file("/tmp/test_realsr_output/result.png");
        remove_dir("/tmp/test_realsr_output");

        int ret = collect_input_output_files(
            "/tmp/test_realsr_input",
            "/tmp/test_realsr_output/result.png",
            "png",
            "{name}",
            "test",
            input_files,
            output_files);

        TEST_ASSERT(ret != 0,
                    "dir input + file-like output (.png) should be REJECTED");
        TEST_ASSERT(input_files.empty(),
                    "no input files should be collected on rejection");
        TEST_ASSERT(!is_dir("/tmp/test_realsr_output/result.png"),
                    "result.png directory should NOT be created");
    }

    /* Case 2: output path has .jpg extension, does NOT exist */
    {
        int ret = collect_input_output_files(
            "/tmp/test_realsr_input",
            "/tmp/test_realsr_output/result.jpg",
            "jpg",
            "{name}",
            "test",
            input_files,
            output_files);

        TEST_ASSERT(ret != 0,
                    "dir input + file-like output (.jpg) should be REJECTED");
        TEST_ASSERT(!is_dir("/tmp/test_realsr_output/result.jpg"),
                    "result.jpg directory should NOT be created");
    }

    /* Case 3: output path has .webp extension, nested directory */
    {
        int ret = collect_input_output_files(
            "/tmp/test_realsr_input",
            "/tmp/test_realsr_output/sub/dir/out.webp",
            "webp",
            "{name}",
            "test",
            input_files,
            output_files);

        TEST_ASSERT(ret != 0,
                    "dir input + nested file-like output (.webp) should be REJECTED");
    }

    /* Cleanup */
    remove_file("/tmp/test_realsr_input/test.png");
    remove_dir("/tmp/test_realsr_input");
    cleanup_dir("/tmp/test_realsr_output");
}

/* ======================================================================== */
/* Test: directory input + existing regular file output is REJECTED         */
/* ======================================================================== */
static void test_dir_input_existing_file_output_rejected()
{
    fprintf(stdout, "\n[test_dir_input_existing_file_output_rejected]\n");

    /* Setup */
    make_dir("/tmp/test_realsr_input2");
    make_file("/tmp/test_realsr_input2/test.png");
    make_file("/tmp/test_realsr_existing_file");

    std::vector<path_t> input_files, output_files;

    int ret = collect_input_output_files(
        "/tmp/test_realsr_input2",
        "/tmp/test_realsr_existing_file",
        "png",
        "{name}",
        "test",
        input_files,
        output_files);

    TEST_ASSERT(ret != 0,
                "dir input + existing regular file output should be REJECTED");
    TEST_ASSERT(input_files.empty(),
                "no input files should be collected on rejection");

    /* Cleanup */
    remove_file("/tmp/test_realsr_input2/test.png");
    remove_dir("/tmp/test_realsr_input2");
    remove_file("/tmp/test_realsr_existing_file");
}

/* ======================================================================== */
/* Test: directory input + valid directory output WORKS                     */
/* ======================================================================== */
static void test_dir_input_dir_output_works()
{
    fprintf(stdout, "\n[test_dir_input_dir_output_works]\n");

    /* Setup */
    make_dir("/tmp/test_realsr_input3");
    make_file("/tmp/test_realsr_input3/test.png");

    std::vector<path_t> input_files, output_files;

    /* Case 1: output directory does not exist yet (should be created) */
    cleanup_dir("/tmp/test_realsr_output3");

    int ret = collect_input_output_files(
        "/tmp/test_realsr_input3",
        "/tmp/test_realsr_output3",
        "png",
        "{name}",
        "test",
        input_files,
        output_files);

    TEST_ASSERT(ret == 0,
                "dir input + new directory output should SUCCEED");
    TEST_ASSERT(is_dir("/tmp/test_realsr_output3"),
                "output directory should be created");
    TEST_ASSERT(!input_files.empty(),
                "input files should be collected");
    TEST_ASSERT(input_files.size() == output_files.size(),
                "input and output file counts should match");

    /* Case 2: output directory already exists */
    input_files.clear();
    output_files.clear();

    ret = collect_input_output_files(
        "/tmp/test_realsr_input3",
        "/tmp/test_realsr_output3",
        "png",
        "{name}",
        "test",
        input_files,
        output_files);

    TEST_ASSERT(ret == 0,
                "dir input + existing directory output should SUCCEED");
    TEST_ASSERT(!input_files.empty(),
                "input files should be collected (existing dir)");

    /* Cleanup */
    remove_file("/tmp/test_realsr_input3/test.png");
    remove_dir("/tmp/test_realsr_input3");
    cleanup_dir("/tmp/test_realsr_output3");
}

/* ======================================================================== */
/* Test: directory input + non-image-extension output creates dir normally  */
/* ======================================================================== */
static void test_dir_input_non_image_ext_creates_dir()
{
    fprintf(stdout, "\n[test_dir_input_non_image_ext_creates_dir]\n");

    /* Setup */
    make_dir("/tmp/test_realsr_input4");
    make_file("/tmp/test_realsr_input4/test.png");

    std::vector<path_t> input_files, output_files;

    /* output path has no image extension -> should be created as directory */
    cleanup_dir("/tmp/test_realsr_output4");

    int ret = collect_input_output_files(
        "/tmp/test_realsr_input4",
        "/tmp/test_realsr_output4",
        "png",
        "{name}",
        "test",
        input_files,
        output_files);

    TEST_ASSERT(ret == 0,
                "dir input + plain directory name should SUCCEED");
    TEST_ASSERT(is_dir("/tmp/test_realsr_output4"),
                "output directory should be created");
    TEST_ASSERT(!input_files.empty(),
                "input files should be collected");

    /* Cleanup */
    remove_file("/tmp/test_realsr_input4/test.png");
    remove_dir("/tmp/test_realsr_input4");
    cleanup_dir("/tmp/test_realsr_output4");
}

/* ======================================================================== */
/* Test: single file input is NOT affected by the fix (regression check)    */
/* ======================================================================== */
static void test_single_file_input_unaffected()
{
    fprintf(stdout, "\n[test_single_file_input_unaffected]\n");

    /* Setup */
    make_file("/tmp/test_realsr_single.png");
    remove_file("/tmp/test_realsr_single_out.png");

    std::vector<path_t> input_files, output_files;

    /* Case 1: single file input -> file output (should work) */
    int ret = collect_input_output_files(
        "/tmp/test_realsr_single.png",
        "/tmp/test_realsr_single_out.png",
        "png",
        "{name}",
        "test",
        input_files,
        output_files);

    TEST_ASSERT(ret == 0,
                "single file input + file output should SUCCEED");
    TEST_ASSERT(input_files.size() == 1,
                "exactly one input file should be collected");
    TEST_ASSERT(output_files.size() == 1,
                "exactly one output file should be collected");
    TEST_ASSERT(output_files[0] == "/tmp/test_realsr_single_out.png",
                "output file path should match expected");

    /* Case 2: single file input -> output without extension (should append) */
    input_files.clear();
    output_files.clear();
    remove_file("/tmp/test_realsr_single_out");

    ret = collect_input_output_files(
        "/tmp/test_realsr_single.png",
        "/tmp/test_realsr_single_out",
        "png",
        "{name}",
        "test",
        input_files,
        output_files);

    TEST_ASSERT(ret == 0,
                "single file input + extensionless output should SUCCEED");
    TEST_ASSERT(output_files.size() == 1,
                "exactly one output file should be collected");
    /* When format is given and output has no ext, it appends .format */
    TEST_ASSERT(output_files[0] == "/tmp/test_realsr_single_out.png",
                "output should have extension appended");

    /* Cleanup */
    remove_file("/tmp/test_realsr_single.png");
    remove_file("/tmp/test_realsr_single_out.png");
    remove_file("/tmp/test_realsr_single_out");
}

/* ======================================================================== */
/* Test: edge cases for path_looks_like_image_file                          */
/* ======================================================================== */
static void test_edge_cases()
{
    fprintf(stdout, "\n[test_edge_cases]\n");

    /* Trailing slash should not be treated as image file */
    TEST_ASSERT(path_looks_like_image_file("output/") == false,
                "trailing slash should NOT look like image file");

    /* Dot in directory name but no image extension */
    TEST_ASSERT(path_looks_like_image_file("my.dir/output") == false,
                "dot in dir name but no image ext should NOT look like image file");

    /* Hidden file with image extension */
    TEST_ASSERT(path_looks_like_image_file(".hidden.png") == true,
                ".hidden.png should look like image file");

    /* Just an extension */
    TEST_ASSERT(path_looks_like_image_file(".png") == true,
                ".png (just ext) should look like image file");

    /* Path with multiple dots */
    TEST_ASSERT(path_looks_like_image_file("my.photo.backup.png") == true,
                "my.photo.backup.png should look like image file");

    /* Case insensitive check */
    TEST_ASSERT(path_looks_like_image_file("result.BMP") == true,
                "result.BMP should look like image file (case insensitive)");
    TEST_ASSERT(path_looks_like_image_file("result.Jpeg") == true,
                "result.Jpeg should look like image file (mixed case)");
}

/* ======================================================================== */
/* main                                                                     */
/* ======================================================================== */
int main()
{
    fprintf(stdout, "=== collect_input_output_files regression tests ===\n");

    test_path_looks_like_image_file();
    test_edge_cases();
    test_dir_input_file_output_rejected();
    test_dir_input_existing_file_output_rejected();
    test_dir_input_dir_output_works();
    test_dir_input_non_image_ext_creates_dir();
    test_single_file_input_unaffected();

    fprintf(stdout, "\n=== Results: %d/%d passed, %d failed ===\n",
            tests_passed, tests_run, tests_failed);

    return tests_failed > 0 ? 1 : 0;
}
